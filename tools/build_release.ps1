<#
.SYNOPSIS
  Build a complete ESP Widget release: firmware image, signed Android APK,
  portable Windows flasher, and SHA-256 checksums.

.EXAMPLE
  cd "C:\Users\Gourav\Documents\Random Projects\Esp Widget"
  powershell -ExecutionPolicy Bypass -File .\tools\build_release.ps1

  Add -SkipAndroid or -SkipFlasher when iterating on one piece.
#>
param(
    [switch]$SkipFirmware,
    [switch]$SkipAndroid,
    [switch]$SkipFlasher,
    [switch]$SkipSmokeTest
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Step([string]$message) {
    Write-Host ""
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Fail([string]$message) {
    Write-Host ""
    Write-Host "BUILD FAILED: $message" -ForegroundColor Red
    exit 1
}

function Require-Exit([string]$what) {
    if ($LASTEXITCODE -ne 0) {
        Fail "$what (exit $LASTEXITCODE)"
    }
}

function Write-Utf8([string]$path, [string]$text) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $encoding)
}

$FirmwareDir = Join-Path $Root "firmware"
$ReleaseDir = Join-Path $Root "release"
$FwOut = Join-Path $ReleaseDir "firmware"
$ApkOut = Join-Path $ReleaseDir "android"
$DesktopOut = Join-Path $ReleaseDir "desktop"
$Assets = Join-Path $Root "flasher\esp_flasher\assets"

