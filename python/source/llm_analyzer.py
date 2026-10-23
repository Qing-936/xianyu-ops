"""
货源管理模块 - DeepSeek LLM 规则生成器 + 本地判定引擎

工作模式：
  1. generate_rules(items) — 将批次统计发给 DeepSeek，生成判定规则（仅 1 次 LLM 调用）
  2. apply_rules(items, rules) — 纯本地 if/else 判定每个商品（0 次 LLM 调用）

三维度：
  - heat_level（热度）：综合 want_count + view_count
  - high_value（高价值）：价格低 + 卖家信用好 + 好评率高
  - competitiveness（竞争力）：价格优势（折扣大）+ 卖家信誉好

使用方式：
  from source.llm_analyzer import generate_rules, apply_rules, calculate_price_index
  rules = generate_rules(valid_items)
  results = apply_rules(valid_items, rules)
"""

import json
import math
import logging
import statistics
from typing import List, Dict, Any, Optional

from openai import OpenAI

from source.config import (
    DEEPSEEK_BASE_URL,
    DEEPSEEK_API_KEY,
    DEEPSEEK_MODEL,
    DEEPSEEK_TIMEOUT,
)

logger = logging.getLogger('goofish.api.source.llm')

# ── 默认规则（LLM 调用失败时的兜底）──────────────────────────────────
_DEFAULT_RULES = {
    "heat_level": {
        "high":   {"want_count_min": 50, "view_count_min": 1000},
        "medium": {"want_count_min": 10, "view_count_min": 200},
    },
    "high_value": {
        "high":   {"discount_rate_min": 50, "seller_credit": ["极好"], "good_rate_min": 98},
        "medium": {"discount_rate_min": 20, "seller_credit": ["极好", "优秀"], "good_rate_min": 90},
    },
    "competitiveness": {
        "high":   {"discount_rate_min": 40, "seller_credit": ["极好", "优秀"]},
        "medium": {"discount_rate_min": 15, "seller_credit": ["极好", "优秀", "良好"]},
    },
}


# ======================================================================
# 1. 统计计算
# ======================================================================

def _compute_batch_stats(items: List[dict]) -> dict:
    """计算批次统计数据，作为 LLM 生成规则的输入"""
    if not items:
        return {}

    prices = [i.get('price', 0) for i in items]
    original_prices = [i.get('original_price', 0) for i in items]
    want_counts = [i.get('want_count', 0) for i in items]
    view_counts = [i.get('view_count', 0) for i in items]

    # 折扣率列表
    discount_rates = []
    for i in items:
        p, op = i.get('price', 0), i.get('original_price', 0)
        if op > 0 and p > 0:
            discount_rates.append(round((1 - p / op) * 100, 1))
        else:
            discount_rates.append(0)

    # 卖家信用分布
    credit_dist: Dict[str, int] = {}
    for i in items:
        c = i.get('seller_credit', '') or ''
        credit_dist[c] = credit_dist.get(c, 0) + 1

    # 好评率列表（提取数字）
    good_rates = []
    for i in items:
        gr = str(i.get('seller_good_rate', ''))
        num = _extract_number(gr)
        if num is not None:
            good_rates.append(num)

    def _safe_stats(vals: list) -> dict:
        nums = [v for v in vals if isinstance(v, (int, float))]
        if not nums:
            return {"min": 0, "max": 0, "avg": 0, "median": 0}
        return {
            "min": min(nums),
            "max": max(nums),
            "avg": round(sum(nums) / len(nums), 1),
            "median": round(statistics.median(nums), 1),
        }

    return {
        "total_items": len(items),
        "price_stats": _safe_stats(prices),
        "original_price_stats": _safe_stats(original_prices),
        "want_count_stats": _safe_stats(want_counts),
        "view_count_stats": _safe_stats(view_counts),
        "discount_rate_stats": _safe_stats(discount_rates),
        "good_rate_stats": _safe_stats(good_rates),
        "seller_credit_distribution": credit_dist,
    }


def _extract_number(s: str) -> Optional[float]:
    """从字符串中提取数字，如 '99.5%' → 99.5"""
    import re
    m = re.search(r'[\d.]+', s)
    if m:
        try:
            return float(m.group())
        except ValueError:
            return None
    return None


# ======================================================================
# 2. LLM 规则生成
# ======================================================================

