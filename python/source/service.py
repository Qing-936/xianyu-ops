"""
货源管理模块 - 服务层
对接 Java 端的 app_source_* 系列表，执行搜索 → 筛选 → 详情采集 → 汇总

数据流：
  app_source_task（Java创建）→ 搜索并由采集器校验价格 → app_source_item
  → 有效商品详情采集 → app_source_item_detail → 汇总 → app_source_execution
"""

import json
import logging
import asyncio
import random
import threading
import time
from datetime import datetime
from typing import List, Dict, Any, Optional, Tuple

import pymysql

from source.goofish_search_api import (
    GoofishSearchAPI,
    RESPONSE_RISK_CONTROL,
    RESPONSE_SUCCESS,
    RESPONSE_USER_VALIDATE,
    classify_response,
)
from source.cookie_pool import (
    acquire_cookie_from_pool,
    mark_cookie_risk_control,
    mark_cookie_user_validate,
    record_runtime_refresh,
)
from source.config import (
    SOURCE_MYSQL_CONFIG, ROWS_PER_PAGE, REQUEST_INTERVAL, SOURCE_SEARCH_MAX_PAGES,
    SOURCE_DETAIL_INTERVAL_MIN, SOURCE_DETAIL_INTERVAL_MAX, SOURCE_DETAIL_JITTER,
    SOURCE_BACKOFF_INITIAL, SOURCE_BACKOFF_MULTIPLIER, SOURCE_BACKOFF_MAX,
    SOURCE_BACKOFF_BATCH_PAUSE, SOURCE_BACKOFF_BATCH_THRESHOLD,
)
from source.llm_analyzer import generate_rules, apply_rules, calculate_price_index
from source.search_collector import GoofishSearchError, collect_search_items

logger = logging.getLogger('goofish.api.source')
_GOOFISH_REQUEST_LOCK = threading.Lock()

SOURCE_TASK_TRIGGER_MODE_ONCE = 0
SOURCE_TASK_TRIGGER_MODE_DAILY = 1

SOURCE_TASK_STATUS_RUNNING = 0
SOURCE_TASK_STATUS_PAUSED = 1
SOURCE_TASK_STATUS_COMPLETED = 2
SOURCE_TASK_STATUS_SUBMIT_FAILED = 3
SOURCE_TASK_STATUS_FAILED = 4


def _acquire_pool_cookie_or_raise(excluded_cookie_ids: Optional[set[int]] = None) -> dict:
    record = acquire_cookie_from_pool(excluded_cookie_ids=excluded_cookie_ids)
    if not record:
        if excluded_cookie_ids:
            raise RuntimeError('没有可切换的共享 Cookie，请先补充新的 Cookie 后再重试')
        raise RuntimeError('共享 Cookie 池为空，请先扫码登录并运行建表脚本')
    return record


def _build_search_api_from_pool(cookie_record: dict) -> GoofishSearchAPI:
    return GoofishSearchAPI(
        cookie_record['cookies'],
        on_cookie_refresh=lambda cookies: record_runtime_refresh(cookie_record['id'], cookies),
    )


def _mark_cookie_failure_by_kind(cookie_record: dict, response_kind: str) -> None:
    cookie_id = cookie_record.get('id') if cookie_record else None
    if not cookie_id:
        return
    if response_kind == RESPONSE_USER_VALIDATE:
        mark_cookie_user_validate(cookie_id)
    elif response_kind == RESPONSE_RISK_CONTROL:
        mark_cookie_risk_control(cookie_id)


# ── 智能延迟 ─────────────────────────────────────────────────────────────

def _detail_stop_message(response_kind: str) -> Optional[str]:
    """返回需要立即终止详情阶段的操作员提示。"""
    if response_kind == RESPONSE_RISK_CONTROL:
        return '闲鱼触发平台风控，本任务已停止且不会立即重试'
    if response_kind == RESPONSE_USER_VALIDATE:
        return '闲鱼登录需要验证，请扫码恢复后重试'
    return None


def _smart_delay(consecutive_fails: int) -> float:
    """
    计算智能延迟时间：
    - 正常情况: 基础间隔 (5~8s) + 随机抖动 (0~2s)
    - 连续失败: 指数退避 (10 → 20 → 40 → 80 → 120s)
    """
    if consecutive_fails == 0:
        base = random.uniform(SOURCE_DETAIL_INTERVAL_MIN, SOURCE_DETAIL_INTERVAL_MAX)
        jitter = random.uniform(0, SOURCE_DETAIL_JITTER)
        return base + jitter

    # 指数退避
    backoff = min(
        SOURCE_BACKOFF_INITIAL * (SOURCE_BACKOFF_MULTIPLIER ** (consecutive_fails - 1)),
        SOURCE_BACKOFF_MAX,
    )
    jitter = random.uniform(0, SOURCE_DETAIL_JITTER)
    return backoff + jitter


# ── 字段映射 ─────────────────────────────────────────────────────────────────

def _yuan_to_fen(price_yuan: float) -> int:
    """元（float）→ 分（int），用于写入 DDL 中单位为分的字段"""
    return int(round(price_yuan * 100))


def _map_search_item_to_source(
    parsed: dict,
    task_id: int,
    execution_no: int,
    keyword: str,
    is_valid: bool,
    crawl_time: str,
) -> dict:
    """
    将 parse_search_item() 的返回值映射为 app_source_item 字段
    parsed 中 price/original_price 单位为元（float），DDL 中为分（int）
    """
    item_id = parsed.get('item_id', '')
    detail_url = f'https://www.goofish.com/item?id={item_id}' if item_id else ''

    tag_list_raw = parsed.get('tag_list', '')
    tag_list_json = None
    if tag_list_raw:
        try:
            tag_list_json = json.loads(tag_list_raw) if isinstance(tag_list_raw, str) else tag_list_raw
        except (json.JSONDecodeError, TypeError):
            tag_list_json = None

    return {
        'task_id': task_id,
        'execution_no': execution_no,
        'external_item_id': item_id,
        'title': parsed.get('title', ''),
        'price': _yuan_to_fen(parsed.get('price', 0.0)),
        'original_price': _yuan_to_fen(parsed.get('original_price', 0.0)),
        'pic_url': parsed.get('pic_url', ''),
        'detail_url': detail_url,
        'location': parsed.get('location', ''),
        'want_count': parsed.get('want_count', 0),
        'view_count': parsed.get('view_count', 0),
        'publish_time': str(parsed.get('publish_time', '')),
        'seller_name': parsed.get('seller_name', ''),
        'seller_credit': parsed.get('seller_credit_tag', ''),
        'seller_level': parsed.get('seller_level', ''),
        'seller_review_count': str(parsed.get('seller_review_count', '')),
        'seller_good_rate': str(parsed.get('seller_good_rate', '')),
        'item_type': parsed.get('item_type', ''),
        'price_drop_tag': parsed.get('price_drop_tag', ''),
        'ship_speed': parsed.get('ship_speed', ''),
        'heat_level': parsed.get('heat_level', ''),
        'high_value': parsed.get('high_value', ''),
        'competitiveness': parsed.get('competitiveness', ''),
        'tag_list': tag_list_json,
        'search_keyword': keyword,
        'is_valid': 1 if is_valid else 0,
        'crawl_time': crawl_time,
    }


