# 货源分析模块（Goofish Source）

> 闲鱼商品 **搜索采集 → AI 智能分析 → 价格筛选 → 详情爬取 → 数据入库** 全流程服务

## 项目简介

本项目是一个基于 **FastAPI + Uvicorn** 的 Python 后端服务，负责对接闲鱼平台，完成货源商品的自动化搜索、筛选与详情采集。集成 **DeepSeek 大模型** 对商品进行智能分析，采用 **LLM 规则生成 + 本地判定** 的高效架构，为每个商品打标热度、高价值、竞争力三维指标。

Java 端通过 MySQL 创建任务配置，本服务读取任务后在后台线程池中异步执行（最大 4 并发），并通过 WebSocket 实时推送执行进度。

**技术栈：** Python 3 / FastAPI / Uvicorn / PyMySQL / WebSocket / PyExecJS / OpenAI SDK (DeepSeek)

---

## 目录结构

```
source/
├── server.py                # 服务启动入口（FastAPI 应用 + WebSocket）
├── route.py                 # API 路由层（接口定义与请求分发）
├── service.py               # 业务逻辑层（搜索、筛选、AI 分析、采集、入库）
├── llm_analyzer.py          # DeepSeek LLM 规则生成器 + 本地判定引擎
├── config.py                # 配置文件（数据库、API、LLM、间隔策略等）
├── goofish_search_api.py    # 闲鱼搜索/详情 API 封装
├── goofish_utils.py         # 工具函数（Cookie 管理、签名生成等）
├── gen_tfstk.js             # Token 生成脚本（JS）
├── cookie.json              # 闲鱼登录 Cookie
├── requirements.txt         # Python 依赖
├── static/
│   └── goofish_js_version_2.js  # 签名算法 JS
├── test/
│   ├── test_source_api.py   # 接口测试脚本
│   ├── simulate_test.py     # 模拟测试
│   ├── quick_test.py        # 快速 API 测试
│   └── TEST_SOURCE_API.md   # 测试文档
├── logs/
│   └── source.log           # 运行日志
└── SOURCE_API.md            # 详细 API 文档
```

---

## 快速开始

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

主要依赖：

| 包 | 说明 |
|---|---|
| `fastapi` | Web 框架 |
| `uvicorn[standard]` | ASGI 服务器 |
| `websockets` | WebSocket 支持 |
| `pymysql` | MySQL 驱动 |
| `requests` | HTTP 请求 |
| `PyExecJS` | JS 脚本执行（Token 签名生成） |
| `openai` | OpenAI 兼容 SDK（调用 DeepSeek API） |

### 2. 配置

通过环境变量配置（参考 `.env.example`）：

```bash
export SOURCE_DB_HOST=127.0.0.1
export SOURCE_DB_PORT=3306
export SOURCE_DB_USER=xianyuops
export SOURCE_DB_PASSWORD=your-password
export SOURCE_DB_NAME=xianyuops

export DEEPSEEK_BASE_URL=https://api.deepseek.com
export DEEPSEEK_API_KEY=sk-your-api-key
export DEEPSEEK_MODEL=deepseek-chat
```

确保 `cookie.json` 中包含有效的闲鱼登录 Cookie（需包含 `_m_h5_tk` 字段）。

### 3. 初始化数据库

执行仓库根目录的 `sql/mysql/app/app-source.sql` 创建所需数据表：

- `app_source_task` — 货源采集任务配置
- `app_source_execution` — 批次汇总记录（含价格指数）
- `app_source_item` — 搜索结果（含 AI 分析三维指标）
- `app_source_item_detail` — 商品详情

### 4. 启动服务

```bash
# 方式一：直接运行
python source/server.py

# 方式二：uvicorn 启动（支持热重载）
python -m uvicorn source.server:app --host 0.0.0.0 --port 8002 --reload
```

启动后服务地址：`http://localhost:8002`
健康检查：`http://localhost:8002/api/health`
WebSocket：`ws://localhost:8002/ws/progress`

---

## 核心特性：AI 智能分析

### 架构设计

采用 **LLM 规则生成 + 本地判定** 的高效架构，仅 1 次大模型调用即可完成整批商品的智能分析：

```
全部商品数据 ──→ 批次统计（价格/想要/折扣率/信用分布）
                      │
                      ▼
          DeepSeek LLM（1 次调用）──→ 生成判定规则 JSON
                      │
                      ▼
         本地 if/else 规则引擎（0 次 LLM 调用）──→ 全量商品打标
```

### 三维度分析

| 维度 | 字段 | 值 | 判定依据 |
|------|------|---|---------|
| 热度 | `heat_level` | 高/中/低 | want_count（想要人数）+ view_count（浏览量） |
| 高价值 | `high_value` | 高/中/低/- | 折扣率 + 卖家信用等级 + 好评率 |
| 竞争力 | `competitiveness` | 高/中/低 | 折扣率 + 卖家信用等级 |

### 规则示例（LLM 生成）

