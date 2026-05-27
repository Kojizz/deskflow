# Start dependencies without launching a second backend beside IntelliJ.
param(
 [string]$PostgresBin = 'C:/Program Files/PostgreSQL/17/bin',
 [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'start-local.ps1') -PostgresBin $PostgresBin -SkipBuild:$SkipBuild -InfrastructureOnly