def _map_detail_item_to_source(
    parsed: dict,
    task_id: int,
    item_row_id: Optional[int],
    keyword: str,
    crawl_time: str,
) -> dict:
    """
    将 parse_detail_item() 的返回值映射为 app_source_item_detail 字段
    """
    ext_item_id = parsed.get('item_id', '')
    detail_url = f'https://www.goofish.com/item?id={ext_item_id}' if ext_item_id else ''

    cpv_raw = parsed.get('cpv_labels', '')
    cpv_json = None
    if cpv_raw:
        try:
            cpv_json = json.loads(cpv_raw) if isinstance(cpv_raw, str) else cpv_raw
        except (json.JSONDecodeError, TypeError):
            cpv_json = None

    tag_raw = parsed.get('tag_list', '')
    tag_json = None
    if tag_raw:
        try:
            tag_json = json.loads(tag_raw) if isinstance(tag_raw, str) else tag_raw
        except (json.JSONDecodeError, TypeError):
            tag_json = None

    return {
        'task_id': task_id,
        'item_id': item_row_id,
        'external_item_id': ext_item_id,
        'title': parsed.get('title', ''),
        'price': _yuan_to_fen(parsed.get('price', 0.0)),
        'original_price': _yuan_to_fen(parsed.get('original_price', 0.0)),
        'pic_url': parsed.get('pic_url', ''),
        'detail_url': detail_url,
        'description': parsed.get('description', ''),
        'location': parsed.get('location', ''),
        'view_count': parsed.get('view_count', 0),
        'want_count': parsed.get('want_count', 0),
        'sold_count': parsed.get('sold_count', 0),
        'collect_count': parsed.get('collect_count', 0),
        'quantity': parsed.get('quantity', 0),
        'transport_fee': str(parsed.get('transport_fee', '')),
        'item_status': parsed.get('item_status', ''),
        'publish_time': str(parsed.get('publish_time', '')),
        'category_name': parsed.get('category_name', ''),
        'cpv_labels': cpv_json,
        'tag_list': tag_json,
        'item_type': parsed.get('item_type', ''),
        'is_free_shipping': 1 if parsed.get('is_free_shipping') else 0,
        'seller_id': str(parsed.get('seller_id', '')),
        'seller_name': parsed.get('seller_name', ''),
        'seller_avatar': parsed.get('seller_avatar', ''),
        'seller_city': parsed.get('seller_city', ''),
        'seller_sold_count': parsed.get('seller_sold_count', 0),
        'seller_reg_days': parsed.get('seller_reg_days', 0),
        'seller_zhima_auth': 1 if parsed.get('seller_zhima_auth') else 0,
        'seller_zhima_level': parsed.get('seller_zhima_level', ''),
        'seller_credit_tag': parsed.get('seller_credit_tag', ''),
        'seller_reply_ratio': str(parsed.get('seller_reply_ratio', '')),
        'seller_reply_interval': str(parsed.get('seller_reply_interval', '') or ''),
        'seller_last_visit': str(parsed.get('seller_last_visit', '')),
        'seller_good_rate': str(parsed.get('seller_good_rate', '')),
        'seller_remark_good': parsed.get('seller_remark_good', 0),
        'seller_remark_bad': parsed.get('seller_remark_bad', 0),
        'search_keyword': keyword,
        'crawl_time': crawl_time,
    }


# ── DB 辅助 ───────────────────────────────────────────────────────────────────

def _get_conn():
    """获取数据库连接"""
    return pymysql.connect(**SOURCE_MYSQL_CONFIG, cursorclass=pymysql.cursors.DictCursor)


def _get_task(conn, task_id: int) -> Optional[dict]:
    """读取任务配置"""
    with conn.cursor() as cur:
        cur.execute(
            'SELECT * FROM app_source_task WHERE id = %s AND deleted = 0', (task_id,)
        )
        return cur.fetchone()


def _get_next_execution_no(conn, task_id: int) -> int:
    """获取下一个批次号"""
    with conn.cursor() as cur:
        cur.execute(
            'SELECT COALESCE(MAX(execution_no), 0) AS max_no FROM app_source_execution WHERE task_id = %s',
            (task_id,)
        )
        row = cur.fetchone()
        return (row['max_no'] if row else 0) + 1


def _get_latest_execution_cookie_id(conn, task_id: int) -> Optional[int]:
    """读取最近一次执行使用的 Cookie ID。"""
    with conn.cursor() as cur:
        cur.execute(
            'SELECT source_cookie_id FROM app_source_execution WHERE task_id = %s AND deleted = 0 ORDER BY execution_no DESC LIMIT 1',
            (task_id,)
        )
        row = cur.fetchone()
        return row['source_cookie_id'] if row else None


def _insert_execution(conn, task_id: int, execution_no: int, keyword: str, tenant_id: int = 0) -> int:
    """插入执行记录（status=0 执行中），返回自增 id"""
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    with conn.cursor() as cur:
        cur.execute('''
            INSERT INTO app_source_execution
                (task_id, execution_no, keyword, fetch_count, valid_count,
                 executed_at, status, tenant_id, creator, create_time, update_time)
            VALUES (%s, %s, %s, 0, 0, %s, 0, %s, 'python', NOW(), NOW())
        ''', (task_id, execution_no, keyword, now, tenant_id))
    conn.commit()
    return cur.lastrowid


def _update_execution_source_cookie(conn, exec_id: int, source_cookie_id: Optional[int]):
    """记录本批次使用的共享 Cookie。"""
    with conn.cursor() as cur:
        cur.execute(
            '''
            UPDATE app_source_execution SET
                source_cookie_id = %s,
                update_time = NOW()
            WHERE id = %s
            ''',
            (source_cookie_id, exec_id)
        )
    conn.commit()


def _update_execution_summary(
    conn, exec_id: int,
    fetch_count: int, valid_count: int,
    min_price: Optional[int], max_price: Optional[int], avg_price: Optional[int],
    price_index: Optional[int] = None, status: int = 1,
):
    """更新执行记录汇总。"""
    with conn.cursor() as cur:
        cur.execute('''
            UPDATE app_source_execution SET
                status = %s, fetch_count = %s, valid_count = %s,
                min_price = %s, max_price = %s, avg_price = %s,
                price_index = %s,
                update_time = NOW()
            WHERE id = %s
        ''', (status, fetch_count, valid_count, min_price, max_price, avg_price, price_index, exec_id))
    conn.commit()


def _update_execution_failed(conn, exec_id: int, error_msg: str):
    """更新执行记录为失败"""
    with conn.cursor() as cur:
        cur.execute('''
            UPDATE app_source_execution SET
                status = 2, error_msg = %s, update_time = NOW()
            WHERE id = %s
        ''', (error_msg[:500], exec_id))
    conn.commit()


