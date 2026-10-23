#Requires -RunAsAdministrator
<#
.SYNOPSIS
  RDP 会话保活：把断开（Disc）的会话回收为 Console 活动会话，防止 OCR 截不到画面。
.DESCRIPTION
  对应 deploy/vm/README.md §6.1（会话与桌面纪律）。
  · 背景：RDP 会话断开后屏幕锁定 / 桌面停止渲染 → Agent 的 OCR 截图会拿到锁屏画面
    。
  · 处理：用 tscon <会话ID> /dest:console 把该会话接回物理控制台（Proxmox noVNC 即控制台屏幕）。
  · 运维纪律：优先用 Proxmox noVNC 直连控制台维护；若用了 RDP，断开后由本脚本/保活任务自动回收。
.PARAMETER TargetUser
  只回收该用户的 Disc 会话（防误接他人 RDP 会话）；缺省读 Winlogon DefaultUserName（= autologon 账户）。
.PARAMETER Install
  注册计划任务 XianyuSessionKeepAlive（每 2 分钟自动巡检回收，SYSTEM 运行），并立即巡检一次。
.PARAMETER Uninstall
  移除上述计划任务。
.EXAMPLE
  .\04-session-keepalive.ps1            # 立即巡检一次
  .\04-session-keepalive.ps1 -Install   # 安装自动保活任务
#>
param(
    [string]$TargetUser,
    [switch]$Install,
    [switch]$Uninstall,
    [string]$TaskName = 'XianyuSessionKeepAlive'
)

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "  [OK] 已移除任务 $TaskName"
    } else {
        Write-Host '  [SKIP] 任务不存在'
    }
    return
}

if (-not $TargetUser) {
    $TargetUser = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon' `
        -Name DefaultUserName -ErrorAction SilentlyContinue).DefaultUserName
}
if (-not $TargetUser) { throw '无法确定 TargetUser（Winlogon DefaultUserName 为空），请显式传 -TargetUser' }

function Invoke-KeepAlive {
    $done = 0
    foreach ($line in (query session 2>$null)) {
        $parts = (($line -replace '^\s*>', '').Trim()) -split '\s+'
        # 定位会话 ID（第一个纯数字字段）：[会话名] [用户名?] [ID] [状态] [类型] [设备]
        $idx = -1
        for ($i = 0; $i -lt $parts.Count; $i++) {
            if ($parts[$i] -match '^\d+$') { $idx = $i; break }
        }
        if ($idx -lt 1 -or $idx + 1 -ge $parts.Count) { continue }
        $id = $parts[$idx]
        $user = $parts[$idx - 1]
        $state = $parts[$idx + 1]
        if ($user -eq $TargetUser -and $state -match 'Disc|断开') {
            Write-Host "  [ACTION] 会话 $id（$user, $state）→ tscon $id /dest:console"
            tscon.exe $id /dest:console
            $done++
        }
    }
    if ($done -eq 0) { Write-Host '  [OK] 无需处理（无 Disc 状态的维护会话）' }
    return $done
}

if ($Install) {
    $scriptPath = $PSCommandPath
    $arg = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -TargetUser {1}' -f $scriptPath, $TargetUser
    $act = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg
    $trg = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
        -RepetitionInterval (New-TimeSpan -Minutes 2) -RepetitionDuration (New-TimeSpan -Days 3650)
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    Register-ScheduledTask -TaskName $TaskName -Action $act -Trigger $trg -User 'SYSTEM' -RunLevel Highest `
        -Description 'RDP 断开会话回收为 Console（闲鱼 Agent VM；deploy/vm/README.md）' | Out-Null
    Write-Host "  [OK] 已注册任务 $TaskName（每 2 分钟巡检）"
}

Write-Host "== 巡检（TargetUser=$TargetUser） =="
Invoke-KeepAlive | Out-Null
