# Leave PostgreSQL, Mailpit and Vite running so IntelliJ can own the backend.
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$statePath=Join-Path $projectRoot '.local/processes.json'
if(-not(Test-Path -LiteralPath $statePath)){
 Write-Host 'No native launcher session is recorded. Start scripts/start-local.ps1 first.'
 exit 0
}
$localState=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if([IO.Path]::GetFullPath($localState.root) -ne $projectRoot){throw 'Session belongs to a different project folder.'}
$expectedJar=([IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/target/deskflow-0.2.0.jar'))).Replace('/','\').ToLowerInvariant()
$stoppedIds=@()
foreach($candidateId in $localState.pids){
 $candidate=Get-CimInstance Win32_Process -Filter "ProcessId=$candidateId" -ErrorAction SilentlyContinue
 if($candidate -and $candidate.Name -eq 'java.exe' -and $candidate.CommandLine -and $candidate.CommandLine.Replace('/','\').ToLowerInvariant().Contains($expectedJar)){
  Stop-Process -Id $candidateId -ErrorAction Stop
  $stoppedIds+= $candidateId
 }
}
if($stoppedIds.Count){
 $localState.pids=@($localState.pids | Where-Object {$_ -notin $stoppedIds})
 $localState | ConvertTo-Json | Set-Content -LiteralPath $statePath
 Write-Host 'Stopped the launcher backend. PostgreSQL, Mailpit and Vite are still running.'
}else{Write-Host 'The launcher backend is already stopped; no other process was changed.'}
Write-Host 'In IntelliJ, run DeskFlow Local or Application with Active profiles set to local.'
