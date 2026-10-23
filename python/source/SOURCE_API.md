# 货源分析模块 - 接口 API 文档

> 模块：xianyuops-module-app  
> 路径前缀：`/api/source`  
> 数据源：MySQL 8.x (xianyuops@app_source_*)  
> 运行时：FastAPI + uvicorn  
> 版本：1.0.0  

---

## 1. 模块概览

货源分析模块负责闲鱼商品的**搜索采集 → 价格筛选 → 详情爬取 → 数据入库**全流程。

**业务流程：**

```
Java 端创建任务(app_source_task)
    │
    ▼
POST /api/source/execute  ──→  Python 后台线程池执行
    │                              │
    │                     ┌────────┼────────────┐
    │                     ▼        ▼            ▼
    │               搜索闲鱼   价格筛选    详情采集
    │                     │        │            │
    │                     ▼        ▼            ▼
    │              app_source_item       app_source_item_detail
    │                     │                     │
    │                     └──────┬──────────────┘
    │                            ▼
    │                   app_source_execution (批次汇总)
    │                            │
    ▼                            ▼
WebSocket /ws/progress  ──→  实时推送执行进度
```

**数据流：**
- `app_source_task` → 任务配置（关键词、价格范围、获取条数）
- `app_source_execution` → 每次执行的批次汇总
- `app_source_item` → 搜索结果列表（全量写入，标记 is_valid）
- `app_source_item_detail` → 有效商品的详情（仅 is_valid=1 的商品）

---

## 2. 认证方式

| 项目 | 值 |
|------|---|
| 类型 | apiKey |
| 参数名 | `Authorization` |
| 位置 | Header |
| 格式 | `Bearer <token>` |
| 附加 Header | `tenant-id`（租户编号，integer，默认值：1） |

> 当前版本无需认证即可调用，预留 Token 机制供后续对接XianyuOps平台使用。

---

## 3. 通用约定

### 3.1 价格单位

所有价格字段单位为**分**（int），与XianyuOps平台保持一致。

| 展示 | 存储值 |
|------|--------|
| 1.00 元 | 100 |
| 9.90 元 | 990 |
| 145.00 元 | 14500 |

### 3.2 BIT 字段

MySQL BIT(1) 类型字段在 JSON 响应中以 Unicode 转义字符返回：

| JSON 值 | 含义 |
|---------|------|
| `"\u0001"` | true / 1 |
| `"\u0000"` | false / 0 |

涉及字段：`is_valid`、`deleted`、`is_free_shipping`、`seller_zhima_auth`

### 3.3 状态枚举

**app_source_task.status：**

| 值 | 含义 |
|----|------|
| 0 | 运行中 |
| 1 | 已暂停 |
| 2 | 已完成 |

**app_source_execution.status：**

| 值 | 含义 |
|----|------|
| 0 | 执行中 |
| 1 | 已完成 |
| 2 | 执行失败 |

### 3.4 通用响应格式

所有接口统一使用以下格式：

```json
{
  "success": true,
  "message": "",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 提示信息（失败时返回错误原因） |
| data | object/array | 业务数据 |

---

## 4. 接口详情

### 4.1 触发货源任务执行

**POST** `/api/source/execute`

触发货源任务在后台执行。接口立即返回，任务在线程池中异步执行（最大 4 并发）。

**执行流程：**
1. 读取 `app_source_task` 配置（关键词、价格范围、获取条数）
2. 调用闲鱼搜索 API，获取商品列表
3. 价格筛选，全量写入 `app_source_item`（有效的标记 is_valid=1）
4. 对有效商品逐个采集详情，写入 `app_source_item_detail`
5. 汇总统计，更新 `app_source_execution`
6. 更新 `app_source_task` 执行记录

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
|--------|------|------|------|------|
| task_id | body | integer(int64) | 是 | 任务ID，对应 app_source_task.id |

**请求体 (application/json)：**

```json
{
  "task_id": 1
}
```

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "message": "任务已提交执行, task_id=1"
}
```

**失败响应 (200)：**

