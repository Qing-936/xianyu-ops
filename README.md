# XianyuOps · 闲鱼运营台（开源核心版）

一套面向闲鱼店铺的自动化运营工具：Windows Agent 读取消息与订单会话，管理端统一查看消息、分配回复任务，并配套货源采集与 AI 分析。

> 非官方项目，与闲鱼、闲管家（闲鱼管家）无任何关联。请仅使用自己的账号，并自行评估平台规则与自动化风险。

## 开源范围

本仓库是**核心版**，只包含：

- **Agent**：Windows 桌面端 UI/OCR 与浏览器 webim 双通道，一机一 Token
- **消息工作台**：多账号会话、消息同步、回复任务、Agent 接管
- **货源采集**：闲鱼搜索抓取、AI 分析（DeepSeek）、货源库、卖家黑名单
- **服务配置**：在线编辑货源清洗提示词/配置
- **基础设施**：登录/菜单/角色/部门/字典/文件/定时任务（基于 RuoYi-Vue-Pro）

以下功能**不在本仓库**（商业版提供）：

- 账号管理、商品管理、订单管理
- 自助下单 H5、卡券与自动发货、货源订单（虚拟货源）
- 经营看板（Dashboard）

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.5、MyBatis-Plus、MySQL 8、Redis、Quartz |
| 管理端 | Vue 3、TypeScript、Ant Design Vue 4、Vite 6、Pinia |
| 货源服务 | Python 3.11、FastAPI、PyMySQL、OpenAI SDK（DeepSeek） |
| Agent | Python 3.11、pywinauto、RapidOCR、Playwright/CDP（webim 通道） |

## 目录结构

```
.
├── server/
│   ├── xianyuops-dependencies/       # 依赖版本 BOM
│   ├── xianyuops-framework/          # 基础框架
│   ├── xianyuops-module-system/      # 用户/角色/菜单/部门/字典
│   ├── xianyuops-module-infra/       # 文件/定时任务/日志
│   ├── xianyuops-module-xianyu/      # Agent / 消息 / 货源
│   └── xianyuops-server/             # 启动模块
├── web/                              # 管理端
├── python/
│   ├── xianyu-agent/                 # Windows Agent
│   └── source/                       # 货源采集服务
├── sql/mysql/                        # 建表与种子数据
├── docker/                           # Dockerfile 与 Nginx 配置
└── docker-compose.yml
```

## 快速开始（Docker Compose）

前置条件：Docker 24+，至少 6 GB 可用内存。

```bash
cp .env.example .env
docker compose up -d --build
```

首次构建会拉取 Maven/npm/Python 依赖，耗时较长。启动完成后：

| 服务 | 地址 |
|---|---|
| 管理端 | http://localhost:5666 |
| 后端 API | http://localhost:48180 |
| 货源服务 | http://localhost:8002/api/health |

默认管理员：`admin / admin123`（租户 `1`）。**首次登录后请立即修改密码。**

登录后默认进入「消息工作台」。数据库初始化只在 MySQL 数据卷为空时执行；需要重置演示数据时：

```bash
docker compose down -v   # 会删除数据库与 Redis 数据
docker compose up -d
```

## 本地开发

### 1. 基础设施

```bash
docker compose up -d mysql redis
```

### 2. 后端

需要 Java 17 与 Maven 3.9+。

```bash
mvn -DskipTests package -pl server/xianyuops-server -am
java -jar server/xianyuops-server/target/xianyuops-server.jar
```

默认使用 `local` profile，端口 `48180`。

### 3. 管理端

需要 Node.js 22+ 与 pnpm 11。

```bash
cd web
pnpm install
pnpm dev          # http://localhost:5666
```

### 4. 货源采集服务

```bash
cd python
pip install -r source/requirements.txt
python -m uvicorn source.server:app --host 0.0.0.0 --port 8002
```

需要准备 `python/source/cookie.json`（自己的闲鱼 Cookie），否则搜索接口不可用。AI 分析为可选项，配置 `DEEPSEEK_API_KEY` 后启用。

### 5. Agent（Windows）

前置条件：Windows 10/11、闲管家客户端、Python 3.11。

```bash
cd python/xianyu-agent
pip install -r requirements.txt
python main.py
```

首次启动前，在管理端「闲鱼 → 客服消息 → Agent 管理」生成一次性注册码，写入 `config.local.yaml`：

```yaml
server:
  base_url: http://127.0.0.1:48180
  enroll_code: "xyag-xxxxxxxx"
```

Token 落盘后（`.agent_token`）不再需要注册码。VM 部署脚本见 `python/xianyu-agent/deploy/vm/`。

## 配置

主要环境变量（`application-local.yaml` 与 `docker-compose.yml` 均支持）：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `DB_HOST` / `DB_PORT` | MySQL 地址 | `127.0.0.1:3306` |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | 数据库名与账号 | `xianyuops` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 | `127.0.0.1:6379` |
| `SOURCE_PYTHON_BASE_URL` | 货源服务地址 | `http://127.0.0.1:8002` |
| `SECURITY_MOCK_ENABLE` | 登录 mock（仅本地调试） | `false` |
| `PERMISSION_CHECK_ENABLE` | 接口权限校验 | `false` |

邮件、Dify/DeepSeek 等凭据在管理端对应页面或环境变量中配置，仓库不包含任何真实凭据。

## 已内置的演示数据

- 管理员账号 `admin / admin123`
- 系统菜单（闲鱼、系统管理、基础设施）
- 闲鱼菜单（货源管理、客服消息、服务配置）
- Agent 维护定时任务（默认停用，需在「基础设施 → 定时任务」中手动开启）

## 使用须知

1. **合规**：请遵守闲鱼、闲管家及所在地区的法律法规与平台规则。自动化操作可能导致账号受限，风险自负。
2. **凭据**：仓库不包含任何账号、Cookie、Token、AccessKey 或内网地址。请勿把本地 `cookie.json`、`.agent_token`、`.env`、`config.local.yaml` 提交到公开仓库。
3. **反爬代码**：货源服务包含闲鱼 mtop 请求签名与 `tfstk` 生成逻辑，仅用于学习与自有账号的数据整理。是否使用由使用者自行判断。
4. **稳定性**：闲鱼页面与接口随时可能变化，Agent 的坐标、选择器、OCR 阈值均可能需要重新校准。

## 交流群

微信扫码加入「Xianyu_自动化运营开发交流群」：

<img src="docs/images/wechat-group.png" alt="Xianyu 自动化运营开发交流群" width="320" />

> 二维码有效期至 2026-10-30，过期后请通过 Issue 联系更新。

## 许可证

Apache License 2.0。项目基于 [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro)（MIT）演化，相关版权声明见 `NOTICE` 与 `LICENSE-RuoYi-Vue-Pro.txt`。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。
