# register_wsl_task.ps1: keeps the production worker alive across reboots.
#
# Registers a task for the current user that, at logon, starts WSL and keeps
# it running (a hidden `sleep infinity`): Docker starts with systemd inside
# WSL and brings the worker back (restart: unless-stopped). Without an
# attached process WSL can shut the VM down when idle.
#
#   powershell -ExecutionPolicy Bypass -File worker\tools\register_wsl_task.ps1 [-Distro Ubuntu-22.04]
#   Unregister-ScheduledTask -TaskName ppcoachtracker-wsl -Confirm:$false   # to remove it

param([string]$Distro = 'Ubuntu-22.04')

$ErrorActionPreference = 'Stop'
$name = 'ppcoachtracker-wsl'
$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument "--headless wsl.exe -d $Distro --exec sleep infinity"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Keeps WSL (Docker) running for the pp-coach-tracker production worker' -Force | Out-Null
Start-ScheduledTask -TaskName $name
Write-Host "Task $name registered and started (WSL $Distro stays up from logon)."
