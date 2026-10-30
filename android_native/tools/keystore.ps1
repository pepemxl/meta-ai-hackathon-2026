# keystore.ps1: creates the release signing key and keystore.properties.
# Run it through `make.bat keystore`. Neither file is versioned.
#
# The password is asked twice without echo, or taken from
# PP_COACH_TRACKER_KEYSTORE_PASSWORD (for scripts: Read-Host only reads the console).
# It never goes on keytool's command line.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root 'keystore.properties'
$storeRel = 'keystore/pp-coach-tracker-release.jks'
$store = Join-Path $root $storeRel
$alias = 'ppcoachtracker'

# Android identifies the app by its signature: overwriting a key that signed an
# installed release means that release can never be updated again.
foreach ($f in @($props, $store)) {
    if (Test-Path $f) {
        Write-Host "  $f already exists and will not be overwritten."
        Write-Host '  If you really want a new key, delete keystore.properties and the .jks by hand.'
        exit 1
    }
}

$keytool = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
foreach ($c in $candidates) { if (Test-Path $c) { $keytool = $c; break } }
if (-not $keytool) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) {
    Write-Host '  keytool not found: set JAVA_HOME or install Android Studio.'
    exit 1
}

function Read-Secret([string]$prompt) {
    $s = Read-Host -Prompt $prompt -AsSecureString
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}

$password = $env:PP_COACH_TRACKER_KEYSTORE_PASSWORD
if ($password) {
    Write-Host '  Using the password from PP_COACH_TRACKER_KEYSTORE_PASSWORD.'
} else {
    $password = Read-Secret '  Keystore password (8+ characters)'
    if ($password -ne (Read-Secret '  Repeat it')) {
        Write-Host '  The passwords do not match.'
        exit 1
    }
}
if ($password.Length -lt 8) {
    Write-Host '  The password needs at least 8 characters.'
    exit 1
}

New-Item -ItemType Directory -Force (Split-Path -Parent $store) | Out-Null
$env:PP_KEYSTORE_PASS = $password
try {
    & $keytool -genkeypair -noprompt -keystore $store -storetype PKCS12 -alias $alias `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=pp-coach-tracker, C=MX' `
        -storepass:env PP_KEYSTORE_PASS -keypass:env PP_KEYSTORE_PASS
    if ($LASTEXITCODE -ne 0) { Write-Host '  keytool failed.'; exit 1 }
} finally {
    Remove-Item Env:PP_KEYSTORE_PASS -ErrorAction SilentlyContinue
}

# .properties: a backslash in a value is an escape. UTF-8 without BOM, or the
# first key is not read.
$p = $password.Replace('\', '\\')
$text = "storeFile=$storeRel`nstorePassword=$p`nkeyAlias=$alias`nkeyPassword=$p`n"
[IO.File]::WriteAllText($props, $text, (New-Object Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "  Key in $storeRel, password in keystore.properties."
Write-Host '  Back up both outside the repo: a lost key means no more updates for this app.'
Write-Host '  Next: make.bat release'