SYSTEM_PROMPT = """你是一个闲鱼商品市场分析专家。你的任务是根据一批商品的统计分布数据，生成一套合理的判定规则（阈值）。

你需要为三个维度生成高/中/低的判定阈值：

1. heat_level（热度）
   判定依据：want_count（想要人数）、view_count（浏览量）
   规则字段：want_count_min, view_count_min
   满足任一条件即判定为该等级

2. high_value（高价值）
   判定依据：discount_rate（折扣率%）、seller_credit（卖家信用）、good_rate（好评率%）
   规则字段：discount_rate_min, seller_credit（数组）, good_rate_min
   seller_credit 是满足该等级所需的信用等级列表

3. competitiveness（竞争力）
   判定依据：discount_rate（折扣率%）、seller_credit（卖家信用）
   规则字段：discount_rate_min, seller_credit（数组）

等级判定从高到低依次匹配，不满足"高"则判"中"，都不满足则判"低"。

请严格返回以下 JSON 格式（不要有其他文字）：
{
  "heat_level": {
    "high": {"want_count_min": 数值, "view_count_min": 数值},
    "medium": {"want_count_min": 数值, "view_count_min": 数值}
  },
  "high_value": {
    "high": {"discount_rate_min": 数值, "seller_credit": ["信用值"], "good_rate_min": 数值},
    "medium": {"discount_rate_min": 数值, "seller_credit": ["信用值"], "good_rate_min": 数值}
  },
  "competitiveness": {
    "high": {"discount_rate_min": 数值, "seller_credit": ["信用值"]},
    "medium": {"discount_rate_min": 数值, "seller_credit": ["信用值"]}
  }
}

注意：
- 阈值应基于实际数据分布合理设定，高阈值 > 中阈值
- 卖家信用可选值：极好、优秀、良好、一般、较差
- 折扣率为百分比数值（如 50 表示 50% off）
- 好评率为百分比数值（如 98 表示 98%）"""


def _build_stats_prompt(stats: dict) -> str:
    """构建发送给 LLM 的统计数据 prompt"""
    price_yuan = {k: v / 100 for k, v in stats['price_stats'].items()} if stats.get('price_stats') else {}
    orig_yuan = {k: v / 100 for k, v in stats['original_price_stats'].items()} if stats.get('original_price_stats') else {}

    return f"""以下是本批次 {stats['total_items']} 个闲鱼商品的统计分布：

【价格分布（元）】
  售价: 最低 {price_yuan.get('min', 0):.2f}, 最高 {price_yuan.get('max', 0):.2f}, 均价 {price_yuan.get('avg', 0):.2f}, 中位数 {price_yuan.get('median', 0):.2f}
  原价: 最低 {orig_yuan.get('min', 0):.2f}, 最高 {orig_yuan.get('max', 0):.2f}, 均价 {orig_yuan.get('avg', 0):.2f}

【想要人数分布】
  最低 {stats['want_count_stats']['min']}, 最高 {stats['want_count_stats']['max']}, 平均 {stats['want_count_stats']['avg']}, 中位数 {stats['want_count_stats']['median']}

【浏览量分布】
  最低 {stats['view_count_stats']['min']}, 最高 {stats['view_count_stats']['max']}, 平均 {stats['view_count_stats']['avg']}, 中位数 {stats['view_count_stats']['median']}

【折扣率分布（%）】
  最低 {stats['discount_rate_stats']['min']}, 最高 {stats['discount_rate_stats']['max']}, 平均 {stats['discount_rate_stats']['avg']}, 中位数 {stats['discount_rate_stats']['median']}

【好评率分布（%）】
  最低 {stats['good_rate_stats']['min']}, 最高 {stats['good_rate_stats']['max']}, 平均 {stats['good_rate_stats']['avg']}, 中位数 {stats['good_rate_stats']['median']}

【卖家信用分布】
  {json.dumps(stats['seller_credit_distribution'], ensure_ascii=False)}

请根据以上数据分布，生成合理的三维度判定规则。"""


def _parse_rules_response(response_text: str) -> Optional[dict]:
    """解析 LLM 返回的规则 JSON"""
    try:
        text = response_text.strip()
        # 处理 markdown 代码块
        if text.startswith('```'):
            lines = text.split('\n')
            text = '\n'.join(lines[1:-1] if lines[-1].strip() == '```' else lines[1:])
            text = text.strip()

        rules = json.loads(text)
        # 基本结构校验
        required_keys = {'heat_level', 'high_value', 'competitiveness'}
        if not required_keys.issubset(rules.keys()):
            logger.warning(f'LLM 规则缺少必要维度: {set(rules.keys())}')
            return None

        for dim in required_keys:
            if 'high' not in rules[dim] or 'medium' not in rules[dim]:
                logger.warning(f'LLM 规则 {dim} 缺少 high/medium 级别')
                return None

        return rules

    except (json.JSONDecodeError, TypeError, KeyError) as e:
        logger.error(f'LLM 规则解析失败: {e}, response={response_text[:300]}')
        return None


