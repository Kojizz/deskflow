$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) { Write-Host '.env already exists; keeping existing keys.'; exit 0 }
function New-RandomSecret { param([int]$Length = 32)
  $bytes = New-Object byte[] $Length
  $generator=[System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  [Convert]::ToBase64String($bytes)
}
@("POSTGRES_PASSWORD=$(New-RandomSecret)", "RABBITMQ_PASSWORD=$(New-RandomSecret)", "APP_ENCRYPTION_KEY=$(New-RandomSecret)", 'APP_PORT=8088') | Set-Content -LiteralPath $envPath -Encoding utf8
Write-Host 'Generated local .env. Keep this file and encryption key private.'