```json
{
  "success": false,
  "error": "任务不存在"
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否提交成功 |
| message | string | 提交结果描述 |
| error | string | 错误信息（仅失败时返回） |

---

### 4.2 单独触发详情采集

**POST** `/api/source/execute-detail`

对已完成的搜索批次，单独触发详情采集。适用于分步触发（先搜索、后详情）或重新采集详情的场景。

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
|--------|------|------|------|------|
| task_id | body | integer(int64) | 是 | 任务ID |
| execution_no | body | integer(int32) | 是 | 批次号（对应 app_source_execution.execution_no） |

**请求体 (application/json)：**

```json
{
  "task_id": 1,
  "execution_no": 1
}
```

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "message": "详情采集已提交, task_id=1, batch=1"
}
```

#### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否提交成功 |
| message | string | 提交结果描述 |

---

### 4.3 查询任务信息 + 执行历史

**GET** `/api/source/task/{task_id}`

返回任务配置信息及该任务的所有执行批次记录。

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
|--------|------|------|------|------|
| task_id | path | integer(int64) | 是 | 任务ID |

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "task": {
    "id": 1,
    "product_name": "Steam充值卡",
    "filter_min_price": 100,
    "filter_max_price": 20000,
    "fetch_count": 20,
    "executed_count": 5,
    "status": 2,
    "trigger_mode": 0,
    "schedule_time": "",
    "last_executed_at": "2026-06-05T14:14:54",
    "creator": "admin",
    "create_time": "2026-06-04T10:00:00",
    "updater": "admin",
    "update_time": "2026-06-05T14:14:54",
    "deleted": "\u0000",
    "tenant_id": 1
  },
  "executions": [
    {
      "id": 5,
      "task_id": 1,
      "execution_no": 5,
      "keyword": "Steam充值卡",
      "fetch_count": 20,
      "valid_count": 20,
      "min_price": 100,
      "max_price": 14500,
      "avg_price": 1560,
      "executed_at": "2026-06-05T14:12:26",
      "status": 1,
      "error_msg": "",
      "creator": "python",
      "create_time": "2026-06-05T14:12:26",
      "updater": "python",
      "update_time": "2026-06-05T14:14:54",
      "deleted": "\u0000",
      "tenant_id": 1
    }
  ],
  "total": 5
}
```

#### task 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer(int64) | 任务主键 |
| product_name | string | 搜索关键词 |
| filter_min_price | integer(int32) | 筛选最低价（分），null 表示不限 |
| filter_max_price | integer(int32) | 筛选最高价（分），null 表示不限 |
| fetch_count | integer(int32) | 每次获取条数 |
| executed_count | integer(int32) | 已执行次数 |
| status | integer(int32) | 任务状态：0=运行中 1=已暂停 2=已完成 |
| trigger_mode | integer(int32) | 执行方式：0=一次性 1=每日定时 |
| schedule_time | string | 每日执行时间（HH:mm 格式） |
| last_executed_at | string(datetime) | 最近一次执行时间 |
| creator / updater | string | 创建者 / 更新者 |
| create_time / update_time | string(datetime) | 创建时间 / 更新时间 |
| deleted | string(BIT) | 是否删除 |
| tenant_id | integer(int64) | 租户编号 |

#### executions 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer(int64) | 批次主键 |
| task_id | integer(int64) | 关联任务ID |
| execution_no | integer(int32) | 批次号（从 1 递增） |
| keyword | string | 本次搜索关键词 |
| fetch_count | integer(int32) | 实际获取条数 |
| valid_count | integer(int32) | 有效数据条数（通过价格筛选） |
| min_price | integer(int32) | 实际最低售价（分） |
| max_price | integer(int32) | 实际最高售价（分） |
| avg_price | integer(int32) | 平均售价（分） |
| executed_at | string(datetime) | 执行时间 |
| status | integer(int32) | 批次状态：0=执行中 1=已完成 2=失败 |
| error_msg | string | 失败原因 |
| total | integer | executions 数组长度 |

---

### 4.4 查询批次汇总

**GET** `/api/source/execution/{task_id}`

查询某任务的所有执行批次汇总信息。

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
|--------|------|------|------|------|
| task_id | path | integer(int64) | 是 | 任务ID |

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "executions": [
    {
      "id": 5,
      "task_id": 1,
      "execution_no": 5,
      "keyword": "Steam充值卡",
      "fetch_count": 20,
      "valid_count": 20,
      "min_price": 100,
      "max_price": 14500,
      "avg_price": 1560,
      "executed_at": "2026-06-05T14:12:26",
      "status": 1,
      "error_msg": ""
    },
    {
      "id": 4,
      "task_id": 1,
      "execution_no": 4,
      "keyword": "Steam充值卡",
      "fetch_count": 20,
      "valid_count": 0,
      "min_price": null,
      "max_price": null,
      "avg_price": null,
      "executed_at": "2026-06-05T14:10:42",
      "status": 1,
      "error_msg": ""
    }
  ],
  "total": 5
}
```

