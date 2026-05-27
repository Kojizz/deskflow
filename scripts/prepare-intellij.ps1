# IntelliJ Before launch task. Release only this launcher's API, then ensure
# its dependencies are ready. Existing database files and secrets are retained.
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(Test-Path -LiteralPath (Join-Path $projectRoot '.local/processes.json')){
 & (Join-Path $PSScriptRoot 'stop-local-api.ps1')
}
& (Join-Path $PSScriptRoot 'start-infrastructure.ps1') -SkipBuild:$SkipBuild