def _resolve_execution_status(valid_count: int, fetch_count: int) -> int:
    """本次批次是否达标。"""
    return 1 if valid_count >= fetch_count else 2


def _resolve_task_status_after_execution(trigger_mode: Optional[int], reached_target: bool) -> int:
    """每日定时任务始终保持运行中，一次性任务按是否达标区分完成/失败。"""
    if trigger_mode == SOURCE_TASK_TRIGGER_MODE_DAILY:
        return SOURCE_TASK_STATUS_RUNNING
    return SOURCE_TASK_STATUS_COMPLETED if reached_target else SOURCE_TASK_STATUS_FAILED


def _update_task_executed(conn, task_id: int, status: Optional[int] = None, error_message: str = ''):
    """
    更新任务的已执行次数、最近执行时间和状态
    status: 0=运行中 1=已暂停 2=已完成 3=提交失败
    error_message: status=3 时填写失败原因
    """
    if status is None:
        status = SOURCE_TASK_STATUS_COMPLETED
    with conn.cursor() as cur:
        cur.execute('''
            UPDATE app_source_task SET
                executed_count = executed_count + 1,
                last_executed_at = NOW(),
                status = %s,
                error_message = %s,
                update_time = NOW()
            WHERE id = %s
        ''', (status, error_message[:500] if error_message else '', task_id))
    conn.commit()


# ── 批量写入 ──────────────────────────────────────────────────────────────────

_INSERT_ITEM_SQL = '''
    INSERT INTO app_source_item
        (task_id, execution_no, external_item_id, title, price, original_price,
         pic_url, detail_url, location, want_count, view_count, publish_time,
         seller_name, seller_credit, seller_level,
         seller_review_count, seller_good_rate,
         item_type, price_drop_tag, ship_speed,
         heat_level, high_value, competitiveness,
         tag_list, search_keyword,
         is_valid, crawl_time, creator, create_time, update_time)
    VALUES
        (%s,%s,%s,%s,%s,%s, %s,%s,%s,%s,%s,%s, %s,%s,%s, %s,%s, %s,%s,%s, %s,%s,%s, %s,%s, %s,%s,
         'python', NOW(), NOW())
'''


def _batch_insert_items(conn, items: List[dict]):
    """批量插入 app_source_item，返回插入后的行 id 列表"""
    row_ids = []
    with conn.cursor() as cur:
        for item in items:
            cur.execute(_INSERT_ITEM_SQL, (
                item['task_id'], item['execution_no'], item['external_item_id'],
                item['title'], item['price'], item['original_price'],
                item['pic_url'], item['detail_url'], item['location'],
                item['want_count'], item['view_count'], item['publish_time'],
                item['seller_name'], item['seller_credit'], item['seller_level'],
                item['seller_review_count'], item['seller_good_rate'],
                item['item_type'], item['price_drop_tag'], item['ship_speed'],
                item['heat_level'], item['high_value'], item['competitiveness'],
                json.dumps(item['tag_list'], ensure_ascii=False) if item['tag_list'] else None,
                item['search_keyword'], item['is_valid'], item['crawl_time'],
            ))
            row_ids.append(cur.lastrowid)
    conn.commit()
    return row_ids


_INSERT_DETAIL_SQL = '''
    INSERT INTO app_source_item_detail
        (task_id, item_id, external_item_id, title, price, original_price,
         pic_url, detail_url, description, location,
         view_count, want_count, sold_count, collect_count, quantity,
         transport_fee, item_status, publish_time, category_name,
         cpv_labels, tag_list,
         item_type, is_free_shipping,
         seller_id, seller_name, seller_avatar, seller_city,
         seller_sold_count, seller_reg_days, seller_zhima_auth, seller_zhima_level,
         seller_credit_tag, seller_reply_ratio, seller_reply_interval, seller_last_visit,
         seller_good_rate, seller_remark_good, seller_remark_bad,
         search_keyword, crawl_time, creator, create_time, update_time)
    VALUES
        (%s,%s,%s,%s,%s,%s, %s,%s,%s,%s, %s,%s,%s,%s,%s, %s,%s,%s,%s, %s,%s,
         %s,%s, %s,%s,%s,%s, %s,%s,%s,%s, %s,%s,%s,%s, %s,%s,%s, %s,%s,
         'python', NOW(), NOW())
'''


def _insert_detail(conn, d: dict):
    """插入单条 app_source_item_detail"""
    with conn.cursor() as cur:
        cur.execute(_INSERT_DETAIL_SQL, (
            d['task_id'], d['item_id'], d['external_item_id'],
            d['title'], d['price'], d['original_price'],
            d['pic_url'], d['detail_url'], d['description'], d['location'],
            d['view_count'], d['want_count'], d['sold_count'],
            d['collect_count'], d['quantity'],
            d['transport_fee'], d['item_status'], d['publish_time'], d['category_name'],
            json.dumps(d['cpv_labels'], ensure_ascii=False) if d['cpv_labels'] else None,
            json.dumps(d['tag_list'], ensure_ascii=False) if d['tag_list'] else None,
            d['item_type'], d['is_free_shipping'],
            d['seller_id'], d['seller_name'], d['seller_avatar'], d['seller_city'],
            d['seller_sold_count'], d['seller_reg_days'],
            d['seller_zhima_auth'], d['seller_zhima_level'],
            d['seller_credit_tag'], d['seller_reply_ratio'],
            d['seller_reply_interval'], d['seller_last_visit'],
            d['seller_good_rate'], d['seller_remark_good'], d['seller_remark_bad'],
            d['search_keyword'], d['crawl_time'],
        ))
    conn.commit()


_UPDATE_VIEW_COUNT_SQL = '''
    UPDATE app_source_item
    SET view_count = %s
    WHERE id = %s
'''

def _update_item_view_count(conn, row_id: int, view_count: int):
    """回写 view_count 到搜索结果表"""
    with conn.cursor() as cur:
        cur.execute(_UPDATE_VIEW_COUNT_SQL, (view_count, row_id))
    conn.commit()


# ── 主流程 ────────────────────────────────────────────────────────────────────

def _safe_broadcast(msg: dict, manager=None, loop=None):
    """线程安全的 WebSocket 广播（从工作线程调用）"""
    if manager and loop:
        asyncio.run_coroutine_threadsafe(manager.broadcast(msg), loop)