```json
{
  "heat_level": {
    "high":   {"want_count_min": 10000, "view_count_min": 0},
    "medium": {"want_count_min": 1000, "view_count_min": 0}
  },
  "high_value": {
    "high":   {"discount_rate_min": 50, "seller_credit": ["极好"], "good_rate_min": 90},
    "medium": {"discount_rate_min": 20, "seller_credit": ["极好", "优秀"], "good_rate_min": 70}
  },
  "competitiveness": {
    "high":   {"discount_rate_min": 50, "seller_credit": ["极好"]},
    "medium": {"discount_rate_min": 20, "seller_credit": ["极好", "优秀"]}
  }
}
```

规则由 DeepSeek 根据实际批次数据的统计分布动态生成，确保判定标准与当前市场情况匹配。LLM 调用失败时自动回退到内置默认规则。

### 价格指数

`price_index` 表示有效商品在不同价格段的分布数量（共 7 个价格段：0-10/10-50/50-100/100-200/200-500/500-1000/1000+ 元），数值越大说明价格覆盖越广。

---

## 业务流程

```
Java 端创建任务 (app_source_task)
    │
    ▼
POST /api/source/execute ──→ Python 后台线程池执行（最大 4 并发）
    │                              │
    │               ┌──────────────┼──────────────────┐
    │               ▼              ▼                  ▼
    │         搜索闲鱼     DeepSeek 规则生成     价格筛选
    │               │              │                  │
    │               ▼              ▼                  ▼
    │         全量本地判定（热度/高价值/竞争力）
    │               │                                 │
    │               ▼                                 ▼
    │     app_source_item（全量写入，标记 is_valid + AI 指标）
    │               │
    │               ▼
    │     有效商品详情采集（智能延迟 + 指数退避）
    │               │
    │               ▼
    │     app_source_item_detail + 回写 view_count
    │               │
    │               ▼
    │     app_source_execution（批次汇总 + 价格指数）
    │               │
    ▼               ▼
WebSocket /ws/progress ──→ 实时推送执行进度
```

---

## API 接口

路径前缀：`/api/source`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/source/execute` | 触发货源任务执行（异步） |
| POST | `/api/source/execute-detail` | 单独触发详情采集 |
| GET | `/api/source/task/{task_id}` | 查询任务信息 + 执行历史 |
| GET | `/api/source/execution/{task_id}` | 查询批次汇总 |
| GET | `/api/source/items` | 查询搜索结果（含 AI 指标） |
| GET | `/api/source/item-detail` | 查询商品详情 |
| GET | `/api/health` | 健康检查 |

### 调用示例

**触发货源任务：**

```bash
curl -X POST http://localhost:8002/api/source/execute \
  -H "Content-Type: application/json" \
  -d '{"task_id": 1}'
```

**查询搜索结果（含 AI 指标）：**

```bash
curl "http://localhost:8002/api/source/items?task_id=1&is_valid=1&limit=10"
```

响应示例：

```json
{
  "success": true,
  "items": [
    {
      "title": "Steam充值卡50元 国区正版 自动发货",
      "price": 4700,
      "heat_level": "高",
      "high_value": "中",
      "competitiveness": "高",
      "view_count": 3690,
      "want_count": 5280,
      "seller_credit": "极好",
      "is_valid": "\u0001"
    }
  ],
  "total": 10
}
```

**Python 调用：**

```python
import requests

BASE = 'http://localhost:8002'

# 触发任务
r = requests.post(f'{BASE}/api/source/execute', json={'task_id': 1})
print(r.json())

# 查询有效商品（含 AI 分析指标）
r = requests.get(f'{BASE}/api/source/items', params={'task_id': 1, 'is_valid': 1, 'limit': 10})
for item in r.json()['items']:
    print(f"  {item['title'][:40]}  "
          f"¥{item['price']/100:.2f}  "
          f"热度={item['heat_level']}  "
          f"价值={item['high_value']}  "
          f"竞争={item['competitiveness']}")
```

更多调用示例（Java / curl）请参阅 [SOURCE_API.md](./SOURCE_API.md)。

---

## WebSocket 实时进度

连接地址：`ws://localhost:8002/ws/progress`

任务执行过程中推送以下消息类型：

| 消息类型 | 触发时机 |
|---------|---------|
| `source_start` | 任务开始执行 |
| `source_search_done` | 搜索完成 |
| `source_llm_start` | AI 规则生成开始 |
| `source_llm_done` | AI 规则生成 + 判定完成 |
| `source_items_done` | 搜索结果写入完成 |
| `source_detail_progress` | 单个详情采集进度 |
| `source_detail_done` | 详情采集全部完成 |
| `source_done` | 任务整体完成 |
| `source_error` | 执行出错 |

消息示例：

```json
{
  "type": "source_llm_done",
  "task_id": 1,
  "message": "AI 规则生成 + 判定完成: 20 个商品"
}
```

---

## 数据模型

