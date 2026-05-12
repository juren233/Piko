#requires -Version 7
<#
.SYNOPSIS
  应用 D1 migrations（local 或 remote）。
.EXAMPLE
  .\apply-migrations.ps1            # 默认 local
  .\apply-migrations.ps1 remote     # 推到生产 D1
#>
param(
  [ValidateSet("local", "remote")]
  [string]$Mode = "local"
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath (Join-Path $PSScriptRoot "..")

if ($Mode -eq "local") {
  pnpm wrangler d1 migrations apply piko-db --local
} else {
  pnpm wrangler d1 migrations apply piko-db --remote
}
