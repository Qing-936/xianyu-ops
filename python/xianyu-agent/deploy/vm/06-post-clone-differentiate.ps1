#Requires -RunAsAdministrator
<#
.SYNOPSIS
  克隆后差异化：改主机名 / 校时区 / 校验专属 Token 不残留，并输出人工检查清单。
.DESCRIPTION
  对应 deploy/vm/README.md §5；01-系统现状.md §1.5（防关联）。
  · 自动化部分：hostname、时区、.agent_token 残留校验（M5 一机一 Token）。
  · 人工部分（防关联要求）：固定 IP / 出口 IP、分辨率、输入法、闲管家扫码登录、基线快照。
.PARAMETER NewHostName
  新主机名（如 WinVM-02）。与现主机名相同则为 no-op。
.PARAMETER TimeZone
  时区 ID，默认 'China Standard Time'。
.PARAMETER AgentDir
  Agent 代码目录（.agent_token 校验用），默认 C:\xianyu-agent。
.EXAMPLE
  .\06-post-clone-differentiate.ps1 -NewHostName WinVM-02
#>
param(
    [Parameter(Mandatory = $true)][string]$NewHostName,
    [string]$TimeZone = 'China Standard Time',
    [string]$AgentDir = 'C:\xianyu-agent'
)

if ($env:COMPUTERNAME -ne $NewHostName) {
    Rename-Computer -NewName $NewHostName -Force
    Write-Host "  [OK] 主机名将变更为 $NewHostName（重启后生效）"
} else {
    Write-Host "  [SKIP] 主机名已是 $NewHostName"
}

$tz = Get-TimeZone
if ($tz.Id -ne $TimeZone) {
    Set-TimeZone -Id $TimeZone
    Write-Host "  [OK] 时区已改为 $TimeZone"
} else {
    Write-Host "  [SKIP] 时区已是 $TimeZone"
}

# M5 部署硬前置校验：母盘漏跑 05 时，克隆机会带旧专属 Token 启动（心跳被 Java 拒绝）
$at = Join-Path $AgentDir '.agent_token'
if (Test-Path $at) {
    Write-Host "  [WARN] 检测到 $at 存在：母盘可能漏跑 05 脚本或克隆后才激活；"
    Write-Host '         请删除该文件并重启 Agent，否则心跳将被 Java 拒绝（401，日志见 logs\agent.log）'
} else {
    Write-Host '  [OK] 无 .agent_token（首次启动将用一次性注册码注册并自动领取专属 Token）'
}

Write-Host ''
Write-Host '  人工检查清单（防关联，01-系统现状.md §1.5）：'
Write-Host '    [ ] 固定 IP（Proxmox 网桥 / ncpa.cpl）'
Write-Host '    [ ] 出口 IP（试点期共用；扩容期每 VM 独立代理）'
Write-Host '    [ ] 显示分辨率与母盘一致（闲管家窗口尺寸固定，变化需重标 UI 坐标）'
Write-Host '    [ ] 输入法 / 键盘布局差异化'
Write-Host '    [ ] config.local.yaml 中 base_url 正确；server.enroll_code 为本机新生成的注册码（管理端「Agent 管理 → 注册码」生成，短时效、一次性）'
Write-Host '    [ ] 重启后：确认自动登录 → Agent 任务自启 → 管理端 Agent 列表出现新机器（新 machineCode、Token 已激活）'
Write-Host '    [ ] 闲管家扫码登录本机唯一闲鱼号 → 成功后立即做 Proxmox 基线快照'
