# 架构概览

## 模块

```
xianyuops-server
├── xianyuops-module-xianyu
│   ├── controller/admin      → /admin-api/xianyu/**（Agent、消息、货源、服务配置）
│   ├── controller/agentapi   → /agent-api/**
│   ├── service               → Agent、消息、回复任务、货源采集
│   ├── dal                   → Mapper（BaseMapperX，无 XML）
│   └── job                   → Agent 维护、货源定时任务
├── xianyuops-module-system   → 登录、RBAC、菜单、部门、字典
└── xianyuops-module-infra    → 文件、定时任务、访问日志
```

## 请求路径

| 前缀 | 使用方 | 认证 |
|---|---|---|
| `/admin-api/**` | 管理端 | 登录态 + 菜单/功能权限 |
| `/agent-api/**` | Windows Agent | `X-Agent-Token`（一机一 Token） |

## 数据流

1. **消息**：Windows Agent 读取闲管家会话 → 上报 `/agent-api/**` → Java 入库并提供消息工作台；回复任务由 Agent 长轮询领取。
2. **货源采集**：管理端创建任务 → Java 调用 Python `source` 服务（8002）→ Python 直接写 `app_source_*` → 管理端查看/清洗。
3. **Agent 接入**：管理端生成一次性注册码 → Agent 首次心跳换取专属 Token → 后续请求携带 `X-Agent-Token`。

## 认证与权限

- 登录态：Spring Security + JWT（访问令牌 + 刷新令牌）。
- 菜单权限：前端静态路由 + 后端菜单树过滤。
- 功能权限：`@PreAuthorize("@ss.hasPermission('...')")`，由 `PERMISSION_CHECK_ENABLE` 控制。
- 数据权限：MyBatis-Plus 数据权限插件按部门/用户过滤，始终生效。
- Agent：注册码首次换取专属 Token，之后请求携带 `X-Agent-Token`。

## 本地端口

| 服务 | 端口 |
|---|---|
| 后端 | 48180 |
| 管理端 | 5666 |
| 货源服务 | 8002 |
