# VM 固化脚本（闲鱼 Agent · Windows VM）

> 脚本按顺序执行，每一步都可重复运行。
> 适用：Proxmox 内 Windows 10/11 VM（试点 1 台；后续母盘 → 克隆扩容）。

## 脚本清单与执行顺序

| 顺序 | 脚本 | 作用 | 何时执行 |
|------|------|------|----------|
| 1 | `01-harden-baseline.ps1` | 关锁屏/屏保/睡眠、禁 Windows 更新与自动重启、NTP 时钟同步 | 新 VM 首次配置 |
| 2 | `02-enable-autologon.ps1` | 自动登录（免人工进入交互桌面会话） | 新 VM 首次配置 |
| 3 | `03-register-agent-task.ps1` | 注册 Agent 计划任务（登录触发 + 崩溃自重启） | Agent 代码/配置就位后 |
| 4 | `04-session-keepalive.ps1` | RDP 断开会话回收为 Console（防锁屏 OCR 失效） | 可选，`-Install` 安装一次 |
| 5 | `05-prepare-for-template.ps1` | 母盘清整（清 `.machine_code` / 日志 / 进程） | 端到端验收后、转模板前 |
| 6 | `06-post-clone-differentiate.ps1` | 克隆后改 hostname/时区 + 人工清单 | 每次克隆后 |

两条流程：

```text
新 VM 精装：  01 → 02 →（装 Python/闲管家/Agent 代码/配置）→ 03 → 端到端验收 → 05 → 关机 → 转模板
克隆扩容：    克隆 → 06 →（改 IP/分辨率/输入法）→ 重启 → 扫码登录闲鱼号 → 基线快照
```

## 前置要求

- Windows PowerShell 5.1（**管理员**运行；脚本自带 `#Requires -RunAsAdministrator`）。
- 脚本为 **UTF-8 with BOM** 编码（5.1 解析中文的必要条件），如需修改请保持编码。
- 首次执行前：`Set-ExecutionPolicy Bypass -Scope Process`
- Python 已安装（勾选 Add python.exe to PATH）；Agent 代码已拷贝到 `C:\xianyu-agent`。

## 关键约定（务必阅读）

1. **为什么用「计划任务」而不是 NSSM 服务**：Windows 服务运行在 Session 0，与用户交互
   桌面隔离 —— UIA 定位、截图 OCR、键鼠模拟全部失效。Agent 必须运行在已登录用户的
   交互会话中，因此方案 = autologon（02）+ 登录触发计划任务（03）。
2. **必须用 `pythonw.exe`**：python.exe 的控制台窗口会遮挡闲管家窗口、干扰 OCR 截图。
3. **`.machine_code`**：每机唯一的 UUID；母盘必须清理（05 脚本），克隆后
   首次启动会自动生成新值。忘记清理会导致多台 VM 注册串号。
4. **token 配置**：注册码放 `config.local.yaml`（.gitignore 已排除），在管理端「Agent 管理」生成，一次性使用；注册后的 `.agent_token` 不得入库。
5. **autologon 密码**：默认写注册表明文（受控内网 VM 可接受）；需加密时改用
   Sysinternals Autologon.exe 再执行一次（写 LSA secret，同样生效）。
6. **RDP 纪律**：优先用 Proxmox noVNC 维护；用了 RDP 就正常「断开」不要注销，
   断开后的会话回收由 04 保活任务兜底。
7. **崩溃自动重启的边界**：03 任务仅在**非零退出码**时按「每 1 分钟 × 999 次」重启
   （覆盖心跳 401 与 guard 检出主循环卡死两条自愈路径，均 `os._exit(1)`）；
   **不覆盖「进程活着但空转」**（如配置错误导致 Agent 静默空转，任务不会拉起）——
   该场景由管理端心跳告警发现后人工处置。

## 相关文档

- `../../README.md`（Agent 安装、注册码与通道配置）
