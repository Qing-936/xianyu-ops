# 货源管理模块 - 接口测试用例

> **测试环境**: `http://127.0.0.1:8002`  
> **测试日期**: 2026-06-05  
> **Swagger 文档**: `http://127.0.0.1:8002/docs`  
> **模块路径**: `source/`（独立于工作流模块）

---

## 测试用例总览

| 编号 | 名称 | 接口 | 方法 |
|------|------|------|------|
| TC-01 | 创建测试任务并验证插入 | 直连数据库 + `GET /api/source/task/{task_id}` | SQL + GET |
| TC-02 | 触发货源任务执行 | `POST /api/source/execute` | POST |
| TC-03 | 轮询等待任务完成并验证状态变更 | `GET /api/source/task/{task_id}` | GET |
| TC-04 | 查询批次汇总 | `GET /api/source/execution/{task_id}` | GET |
| TC-05 | 查询搜索结果 | `GET /api/source/items` | GET |
| TC-06 | 查询商品详情 | `GET /api/source/item-detail` | GET |
| TC-07 | 单独触发详情采集 | `POST /api/source/execute-detail` | POST |
| TC-08 | 任务状态自动更新验证 | 直连数据库 | SQL |

---

## TC-01：创建测试任务并验证插入

### 目标
模拟 Java 端创建货源任务，插入 3 种不同配置的任务数据，验证 API 能正确读取。

### 步骤 1：插入测试数据（直连 MySQL）

```sql
-- 任务 A：标准价格筛选（瑞幸，7~11元）
INSERT INTO app_source_task
    (product_name, filter_min_price, filter_max_price, fetch_count,
     status, trigger_mode, creator, create_time, update_time, tenant_id)
VALUES
    ('瑞幸 澳瑞白 自助发货', 700, 1100, 30,
     0, 0, 'test_sim', NOW(), NOW(), 1);

-- 任务 B：高价区间筛选（星巴克，10~20元）
INSERT INTO app_source_task
    (product_name, filter_min_price, filter_max_price, fetch_count,
     status, trigger_mode, creator, create_time, update_time, tenant_id)
VALUES
    ('星巴克 拿铁 代下单', 1000, 2000, 20,
     0, 0, 'test_sim', NOW(), NOW(), 1);

-- 任务 C：无价格筛选（奶茶，全量获取）
INSERT INTO app_source_task
    (product_name, filter_min_price, filter_max_price, fetch_count,
     status, trigger_mode, creator, create_time, update_time, tenant_id)
VALUES
    ('奶茶 代下', NULL, NULL, 10,
     0, 0, 'test_sim', NOW(), NOW(), 1);
```

> **字段说明**：
> - `filter_min_price` / `filter_max_price`：单位为 **分**（700 = 7.00 元），`NULL` 表示不限
> - `status`：`0=运行中 1=已暂停 2=已完成`
> - `fetch_count`：每次获取条数，决定搜索页数（每页 30 条）

### 步骤 2：通过 API 验证任务已创建

**请求**：
```bash
# 替换 {task_id} 为上一步 INSERT 返回的自增 ID
curl http://127.0.0.1:8002/api/source/task/{task_id}
```

**示例**（以 task_id=9 为例）：
```bash
curl http://127.0.0.1:8002/api/source/task/9
```

**预期响应**（200 OK）：
```json
{
  "success": true,
  "task": {
    "id": 9,
    "product_name": "瑞幸 澳瑞白 自助发货",
    "filter_min_price": 700,
    "filter_max_price": 1100,
    "fetch_count": 30,
    "executed_count": 0,
    "status": 0,
    "trigger_mode": 0,
    "creator": "test_sim",
    "tenant_id": 1
  },
  "executions": []
}
```

**验证要点**：
- [x] `success` 为 `true`
- [x] `task.status` 为 `0`（运行中，初始状态）
- [x] `task.executed_count` 为 `0`（未执行）
- [x] `executions` 为空数组（无执行记录）

### 实际测试结果