字段说明同 4.3 中的 executions 字段。

---

### 4.5 查询搜索结果

**GET** `/api/source/items`

查询货源搜索结果数据，支持按批次和有效性筛选。

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|------|--------|------|
| task_id | query | integer(int64) | 是 | - | 任务ID |
| execution_no | query | integer(int32) | 否 | - | 批次号（不传则查全部批次） |
| is_valid | query | integer(int32) | 否 | - | 有效标记：0=无效 1=有效（不传则查全部） |
| limit | query | integer(int32) | 否 | 200 | 返回条数上限，最小值 1，最大值 500 |

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "items": [
    {
      "id": 100,
      "task_id": 1,
      "execution_no": 5,
      "external_item_id": "929846022482",
      "title": "【自动秒发】Steam国区充值卡钱包卡密余额",
      "price": 250,
      "original_price": 300,
      "pic_url": "https://img.alicdn.com/xxx.jpg",
      "detail_url": "https://www.goofish.com/item?id=929846022482",
      "location": "上海",
      "want_count": 888,
      "publish_time": "6小时前",
      "seller_name": "引力游戏",
      "seller_credit": "极好",
      "seller_review_count": "5418",
      "seller_good_rate": "99.5%",
      "item_type": "normal",
      "price_drop_tag": "",
      "ship_speed": "1小时内",
      "tag_list": null,
      "search_keyword": "Steam充值卡",
      "is_valid": "\u0001",
      "crawl_time": "2026-06-05T14:12:26",
      "creator": "python",
      "create_time": "2026-06-05T14:12:26",
      "updater": "python",
      "update_time": "2026-06-05T14:12:26",
      "deleted": "\u0000",
      "tenant_id": 1
    }
  ],
  "total": 20
}
```

#### items 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer(int64) | 搜索结果主键 |
| task_id | integer(int64) | 关联任务ID |
| execution_no | integer(int32) | 批次号 |
| external_item_id | string | 闲鱼平台商品ID |
| title | string | 商品标题 |
| price | integer(int32) | 售价（分） |
| original_price | integer(int32) | 原价/划线价（分） |
| pic_url | string | 主图URL |
| detail_url | string | 商品详情页链接 |
| location | string | 发货地 |
| want_count | integer(int32) | 想要人数 |
| publish_time | string | 发布时间（平台原始值） |
| seller_name | string | 卖家昵称 |
| seller_credit | string | 信用等级（极好/优秀/良好） |
| seller_review_count | string | 店铺评价数 |
| seller_good_rate | string | 好评率 |
| item_type | string | 商品类型 |
| price_drop_tag | string | 降价标签 |
| ship_speed | string | 发货时效 |
| tag_list | array/null | 标签列表（fishTags JSON） |
| search_keyword | string | 搜索关键词 |
| is_valid | string(BIT) | 是否有效：\u0001=有效 \u0000=无效 |
| crawl_time | string(datetime) | 爬取时间 |

---

### 4.6 查询商品详情

**GET** `/api/source/item-detail`

查询货源商品详情数据。仅 is_valid=1 的商品会被采集详情，因此详情数据量通常少于搜索结果。

#### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|------|--------|------|
| task_id | query | integer(int64) | 是 | - | 任务ID |
| external_item_id | query | string | 否 | - | 外部商品ID（精确匹配） |
| item_row_id | query | integer(int64) | 否 | - | 关联的搜索结果ID（app_source_item.id） |
| limit | query | integer(int32) | 否 | 200 | 返回条数上限，最小值 1，最大值 500 |

#### 响应

**成功响应 (200)：**

```json
{
  "success": true,
  "items": [
    {
      "id": 20,
      "task_id": 1,
      "item_id": 100,
      "external_item_id": "929846022482",
      "title": "【自动秒发】Steam国区充值卡钱包卡密余额",
      "price": 250,
      "original_price": 300,
      "pic_url": "https://img.alicdn.com/xxx.jpg",
      "detail_url": "https://www.goofish.com/item?id=929846022482",
      "description": "自动发货，拍下即发...",
      "location": "上海",
      "view_count": 5418,
      "want_count": 888,
      "sold_count": 12500,
      "collect_count": 320,
      "quantity": 999,
      "transport_fee": "0.00",
      "item_status": "onsale",
      "publish_time": "6小时前",
      "category_name": "游戏充值",
      "cpv_labels": null,
      "tag_list": ["自动发货", "秒发"],
      "item_type": "normal",
      "is_free_shipping": "\u0001",
      "seller_id": "2207618440224",
      "seller_name": "引力游戏",
      "seller_avatar": "https://img.alicdn.com/avatar/xxx.jpg",
      "seller_city": "上海",
      "seller_sold_count": 58000,
      "seller_reg_days": 1200,
      "seller_zhima_auth": "\u0001",
      "seller_zhima_level": "极好",
      "seller_credit_tag": "极好",
      "seller_reply_ratio": "98%",
      "seller_reply_interval": "10分钟内",
      "seller_last_visit": "1小时前",
      "seller_good_rate": "99.5%",
      "seller_remark_good": 4800,
      "seller_remark_bad": 12,
      "search_keyword": "Steam充值卡",
      "crawl_time": "2026-06-05T14:12:26"
    }
  ],
  "total": 20
}
```

#### items 字段说明

**商品信息：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer(int64) | 详情主键 |
| task_id | integer(int64) | 关联任务ID |
| item_id | integer(int64) | 关联搜索结果ID（app_source_item.id） |
| external_item_id | string | 闲鱼平台商品ID |
| title | string | 标题 |
| price | integer(int32) | 价格（分） |
| original_price | integer(int32) | 原价（分） |
| pic_url | string | 主图URL |
| detail_url | string | 详情页URL |
| description | string | 商品描述 |
| location | string | 发货地 |
| view_count | integer(int32) | 浏览量 |
| want_count | integer(int32) | 想要人数 |
| sold_count | integer(int32) | 已售数量 |
| collect_count | integer(int32) | 收藏数 |
| quantity | integer(int32) | 库存 |
| transport_fee | string | 运费 |
| item_status | string | 商品状态 |
| publish_time | string | 发布时间 |
| category_name | string | 分类名称 |
| cpv_labels | array/null | 属性标签（JSON） |
| tag_list | array/null | 标签列表（JSON） |
| item_type | string | 商品类型 |
| is_free_shipping | string(BIT) | 是否包邮 |

**卖家信息：**

| 字段 | 类型 | 说明 |
|------|------|------|
| seller_id | string | 卖家ID |
| seller_name | string | 卖家昵称 |
| seller_avatar | string | 卖家头像URL |
| seller_city | string | 卖家城市 |
| seller_sold_count | integer(int32) | 卖家已售数 |
| seller_reg_days | integer(int32) | 注册天数 |
| seller_zhima_auth | string(BIT) | 芝麻认证 |
| seller_zhima_level | string | 芝麻等级 |
| seller_credit_tag | string | 信用等级标签 |
| seller_reply_ratio | string | 回复率 |
| seller_reply_interval | string | 回复速度 |
| seller_last_visit | string | 最近访问 |
| seller_good_rate | string | 好评率 |
| seller_remark_good | integer(int32) | 好评数 |
| seller_remark_bad | integer(int32) | 差评数 |

---

## 5. 数据模型

### 5.1 表关系

```
app_source_task (任务配置)
    │  1:N
    ▼
