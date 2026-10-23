# 数据库说明

所有表结构以本目录 SQL 为准，项目没有 Mapper XML。

## 全新建库执行顺序

在数据库 `xianyuops`（或自定义库名）中按以下顺序执行：

1. `base.sql` — 框架层 `system_*` / `infra_*` 基础表与字典种子
2. `quartz.sql` — Quartz 集群表
3. `system/system-menu-seed.sql` — 管理后台菜单（仪表盘、闲鱼、系统管理、基础设施）
4. `app/app-xianyu.sql` — Agent / 消息 / 回复任务表 + 菜单 + 维护任务
5. `app/app-source.sql` — 货源采集与共享 Cookie 池
6. `app/app-config.sql` — 服务配置文件表
7. `system/system-tenant-menu-seed.sql` — 租户管理菜单

Docker Compose 已按上述顺序挂载到 MySQL 初始化目录，无需手动执行。

## 表分组

| 前缀 | 模块 | 说明 |
|---|---|---|
| `system_*` | 系统 | 用户、角色、菜单、部门、字典、租户、邮件、短信、社交登录 |
| `infra_*` | 基础设施 | 文件、定时任务、日志、代码生成、数据源 |
| `app_xianyu_*` | 闲鱼核心 | Agent、账号绑定快照、会话消息、回复任务、事件、注册码 |
| `app_source_*` | 货源采集 | 任务、批次、商品、详情、货源库、清洗、技能、黑名单、Cookie 池 |
| `app_config_file` | 服务配置 | 在线编辑的提示词/配置文件 |
| `QRTZ_*` | 定时任务 | Quartz 集群表 |

## 注意

- 种子数据使用 `INSERT`，重复执行菜单脚本前会先按 ID 区间清理，保持幂等。
- 存量库不要整库重跑 `base.sql`：其中的 `DROP TABLE` 会清空数据。字段变更请使用 `ALTER`。
- 统一使用 `utf8mb4`，时间字段为 `DATETIME`，金额字段以「分」存储。