foreach ($dir in @($ReleaseDir, $FwOut, $ApkOut, $DesktopOut, $Assets)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

# ---------------------------------------------------------------- toolchain
$Pio = Join-Path $env:USERPROFILE ".platformio\penv\Scripts\pio.exe"
if (-not (Test-Path $Pio)) {
    $pioCommand = Get-Command pio.exe -ErrorAction SilentlyContinue
    if ($null -eq $pioCommand) {
        Fail "PlatformIO was not found. Install it, then run: pio run -d firmware"
    }
    $Pio = $pioCommand.Source
}

$Py = Join-Path $Root ".flasher-venv\Scripts\python.exe"
if (-not (Test-Path $Py)) {
    Step "Creating the flasher build environment"
    $base = $null
    foreach ($candidate in @("py -3.12", "py -3.14", "py -3")) {
        $parts = $candidate.Split(" ")
        try {
            & $parts[0] $parts[1] -c "import tkinter" 2>$null
            if ($LASTEXITCODE -eq 0) {
                $base = $candidate
                break
            }
        } catch {
        }
    }
    if ($null -eq $base) {
        Fail "A Python 3.12+ install with tkinter is required to build the flasher."
    }
    $baseParts = $base.Split(" ")
    & $baseParts[0] $baseParts[1] -m venv (Join-Path $Root ".flasher-venv")
    Require-Exit "Could not create .flasher-venv"
    & $Py -m pip install --upgrade pip -q
    & $Py -m pip install -q -r (Join-Path $Root "flasher\requirements.txt")
    Require-Exit "Could not install flasher build dependencies"
}

# Make sure an existing environment can import everything before a long build.
& $Py -c "import esptool, serial, tkinter, PyInstaller, PIL" 2>$null
if ($LASTEXITCODE -ne 0) {
    Step "Installing missing flasher build dependencies"
    & $Py -m pip install -q -r (Join-Path $Root "flasher\requirements.txt")
    Require-Exit "Could not install flasher build dependencies"
}

$ini = Get-Content (Join-Path $FirmwareDir "platformio.ini") -Raw
$major = [regex]::Match($ini, "ESPW_VERSION_MAJOR=(\d+)").Groups[1].Value
$minor = [regex]::Match($ini, "ESPW_VERSION_MINOR=(\d+)").Groups[1].Value
$patch = [regex]::Match($ini, "ESPW_VERSION_PATCH=(\d+)").Groups[1].Value
if (-not $major) { $major = "1" }
if (-not $minor) { $minor = "0" }
if (-not $patch) { $patch = "0" }
$Version = "$major.$minor.$patch"
$BuiltUtc = (Get-Date).ToUniversalTime().ToString(
    "yyyy-MM-dd'T'HH':'mm':'ss'Z'",
    [System.Globalization.CultureInfo]::InvariantCulture
)

Write-Host ""
Write-Host "ESP Widget release $Version" -ForegroundColor Yellow
Write-Host "Output: $ReleaseDir"

# ----------------------------------------------------------------- firmware
if (-not $SkipFirmware) {
    Step "Building firmware $Version (clean release build)"
    & $Pio run -d $FirmwareDir -e esp32-c3-devkitm-1 -t clean
    Require-Exit "Firmware clean failed"
    & $Pio run -d $FirmwareDir -e esp32-c3-devkitm-1
    Require-Exit "Firmware build failed"
} else {
    Step "Skipping firmware build; using the existing PlatformIO output"
}

$Boot = Join-Path $FirmwareDir ".pio\build\esp32-c3-devkitm-1\bootloader.bin"
$Parts = Join-Path $FirmwareDir ".pio\build\esp32-c3-devkitm-1\partitions.bin"
$App = Join-Path $FirmwareDir ".pio\build\esp32-c3-devkitm-1\firmware.bin"
foreach ($file in @($Boot, $Parts, $App)) {
    if (-not (Test-Path $file)) {
        Fail "Missing firmware artifact: $file"
    }
}

Step "Merging factory image"
$Full = Join-Path $FwOut "esp-widget-$Version-full.bin"
& $Py -m esptool --chip esp32c3 merge_bin -o $Full --flash_mode dio --flash_freq 80m --flash_size 4MB `
    0x0 $Boot 0x8000 $Parts 0x10000 $App
Require-Exit "Could not merge the firmware image"

Copy-Item $Boot (Join-Path $FwOut "bootloader.bin") -Force
Copy-Item $Parts (Join-Path $FwOut "partitions.bin") -Force
Copy-Item $App (Join-Path $FwOut "firmware.bin") -Force

$Size = (Get-Item $Full).Length
$Hash = (Get-FileHash -Algorithm SHA256 $Full).Hash.ToLower()
$manifest = [ordered]@{
    name = "ESP Widget"
    version = $Version
    built_utc = $BuiltUtc
    chip = "esp32c3"
    flash_mode = "dio"
    flash_freq = "80m"
    flash_size = "4MB"
    offset = "0x0"
    file = (Split-Path $Full -Leaf)
    size = $Size
    sha256 = $Hash
}
$ManifestPath = Join-Path $FwOut "firmware-manifest.json"
Write-Utf8 $ManifestPath (($manifest | ConvertTo-Json) + "`n")

# The flasher embeds exactly these two files.
Get-ChildItem $Assets -Filter "*.bin" -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $Full $Assets -Force
Copy-Item $ManifestPath (Join-Path $Assets "firmware-manifest.json") -Force

Write-Host ("    firmware.bin: {0:N0} bytes" -f $Size)
Write-Host "    sha256: $Hash"

# ------------------------------------------------------------- desktop app
if (-not $SkipFlasher) {
    Step "Building the portable Windows flasher"
    & $Py (Join-Path $Root "flasher\make_icon.py") | Out-Null
    Require-Exit "Could not generate the flasher icon"

    & $Py -m PyInstaller --clean --noconfirm --distpath $DesktopOut `
        --workpath (Join-Path $Root "flasher\build") `
        (Join-Path $Root "flasher\ESPWidgetFlasher.spec") --log-level WARN
    Require-Exit "PyInstaller failed"

    $Exe = Join-Path $DesktopOut "ESPWidgetFlasher.exe"
    if (-not (Test-Path $Exe)) {
        Fail "The flasher executable was not created."
    }

    Step "Running the packaged flasher self-test"
    $selftest = Join-Path $DesktopOut "self-test.json"
    if (Test-Path $selftest) { Remove-Item $selftest -Force }
    $proc = Start-Process -FilePath $Exe -ArgumentList @("--self-test", "--output", "self-test.json") `
        -WorkingDirectory $DesktopOut -Wait -PassThru
    if ($proc.ExitCode -ne 0 -or -not (Test-Path $selftest)) {
        Fail "Packaged flasher self-test failed (exit $($proc.ExitCode))"
    }

    if (-not $SkipSmokeTest) {
        $proc = Start-Process -FilePath $Exe -ArgumentList @("--smoke-ui") `
            -WorkingDirectory $DesktopOut -Wait -PassThru
        if ($proc.ExitCode -ne 0) {
            Fail "Packaged flasher UI smoke test failed (exit $($proc.ExitCode))"
        }
    }

    $ExeSize = (Get-Item $Exe).Length
    Write-Host ("    ESPWidgetFlasher.exe: {0:N1} MB" -f ($ExeSize / 1MB))
}