app_source_execution (批次汇总)
    │  1:N (通过 task_id + execution_no)
    ▼
app_source_item (搜索结果)
    │  1:1 (通过 item_id)
    ▼
app_source_item_detail (商品详情)
```

### 5.2 app_source_task - 货源采集任务表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| product_name | VARCHAR(128) | 搜索关键词 |
| filter_min_price | INT | 筛选最低价（分） |
| filter_max_price | INT | 筛选最高价（分） |
| fetch_count | INT | 每次获取条数 |
| executed_count | INT | 已执行次数 |
| status | TINYINT | 0=运行中 1=已暂停 2=已完成 |
| trigger_mode | TINYINT | 0=一次性 1=每日定时 |
| schedule_time | VARCHAR(8) | 每日执行时间（HH:mm） |
| last_executed_at | DATETIME | 最近执行时间 |
| tenant_id | BIGINT | 租户编号 |

### 5.3 app_source_execution - 货源采集批次汇总表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| task_id | BIGINT FK | 关联任务ID |
| execution_no | INT | 批次号（从 1 递增） |
| keyword | VARCHAR(128) | 本次搜索关键词 |
| fetch_count | INT | 实际获取条数 |
| valid_count | INT | 有效数据条数 |
| min_price | INT | 最低售价（分） |
| max_price | INT | 最高售价（分） |
| avg_price | INT | 平均售价（分） |
| executed_at | DATETIME | 执行时间 |
| status | TINYINT | 0=执行中 1=已完成 2=失败 |
| error_msg | VARCHAR(512) | 失败原因 |
| UK | task_id + execution_no | 唯一索引 |

### 5.4 app_source_item - 货源搜索结果表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| task_id | BIGINT FK | 关联任务ID |
| execution_no | INT | 批次号 |
| external_item_id | VARCHAR(64) | 外部商品ID |
| title | TEXT | 商品标题 |
| price | INT | 售价（分） |
| original_price | INT | 原价（分） |
| pic_url | VARCHAR(1024) | 主图URL |
| detail_url | VARCHAR(1024) | 详情页链接 |
| location | VARCHAR(64) | 发货地 |
| want_count | INT | 想要人数 |
| seller_name | VARCHAR(128) | 卖家昵称 |
| seller_credit | VARCHAR(32) | 信用等级 |
| is_valid | BIT(1) | 是否有效 |
| search_keyword | VARCHAR(128) | 搜索关键词 |
| INDEX | task_id, execution_no | 批次索引 |
| INDEX | price | 价格索引 |

### 5.5 app_source_item_detail - 货源商品详情表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| task_id | BIGINT FK | 关联任务ID |
| item_id | BIGINT FK | 关联 app_source_item.id |
| external_item_id | VARCHAR(64) | 外部商品ID |
| title | TEXT | 标题 |
| price | INT | 价格（分） |
| description | TEXT | 商品描述 |
| view_count | INT | 浏览量 |
| want_count | INT | 想要人数 |
| sold_count | INT | 已售数量 |
| collect_count | INT | 收藏数 |
| quantity | INT | 库存 |
| transport_fee | VARCHAR(32) | 运费 |
| is_free_shipping | BIT(1) | 是否包邮 |
| seller_id | VARCHAR(64) | 卖家ID |
| seller_name | VARCHAR(128) | 卖家昵称 |
| seller_avatar | VARCHAR(512) | 卖家头像 |
| seller_city | VARCHAR(64) | 卖家城市 |
| seller_sold_count | INT | 卖家已售数 |
| seller_reg_days | INT | 注册天数 |
| seller_zhima_auth | BIT(1) | 芝麻认证 |
| seller_credit_tag | VARCHAR(64) | 信用等级标签 |
| seller_reply_ratio | VARCHAR(16) | 回复率 |
| seller_good_rate | VARCHAR(16) | 好评率 |
| cpv_labels | JSON | 属性标签 |
| tag_list | JSON | 标签列表 |
| INDEX | task_id, item_id | 关联索引 |
| INDEX | external_item_id | 外部商品索引 |

---

## 6. WebSocket 实时进度

连接地址：`ws://{host}:{port}/ws/progress`