| task_id | product_name | status | 价格筛选范围 |
|---------|-------------|--------|-------------|
| 9 | 瑞幸 澳瑞白 自助发货 | 0（运行中） | 700~1100 分（7.00~11.00 元） |
| 10 | 星巴克 拿铁 代下单 | 0（运行中） | 1000~2000 分（10.00~20.00 元） |
| 11 | 奶茶 代下 | 0（运行中） | NULL（不限） |

---

## TC-02：触发货源任务执行

### 目标
模拟 Java 端创建任务后调用执行接口，验证任务被正确提交到后台线程池。

### 请求

```bash
# 触发 task_id=9 执行
curl -X POST http://127.0.0.1:8002/api/source/execute \
  -H "Content-Type: application/json" \
  -d '{"task_id": 9}'

# 触发 task_id=10 执行
curl -X POST http://127.0.0.1:8002/api/source/execute \
  -H "Content-Type: application/json" \
  -d '{"task_id": 10}'

# 触发 task_id=11 执行
curl -X POST http://127.0.0.1:8002/api/source/execute \
  -H "Content-Type: application/json" \
  -d '{"task_id": 11}'
```

### 预期响应（200 OK）

```json
{
  "success": true,
  "message": "任务已提交执行, task_id=9"
}
```

**验证要点**：
- [x] `success` 为 `true`
- [x] 接口 **立即返回**（异步执行，不阻塞）
- [x] 后端日志显示 `货源任务已提交: task_id=9`

### 实际测试结果

```
task_id=9:  200 - {'success': True, 'message': '任务已提交执行, task_id=9'}
task_id=10: 200 - {'success': True, 'message': '任务已提交执行, task_id=10'}
task_id=11: 200 - {'success': True, 'message': '任务已提交执行, task_id=11'}
```

### 执行流程说明

```
POST /api/source/execute
  └→ ThreadPoolExecutor 提交阻塞任务
       └→ execute_source_task() 在线程中执行：
            1. 读取 app_source_task 配置
            2. 搜索闲鱼（按 fetch_count 计算页数）
            3. 价格筛选 → 写入 app_source_item
            4. 有效商品详情采集 → 写入 app_source_item_detail
            5. 汇总统计 → 更新 app_source_execution
            6. 更新 app_source_task（status=2, executed_count+1）
```

---

## TC-03：轮询等待任务完成并验证状态变更

### 目标
任务执行后通过轮询监控状态变化，验证 `app_source_task.status` 从 `0`（运行中）变为 `2`（已完成）。

### 请求

```bash
# 每 10 秒查询一次，直到 status 变为 2
curl http://127.0.0.1:8002/api/source/task/9
curl http://127.0.0.1:8002/api/source/task/10
curl http://127.0.0.1:8002/api/source/task/11
```

### 预期响应（任务执行中）

```json
{
  "success": true,
  "task": { "status": 0, "executed_count": 0 },
  "executions": [
    { "execution_no": 1, "status": 0, "fetch_count": 0, "valid_count": 0 }
  ]
}
```

### 预期响应（任务完成后）

```json
{
  "success": true,
  "task": { "status": 2, "executed_count": 1 },
  "executions": [
    { "execution_no": 1, "status": 1, "fetch_count": 30, "valid_count": 24 }
  ]
}
```

**验证要点**：
- [x] `task.status` 从 `0` → `2`（已完成）
- [x] `task.executed_count` 从 `0` → `1`
- [x] `executions[0].status` 为 `1`（批次已完成）
- [x] `executions[0].fetch_count` > 0（有搜索结果）

### 实际测试结果

```
task_id=9:  ✓ status=2(已完成), executed_count=1, last_executed=2026-06-05 13:15:09
task_id=10: ✓ status=2(已完成), executed_count=1, last_executed=2026-06-05 13:13:26
task_id=11: ✓ status=2(已完成), executed_count=1, last_executed=2026-06-05 13:13:19
```

| task_id | 耗时 | 获取条数 | 有效条数 | 有效率 |
|---------|------|---------|---------|--------|
| 9 | ~180s | 30 | 24 | 80% |
| 10 | ~70s | 20 | 10 | 50% |
| 11 | ~70s | 10 | 10 | 100% |

