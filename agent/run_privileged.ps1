#
# Copyright 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

[CmdletBinding()]
param(
    [Alias("b")]
    [switch]$Build,

    [Alias("r")]
    [switch]$Release,

    [Alias("f")]
    [ValidateSet("standard", "retail")]
    [string]$Flavor = "standard",

    [Alias("k")]
    [string]$ApiKey = "",

    [Alias("s")]
    [string]$Serial = "",

    [Alias("h")]
    [switch]$Help
)

$ErrorActionPreference = "Stop"

$PackageName = "com.example.appfunctions.agent"
$InstrumentationName = "$PackageName/.ShellIdentityInstrumentation"
$InstrumentationStartupTimeoutMilliseconds = 2000

function Show-Usage {
    @"
Usage: .\run_privileged.ps1 [OPTIONS]

Options:
  -Build             Force rebuild of the app before installing
  -Release           Use release build instead of debug
  -Flavor FLAVOR     Build flavor: standard or retail (default: standard)
  -ApiKey KEY        Gemini API key (required for retail builds)
  -Serial SERIAL     ADB device serial number
  -Help              Show this help message

By default, this script installs the existing APK if found. If the APK is
missing, it builds it automatically. Use -Build to force a rebuild.

Examples:
  .\run_privileged.ps1 -Build
  .\run_privileged.ps1 -Build -Flavor retail -ApiKey YOUR_API_KEY
"@
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    $commandArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($script:SelectedSerial)) {
        $commandArguments += @("-s", $script:SelectedSerial)
    }
    $commandArguments += $Arguments

    $output = & $script:AdbPath @commandArguments 2>&1
    $exitCode = $LASTEXITCODE
    $outputText = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "ADB command failed with exit code ${exitCode}: adb $($commandArguments -join ' ')`n$outputText"
    }

    return $outputText
}

if ($Help) {
    Show-Usage
    exit 0
}

