# setupqr.ps1: makes a setup QR for the app (tools/SetupQr.java, with ZXing).
#
#   powershell -File tools\setupqr.ps1 -Out ..\LOCAL_DATA\my_user\setup_qr.png -Server https://ppcoachtracker.pepemxl.com `
#       -EmailVar PROD_COACH_EMAIL -PasswordVar PROD_COACH_PASSWORD -EnvFile ..\.env
#
# -EmailVar and -PasswordVar are variable NAMES. With -EnvFile, only those two
# are read from that file (KEY=value, quotes allowed) into this process; they
# are never printed. Without -PasswordVar the QR has no password. Uses the JDK
# of JAVA_HOME or Android Studio and the ZXing jar Gradle already downloaded.

param(
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Server,
    [Parameter(Mandatory = $true)][string]$EmailVar,
    [string]$PasswordVar = '',
    [string]$EnvFile = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($EnvFile) {
    $wanted = @($EmailVar, $PasswordVar) | Where-Object { $_ }
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' -and $wanted -contains $Matches[1]) {
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($Matches[1], $value, 'Process')
        }
    }
}

$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' }
if (-not (Test-Path $java)) { throw 'No JDK: set JAVA_HOME or install Android Studio.' }
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.zxing\core" -Recurse -Filter 'core-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*sources*' } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $jar) { throw 'No ZXing jar in the Gradle cache: run make.bat debug once.' }

$javaArgs = @('-cp', $jar.FullName, (Join-Path $here 'SetupQr.java'), $Out, $Server, $EmailVar)
if ($PasswordVar) { $javaArgs += $PasswordVar }
& $java @javaArgs
exit $LASTEXITCODE
