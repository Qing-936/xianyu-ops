# Xianyu Agent

运行在 Windows 上的闲鱼消息与订单 Agent，通过 `/agent-api/**` 与 Java 后端通信。

## 前置条件

- Windows 10/11（需要可交互桌面，锁屏会导致 UI 自动化失效）
- 闲管家客户端已登录目标闲鱼账号
- Python 3.11+
- 可访问后端（默认 `http://127.0.0.1:48180`）

## 安装与启动

```bash
pip install -r requirements.txt
python main.py
```

首次启动前在管理端「闲鱼 → 客服消息 → Agent 管理」生成一次性注册码，写入 `config.local.yaml`：

```yaml
server:
  base_url: http://127.0.0.1:48180
  enroll_code: "xyag-xxxxxxxx"
```

注册成功后 Token 保存在 `.agent_token`，该文件等价于机器凭据，禁止提交或分享。`.machine_code` 为机器指纹，同样禁止入库。

## 通道

| 通道 | 配置 | 说明 |
|---|---|---|
| `ui_ocr` | `channel.impl: ui_ocr` | pywinauto + RapidOCR 读取闲管家窗口，兼容性好但依赖窗口坐标 |
| `webim` | `channel.impl: webim` | 通过浏览器调试端口读取 webim DOM，需保持浏览器长驻 |

窗口尺寸变化后需要重新校准 `config.yaml` 中的区域坐标。

## VM 部署脚本

`deploy/vm/` 提供 Windows 虚机基线脚本（禁用屏保、自动登录、注册计划任务、会话保活、模板准备等）。执行前请阅读脚本头部说明，并按自己的环境修改路径与账户参数。

## 免责声明

本项目与闲鱼、闲管家无关联。自动化可能触发平台风控或账号限制，请仅用于自己的账号，并自行承担风险。
