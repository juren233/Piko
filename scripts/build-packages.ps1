param(
    [ValidateSet("android", "all")]
    [string]$Platform = "all"
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-PlatformBuild {
    param(
        [string]$Name
    )

    $entry = Join-Path $scriptRoot "$Name\build-packages.ps1"
    if (-not (Test-Path -LiteralPath $entry)) {
        throw "未找到平台构建脚本：$entry"
    }

    & $entry
}

switch ($Platform) {
    "android" {
        Invoke-PlatformBuild -Name "android"
    }
    "all" {
        Invoke-PlatformBuild -Name "android"
    }
}