def execute_source_task(
    task_id: int,
    manager=None,
    loop=None,
    keyword_override: Optional[str] = None,
    switch_cookie: bool = False,
    fetch_count_override: Optional[int] = None,
):
    """
    货源任务主执行流程（同步，在线程池中运行）

    1. 读取 app_source_task 配置
    2. 按有效商品目标搜索闲鱼，达到目标、结果耗尽或安全页数上限时停止
    3. 写入搜索扫描结果及采集器已判定的有效/无效标记
    4. 对有效商品逐个采集详情，写入 app_source_item_detail
    5. 汇总统计，更新 app_source_execution
    6. 更新 app_source_task 执行记录
    """
    exec_id = None
    conn = None

    def _broadcast(msg: dict):
        _safe_broadcast(msg, manager, loop)

    try:
        conn = _get_conn()

        # ── Step 1: 读取任务配置 ──────────────────────────────────────────
        task = _get_task(conn, task_id)
        if not task:
            logger.error(f'任务不存在: task_id={task_id}')
            _broadcast({'type': 'source_error', 'task_id': task_id, 'message': '任务不存在'})
            return {'success': False, 'error': '任务不存在'}

        keyword = keyword_override or task.get('product_name', '')
        filter_min = task.get('filter_min_price')    # 分，None 表示不限
        filter_max = task.get('filter_max_price')    # 分，None 表示不限
        filter_view_min = task.get('filter_view_count_min')
        filter_view_max = task.get('filter_view_count_max')
        filter_want_min = task.get('filter_want_count_min')
        filter_want_max = task.get('filter_want_count_max')
        fetch_count = fetch_count_override or task.get('fetch_count', 5)
        tenant_id = task.get('tenant_id', 0)

        logger.info(
            f'开始执行货源任务: task_id={task_id}, keyword="{keyword}", '
            f'fetch_count={fetch_count}, filter_price=[{filter_min},{filter_max}]分, '
            f'filter_view=[{filter_view_min},{filter_view_max}], '
            f'filter_want=[{filter_want_min},{filter_want_max}]'
        )
        excluded_cookie_ids: set[int] = set()
        if switch_cookie:
            latest_cookie_id = _get_latest_execution_cookie_id(conn, task_id)
            if latest_cookie_id:
                excluded_cookie_ids.add(latest_cookie_id)
                logger.info(
                    '手动重试切换 Cookie: task_id=%s, excluded_cookie_id=%s',
                    task_id, latest_cookie_id
                )
        _broadcast({
            'type': 'source_start', 'task_id': task_id,
            'message': f'开始执行: {keyword} ({fetch_count}条)'
        })

        # ── Step 2: 计算批次号，插入执行记录 ──────────────────────────────
        execution_no = _get_next_execution_no(conn, task_id)
        exec_id = _insert_execution(conn, task_id, execution_no, keyword, tenant_id)

        # ── Step 3: 搜索闲鱼 ─────────────────────────────────────────────
        cookie_record = _acquire_pool_cookie_or_raise(excluded_cookie_ids=excluded_cookie_ids)
        _update_execution_source_cookie(conn, exec_id, cookie_record.get('id'))
        api = _build_search_api_from_pool(cookie_record)
        prefetched_details: Dict[str, Dict[str, Any]] = {}
        needs_detail_metrics = any(value is not None for value in (
            filter_view_min,
            filter_view_max,
            filter_want_min,
            filter_want_max,
        ))
        detail_metric_requests = 0
        detail_metric_fails = 0

        def _resolve_item_metrics(parsed_item: Dict[str, Any]) -> bool:
            """用详情接口的权威浏览量/想要量覆盖搜索列表指标。"""
            nonlocal detail_metric_requests, detail_metric_fails

            if detail_metric_requests > 0:
                time.sleep(_smart_delay(detail_metric_fails))
            detail_metric_requests += 1

            ext_item_id = parsed_item.get('item_id', '')
            try:
                detail_raw = api.get_item_detail(ext_item_id)
                response_kind = classify_response(detail_raw)
                if response_kind != RESPONSE_SUCCESS:
                    detail_metric_fails += 1
                    stop_message = _detail_stop_message(response_kind)
                    if stop_message:
                        raise GoofishSearchError(response_kind, stop_message)
                    logger.warning(
                        f'筛选阶段详情API失败: {ext_item_id}, '
                        f'ret={detail_raw.get("ret", []) if isinstance(detail_raw, dict) else []}'
                    )
                    return False

                parsed_detail = api.parse_detail_item(detail_raw.get('data', {}))
                parsed_item['view_count'] = parsed_detail.get('view_count', 0)
                parsed_item['want_count'] = parsed_detail.get('want_count', 0)
                prefetched_details[ext_item_id] = parsed_detail
                detail_metric_fails = 0
                return True
            except GoofishSearchError:
                raise
            except Exception as error:
                detail_metric_fails += 1
                logger.error(f'筛选阶段详情采集异常 {ext_item_id}: {error}')
                return False

        def _search_progress(progress: dict):
            _broadcast({
                'type': 'source_search_progress',
                'task_id': task_id,
                **progress,
            })

        try:
            with _GOOFISH_REQUEST_LOCK:
                collection = collect_search_items(
                    api,
                    keyword=keyword,
                    target_valid_count=fetch_count,
                    min_price_fen=filter_min,
                    max_price_fen=filter_max,
                    max_pages=SOURCE_SEARCH_MAX_PAGES,
                    rows_per_page=ROWS_PER_PAGE,
                    request_interval=REQUEST_INTERVAL,
                    min_view_count=filter_view_min,
                    max_view_count=filter_view_max,
                    min_want_count=filter_want_min,
                    max_want_count=filter_want_max,
                    resolve_item_metrics=(
                        _resolve_item_metrics if needs_detail_metrics else None
                    ),
                    on_progress=_search_progress,
                )
        except GoofishSearchError as error:
            _mark_cookie_failure_by_kind(cookie_record, error.kind)
            if error.kind == 'user_validate':
                message = '闲鱼登录需要验证，请扫码恢复后重试'
            elif error.kind == 'risk_control':
                message = '闲鱼触发平台风控，本任务已停止且不会立即重试'
            else:
                message = f'闲鱼搜索失败：{error}'
            raise RuntimeError(message) from error

        parsed_items = collection.items
        valid_items = collection.valid_items
        valid_count = len(valid_items)
        if collection.stop_reason == 'target_reached':
            search_message = (
                f'已达到目标：有效 {valid_count}/{fetch_count} 条，'
                f'扫描 {collection.pages_scanned} 页'
            )
        elif collection.stop_reason == 'result_exhausted' and valid_count == 0:
            search_message = '搜索成功，但该价格区间暂无匹配商品'
        elif collection.stop_reason == 'result_exhausted':
            search_message = (
                f'结果已耗尽：有效 {valid_count}/{fetch_count} 条，'
                f'扫描 {collection.pages_scanned} 页'
            )
        else:
            search_message = (
                f'达到安全页数上限：有效 {valid_count}/{fetch_count} 条，'
                f'扫描 {collection.pages_scanned} 页'
            )

        logger.info(
            f'搜索完成: 获取 {len(parsed_items)} 条, 有效 {valid_count} 条 '
            f'(目标 {fetch_count})'
        )
        _broadcast({
            'type': 'source_search_done', 'task_id': task_id,
            'message': search_message,
            'data': {
                'total': len(parsed_items),
                'valid': valid_count,
                'target': fetch_count,
                'scanned': collection.scanned_count,
                'pages': collection.pages_scanned,
                'stop_reason': collection.stop_reason,
            }
        })

        # ── Step 4: LLM 规则生成 + 写入采集器已判定的搜索结果 ───────────────
        crawl_time = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        valid_item_ids = {item.get('item_id') for item in valid_items}
        item_records = [
            (parsed, parsed.get('item_id') in valid_item_ids)
            for parsed in parsed_items
        ]

        # ── Step 4.5: DeepSeek LLM 生成规则 + 全部商品本地判定 ────────────
        if parsed_items:
            _broadcast({
                'type': 'source_llm_start', 'task_id': task_id,
                'message': f'正在使用 AI 生成 {len(parsed_items)} 个商品的判定规则...'
            })

            # LLM 分析全部商品的批次统计，生成规则（仅 1 次调用）
            rules = generate_rules(parsed_items)

            # 本地规则判定全部商品（0 次 LLM 调用）
            llm_results = apply_rules(parsed_items, rules)

            # 将判定结果注入所有 parsed_items（包括有效和无效）
            for parsed, result in zip(parsed_items, llm_results):
                parsed['heat_level'] = result.get('heat_level', '中')
                parsed['high_value'] = result.get('high_value', '中')
                parsed['competitiveness'] = result.get('competitiveness', '中')

            logger.info(f'规则判定完成: 全部 {len(llm_results)} 个商品（有效 {len(valid_items)} + 无效 {len(llm_results) - len(valid_items)}）')
            _broadcast({
                'type': 'source_llm_done', 'task_id': task_id,
                'message': f'AI 规则生成 + 判定完成: {len(llm_results)} 个商品'
            })

        source_items = [
            _map_search_item_to_source(p, task_id, execution_no, keyword, v, crawl_time)
            for p, v in item_records
        ]
        row_ids = _batch_insert_items(conn, source_items)

        # 建立 valid_items → row_id 的映射（只取 is_valid=1 的）
        valid_row_ids = [
            rid for rid, (_, v) in zip(row_ids, item_records) if v
        ]

        logger.info(f'写入搜索结果: 共 {len(source_items)} 条, 有效 {len(valid_items)} 条')
        _broadcast({
            'type': 'source_items_done', 'task_id': task_id,
            'message': f'结果写入完成: {len(source_items)} 条, 有效 {len(valid_items)} 条',
            'data': {'total': len(source_items), 'valid': len(valid_items)}
        })

        # ── Step 5: 对有效商品采集详情（智能延迟 + 指数退避）────────────
        detail_ok = 0
        detail_fail = 0
        consecutive_fails = 0  # 连续失败计数
        detail_stop_error = None
        db_ready_details = []

        if valid_items:
            stale_conn = conn
            conn = None
            try:
                stale_conn.close()
            except Exception:
                pass

            try:
                with _GOOFISH_REQUEST_LOCK:
                    for idx, (parsed, row_id) in enumerate(zip(valid_items, valid_row_ids)):
                        ext_item_id = parsed.get('item_id', '')
                        _broadcast({
                            'type': 'source_detail_progress', 'task_id': task_id,
                            'current': idx + 1, 'total': len(valid_items),
                            'message': f'详情 [{idx+1}/{len(valid_items)}] {ext_item_id}'
                        })

                        try:
                            parsed_detail = prefetched_details.get(ext_item_id)
                            if parsed_detail is not None:
                                detail_record = _map_detail_item_to_source(
                                    parsed_detail, task_id, row_id, keyword, crawl_time
                                )
                                db_ready_details.append((
                                    detail_record,
                                    row_id,
                                    ext_item_id,
                                    parsed_detail.get('view_count', 0),
                                ))
                                consecutive_fails = 0
                            else:
                                detail_raw = api.get_item_detail(ext_item_id)
                                response_kind = classify_response(detail_raw)

                                if response_kind == RESPONSE_SUCCESS:
                                    parsed_detail = api.parse_detail_item(detail_raw.get('data', {}))
                                    detail_record = _map_detail_item_to_source(
                                        parsed_detail, task_id, row_id, keyword, crawl_time
                                    )
                                    db_ready_details.append((
                                        detail_record,
                                        row_id,
                                        ext_item_id,
                                        parsed_detail.get('view_count', 0),
                                    ))
                                    consecutive_fails = 0
                                else:
                                    detail_fail += 1
                                    consecutive_fails += 1
                                    detail_stop_error = _detail_stop_message(response_kind)
                                    ret = detail_raw.get('ret', []) if isinstance(detail_raw, dict) else []

                                    if detail_stop_error:
                                        logger.warning(
                                            f'详情阶段终止: {ext_item_id}, kind={response_kind}'
                                        )
                                        break

                                    logger.warning(f'详情API失败: {ext_item_id}, ret={ret}')

                                    # 连续失败达阈值，触发批次暂停
                                    if consecutive_fails >= SOURCE_BACKOFF_BATCH_THRESHOLD:
                                        pause = SOURCE_BACKOFF_BATCH_PAUSE + random.uniform(0, 5)
                                        logger.info(
                                            f'连续失败 {consecutive_fails} 次，'
                                            f'批次暂停 {pause:.1f}s 等待风控冷却'
                                        )
                                        _broadcast({
                                            'type': 'source_detail_progress', 'task_id': task_id,
                                            'current': idx + 1, 'total': len(valid_items),
                                            'message': f'风控冷却中...暂停 {pause:.0f}s'
                                        })
                                        time.sleep(pause)
                                        consecutive_fails = 0  # 暂停后重置退避

                        except Exception as e:
                            logger.error(f'详情采集异常 {ext_item_id}: {e}')
                            detail_fail += 1
                            consecutive_fails += 1

                        # 智能延迟：基础间隔 + 随机抖动 + 指数退避
                        if (
                            idx < len(valid_items) - 1
                            and ext_item_id not in prefetched_details
                        ):
                            delay = _smart_delay(consecutive_fails)
                            logger.debug(
                                f'详情采集延迟: {delay:.1f}s '
                                f'(连续失败={consecutive_fails})'
                            )
                            time.sleep(delay)
            finally:
                conn = _get_conn()

        for detail_record, row_id, ext_item_id, view_count in db_ready_details:
            try:
                _insert_detail(conn, detail_record)

                # 回写 view_count 到搜索结果表（搜索API不提供，详情API才有）
                if view_count > 0:
                    _update_item_view_count(conn, row_id, view_count)

                detail_ok += 1
                logger.info(f'详情采集成功: {ext_item_id} ({detail_ok}/{len(valid_items)})')
            except Exception as e:
                logger.error(f'详情处理异常 {ext_item_id}: {e}')
                detail_fail += 1

        logger.info(f'详情采集完成: 成功 {detail_ok}, 失败 {detail_fail}')
        _broadcast({
            'type': 'source_detail_done', 'task_id': task_id,
            'message': f'详情完成: 成功{detail_ok} 失败{detail_fail}',
            'data': {'ok': detail_ok, 'fail': detail_fail}
        })

        if detail_stop_error:
            raise RuntimeError(detail_stop_error)

        # ── Step 6: 汇总统计 ─────────────────────────────────────────────
        valid_prices_fen = [_yuan_to_fen(p.get('price', 0.0)) for p in valid_items]
        actual_min = min(valid_prices_fen) if valid_prices_fen else None
        actual_max = max(valid_prices_fen) if valid_prices_fen else None
        actual_avg = (
            int(sum(valid_prices_fen) / len(valid_prices_fen))
            if valid_prices_fen else None
        )
        price_index = calculate_price_index(valid_prices_fen)
        reached_target = len(valid_items) >= fetch_count
        execution_status = _resolve_execution_status(valid_count, fetch_count)

        if not reached_target:
            logger.warning(
                '货源任务有效数不足: task_id=%s, execution_no=%s, valid=%s, target=%s, source_cookie_id=%s',
                task_id,
                execution_no,
                len(valid_items),
                fetch_count,
                cookie_record.get('id'),
            )

        _update_execution_summary(
            conn, exec_id,
            fetch_count=len(parsed_items),
            valid_count=len(valid_items),
            min_price=actual_min,
            max_price=actual_max,
            avg_price=actual_avg,
            price_index=price_index,
            status=execution_status,
        )
        _update_task_executed(
            conn,
            task_id,
            status=_resolve_task_status_after_execution(task.get('trigger_mode'), reached_target),
        )

        logger.info(
            f'货源任务完成: task_id={task_id}, execution_no={execution_no}, '
            f'valid={len(valid_items)}, price_range=[{actual_min},{actual_max}]分'
        )
        _broadcast({
            'type': 'source_done', 'task_id': task_id,
            'message': (
                f'任务完成: 有效{len(valid_items)}条, 价格{actual_min}~{actual_max}分'
                if reached_target else
                f'任务结束: 有效{len(valid_items)}/{fetch_count}条，未达到目标'
            )
        })

        return {
            'success': True,
            'task_id': task_id,
            'execution_no': execution_no,
            'fetch_count': len(parsed_items),
            'valid_count': len(valid_items),
            'detail_ok': detail_ok,
            'detail_fail': detail_fail,
            'min_price': actual_min,
            'max_price': actual_max,
            'avg_price': actual_avg,
            'price_index': price_index,
        }

    except Exception as e:
        logger.error(f'货源任务执行失败 task_id={task_id}: {e}')
        if exec_id and conn:
            try:
                _update_execution_failed(conn, exec_id, str(e))
            except Exception as ex:
                logger.error(f'更新失败状态时出错: {ex}')
        # 未产生批次记录时为提交失败(status=3)，否则标记已完成(status=2)
        if conn:
            try:
                if exec_id:
                    _update_task_executed(
                        conn,
                        task_id,
                        status=_resolve_task_status_after_execution(task.get('trigger_mode'), False),
                    )
                else:
                    _update_task_executed(
                        conn,
                        task_id,
                        status=SOURCE_TASK_STATUS_SUBMIT_FAILED,
                        error_message=str(e),
                    )
            except Exception as ex:
                logger.error(f'更新任务状态失败: {ex}')
        _broadcast({'type': 'source_error', 'task_id': task_id, 'message': str(e)})
        return {'success': False, 'error': str(e)}

    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