```
app_source_task (任务配置)
    │  1:N
    ▼
app_source_execution (批次汇总 + 价格指数)
    │  1:N (通过 task_id + execution_no)
    ▼
app_source_item (搜索结果 + AI 三维指标)
    │  1:1 (通过 item_id)
    ▼
app_source_item_detail (商品详情)
```

### 核心表说明

| 表名 | 说明 |
|------|------|
| `app_source_task` | 任务配置：搜索关键词、价格范围、获取条数、执行方式。status 支持 3=提交失败 |
| `app_source_execution` | 批次汇总：获取/有效条数、价格统计、`price_index` 价格指数 |
| `app_source_item` | 搜索结果：全量写入 + `heat_level` / `high_value` / `competitiveness` AI 指标 + `view_count` + `seller_level` |
| `app_source_item_detail` | 有效商品详情（仅 is_valid=1），含完整卖家信息和浏览量 |

### app_source_item 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `view_count` | INT | 浏览量（有效商品从详情 API 回写，无效商品搜索 API 不提供） |
| `seller_level` | VARCHAR(32) | 卖家级别（优质/个人） |
| `heat_level` | VARCHAR(16) | 热度：高/中/低（AI 分析，全量商品） |
| `high_value` | VARCHAR(16) | 高价值：高/中/低/-（AI 分析，全量商品） |
| `competitiveness` | VARCHAR(16) | 竞争力：高/中/低（AI 分析，全量商品） |

### app_source_execution 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `price_index` | INT | 价格指数（价格段分布数量，0~7） |

### app_source_task 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `error_message` | VARCHAR(512) | 提交失败原因（status=3 时有值） |

详细字段说明请参阅 [SOURCE_API.md](./SOURCE_API.md) 第 5 节。

---

## 通用约定

### 价格单位

所有价格字段单位为**分**（int），展示时需除以 100。

| 展示 | 存储值 |
|------|--------|
| 1.00 元 | 100 |
| 9.90 元 | 990 |

### 状态枚举

**任务状态（app_source_task.status）：**

| 值 | 含义 |
|----|------|
| 0 | 运行中 |
| 1 | 已暂停 |
| 2 | 已完成 |
| 3 | 提交失败 |

**批次状态（app_source_execution.status）：**

| 值 | 含义 |
|----|------|
| 0 | 执行中 |
| 1 | 已完成 |
| 2 | 执行失败 |

### 通用响应格式

```json
{
  "success": true,
  "message": "",
  "data": {}
}
```

---

## 反爬与限流策略

详情采集时可能触发闲鱼风控（`RGV587_ERROR`），系统内置以下防护机制：

| 策略 | 配置项 | 默认值 |
|------|--------|--------|
| 智能延迟 | `SOURCE_DETAIL_INTERVAL_MIN` / `SOURCE_DETAIL_INTERVAL_MAX` | 5~8 秒 |
| 随机抖动 | `SOURCE_DETAIL_JITTER` | 2 秒 |
| 指数退避 | `SOURCE_BACKOFF_INITIAL` / `SOURCE_BACKOFF_MULTIPLIER` / `SOURCE_BACKOFF_MAX` | 10s / 2x / 120s |
| 批次暂停 | `SOURCE_BACKOFF_BATCH_PAUSE` / `SOURCE_BACKOFF_BATCH_THRESHOLD` | 60s / 5次 |

以上参数均可在 `config.py` 中调整。

---

## 注意事项

1. **价格单位**：所有价格字段单位为「分」，展示时需除以 100
2. **BIT 字段**：`is_valid`、`deleted` 等字段在 JSON 中表现为 `"\u0001"` / `"\u0000"`
3. **异步执行**：`POST /execute` 和 `POST /execute-detail` 立即返回，任务在后台线程池中执行
4. **并发限制**：线程池最大并发 4 个任务
5. **AI 指标覆盖**：`heat_level` / `high_value` / `competitiveness` 对全量商品打标（包括有效和无效商品）
6. **浏览量来源**：`view_count` 搜索 API 不提供，仅通过详情 API 获取并回写，因此只有有效商品（is_valid=1）有浏览量
7. **LLM 降级**：DeepSeek API 调用失败时自动使用内置默认规则，不影响任务执行
8. **数据量差异**：`app_source_item_detail` 数据量通常少于 `app_source_item`，因为只对有效商品采集详情
9. **WebSocket**：建议前端连接 WebSocket 获取实时进度，而非轮询
10. **Cookie**：需确保 `cookie.json` 中的闲鱼登录凭证有效

---

## 认证方式

| 项目 | 值 |
|------|---|
| 类型 | apiKey |
| 参数名 | `Authorization` |
| 位置 | Header |
| 格式 | `Bearer <token>` |
| 附加 Header | `tenant-id`（租户编号，默认值：1） |

> 当前版本无需认证即可调用，预留 Token 机制供后续对接平台使用。

---

## 完整 API 文档

详细的接口参数、响应字段、数据模型定义及多语言调用示例，请参阅 [SOURCE_API.md](./SOURCE_API.md)。