def generate_rules(items: List[dict]) -> dict:
    """
    根据批次商品数据，调用 DeepSeek 生成判定规则

    参数:
        items: 有效商品列表（parsed 格式，价格为元）

    返回:
        dict: 判定规则，包含 heat_level / high_value / competitiveness 三个维度的阈值
    """
    if not items:
        return _DEFAULT_RULES.copy()

    # 构建统计（价格转为分）
    llm_input = []
    for p in items:
        llm_input.append({
            'price': _yuan_to_fen(p.get('price', 0.0)),
            'original_price': _yuan_to_fen(p.get('original_price', 0.0)),
            'want_count': p.get('want_count', 0),
            'view_count': p.get('view_count', 0),
            'seller_credit': p.get('seller_credit_tag', ''),
            'seller_good_rate': str(p.get('seller_good_rate', '')),
        })

    stats = _compute_batch_stats(llm_input)

    try:
        client = OpenAI(
            api_key=DEEPSEEK_API_KEY,
            base_url=DEEPSEEK_BASE_URL,
            timeout=DEEPSEEK_TIMEOUT,
        )

        user_prompt = _build_stats_prompt(stats)
        logger.info(f'调用 DeepSeek 生成判定规则（{len(items)} 个商品统计）...')

        response = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {'role': 'system', 'content': SYSTEM_PROMPT},
                {'role': 'user', 'content': user_prompt},
            ],
            temperature=0.3,
            max_tokens=1000,
        )

        response_text = response.choices[0].message.content
        logger.info(f'DeepSeek 规则响应: {response_text[:300]}...')

        rules = _parse_rules_response(response_text)
        if rules:
            _print_rules(rules, stats)
            return rules

        logger.warning('LLM 返回规则格式异常，使用默认规则')
        _print_rules(_DEFAULT_RULES, stats, is_default=True)
        return _DEFAULT_RULES.copy()

    except Exception as e:
        logger.error(f'DeepSeek API 调用失败: {e}，使用默认规则')
        _print_rules(_DEFAULT_RULES, stats, is_default=True)
        return _DEFAULT_RULES.copy()


def _yuan_to_fen(price_yuan: float) -> int:
    """元（float）→ 分（int）"""
    return int(round(price_yuan * 100))


# ======================================================================
# 3. 终端输出规则
# ======================================================================

def _print_rules(rules: dict, stats: dict, is_default: bool = False):
    """终端打印生成的判定规则"""
    tag = '[默认规则]' if is_default else '[AI 生成规则]'
    print()
    print('=' * 60)
    print(f'  {tag} 商品指标判定规则')
    print('=' * 60)

    # 热度
    hl = rules.get('heat_level', {})
    h, m = hl.get('high', {}), hl.get('medium', {})
    print(f'\n  [热度 heat_level]')
    print(f'    高: want_count >= {h.get("want_count_min", "N/A")} 或 view_count >= {h.get("view_count_min", "N/A")}')
    print(f'    中: want_count >= {m.get("want_count_min", "N/A")} 或 view_count >= {m.get("view_count_min", "N/A")}')
    print(f'    低: 不满足以上条件')

    # 高价值
    hv = rules.get('high_value', {})
    h, m = hv.get('high', {}), hv.get('medium', {})
    print(f'\n  [高价值 high_value]')
    print(f'    高: 折扣率 >= {h.get("discount_rate_min", "N/A")}% 且 信用 in {h.get("seller_credit", [])} 且 好评率 >= {h.get("good_rate_min", "N/A")}%')
    print(f'    中: 折扣率 >= {m.get("discount_rate_min", "N/A")}% 且 信用 in {m.get("seller_credit", [])} 且 好评率 >= {m.get("good_rate_min", "N/A")}%')
    print(f'    低: 不满足以上条件')

    # 竞争力
    cp = rules.get('competitiveness', {})
    h, m = cp.get('high', {}), cp.get('medium', {})
    print(f'\n  [竞争力 competitiveness]')
    print(f'    高: 折扣率 >= {h.get("discount_rate_min", "N/A")}% 且 信用 in {h.get("seller_credit", [])}')
    print(f'    中: 折扣率 >= {m.get("discount_rate_min", "N/A")}% 且 信用 in {m.get("seller_credit", [])}')
    print(f'    低: 不满足以上条件')

    print()
    print(f'  批次统计: {stats.get("total_items", 0)} 个商品')
    ws = stats.get('want_count_stats', {})
    print(f'  想要人数: {ws.get("min", 0)} ~ {ws.get("max", 0)}, 平均 {ws.get("avg", 0)}')
    ds = stats.get('discount_rate_stats', {})
    print(f'  折扣率:   {ds.get("min", 0)}% ~ {ds.get("max", 0)}%, 平均 {ds.get("avg", 0)}%')
    print('=' * 60)
    print()