# ── 查询接口 ──────────────────────────────────────────────────────────────────

def get_task_status(task_id: int) -> dict:
    """查询任务信息及其执行历史"""
    conn = _get_conn()
    try:
        task = _get_task(conn, task_id)
        if not task:
            return {'success': False, 'error': '任务不存在'}

        with conn.cursor() as cur:
            cur.execute(
                '''SELECT * FROM app_source_execution
                   WHERE task_id = %s AND deleted = 0
                   ORDER BY execution_no DESC LIMIT 50''',
                (task_id,)
            )
            executions = cur.fetchall()

        return {'success': True, 'task': task, 'executions': executions}
    except Exception as e:
        return {'success': False, 'error': str(e)}
    finally:
        conn.close()


def get_executions(task_id: int) -> dict:
    """查询某任务的所有批次汇总"""
    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                '''SELECT * FROM app_source_execution
                   WHERE task_id = %s AND deleted = 0
                   ORDER BY execution_no DESC''',
                (task_id,)
            )
            rows = cur.fetchall()
        return {'success': True, 'executions': rows, 'total': len(rows)}
    except Exception as e:
        return {'success': False, 'error': str(e)}
    finally:
        conn.close()


def get_items(
    task_id: int,
    execution_no: Optional[int] = None,
    is_valid: Optional[int] = None,
    limit: int = 200,
) -> dict:
    """查询搜索结果"""
    conn = _get_conn()
    try:
        conditions = ['task_id = %s', 'deleted = 0']
        params: list = [task_id]

        if execution_no is not None:
            conditions.append('execution_no = %s')
            params.append(execution_no)
        if is_valid is not None:
            conditions.append('is_valid = %s')
            params.append(is_valid)

        where = ' AND '.join(conditions)
        sql = f'SELECT * FROM app_source_item WHERE {where} ORDER BY id DESC LIMIT %s'
        params.append(limit)

        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return {'success': True, 'items': rows, 'total': len(rows)}
    except Exception as e:
        return {'success': False, 'error': str(e)}
    finally:
        conn.close()