Push-Location $PSScriptRoot
try {
    $adbCommand = Get-Command adb -ErrorAction Stop
    $script:AdbPath = $adbCommand.Source
    if ([string]::IsNullOrWhiteSpace($script:AdbPath)) {
        throw "Could not resolve adb.exe. Add Android platform-tools to PATH."
    }

    $deviceLines = Invoke-Adb -Arguments @("devices")
    $authorizedDevices = @()
    foreach ($line in ($deviceLines -split "\r?\n")) {
        if ($line -match "^\s*(\S+)\s+device(?:\s|$)") {
            $authorizedDevices += $Matches[1]
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        if ($authorizedDevices -notcontains $Serial) {
            throw "ADB device '$Serial' is not connected and authorized. Run 'adb devices' to check its state."
        }
        $script:SelectedSerial = $Serial
    }
    elseif ($authorizedDevices.Count -eq 1) {
        $script:SelectedSerial = $authorizedDevices[0]
        Write-Host "Using device: $script:SelectedSerial"
    }
    elseif ($authorizedDevices.Count -eq 0) {
        throw "No authorized devices are connected via ADB."
    }
    else {
        throw "Multiple authorized devices are connected. Specify one with -Serial. Devices: $($authorizedDevices -join ', ')"
    }

    $buildType = if ($Release) { "release" } else { "debug" }
    $flavorLower = $Flavor.ToLowerInvariant()
    $buildTypeLower = $buildType.ToLowerInvariant()
    $flavorCapitalized = $flavorLower.Substring(0, 1).ToUpperInvariant() + $flavorLower.Substring(1)
    $buildTypeCapitalized = $buildTypeLower.Substring(0, 1).ToUpperInvariant() + $buildTypeLower.Substring(1)
    $apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\$flavorLower\$buildTypeLower\app-$flavorLower-$buildTypeLower.apk"
    $apkLabel = "$flavorCapitalized $buildTypeCapitalized"

    if ($Build -or -not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        $gradleWrapper = Join-Path $PSScriptRoot "gradlew.bat"
        if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
            throw "Gradle wrapper was not found at '$gradleWrapper'."
        }

        Write-Host "Building $apkLabel app..."
        $gradleArguments = @("assemble$flavorCapitalized$buildTypeCapitalized")
        if (-not [string]::IsNullOrWhiteSpace($ApiKey)) {
            $gradleArguments += "-PGEMINI_API_KEY=$ApiKey"
        }

        & $gradleWrapper @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }

    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "$apkLabel APK was not found at '$apkPath' after the build."
    }

    Write-Host "Installing $apkPath..."
    Invoke-Adb -Arguments @("install", "-r", $apkPath) | Out-Host

    Write-Host "Checking package installation..."
    $installedPackages = Invoke-Adb -Arguments @("shell", "pm", "list", "packages")
    if ($installedPackages -notmatch "(?m)^package:$([regex]::Escape($PackageName))\s*$") {
        throw "Package $PackageName is not installed on $script:SelectedSerial."
    }

    Write-Host "Extracting package fingerprint..."
    $packageDump = Invoke-Adb -Arguments @("shell", "dumpsys", "package", $PackageName)
    $fingerprintMatch = [regex]::Match(
        $packageDump,
        "PackageSignatures.*?signatures:\[([^\]]+)\]"
    )
    if (-not $fingerprintMatch.Success) {
        throw "Could not extract the package signing fingerprint from the device."
    }
    $fingerprint = $fingerprintMatch.Groups[1].Value.ToLowerInvariant()
    Write-Host "Using fingerprint: $fingerprint"

    $sdkVersionText = Invoke-Adb -Arguments @("shell", "getprop", "ro.build.version.sdk")
    $sdkVersion = 0
    if (-not [int]::TryParse($sdkVersionText.Trim(), [ref]$sdkVersion)) {
        throw "Could not determine the Android SDK version on the device."
    }

    if ($sdkVersion -ge 37) {
        Write-Host "Adding package to the AppFunctions allowlist..."
        $appFunctionHelp = Invoke-Adb -Arguments @("shell", "cmd", "app_function", "help") -AllowFailure
        if ($appFunctionHelp -match "purge-allowlist-cache") {
            Invoke-Adb -Arguments @("shell", "cmd", "app_function", "purge-allowlist-cache") | Out-Null
        }

        $allowlistHelp = Invoke-Adb -Arguments @("shell", "cmd", "allowlist", "help") -AllowFailure
        if ($allowlistHelp -match "add-package-with-metadata-multimap") {
            Invoke-Adb -Arguments @(
                "shell", "cmd", "allowlist", "add-package-with-metadata-multimap",
                "2", "${PackageName}:$fingerprint",
                "android.allowlist.metadata.key.APP_FUNCTION_TYPE_DOWNLOADED:boolean:true",
                "'*'", "ignore:string:default"
            ) | Out-Null
        }
        else {
            Invoke-Adb -Arguments @(
                "shell", "cmd", "allowlist", "add-package-multimap",
                "2", "${PackageName}:$fingerprint", "'*'"
            ) | Out-Null
        }

        if ($appFunctionHelp -match "set-temporary-caller") {
            Write-Host "Flushing allowlist changes..."
            Invoke-Adb -Arguments @("shell", "cmd", "app_function", "flush-allowlist-changes") | Out-Null
        }
    }
    else {
        Write-Host "Skipping allowlist setup for Android SDK $sdkVersion."
    }

    Write-Host "Stopping existing AppFunction Testing Agent instances..."
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null

    Write-Host "Starting AppFunction Testing Agent with ShellIdentityInstrumentation..."
    $instrumentationArguments = @("shell", "am", "instrument", "-w", $InstrumentationName)
    $adbProcess = Start-Process -FilePath $script:AdbPath `
        -ArgumentList (@("-s", $script:SelectedSerial) + $instrumentationArguments) `
        -PassThru -WindowStyle Hidden

    if ($adbProcess.WaitForExit($InstrumentationStartupTimeoutMilliseconds)) {
        $errorMessage = "ShellIdentityInstrumentation exited immediately (ADB exit code $($adbProcess.ExitCode))."
        Write-Warning "$errorMessage Falling back to a regular app launch."
        Invoke-Adb -Arguments @(
            "shell", "am", "start", "-n", "$PackageName/.MainActivity",
            "--es", "EXTRA_INSTRUMENTATION_ERROR", $errorMessage
        ) | Out-Host
    }
    else {
        Write-Host "Instrumentation started successfully."
    }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
finally {
    Pop-Location
}