任务执行过程中通过 WebSocket 推送实时进度消息，消息格式为 JSON：

### 6.1 消息类型

| type | 触发时机 | 额外字段 |
|------|---------|---------|
| `source_start` | 任务开始执行 | task_id, message |
| `source_search_done` | 搜索完成 | task_id, data.total |
| `source_items_done` | 搜索结果写入完成 | task_id, data.total, data.valid |
| `source_detail_progress` | 单个详情采集进度 | task_id, current, total, message |
| `source_detail_done` | 详情采集全部完成 | task_id, data.ok, data.fail |
| `source_complete` | 任务整体完成 | task_id, data.execution_no, data.valid, data.price_range |
| `source_error` | 执行出错 | task_id, message |

### 6.2 消息示例

```json
{
  "type": "source_detail_progress",
  "task_id": 1,
  "current": 5,
  "total": 20,
  "message": "详情 [5/20] 929846022482"
}
```

```json
{
  "type": "source_complete",
  "task_id": 1,
  "message": "货源任务完成: task_id=1, batch=5",
  "data": {
    "execution_no": 5,
    "valid": 20,
    "price_range": [100, 14500]
  }
}
```

---

## 7. 调用示例

### 7.1 Java (RestTemplate)

```java
// 1. 触发货源任务执行
RestTemplate restTemplate = new RestTemplate();
String baseUrl = "http://localhost:8002";

Map<String, Object> reqBody = new HashMap<>();
reqBody.put("task_id", 1);

HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("tenant-id", "1");

HttpEntity<Map<String, Object>> request = new HttpEntity<>(reqBody, headers);
ResponseEntity<Map> resp = restTemplate.postForEntity(
    baseUrl + "/api/source/execute", request, Map.class
);
System.out.println("执行结果: " + resp.getBody());

// 2. 查询任务状态（轮询直到 status=2 表示完成）
while (true) {
    ResponseEntity<Map> statusResp = restTemplate.getForEntity(
        baseUrl + "/api/source/task/1", Map.class
    );
    Map<String, Object> task = (Map<String, Object>) statusResp.getBody().get("task");
    int status = (int) task.get("status");
    if (status == 2) {
        System.out.println("任务已完成");
        break;
    }
    Thread.sleep(5000); // 每5秒轮询
}

// 3. 查询搜索结果
ResponseEntity<Map> itemsResp = restTemplate.getForEntity(
    baseUrl + "/api/source/items?task_id=1&is_valid=1&limit=10", Map.class
);
List<Map<String, Object>> items = (List<Map<String, Object>>) itemsResp.getBody().get("items");
for (Map<String, Object> item : items) {
    System.out.printf("商品: %s, 价格: %.2f元%n",
        item.get("title"), (int) item.get("price") / 100.0);
}

// 4. 查询商品详情
ResponseEntity<Map> detailResp = restTemplate.getForEntity(
    baseUrl + "/api/source/item-detail?task_id=1&limit=5", Map.class
);
List<Map<String, Object>> details = (List<Map<String, Object>>) detailResp.getBody().get("items");
for (Map<String, Object> detail : details) {
    System.out.printf("详情: %s, 浏览: %s, 想要: %s%n",
        detail.get("title"), detail.get("view_count"), detail.get("want_count"));
}
```