# ======================================================================
# 4. 本地规则判定（无 LLM 调用）
# ======================================================================

def _get_discount_rate(item: dict) -> float:
    """计算折扣率（%），原价 0 时返回 0"""
    price = item.get('price', 0)
    original = item.get('original_price', 0)
    if original <= 0 or price <= 0:
        return 0.0
    return round((1 - price / original) * 100, 1)


def _get_good_rate_number(item: dict) -> float:
    """提取好评率数值"""
    gr = str(item.get('seller_good_rate', ''))
    num = _extract_number(gr)
    return num if num is not None else 0.0


def _judge_heat_level(item: dict, rule: dict) -> str:
    """判定热度"""
    high = rule.get('high', {})
    medium = rule.get('medium', {})

    want = item.get('want_count', 0)
    view = item.get('view_count', 0)

    # 高：满足任一条件
    if want >= high.get('want_count_min', float('inf')) or view >= high.get('view_count_min', float('inf')):
        return '高'
    # 中：满足任一条件
    if want >= medium.get('want_count_min', float('inf')) or view >= medium.get('view_count_min', float('inf')):
        return '中'
    return '低'


def _judge_high_value(item: dict, rule: dict) -> str:
    """判定高价值"""
    high = rule.get('high', {})
    medium = rule.get('medium', {})

    discount = _get_discount_rate(item)
    credit = item.get('seller_credit', '') or ''
    good_rate = _get_good_rate_number(item)

    def _match(level_rule: dict) -> bool:
        d_min = level_rule.get('discount_rate_min', 0)
        credits = level_rule.get('seller_credit', [])
        gr_min = level_rule.get('good_rate_min', 0)
        return (discount >= d_min and credit in credits and good_rate >= gr_min)

    if _match(high):
        return '高'
    if _match(medium):
        return '中'
    # 数据不足时标记 '-'
    if discount == 0 and not credit and good_rate == 0:
        return '-'
    return '低'


def _judge_competitiveness(item: dict, rule: dict) -> str:
    """判定竞争力"""
    high = rule.get('high', {})
    medium = rule.get('medium', {})

    discount = _get_discount_rate(item)
    credit = item.get('seller_credit', '') or ''

    def _match(level_rule: dict) -> bool:
        d_min = level_rule.get('discount_rate_min', 0)
        credits = level_rule.get('seller_credit', [])
        return (discount >= d_min and credit in credits)

    if _match(high):
        return '高'
    if _match(medium):
        return '中'
    return '低'


def apply_rules(items: List[dict], rules: dict) -> List[dict]:
    """
    使用规则本地判定每个商品的三维度（无 LLM 调用）

    参数:
        items: 商品列表（parsed 格式，价格为元）
        rules: generate_rules() 返回的规则 dict

    返回:
        List[dict]，每个元素包含 heat_level, high_value, competitiveness
    """
    if not items:
        return []

    heat_rule = rules.get('heat_level', {})
    value_rule = rules.get('high_value', {})
    comp_rule = rules.get('competitiveness', {})

    results = []
    for item in items:
        # 构造统一格式的 item（兼容 parsed 格式中的 seller_credit_tag）
        normalized = {
            'want_count': item.get('want_count', 0),
            'view_count': item.get('view_count', 0),
            'price': _yuan_to_fen(item.get('price', 0.0)),
            'original_price': _yuan_to_fen(item.get('original_price', 0.0)),
            'seller_credit': item.get('seller_credit_tag', '') or item.get('seller_credit', ''),
            'seller_good_rate': str(item.get('seller_good_rate', '')),
        }

        results.append({
            'heat_level': _judge_heat_level(normalized, heat_rule),
            'high_value': _judge_high_value(normalized, value_rule),
            'competitiveness': _judge_competitiveness(normalized, comp_rule),
        })

    return results


# ======================================================================
# 5. 价格指数计算（保持不变）
# ======================================================================

# 价格段定义（单位：分）
_PRICE_RANGES = [
    (0, 1000),       # 0-10元
    (1000, 5000),    # 10-50元
    (5000, 10000),   # 50-100元
    (10000, 20000),  # 100-200元
    (20000, 50000),  # 200-500元
    (50000, 100000), # 500-1000元
    (100000, float('inf')),  # 1000元以上
]


def calculate_price_index(prices_fen: List[int]) -> int:
    """
    计算价格指数：有效商品分布在不同价格段的数量

    参数:
        prices_fen: 有效商品的价格列表（单位：分）

    返回:
        int: 有商品分布的价格段数量
    """
    if not prices_fen:
        return 0

    occupied_ranges = 0
    for low, high in _PRICE_RANGES:
        for price in prices_fen:
            if low <= price < high:
                occupied_ranges += 1
                break

    return occupied_ranges