def get_item_detail(
    task_id: int,
    external_item_id: Optional[str] = None,
    item_row_id: Optional[int] = None,
    limit: int = 200,
) -> dict:
    """查询商品详情"""
    conn = _get_conn()
    try:
        conditions = ['task_id = %s', 'deleted = 0']
        params: list = [task_id]

        if external_item_id:
            conditions.append('external_item_id = %s')
            params.append(external_item_id)
        if item_row_id is not None:
            conditions.append('item_id = %s')
            params.append(item_row_id)

        where = ' AND '.join(conditions)
        sql = f'SELECT * FROM app_source_item_detail WHERE {where} ORDER BY id DESC LIMIT %s'
        params.append(limit)

        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return {'success': True, 'items': rows, 'total': len(rows)}
    except Exception as e:
        return {'success': False, 'error': str(e)}
    finally:
        conn.close()


def execute_detail_only(task_id: int, execution_no: int, manager=None, loop=None):
    """
    单独触发详情采集（对某批次 is_valid=1 的商品）
    用于 Java 端需要分步触发搜索和详情的场景
    """
    def _broadcast(msg: dict):
        _safe_broadcast(msg, manager, loop)

    conn = None
    try:
        conn = _get_conn()

        # 读取有效商品
        with conn.cursor() as cur:
            cur.execute(
                '''SELECT id, external_item_id FROM app_source_item
                   WHERE task_id = %s AND execution_no = %s AND is_valid = 1 AND deleted = 0
                   ORDER BY id''',
                (task_id, execution_no)
            )
            items = cur.fetchall()

        if not items:
            return {'success': False, 'error': '该批次无有效商品'}

        cookie_record = _acquire_pool_cookie_or_raise()
        api = _build_search_api_from_pool(cookie_record)
        task = _get_task(conn, task_id)
        keyword = task.get('product_name', '') if task else ''
        crawl_time = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        ok = 0
        fail = 0
        consecutive_fails = 0  # 连续失败计数
        detail_stop_error = None
        db_ready_details = []

        stale_conn = conn
        conn = None
        try:
            stale_conn.close()
        except Exception:
            pass

        try:
            with _GOOFISH_REQUEST_LOCK:
                for idx, item in enumerate(items):
                    ext_id = item['external_item_id']
                    row_id = item['id']

                    _broadcast({
                        'type': 'source_detail_progress', 'task_id': task_id,
                        'current': idx + 1, 'total': len(items),
                        'message': f'详情 [{idx+1}/{len(items)}] {ext_id}'
                    })

                    try:
                        detail_raw = api.get_item_detail(ext_id)
                        response_kind = classify_response(detail_raw)

                        if response_kind == RESPONSE_SUCCESS:
                            parsed = api.parse_detail_item(detail_raw.get('data', {}))
                            record = _map_detail_item_to_source(
                                parsed, task_id, row_id, keyword, crawl_time
                            )
                            db_ready_details.append((record, ext_id))
                            consecutive_fails = 0
                        else:
                            _mark_cookie_failure_by_kind(cookie_record, response_kind)
                            fail += 1
                            consecutive_fails += 1
                            detail_stop_error = _detail_stop_message(response_kind)
                            ret = detail_raw.get('ret', []) if isinstance(detail_raw, dict) else []

                            if detail_stop_error:
                                logger.warning(
                                    f'详情阶段终止: {ext_id}, kind={response_kind}'
                                )
                                break

                            logger.warning(f'详情API失败: {ext_id}, ret={ret}')

                            if consecutive_fails >= SOURCE_BACKOFF_BATCH_THRESHOLD:
                                pause = SOURCE_BACKOFF_BATCH_PAUSE + random.uniform(0, 5)
                                logger.info(
                                    f'连续失败 {consecutive_fails} 次，'
                                    f'批次暂停 {pause:.1f}s 等待风控冷却'
                                )
                                _broadcast({
                                    'type': 'source_detail_progress', 'task_id': task_id,
                                    'current': idx + 1, 'total': len(items),
                                    'message': f'风控冷却中...暂停 {pause:.0f}s'
                                })
                                time.sleep(pause)
                                consecutive_fails = 0

                    except Exception as e:
                        logger.error(f'详情采集异常 {ext_id}: {e}')
                        fail += 1
                        consecutive_fails += 1

                    # 智能延迟：基础间隔 + 随机抖动 + 指数退避
                    if idx < len(items) - 1:
                        delay = _smart_delay(consecutive_fails)
                        logger.debug(
                            f'详情采集延迟: {delay:.1f}s '
                            f'(连续失败={consecutive_fails})'
                        )
                        time.sleep(delay)
        finally:
            conn = _get_conn()

        for record, ext_id in db_ready_details:
            try:
                _insert_detail(conn, record)
                ok += 1
                logger.info(f'详情采集成功: {ext_id} ({ok}/{len(items)})')
            except Exception as e:
                logger.error(f'详情处理异常 {ext_id}: {e}')
                fail += 1

        _broadcast({
            'type': 'source_detail_done', 'task_id': task_id,
            'message': f'详情完成: 成功{ok} 失败{fail}',
            'data': {'ok': ok, 'fail': fail}
        })

        if detail_stop_error:
            return {
                'success': False,
                'error': detail_stop_error,
                'ok': ok,
                'fail': fail,
            }

        return {'success': True, 'ok': ok, 'fail': fail}

    except Exception as e:
        logger.error(f'详情采集失败 task_id={task_id}: {e}')
        return {'success': False, 'error': str(e)}
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