### 7.2 Java (WebClient - 异步)

```java
WebClient webClient = WebClient.builder()
    .baseUrl("http://localhost:8002")
    .defaultHeader("tenant-id", "1")
    .build();

// 触发货源任务
webClient.post()
    .uri("/api/source/execute")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(Map.of("task_id", 1))
    .retrieve()
    .bodyToMono(Map.class)
    .subscribe(resp -> System.out.println("执行结果: " + resp));
```

### 7.3 Python (requests)

```python
import requests

BASE = 'http://localhost:8002'

# 1. 触发货源任务
r = requests.post(f'{BASE}/api/source/execute', json={'task_id': 1})
print(r.json())  # {'success': True, 'message': '任务已提交执行, task_id=1'}

# 2. 轮询任务状态
import time
while True:
    r = requests.get(f'{BASE}/api/source/task/1')
    task = r.json()['task']
    if task['status'] == 2:
        print(f'任务完成! 执行了 {task["executed_count"]} 次')
        break
    time.sleep(5)

# 3. 查询有效搜索结果
r = requests.get(f'{BASE}/api/source/items', params={
    'task_id': 1,
    'is_valid': 1,
    'limit': 10
})
for item in r.json()['items']:
    price_yuan = item['price'] / 100
    print(f"  {item['title'][:40]}  ¥{price_yuan:.2f}")

# 4. 查询商品详情
r = requests.get(f'{BASE}/api/source/item-detail', params={
    'task_id': 1,
    'limit': 5
})
for detail in r.json()['items']:
    print(f"  {detail['title'][:40]}  浏览:{detail['view_count']}  想要:{detail['want_count']}")
```