---

## TC-04：查询批次汇总

### 目标
验证任务完成后 `app_source_execution` 表的汇总统计数据正确。

### 请求

```bash
curl http://127.0.0.1:8002/api/source/execution/9
curl http://127.0.0.1:8002/api/source/execution/10
curl http://127.0.0.1:8002/api/source/execution/11
```

### 预期响应（200 OK）

```json
{
  "success": true,
  "executions": [
    {
      "id": 1,
      "task_id": 9,
      "execution_no": 1,
      "keyword": "瑞幸 澳瑞白 自助发货",
      "fetch_count": 30,
      "valid_count": 24,
      "min_price": 969,
      "max_price": 1100,
      "avg_price": 1045,
      "status": 1,
      "error_msg": ""
    }
  ],
  "total": 1
}
```

**验证要点**：
- [x] `status` 为 `1`（已完成）
- [x] `fetch_count` > 0（有搜索结果）
- [x] `valid_count` <= `fetch_count`（有效数不超过总数）
- [x] `min_price` / `max_price` / `avg_price` 有值
- [x] 有价格筛选时，`min_price` >= `filter_min_price`，`max_price` <= `filter_max_price`

### 实际测试结果

| task_id | 批次 | status | fetch | valid | 价格范围（分） | 均价 |
|---------|------|--------|-------|-------|--------------|------|
| 9 | #1 | 已完成 | 30 | 24 | 969~1100 | 1045 |
| 10 | #1 | 已完成 | 20 | 10 | 1479~2000 | 1809 |
| 11 | #1 | 已完成 | 10 | 10 | 100~2500 | 498 |

> **注意**：task_id=9 设置了价格筛选 700~1100 分，实际结果 `min_price=969, max_price=1100`，全部在筛选范围内 ✓

---

## TC-05：查询搜索结果

### 目标
验证 `app_source_item` 表中的搜索结果数据，包括价格筛选标记。

### 请求

```bash
# 查询 task_id=9 的前 5 条搜索结果
curl "http://127.0.0.1:8002/api/source/items?task_id=9&limit=5"

# 只查询有效商品
curl "http://127.0.0.1:8002/api/source/items?task_id=9&is_valid=1&limit=5"

# 指定批次查询
curl "http://127.0.0.1:8002/api/source/items?task_id=9&execution_no=1&limit=5"
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `task_id` | int | 是 | 任务ID |
| `execution_no` | int | 否 | 批次号，不传则查所有批次 |
| `is_valid` | int | 否 | 有效标记（0=无效, 1=有效），不传则查全部 |
| `limit` | int | 否 | 返回条数上限，默认 200 |

### 预期响应（200 OK）

```json
{
  "success": true,
  "items": [
    {
      "id": 1002,
      "task_id": 9,
      "execution_no": 1,
      "external_item_id": "xxxxx",
      "title": "【自动发货】瑞幸SOE小黑杯...",
      "price": 1090,
      "original_price": 0,
      "is_valid": "\u0001",
      "seller_name": "味多美代购",
      "search_keyword": "瑞幸 澳瑞白 自助发货"
    }
  ],
  "total": 5
}
```

> **注意**：`is_valid` 字段类型为 `BIT(1)`，Python/JSON 中表现为 `"\u0001"`（有效）或 `"\u0000"`（无效），需在客户端转换为 0/1。

### 实际测试结果

**task_id=9（瑞幸，价格筛选 7~11 元）**：共 5 条返回
```
id=1002 price=1090分 title=【自动发货】瑞幸SOE小黑杯，耶加雪菲3选1...
id=1001 price=1100分 title=瑞幸咖啡大全通用券｜自助秒发...
id=999  price=1083分 title=【自动秒发】瑞幸咖啡60选1饮品券...
id=998  price=1026分 title=【自动发货】瑞幸-耶加雪菲澳瑞白...
id=997  price=1068分 title=【到店自提】瑞幸咖啡耶加雪菲·澳瑞白...
```

**task_id=10（星巴克，价格筛选 10~20 元）**：共 5 条返回
```
id=1031 price=1549分  title=【自动发货】星巴克代下单六折起...
id=1030 price=2300分  title=星巴克香草拿铁榛果拿铁代下...
id=1029 price=11500分 title=【官旗直发】星巴克即饮瓶装...（无效，超出范围）
id=1028 price=2299分  title=【门店自取 非外卖】星巴克 浓郁咖啡拿铁...
id=1027 price=1855分  title=星巴克-抹茶拿铁 超大杯...
```

**task_id=11（奶茶，无价格筛选）**：共 5 条返回
```
id=1026 price=590分 title=呷哺呷哺饮品代下单！...
id=1024 price=108分 title=【淘宝闪购奶茶免单卡】...
id=1022 price=410分 title=【沪上阿姨 代下】奇兰苹果茶...
id=1021 price=128分 title=【省钱神券】蜜雪冰城奶茶兑换券...
id=1020 price=299分 title=全国【另茶】OT另茶 代下单...
```

---

## TC-06：查询商品详情

### 目标
验证 `app_source_item_detail` 表中的详情采集数据。

### 请求

```bash
# 查询 task_id=9 的详情（前3条）
curl "http://127.0.0.1:8002/api/source/item-detail?task_id=9&limit=3"

