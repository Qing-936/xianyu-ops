#Requires -RunAsAdministrator
<#
.SYNOPSIS
  注册「闲鱼 Agent」计划任务（登录触发 + 崩溃自动重启），替代 NSSM 服务方案。
.DESCRIPTION
  对应 deploy/vm/README.md §4.4。
  · 为什么不用 NSSM 服务：Windows 服务运行在 Session 0，与用户交互桌面隔离，
    无法访问桌面 UI —— Agent 的 UIA 定位、截图 OCR、键鼠模拟全部失效。
    Agent 必须运行在「已登录用户的交互会话」中。
  · 本脚本方案：autologon（02 脚本）保证自动进入桌面会话 →
    本任务「用户登录时」以交互方式启动 pythonw.exe main.py →
    失败自动重启（每 1 分钟、最多 999 次），覆盖 Agent 主循环卡死/进程退出后的自拉起。
  · 必须用 pythonw.exe（无控制台窗口）：控制台窗口若出现会遮挡闲管家窗口，干扰截图 OCR。
.PARAMETER AgentDir
  Agent 代码目录，默认 C:\xianyu-agent（应包含 main.py、config.yaml）。
.PARAMETER PythonPath
  pythonw.exe 完整路径；缺省自动从 PATH 探测。
.PARAMETER UserName
  运行账户，默认当前用户（应与 autologon 账户一致）。
.PARAMETER TaskName
  任务名，默认 XianyuAgent。
.EXAMPLE
  .\03-register-agent-task.ps1 -AgentDir C:\xianyu-agent
#>
param(
    [string]$AgentDir = 'C:\xianyu-agent',
    [string]$PythonPath,
    [string]$UserName = $env:USERNAME,
    [string]$TaskName = 'XianyuAgent'
)

# 1. 前置校验
$mainPy = Join-Path $AgentDir 'main.py'
if (-not (Test-Path $mainPy)) {
    throw "未找到 $mainPy —— 请先把 python/xianyu-agent 代码拷贝到 $AgentDir"
}
if (-not $PythonPath) {
    $cmd = Get-Command pythonw.exe -ErrorAction SilentlyContinue
    if (-not $cmd) { throw '未找到 pythonw.exe —— 请安装 Python（勾选 Add python.exe to PATH）或用 -PythonPath 指定完整路径' }
    $PythonPath = $cmd.Source
}
if (-not (Test-Path $PythonPath)) { throw "PythonPath 不存在：$PythonPath" }
$configLocal = Join-Path $AgentDir 'config.local.yaml'
if (-not (Test-Path $configLocal)) {
    Write-Host "  [WARN] 未发现 $configLocal —— 启动前必须配置 server.base_url，且 server.enroll_code（首次注册用一次性注册码）或已存在 .agent_token，否则 Agent 校验失败退出"
}

# 2. 任务定义
$action = New-ScheduledTaskAction -Execute $PythonPath -Argument 'main.py' -WorkingDirectory $AgentDir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $UserName
try { $trigger.Delay = 'PT1M' } catch { Write-Host '  [WARN] 登录延迟（PT1M）设置失败，忽略' }  # 等桌面/闲管家就绪
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId "$env:COMPUTERNAME\$UserName" -LogonType Interactive -RunLevel Highest

# 3. 注册（幂等：同名先删后建）
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "  [OK] 已移除旧任务 $TaskName"
}
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description '闲鱼 Agent：登录触发 + 失败自动重启（替代 NSSM 服务；详见 deploy/vm/README.md）' | Out-Null
Write-Host "  [OK] 已注册计划任务 $TaskName（登录触发，延迟 1 分钟）"

# 4. 当前会话已登录时尝试立即启动
try {
    Start-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    Start-Sleep -Seconds 3
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Write-Host "  [OK] 已尝试启动；LastRunTime=$($info.LastRunTime) LastTaskResult=$($info.LastTaskResult)"
} catch {
    Write-Host "  [WARN] 立即启动失败：$($_.Exception.Message)（登录后会自动触发，可忽略）"
}

Write-Host ''
Write-Host '  验证：'
Write-Host '    1) 确认 Agent 进程：Get-Process pythonw'
Write-Host "    2) 管理端 Agent 列表应看到 $env:COMPUTERNAME 上线（心跳后 15s 内）"
Write-Host "    3) 任务详情：schtasks /query /tn $TaskName /v /fo LIST"