# ── 货源入库功能 ──────────────────────────────────────────────────────────────

def _resolve_inventory_attributes(detail: Dict[str, Any]) -> Tuple[str, str, str]:
    """解析入库所需的成色、功能状态和品牌。"""
    item_condition = detail.get('item_condition', '') or ''
    function_status = detail.get('function_status', '') or ''
    brand = detail.get('brand', '') or ''

    cpv_labels = detail.get('cpv_labels')
    if isinstance(cpv_labels, str):
        try:
            cpv_labels = json.loads(cpv_labels)
        except (json.JSONDecodeError, TypeError):
            cpv_labels = None

    if isinstance(cpv_labels, list):
        for label in cpv_labels:
            if not isinstance(label, dict):
                continue
            prop = label.get('property', '')
            value = label.get('value', '')
            if prop == '成色' and not item_condition:
                item_condition = value
            elif prop == '功能状态' and not function_status:
                function_status = value
            elif prop == '品牌' and not brand:
                brand = value

    return item_condition, function_status, brand


def _resolve_inventory_location(detail: Dict[str, Any]) -> Tuple[str, str, str]:
    """组合详情城市和搜索结果省份。"""
    location = str(detail.get('location', '') or '').strip()
    source_location = str(detail.get('source_location', '') or '').strip()
    province, city = '', ''

    if location:
        parts = location.split()
        if len(parts) >= 2:
            province, city = parts[0], parts[1]
        else:
            city = parts[0]

    if not province and source_location:
        province = source_location.split()[0]

    return location, province, city


def _resolve_inventory_images(detail: Dict[str, Any]) -> Optional[str]:
    """在完整多图尚未落库时，使用主图构建图片数组。"""
    pic_url = detail.get('pic_url', '') or ''
    return json.dumps([pic_url], ensure_ascii=False) if pic_url else None