# 按外部商品ID查询
curl "http://127.0.0.1:8002/api/source/item-detail?task_id=9&external_item_id=999059963336"

# 按搜索结果行ID查询
curl "http://127.0.0.1:8002/api/source/item-detail?task_id=9&item_row_id=85"
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `task_id` | int | 是 | 任务ID |
| `external_item_id` | string | 否 | 外部商品ID（闲鱼平台侧） |
| `item_row_id` | int | 否 | `app_source_item.id`（数据库行ID） |
| `limit` | int | 否 | 返回条数上限，默认 200 |

### 预期响应（200 OK）

```json
{
  "success": true,
  "items": [
    {
      "id": 87,
      "task_id": 9,
      "item_id": 1002,
      "external_item_id": "xxxxx",
      "title": "【自动发货】瑞幸SOE小黑杯...",
      "price": 1090,
      "seller_name": "味多美代购",
      "seller_credit_tag": "极好",
      "seller_good_rate": "99%",
      "view_count": 120,
      "want_count": 15,
      "sold_count": 50
    }
  ],
  "total": 3
}
```

### 实际测试结果

**task_id=9**：共 3 条详情（首次执行时采集的前几条）
```
id=87 price=1090分 seller=味多美代购
id=86 price=1100分 seller=便民特惠
id=85 price=1083分 seller=安欣权益
```

**task_id=10**：共 3 条详情
```
id=73 price=1549分 seller=贝贝北贝
id=70 price=1855分 seller=心情好点卡券
id=68 price=1674分 seller=朵朵权益
```

**task_id=11**：共 3 条详情
```
id=71 price=590分 seller=凑凑奶茶福利官
id=67 price=108分 seller=tbNick_6f5we
id=65 price=410分 seller=桃子的桃
```

---

## TC-07：单独触发详情采集

### 目标
对已完成搜索的任务，单独触发详情采集（不重新搜索），用于分步执行场景。

### 请求

