#Requires -RunAsAdministrator
<#
.SYNOPSIS
  配置 Windows 自动登录（autologon）：VM 重启后免人工进入交互桌面会话。
.DESCRIPTION
  对应 deploy/vm/README.md §4.4。
  · 原理：在 HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon 写入
    AutoAdminLogon / DefaultUserName / DefaultDomainName / DefaultPassword。
  · 为什么需要：Windows 服务运行在 Session 0，与交互桌面隔离 —— Agent（UIA/OCR）与
    闲管家必须运行在已登录的桌面会话中；autologon 是「无人值守保持桌面会话」的前提。
  · 安全说明：DefaultPassword 为注册表明文（受控内网 VM 可接受）；如需加密存储，
    可在本脚本执行后再用 Sysinternals Autologon.exe 执行一次（写 LSA secret，同样生效）。
.PARAMETER UserName
  自动登录的本地账户名（建议 VM 专用维护账户，如 xianyu）。
.PARAMETER Password
  账户密码（SecureString）；缺省时脚本交互式输入。
.PARAMETER Domain
  域 / 计算机名；本地账户缺省取本机计算机名。
.EXAMPLE
  .\02-enable-autologon.ps1 -UserName xianyu
#>
param(
    [Parameter(Mandatory = $true)][string]$UserName,
    [securestring]$Password,
    [string]$Domain = $env:COMPUTERNAME
)

# 前置校验（评审补审）：账户存在 / 启用 / 密码状态——空密码账户受安全策略
# 「Accounts: Limit local account use of blank passwords」限制，无法 autologon。
$u = Get-LocalUser -Name $UserName -ErrorAction SilentlyContinue
if (-not $u) {
    Write-Host "  [WARN] 未找到本地账户 $UserName（域账户可忽略；否则请先创建 VM 维护账户）"
} else {
    if (-not $u.Enabled) { Write-Host "  [WARN] 账户 $UserName 已禁用，autologon 将失败" }
    if (-not $u.PasswordRequired) { Write-Host "  [WARN] 账户 $UserName 未要求密码；空密码账户无法 autologon，请先设置密码" }
}

if (-not $Password) {
    $Password = Read-Host -AsSecureString -Prompt "请输入账户 [$UserName] 的密码"
}
$plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password))

$winlogon = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
Set-ItemProperty -Path $winlogon -Name AutoAdminLogon     -Value '1'       -Type String
Set-ItemProperty -Path $winlogon -Name DefaultUserName    -Value $UserName -Type String
Set-ItemProperty -Path $winlogon -Name DefaultDomainName  -Value $Domain   -Type String
Set-ItemProperty -Path $winlogon -Name DefaultPassword    -Value $plain    -Type String
Remove-ItemProperty -Path $winlogon -Name AutoLogonCount -ErrorAction SilentlyContinue

# 密码永不过期（防止 autologon 因密码到期而失效）
try {
    Set-LocalUser -Name $UserName -PasswordNeverExpires $true -ErrorAction Stop
    Write-Host "  [OK] $UserName 密码已设为永不过期"
} catch {
    Write-Host "  [WARN] 密码永不过期设置失败（$($_.Exception.Message)），请手动确认账户策略"
}

Write-Host ''
Write-Host "  [OK] 自动登录已配置：$Domain\$UserName"
Get-ItemProperty -Path $winlogon | Select-Object AutoAdminLogon, DefaultUserName, DefaultDomainName
Write-Host '  验证方式：重启 VM，应免密自动进入桌面（无需人工干预）。'
Write-Host '  提示：DefaultPassword 为明文；如需加密存储，用 Sysinternals Autologon.exe 重新写入。'
