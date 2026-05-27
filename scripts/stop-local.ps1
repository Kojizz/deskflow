$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $projectRoot '.local/processes.json'
if(-not(Test-Path -LiteralPath $statePath)){Write-Host 'No local session is recorded.';exit 0}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if([IO.Path]::GetFullPath($state.root) -ne [IO.Path]::GetFullPath($projectRoot)){throw 'Session belongs to a different project folder.'}
$normalizedRoot=([IO.Path]::GetFullPath($projectRoot)).Replace('/','\').ToLowerInvariant()
foreach($processId in $state.pids){
 $owned=Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
 if($owned -and $owned.CommandLine -and $owned.CommandLine.Replace('/','\').ToLowerInvariant().Contains($normalizedRoot)) {Stop-Process -Id $processId -ErrorAction SilentlyContinue}
}
& (Join-Path $state.postgresBin 'pg_ctl.exe') -D $state.pgdata -m fast stop
Remove-Item -LiteralPath $statePath
Write-Host 'Stopped the local session. Database files are retained.'