```bash
curl -X POST http://127.0.0.1:8002/api/source/execute-detail \
  -H "Content-Type: application/json" \
  -d '{"task_id": 9, "execution_no": 1}'

curl -X POST http://127.0.0.1:8002/api/source/execute-detail \
  -H "Content-Type: application/json" \
  -d '{"task_id": 10, "execution_no": 1}'

curl -X POST http://127.0.0.1:8002/api/source/execute-detail \
  -H "Content-Type: application/json" \
  -d '{"task_id": 11, "execution_no": 1}'
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `task_id` | int | 是 | 任务ID |
| `execution_no` | int | 是 | 批次号（对哪个批次的有效商品采集详情） |

### 预期响应（200 OK）

```json
{
  "success": true,
  "message": "详情采集已提交, task_id=9, batch=1"
}
```

**验证要点**：
- [x] `success` 为 `true`
- [x] 接口立即返回（异步执行）
- [x] 只对 `is_valid=1` 的商品采集详情

### 实际测试结果

```
task_id=9:  200 - {'success': True, 'message': '详情采集已提交, task_id=9, batch=1'}
task_id=10: 200 - {'success': True, 'message': '详情采集已提交, task_id=10, batch=1'}
task_id=11: 200 - {'success': True, 'message': '详情采集已提交, task_id=11, batch=1'}
```

---

## TC-08：任务状态自动更新验证

### 目标
验证任务执行完成后 `app_source_task.status` 字段被自动更新为 `2`（已完成）。

### 验证 SQL

```sql
SELECT id, product_name, status, executed_count, last_executed_at
FROM app_source_task
WHERE creator = 'test_sim' AND deleted = 0
ORDER BY id;
```

### 预期结果

| id | product_name | status | executed_count | last_executed_at |
|----|-------------|--------|---------------|-----------------|
| 9 | 瑞幸 澳瑞白 自助发货 | 2 | 1 | 2026-06-05 13:15:09 |
| 10 | 星巴克 拿铁 代下单 | 2 | 1 | 2026-06-05 13:13:26 |
| 11 | 奶茶 代下 | 2 | 1 | 2026-06-05 13:13:19 |

### 状态变更规则

| 场景 | 变更前 status | 变更后 status | 说明 |
|------|-------------|-------------|------|
| 任务执行成功 | 0（运行中） | 2（已完成） | `_update_task_executed(conn, task_id, status=2)` |
| 任务执行失败 | 0（运行中） | 2（已完成） | 批次记录已有 `status=2`（失败），任务本身标记为已完成 |
| 用户手动暂停 | 0（运行中） | 1（已暂停） | Java 端操作，Python 不处理 |

### 实际验证结果

```
✓ task_id=9:  status=2(已完成), executed_count=1, last_executed=2026-06-05 13:15:09
✓ task_id=10: status=2(已完成), executed_count=1, last_executed=2026-06-05 13:13:26
✓ task_id=11: status=2(已完成), executed_count=1, last_executed=2026-06-05 13:13:19
```

---

## 附录 A：全部 API 端点一览

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| POST | `/api/source/execute` | 触发货源任务执行 | `{"task_id": int}` |
| POST | `/api/source/execute-detail` | 单独触发详情采集 | `{"task_id": int, "execution_no": int}` |
| GET | `/api/source/task/{task_id}` | 查询任务信息+执行历史 | 路径参数 `task_id` |
| GET | `/api/source/execution/{task_id}` | 查询批次汇总 | 路径参数 `task_id` |
| GET | `/api/source/items` | 查询搜索结果 | `task_id, execution_no?, is_valid?, limit?` |
| GET | `/api/source/item-detail` | 查询商品详情 | `task_id, external_item_id?, item_row_id?, limit?` |

## 附录 B：测试数据清理 SQL

```sql
-- 清理本次测试插入的数据
DELETE FROM app_source_item_detail WHERE task_id IN (9, 10, 11);
DELETE FROM app_source_item WHERE task_id IN (9, 10, 11);
DELETE FROM app_source_execution WHERE task_id IN (9, 10, 11);
DELETE FROM app_source_task WHERE id IN (9, 10, 11);
```

## 附录 C：自动化测试脚本

完整自动化测试脚本位于 `source/simulate_test.py`，运行方式：

```bash
cd e:\work\workflowdemo
python source/simulate_test.py
```

脚本执行流程：
1. 清理旧测试数据
2. 插入 3 条测试任务（不同配置）
3. 通过 API 查询验证插入
4. 调用 `POST /api/source/execute` 触发采集
5. 轮询等待完成（每 10 秒检查）
6. 验证 `status` 从 0 → 2
7. 查询批次汇总
8. 查询搜索结果
9. 查询商品详情
10. 单独触发详情采集
