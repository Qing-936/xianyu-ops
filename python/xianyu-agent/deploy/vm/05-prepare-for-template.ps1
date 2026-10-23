#Requires -RunAsAdministrator
<#
.SYNOPSIS
  母盘清整：清理机器指纹 / 专属 Token / 日志 / 进程，为 Proxmox「转换为模板」做准备。
.DESCRIPTION
  对应 deploy/vm/README.md §4.6（转模板）。
  执行顺序：端到端验收通过 → 本脚本 → 关机 → Proxmox 转模板。
  ⚠️ 必须删除 .machine_code：它是每台机器唯一的 UUID，随母盘克隆会导致
     多台 VM 共用同一 machineCode → Java 侧 uk_machine_code 冲突、注册串号（部署硬前置）。
  ⚠️ 必须删除 .agent_token：M5 一机一 Token 的专属 Token（激活后由心跳一次性下发），
     随母盘克隆会导致多台 VM 携带旧专属 Token 启动 → Java 侧身份校验拒绝（部署硬前置）。
  计划任务定义保留（克隆后登录即生效）；config.local.yaml 保留，但其中 server.enroll_code 已被母盘首次心跳消费掉
  （一次性注册码，单次有效）——克隆后必须在管理端「Agent 管理 → 注册码」为每台新机重新生成一张并替换。
.PARAMETER AgentDir
  Agent 代码目录，默认 C:\xianyu-agent。
.EXAMPLE
  .\05-prepare-for-template.ps1
#>
param([string]$AgentDir = 'C:\xianyu-agent')

Write-Host '== 1/5 停止 Agent 相关计划任务与进程 =='
foreach ($t in @('XianyuAgent', 'XianyuSessionKeepAlive')) {
    $task = Get-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue
    if ($task) {
        Stop-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue
        Write-Host "  [OK] 已停止任务 $t（保留任务定义）"
    }
}
# 按命令行匹配（$_.Path 是 pythonw.exe 的 exe 路径，永不等于 $AgentDir——改用 CommandLine 定位 Agent 进程）
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($AgentDir, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host "  [OK] 已结束进程 $($_.Name) (PID $($_.ProcessId))" }

Write-Host '== 2/5 删除机器指纹与专属 Token（关键） =='
foreach ($f in @('.machine_code', '.agent_token')) {
    $p = Join-Path $AgentDir $f
    if (Test-Path $p) {
        Remove-Item $p -Force
        Write-Host "  [OK] 已删除 $p"
    } else {
        Write-Host "  [SKIP] $f 不存在"
    }
}
Write-Host '  （克隆后首次启动自动生成新 machineCode，并用新生成的一次性注册码注册领取专属 Token）'

Write-Host '== 3/5 清理运行产物 =='
$logsDir = Join-Path $AgentDir 'logs'
if (Test-Path $logsDir) {
    Remove-Item (Join-Path $logsDir '*') -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  [OK] 已清理 $logsDir\*"
}
# 递归清掉全部 __pycache__（根目录 + agent/ 各子包，含目录壳与其中的 .pyc）
Get-ChildItem $AgentDir -Recurse -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Write-Host '  [OK] 已递归清理 __pycache__'

Write-Host '== 4/5 清理回收站 =='
try {
    Clear-RecycleBin -Force -ErrorAction Stop
    Write-Host '  [OK] 回收站已清空'
} catch { Write-Host "  [WARN] $($_.Exception.Message)" }

Write-Host '== 5/5 完成 =='
Write-Host '  下一步：关机 → Proxmox「转换为模板」。'
Write-Host '  人工检查清单：'
Write-Host '    [ ] 闲管家已退出所有闲鱼账号登录（登录在克隆后逐台人工执行）'
Write-Host '    [ ] 显示分辨率固定且与母盘 UI 标定一致；闲管家窗口尺寸未变'
Write-Host '    [ ] 桌面无个人化残留（壁纸 / 文档 / 无关软件）'
Write-Host '    [ ] config.local.yaml 的 base_url 为部署目标环境值；server.enroll_code 已清空（克隆后逐台填入新生成的注册码）'
Write-Host '    [ ] .machine_code / .agent_token 已删除（专属 Token 由各克隆机首次心跳自动领取，人工不填）'
