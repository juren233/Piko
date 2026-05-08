$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..")
$gradle = Join-Path $repoRoot "gradlew.bat"
$configPath = Join-Path $repoRoot ".github\build-config.json"
$artifactRoot = Join-Path $repoRoot "scripts\artifacts\android"

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "未找到 Gradle Wrapper：$gradle"
}
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "未找到构建配置：$configPath"
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $config.build.enabled -or -not $config.android.enabled) {
    Write-Host "Android 构建已在配置中关闭。"
    exit 0
}

$enabledAbis = @(
    $config.android.architectures.PSObject.Properties |
        Where-Object { $_.Value -eq $true } |
        ForEach-Object { $_.Name }
)
if ($enabledAbis.Count -eq 0) {
    throw "Android 至少需要开启一个预设架构。"
}

$enabledVariants = @(
    $config.android.variants.PSObject.Properties |
        Where-Object { $_.Value -eq $true } |
        ForEach-Object { $_.Name.ToLowerInvariant() }
)
if ($enabledVariants.Count -eq 0) {
    throw "Android 至少需要开启 debug 或 release 中的一个构建类型。"
}

$javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
}
if ([string]::IsNullOrWhiteSpace($javaHome) -or -not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "JAVA_HOME 无效，请先配置到可用 JDK。当前值：$javaHome"
}

$env:JAVA_HOME = $javaHome

function Copy-Apk {
    param(
        [string]$Source,
        [string]$Destination
    )

    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            if (Test-Path -LiteralPath $Destination) {
                Remove-Item -LiteralPath $Destination -Force
            }
            Copy-Item -LiteralPath $Source -Destination $Destination -Force
            return
        } catch {
            if ($attempt -eq 5) {
                throw
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

function Remove-ApkWithRetry {
    param(
        [string]$Path
    )

    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            if (Test-Path -LiteralPath $Path) {
                Remove-Item -LiteralPath $Path -Force
            }
            return
        } catch {
            if ($attempt -eq 10) {
                throw
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

function Get-Apk {
    param(
        [string]$Variant
    )

    $sourceDir = Join-Path $repoRoot "android\build\outputs\apk\$Variant"
    $apk = Get-ChildItem -LiteralPath $sourceDir -Filter "*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $apk) {
        throw "未找到 $Variant APK：$sourceDir"
    }
    return $apk.FullName
}

$variantTasks = @{
    debug = ":android:assembleDebug"
    release = ":android:assembleRelease"
}

New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
Get-ChildItem -LiteralPath $artifactRoot -Recurse -Filter "piko-android-*.apk" |
    ForEach-Object { Remove-ApkWithRetry -Path $_.FullName }

Push-Location $repoRoot
try {
    foreach ($abi in $enabledAbis) {
        $tasks = $enabledVariants | ForEach-Object { $variantTasks[$_] }
        & $gradle @tasks "-PpikoAndroidAbis=$abi"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle 构建失败，退出码：$LASTEXITCODE"
        }

        foreach ($variant in $enabledVariants) {
            $targetDir = Join-Path $artifactRoot $variant
            New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

            $source = Get-Apk -Variant $variant
            $suffix = if ($variant -eq "debug") { "-debug" } else { "" }
            $target = Join-Path $targetDir "piko-android-$abi$suffix.apk"
            Copy-Apk -Source $source -Destination $target
        }
    }

    Write-Host "Android 构建完成，产物已复制到：$artifactRoot"
} finally {
    Pop-Location
}