def add_to_inventory(detail_id: int, inventory_user: str = 'system', tenant_id: int = 1) -> int:
    """
    将商品详情添加到货源库（严格按 inventory 表字段映射）
    
    Args:
        detail_id: app_source_item_detail 的 id
        inventory_user: 入库操作人
        tenant_id: 租户ID
        
    Returns:
        插入的 inventory id
    """
    conn = _get_conn()
    try:
        # 1. 从详情表读取数据
        with conn.cursor() as cur:
            cur.execute('''
                SELECT d.*, i.location AS source_location
                FROM app_source_item_detail d
                LEFT JOIN app_source_item i
                    ON i.id = d.item_id AND i.deleted = 0
                WHERE d.id = %s AND d.deleted = 0
            ''', (detail_id,))
            detail = cur.fetchone()
            
        if not detail:
            raise ValueError(f'详情记录不存在: detail_id={detail_id}')
        
        # 2. 检查是否已入库
        with conn.cursor() as cur:
            cur.execute('''
                SELECT id FROM app_source_inventory 
                WHERE source_detail_id = %s AND deleted = 0
            ''', (detail_id,))
            existing = cur.fetchone()
            if existing:
                raise ValueError(f'该商品已入库: inventory_id={existing["id"]}')
        
        # 3. 补全商品属性、地址和图片
        item_condition, function_status, brand = _resolve_inventory_attributes(detail)
        location, province, city = _resolve_inventory_location(detail)
        images = _resolve_inventory_images(detail)
        
        # 4. 构建插入语句
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        
        insert_sql = '''
            INSERT INTO app_source_inventory (
                task_id, source_item_id, source_detail_id, external_item_id,
                title, description, search_keyword,
                price, original_price, suggested_price, profit_margin,
                item_condition, function_status, brand,
                pic_url, detail_url, images,
                stock, location, province, city, transport_fee,
                view_count, want_count, sold_count,
                seller_name, seller_city, seller_credit, seller_level,
                category_name, item_type, tag_list,
                inventory_status, inventory_time, inventory_user,
                xianyu_product_id, xianyu_account_id, xianyu_remote_id, xianyu_status,
                remark, audit_remark,
                creator, create_time, update_time, tenant_id
            ) VALUES (
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s,
                %s, NOW(), NOW(), %s
            )
        '''
        
        with conn.cursor() as cur:
            cur.execute(insert_sql, (
                # 关联字段
                detail.get('task_id'),
                detail.get('item_id'),
                detail_id,
                detail.get('external_item_id', ''),
                
                # 核心商品信息
                detail.get('title'),
                detail.get('description'),
                detail.get('search_keyword', ''),
                
                # 价格信息
                detail.get('price', 0),
                detail.get('original_price', 0),
                None,  # suggested_price
                None,  # profit_margin
                
                # 商品属性（新增字段）
                item_condition,
                function_status,
                brand,
                
                # 图片与链接
                detail.get('pic_url', ''),
                detail.get('detail_url', ''),
                images,
                
                # 库存与物流
                detail.get('quantity', 1),
                location,
                province,
                city,
                detail.get('transport_fee', ''),
                
                # 统计数据
                detail.get('view_count', 0),
                detail.get('want_count', 0),
                detail.get('sold_count', 0),
                
                # 卖家信息
                detail.get('seller_name', ''),
                detail.get('seller_city', ''),
                detail.get('seller_credit_tag', ''),
                detail.get('seller_zhima_level', ''),
                
                # 分类与标签
                detail.get('category_name', ''),
                detail.get('item_type', ''),
                detail.get('tag_list'),
                
                # 业务状态
                1,  # inventory_status: 0=待审核,1=已入库
                now,  # inventory_time
                inventory_user,
                
                # 闲鱼关联
                None,  # xianyu_product_id
                None,  # xianyu_account_id
                None,  # xianyu_remote_id
                0,     # xianyu_status
                
                # 备注
                '',  # remark
                '',  # audit_remark
                
                # 标准字段
                'python',
                tenant_id,
            ))
            
            inventory_id = cur.lastrowid
            
        conn.commit()
        logger.info(f'商品已入库: detail_id={detail_id} -> inventory_id={inventory_id}')
        return inventory_id
        
    except pymysql.err.IntegrityError as e:
        conn.rollback()
        if e.args and e.args[0] == 1062:
            raise ValueError(f'该商品已入库: detail_id={detail_id}') from e
        logger.error(f'入库失败: detail_id={detail_id}, error={e}')
        raise
    except Exception as e:
        conn.rollback()
        logger.error(f'入库失败: detail_id={detail_id}, error={e}')
        raise
    finally:
        conn.close()


def manual_import_by_url(detail_url: str, inventory_user: str = 'system', tenant_id: int = 1) -> int:
    """从闲鱼链接读取真实详情并直接写入货源库，不创建采集链路记录。"""
    from urllib.parse import parse_qs, urlparse
    import re

    parsed_url = urlparse((detail_url or '').strip())
    hostname = (parsed_url.hostname or '').lower()
    if not (hostname == 'goofish.com' or hostname.endswith('.goofish.com')
            or hostname == '2.taobao.com' or hostname.endswith('.taobao.com')):
        raise ValueError('链接不是有效的闲鱼商品链接')

    item_id = (parse_qs(parsed_url.query).get('id') or [None])[0]
    if not item_id:
        match = re.search(r'/item/(\d+)', parsed_url.path)
        item_id = match.group(1) if match else None
    if not item_id or not re.fullmatch(r'\d+', str(item_id)):
        raise ValueError('无法从闲鱼链接解析商品ID')

    cookie_record = _acquire_pool_cookie_or_raise()
    api = _build_search_api_from_pool(cookie_record)
    with _GOOFISH_REQUEST_LOCK:
        raw = api.get_item_detail(str(item_id))
    response_kind = classify_response(raw)
    if response_kind != RESPONSE_SUCCESS:
        _mark_cookie_failure_by_kind(cookie_record, response_kind)
        raise ValueError('闲鱼商品详情获取失败，请确认链接仍然有效')

    detail = api.parse_detail_item(raw.get('data', {}))
    if str(detail.get('item_id', '')) != str(item_id):
        raise ValueError('闲鱼商品详情缺少有效商品ID')

    conn = _get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute('''
                SELECT id FROM app_source_inventory
                WHERE tenant_id = %s AND external_item_id = %s AND deleted = 0
                LIMIT 1
            ''', (tenant_id, str(item_id)))
            existing = cur.fetchone()
            if existing:
                raise ValueError(f'该链接已入库: inventory_id={existing["id"]}')

        item_condition, function_status, brand = _resolve_inventory_attributes(detail)
        location, province, city = _resolve_inventory_location(detail)
        images = _resolve_inventory_images(detail)
        insert_sql = '''
            INSERT INTO app_source_inventory (
                task_id, source_item_id, source_detail_id, external_item_id,
                title, description, search_keyword, price, original_price,
                item_condition, function_status, brand, pic_url, detail_url, images,
                stock, location, province, city, transport_fee,
                view_count, want_count, sold_count, seller_name, seller_city,
                seller_credit, seller_level, category_name, item_type, tag_list,
                inventory_status, inventory_time, inventory_user,
                xianyu_status, remark, audit_remark, creator, create_time, update_time, tenant_id
            ) VALUES (
                NULL, NULL, NULL, %s, %s, %s, '', %s, %s,
                %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                1, NOW(), %s, 0, '', '', 'python', NOW(), NOW(), %s
            )
        '''
        with conn.cursor() as cur:
            cur.execute(insert_sql, (
                str(item_id), detail.get('title', ''), detail.get('description', ''),
                _yuan_to_fen(detail.get('price', 0.0)),
                _yuan_to_fen(detail.get('original_price', 0.0)),
                item_condition, function_status, brand,
                detail.get('pic_url', ''), detail_url.strip(), images,
                detail.get('quantity', 1) or 1, location, province, city,
                str(detail.get('transport_fee', '')), detail.get('view_count', 0),
                detail.get('want_count', 0), detail.get('sold_count', 0),
                detail.get('seller_name', ''), detail.get('seller_city', ''),
                detail.get('seller_credit_tag', ''), detail.get('seller_level', ''),
                detail.get('category_name', ''), detail.get('item_type', ''),
                json.dumps(detail.get('tag_list'), ensure_ascii=False)
                if detail.get('tag_list') else None,
                inventory_user, tenant_id,
            ))
            inventory_id = cur.lastrowid
        conn.commit()
        logger.info('人工链接入库成功: item_id=%s, inventory_id=%s', item_id, inventory_id)
        return inventory_id
    except pymysql.err.IntegrityError as error:
        conn.rollback()
        if error.args and error.args[0] == 1062:
            raise ValueError(f'该链接已入库: item_id={item_id}') from error
        raise
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