# ----------------------------------------------------------------- Android
if (-not $SkipAndroid) {
    Step "Building the signed Android release APK"

    $Jdk = $env:JAVA_HOME
    if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
        $Jdk = $null
        foreach ($candidate in @(
            (Join-Path $env:ProgramFiles "Android\Android Studio\jbr"),
            (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr")
        )) {
            if (Test-Path (Join-Path $candidate "bin\java.exe")) {
                $Jdk = $candidate
                break
            }
        }
    }
    if (-not $Jdk) {
        Fail "JDK 17+ was not found. Set JAVA_HOME to Android Studio's jbr folder."
    }

    $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    $LocalProps = Join-Path $Root "app\local.properties"
    if (-not (Test-Path $LocalProps) -and (Test-Path $Sdk)) {
        Write-Utf8 $LocalProps ("sdk.dir=" + $Sdk.Replace("\", "/") + "`n")
    }

    $Keystore = Join-Path $Root "app\keystore\espwidget-release.jks"
    $KeystoreProps = Join-Path $Root "app\keystore.properties"
    if (-not (Test-Path $KeystoreProps)) {
        if (Test-Path $Keystore) {
            Fail "app\keystore.properties is missing but the keystore exists. Restore the properties file."
        }
        Step "Generating a local release keystore"
        New-Item -ItemType Directory -Force -Path (Split-Path $Keystore) | Out-Null
        $alphabet = (48..57) + (65..90) + (97..122)
        $password = -join (1..24 | ForEach-Object { [char]($alphabet | Get-Random) })
        & (Join-Path $Jdk "bin\keytool.exe") -genkeypair -v `
            -keystore $Keystore -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 `
            -alias espwidget -storepass $password -keypass $password `
            -dname "CN=ESP Widget, OU=Personal, O=Gourav, L=India, ST=India, C=IN" | Out-Null
        Require-Exit "Could not generate the release keystore"
        Write-Utf8 $KeystoreProps (
            "storeFile=keystore/espwidget-release.jks`n" +
            "storePassword=$password`n" +
            "keyAlias=espwidget`n" +
            "keyPassword=$password`n"
        )
        Write-Host "    Kept app\keystore.properties and the JKS; back them up to keep update signatures stable."
    }

    $env:JAVA_HOME = $Jdk
    $env:Path = (Join-Path $Jdk "bin") + ";" + $env:Path
    Push-Location (Join-Path $Root "app")
    try {
        & ".\gradlew.bat" :app:assembleRelease --console=plain
        Require-Exit "Android release build failed"
    } finally {
        Pop-Location
    }

    $Apk = Join-Path $Root "app\app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $Apk)) {
        Fail "Gradle did not produce app-release.apk"
    }
    $ApkName = "EspWidget-$Version-release.apk"
    Copy-Item $Apk (Join-Path $ApkOut $ApkName) -Force

    $signer = Get-ChildItem (Join-Path $Sdk "build-tools") -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1
    if ($signer) {
        & $signer.FullName verify --print-certs (Join-Path $ApkOut $ApkName) 2>&1 | Out-Null
        Require-Exit "APK signature verification failed"
        Write-Host "    signature verified"
    }
    Write-Host ("    {0}: {1:N1} MB" -f $ApkName, ((Get-Item (Join-Path $ApkOut $ApkName)).Length / 1MB))
}

# --------------------------------------------------------------- packaging
Step "Writing release metadata and checksums"
$buildInfo = @"
ESP Widget $Version
Built: $BuiltUtc
Firmware: esp-widget-$Version-full.bin
Chip: ESP32-C3, 4 MB flash

Flash with:
  release\desktop\ESPWidgetFlasher.exe

Install the app with:
  adb install -r release\android\EspWidget-$Version-release.apk
"@
Write-Utf8 (Join-Path $ReleaseDir "BUILD-INFO.txt") ($buildInfo.TrimEnd() + "`n")

$lines = New-Object System.Collections.Generic.List[string]
Get-ChildItem $ReleaseDir -Recurse -File |
    Where-Object { $_.Name -notin @("SHA256SUMS.txt", "BUILD-INFO.txt", "self-test.json") } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($ReleaseDir.Length + 1).Replace("\", "/")
        $digest = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower()
        $lines.Add("$digest  $relative")
    }
Write-Utf8 (Join-Path $ReleaseDir "SHA256SUMS.txt") (($lines -join "`n") + "`n")

Write-Host ""
Write-Host "Release $Version ready in $ReleaseDir" -ForegroundColor Green
Get-ChildItem $ReleaseDir -Recurse -File |
    Where-Object { $_.Name -ne "self-test.json" } |
    Select-Object @{Name = "File"; Expression = { $_.FullName.Substring($ReleaseDir.Length + 1) } },
        @{Name = "MB"; Expression = { [math]::Round($_.Length / 1MB, 2) } } |
    Format-Table -AutoSize
