#Requires -RunAsAdministrator
<#
.SYNOPSIS
  闲鱼 Agent VM 基线加固（锁屏 / 屏保 / 睡眠 / 自动更新 / 自动重启 / NTP）。
.DESCRIPTION
  对应 deploy/vm/README.md §4.4。幂等，可重复执行。
  目的：VM 长期无人值守时，桌面会话持续可用（Agent 的 UIA/OCR 依赖桌面渲染），
        且系统时钟与 Java 服务端偏差可控（Agent 有时间偏移校正，但源头要稳）。
.NOTES
  - 执行完建议重启一次（电源/策略类设置重启后完全生效）。
  - 回滚口径：本脚本不备份原始值；需要恢复时用 Proxmox 快照回滚，或手动反向设置。
  - Windows 更新被完全禁用（含 WaaSMedicSvc 自愈服务）；维护窗口要更新时先恢复更新服务。
#>

$ErrorActionPreference = 'Continue'

function Set-RegValue {
    param([string]$Path, [string]$Name, $Value, [string]$Type = 'String')
    if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
    Set-ItemProperty -Path $Path -Name $Name -Value $Value -Type $Type -ErrorAction Stop
    Write-Host ("  [OK] {0} :: {1} = {2}" -f $Path, $Name, $Value)
}

Write-Host '== 1/6 电源计划：永不关屏 / 永不睡眠 =='
foreach ($c in @('monitor-timeout-ac', 'monitor-timeout-dc', 'standby-timeout-ac', 'standby-timeout-dc',
                 'hibernate-timeout-ac', 'hibernate-timeout-dc', 'disk-timeout-ac', 'disk-timeout-dc')) {
    powercfg /change $c 0 | Out-Null
}
Write-Host '  [OK] powercfg 超时全部归零（交流/直流）'

Write-Host '== 2/6 屏保：禁用（含恢复时需要密码） =='
foreach ($k in @('HKCU:\Control Panel\Desktop', 'Registry::HKEY_USERS\.DEFAULT\Control Panel\Desktop')) {
    Set-RegValue -Path $k -Name ScreenSaveActive -Value '0'
    Set-RegValue -Path $k -Name ScreenSaverIsSecure -Value '0'
    Set-RegValue -Path $k -Name ScreenSaveTimeOut -Value '0'
}

Write-Host '== 3/6 自动锁屏：禁用（机器不活动限制） =='
Set-RegValue -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System' `
    -Name InactivityTimeoutSecs -Value 0 -Type DWord

Write-Host '== 4/6 Windows 自动更新：禁用（含自愈服务 WaaSMedicSvc） =='
Set-RegValue -Path 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' -Name NoAutoUpdate -Value 1 -Type DWord
Set-RegValue -Path 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' -Name NoAutoRebootWithLoggedOnUsers -Value 1 -Type DWord
foreach ($svc in @('wuauserv', 'UsoSvc')) {
    try {
        Stop-Service $svc -Force -ErrorAction Stop
        Set-Service $svc -StartupType Disabled
        Write-Host "  [OK] 服务 $svc 已停用"
    } catch { Write-Host "  [WARN] 服务 ${svc}: $($_.Exception.Message)" }
}
# WaaSMedicSvc 不允许通过 Set-Service 修改，直接改注册表 Start=4（禁用），否则它会自愈恢复更新
try {
    Stop-Service WaaSMedicSvc -Force -ErrorAction SilentlyContinue
    Set-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\WaaSMedicSvc' -Name Start -Value 4 -Type DWord
    Write-Host '  [OK] 服务 WaaSMedicSvc Start=4（注册表禁用，重启后生效）'
} catch { Write-Host "  [WARN] WaaSMedicSvc: $($_.Exception.Message)" }
# 更新相关计划任务（防后台扫描 / 触发重启）
foreach ($tp in @('\Microsoft\Windows\UpdateOrchestrator\', '\Microsoft\Windows\WindowsUpdate\', '\Microsoft\Windows\InstallService\')) {
    Get-ScheduledTask -TaskPath ($tp + '*') -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            Disable-ScheduledTask -TaskName $_.TaskName -TaskPath $_.TaskPath | Out-Null
            Write-Host "  [OK] 禁用任务 $($_.TaskPath)$($_.TaskName)"
        } catch { Write-Host "  [WARN] 任务 $($_.TaskPath)$($_.TaskName) 禁用失败" }
    }
}

Write-Host '== 5/6 NTP 时钟同步（ntp.aliyun.com） =='
Set-Service w32time -StartupType Automatic
Start-Service w32time
w32tm /config /manualpeerlist:"ntp.aliyun.com,ntp1.aliyun.com" /syncfromflags:manual /update | Out-Null
Restart-Service w32time
Start-Sleep -Seconds 2
w32tm /resync /force | Out-Null
w32tm /query /status | Select-Object -First 8
# 每小时兜底 resync 计划任务
$tskName = 'XianyuTimeSync'
if (Get-ScheduledTask -TaskName $tskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $tskName -Confirm:$false
}
$act = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\w32tm.exe" -Argument '/resync'
$trg = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
    -RepetitionInterval (New-TimeSpan -Hours 1) -RepetitionDuration (New-TimeSpan -Days 3650)
Register-ScheduledTask -TaskName $tskName -Action $act -Trigger $trg -User 'SYSTEM' `
    -Description '每小时 NTP 兜底同步（闲鱼 Agent VM 基线，deploy/vm/README.md）' | Out-Null
Write-Host "  [OK] 计划任务 $tskName 已注册（每 1 小时 w32tm /resync）"

Write-Host '== 6/6 汇总检查 =='
Write-Host ("  当前时间: {0}；时区: {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), (Get-TimeZone).Id)
Write-Host ("  wuauserv 状态: {0}" -f (Get-Service wuauserv).Status)
w32tm /query /source

Write-Host ''
Write-Host '基线加固完成。建议重启一次，再执行 02-enable-autologon.ps1。'