### 7.4 curl

```bash
# 1. 触发货源任务执行
curl -X POST http://localhost:8002/api/source/execute \
  -H "Content-Type: application/json" \
  -d '{"task_id": 1}'

# 2. 单独触发详情采集
curl -X POST http://localhost:8002/api/source/execute-detail \
  -H "Content-Type: application/json" \
  -d '{"task_id": 1, "execution_no": 1}'

# 3. 查询任务信息 + 执行历史
curl http://localhost:8002/api/source/task/1

# 4. 查询批次汇总
curl http://localhost:8002/api/source/execution/1

# 5. 查询搜索结果（仅有效商品）
curl "http://localhost:8002/api/source/items?task_id=1&is_valid=1&limit=10"

# 6. 查询商品详情
curl "http://localhost:8002/api/source/item-detail?task_id=1&limit=5"
```

---

## 8. 错误处理

### 8.1 常见错误码

| 场景 | success | 错误信息 |
|------|---------|---------|
| 任务不存在 | false | "任务不存在" |
| Cookie 无效 | false | "Cookie 无效，请先扫码登录" |
| 搜索 API 失败 | false | 闲鱼 API 返回的错误信息 |
| 参数缺失 | HTTP 422 | FastAPI 自动返回参数校验错误 |

### 8.2 闲鱼反爬限流

详情采集时可能触发 `RGV587_ERROR`（闲鱼风控），系统内置以下防护机制：

| 策略 | 配置项 | 默认值 |
|------|--------|--------|
| 智能延迟 | SOURCE_DETAIL_INTERVAL_MIN/MAX | 5~8 秒 |
| 随机抖动 | SOURCE_DETAIL_JITTER | 2 秒 |
| 指数退避 | SOURCE_BACKOFF_INITIAL/MULTIPLIER/MAX | 10s / 2x / 120s |
| 批次暂停 | SOURCE_BACKOFF_BATCH_PAUSE/THRESHOLD | 60s / 5次 |

### 8.3 注意事项

1. **价格单位**：所有价格字段单位为「分」，展示时需除以 100
2. **BIT 字段**：`is_valid`、`deleted` 等字段在 JSON 中表现为 `"\u0001"` / `"\u0000"`
3. **异步执行**：`POST /execute` 和 `POST /execute-detail` 立即返回，任务在后台线程池中执行
4. **并发限制**：线程池最大并发 4 个任务
5. **数据量差异**：`app_source_item_detail` 数据量通常少于 `app_source_item`，因为只对有效商品采集详情，且详情 API 可能因限流失败
6. **WebSocket**：建议前端连接 `ws://host:port/ws/progress` 获取实时进度，而非轮询
