param(
  [string]$PostgresBin = 'C:/Program Files/PostgreSQL/17/bin',
  [ValidateRange(0,65535)][int]$FrontendPort = 0,
  [switch]$SkipBuild,
  [switch]$InfrastructureOnly
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $projectRoot '.local'
$toolsDir=Join-Path $runtime 'tools'
$logs=Join-Path $runtime 'logs'
$pgdata=Join-Path $runtime 'postgres'
$jar=Join-Path $projectRoot 'backend/target/deskflow-0.2.0.jar'
$vite=Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
$mailpit=Join-Path $toolsDir 'mailpit/mailpit.exe'
$statePath=Join-Path $runtime 'processes.json'
$frontendPortPath=Join-Path $runtime 'frontend-port.txt'
if($FrontendPort -eq 0){
 if(Test-Path -LiteralPath $frontendPortPath){
  $savedPort=(Get-Content -LiteralPath $frontendPortPath -Raw).Trim()
  if(-not [int]::TryParse($savedPort,[ref]$FrontendPort)){throw 'Saved frontend port is invalid. Pass -FrontendPort explicitly.'}
 }else{$FrontendPort=5173}
}
if($FrontendPort -lt 1024 -or $FrontendPort -gt 65535){throw 'FrontendPort must be between 1024 and 65535.'}
New-Item -ItemType Directory -Path $toolsDir,$logs -Force | Out-Null
& (Join-Path $PSScriptRoot 'setup.ps1')
$settings=Get-Content -LiteralPath (Join-Path $projectRoot '.env') | ConvertFrom-StringData
# Reuse only listeners whose command line identifies this exact checkout.
# IntelliJ owns its JVM; it is deliberately excluded from launcher-managed PIDs.
$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in @(55432,18080,$FrontendPort,1025,8025)})
function Get-ProjectListener {
 param([int]$Port,[string[]]$Paths)
 $ownerIds=@($listeners | Where-Object {$_.LocalPort -eq $Port} | Select-Object -ExpandProperty OwningProcess -Unique)
 if(-not $ownerIds.Count){return $null}
 if($ownerIds.Count -ne 1){throw "Port $Port has multiple owners; no process was changed."}
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($ownerIds[0])" -ErrorAction SilentlyContinue
 $matchesProject=$false
 if($process -and $process.CommandLine){
  $normalizedCommand=$process.CommandLine.Replace('/','\').ToLowerInvariant()
  foreach($path in $Paths){if($normalizedCommand.Contains(([IO.Path]::GetFullPath($path)).Replace('/','\').ToLowerInvariant())){$matchesProject=$true}}
 }
 if(-not $matchesProject){throw "Port $Port is used by another process (PID $($ownerIds[0])). DeskFlow will not replace it."}
 return $process
}
if(Test-Path -LiteralPath $statePath){
 $previousState=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
 if([IO.Path]::GetFullPath($previousState.root) -ne [IO.Path]::GetFullPath($projectRoot)){throw 'Session belongs to a different project folder.'}
}
$existingApi=Get-ProjectListener -Port 18080 -Paths @($jar,(Join-Path $projectRoot 'backend/target/classes'))
$apiInIdea=$false
if($existingApi){
 $apiCommand=$existingApi.CommandLine.Replace('/','\').ToLowerInvariant()
 if($existingApi.Name -notin @('java.exe','javaw.exe')){throw 'The backend port is not owned by a Java backend; no process was changed.'}
 if($apiCommand.Contains('dev.deskflow.application')){
  if($apiCommand -notmatch 'spring\.profiles\.active[= ]local(?:[ ,"\x27]|$)'){throw 'The IntelliJ backend must use Active profiles: local. Stop that run, select the local profile, and run it again.'}
  $apiInIdea=$true
 }elseif(-not $apiCommand.Contains(([IO.Path]::GetFullPath($jar)).Replace('/','\').ToLowerInvariant())){
  throw 'Port 18080 is owned by a different application using this project classpath; no process was changed.'
 }
 Write-Host 'Reusing the running DeskFlow backend.'
}
$existingPg=Get-ProjectListener -Port 55432 -Paths @($pgdata)
$existingMail=Get-ProjectListener -Port 1025 -Paths @($mailpit)
$existingInbox=Get-ProjectListener -Port 8025 -Paths @($mailpit)
if(($existingMail -and -not $existingInbox) -or ($existingInbox -and -not $existingMail) -or ($existingMail -and $existingInbox -and $existingMail.ProcessId -ne $existingInbox.ProcessId)){throw 'Mailpit has a partial or conflicting listener. Stop the local mail service before restarting.'}
$existingUi=Get-ProjectListener -Port $FrontendPort -Paths @($vite)
foreach($tool in @('java','node','npm.cmd')) { if(-not (Get-Command $tool -ErrorAction SilentlyContinue)) {throw "$tool is required."} }
# Java writes version/property diagnostics to stderr even when it succeeds.
# Redirect at the process boundary so Windows PowerShell 5.1 does not turn
# that normal output into a terminating NativeCommandError.
$javaPropertiesLog=Join-Path $logs 'java-properties.log'
$javaProbe=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-XshowSettings:properties','-version') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'java-probe.out.log') -RedirectStandardError $javaPropertiesLog -Wait -PassThru
if($javaProbe.ExitCode -ne 0){throw "Java detection failed. See $javaPropertiesLog"}
$javaHomePath=(Get-Content -LiteralPath $javaPropertiesLog | ForEach-Object {if("$_" -match '^\s*java.home\s*=\s*(.+)$'){$matches[1].Trim()}} | Select-Object -First 1)
if(-not $javaHomePath){throw 'Cannot locate the actual Java runtime.'}
$javaBinary=Join-Path $javaHomePath 'bin/java.exe'
if(-not(Test-Path -LiteralPath $javaBinary)){throw 'Java runtime executable was not found.'}
if(-not (Test-Path -LiteralPath (Join-Path $PostgresBin 'pg_ctl.exe'))) {throw 'PostgreSQL 17 binaries are required. Pass -PostgresBin with their location.'}
if(-not $InfrastructureOnly -and -not $SkipBuild -and -not $existingApi){
 $mavenCommand=Get-Command mvn.cmd -ErrorAction SilentlyContinue
 if($mavenCommand){$maven=$mavenCommand.Source}else{
  $maven=Join-Path $toolsDir 'apache-maven-3.9.11/bin/mvn.cmd'
  if(-not(Test-Path -LiteralPath $maven)){
   $zip=Join-Path $toolsDir 'maven.zip'
   Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile $zip
   Expand-Archive -LiteralPath $zip -DestinationPath $toolsDir -Force
  }
 }
}
if(-not $existingMail -and -not(Test-Path -LiteralPath $mailpit)){
 $zip=Join-Path $toolsDir 'mailpit.zip'
 Invoke-WebRequest -Uri 'https://github.com/axllent/mailpit/releases/download/v1.27.0/mailpit-windows-amd64.zip' -OutFile $zip
 Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolsDir 'mailpit') -Force
}
if(-not $InfrastructureOnly -and -not $SkipBuild -and -not $existingApi){
 Push-Location (Join-Path $projectRoot 'backend')
 try { & $maven -B -ntp package -DskipTests; if($LASTEXITCODE -ne 0){throw 'Backend build failed.'} } finally {Pop-Location}
}
if(-not $SkipBuild -and -not $existingUi){
 Push-Location (Join-Path $projectRoot 'frontend')
 try { npm.cmd ci; if($LASTEXITCODE -ne 0){throw 'Frontend install failed.'}; npm.cmd run build; if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'} } finally {Pop-Location}
}
if(-not(Test-Path -LiteralPath (Join-Path $pgdata 'PG_VERSION'))){
 $passwordFile=Join-Path $runtime 'pg-password.tmp'
 try{
  [IO.File]::WriteAllText($passwordFile,$settings.POSTGRES_PASSWORD)
  & (Join-Path $PostgresBin 'initdb.exe') -D $pgdata -U deskflow -A scram-sha-256 "--pwfile=$passwordFile" --encoding=UTF8
  if($LASTEXITCODE -ne 0){throw 'Database initialization failed.'}
 }finally{if(Test-Path -LiteralPath $passwordFile){Remove-Item -LiteralPath $passwordFile}}
}
if($existingPg){Write-Host 'Reusing this project''s PostgreSQL instance.'}else{
 & (Join-Path $PostgresBin 'pg_ctl.exe') -D $pgdata -l (Join-Path $logs 'postgres.log') -o '-p 55432 -h 127.0.0.1' start
 if($LASTEXITCODE -ne 0){throw 'Database startup failed.'}
}
$env:PGPASSWORD=$settings.POSTGRES_PASSWORD
try{
$databaseExists=& (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p 55432 -U deskflow -d postgres -tAc "select 1 from pg_database where datname='deskflow'"
if($LASTEXITCODE -ne 0){throw 'Cannot authenticate to the local database. Check the existing .env; database credentials were not changed.'}
if($databaseExists -ne '1'){& (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p 55432 -U deskflow deskflow;if($LASTEXITCODE -ne 0){throw 'Database creation failed.'}}
}finally{Remove-Item Env:PGPASSWORD}
$managedPids=@()
if($existingMail){$managedPids+=$existingMail.ProcessId;Write-Host 'Reusing Mailpit.'}else{
 $mailProcess=Start-Process -FilePath $mailpit -ArgumentList @('--listen','127.0.0.1:8025','--smtp','127.0.0.1:1025') -WorkingDirectory $runtime -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'mailpit.out.log') -RedirectStandardError (Join-Path $logs 'mailpit.err.log') -PassThru
 $managedPids+=$mailProcess.Id
}
if(-not $InfrastructureOnly -and -not $existingApi){
$env:APP_ENCRYPTION_KEY=$settings.APP_ENCRYPTION_KEY
$previousPublicUrl=$env:APP_PUBLIC_URL
$env:APP_PUBLIC_URL=$(if($settings.APP_PUBLIC_URL){$settings.APP_PUBLIC_URL}else{"http://127.0.0.1:$FrontendPort"})
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:55432/deskflow'
$env:SPRING_DATASOURCE_USERNAME='deskflow'
$env:SPRING_DATASOURCE_PASSWORD=$settings.POSTGRES_PASSWORD
$env:SERVER_PORT='18080';$env:SERVER_ADDRESS='127.0.0.1'
$env:APP_ROLE='api';$env:APP_WORKER_ENABLED='true';$env:APP_PUBLISHER_ENABLED='false'
$env:SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP='false';$env:MANAGEMENT_HEALTH_RABBIT_ENABLED='false'
try{
$apiProcess=Start-Process -FilePath $javaBinary -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'api.out.log') -RedirectStandardError (Join-Path $logs 'api.err.log') -PassThru
 $managedPids+=$apiProcess.Id
}finally{$env:APP_PUBLIC_URL=$previousPublicUrl;foreach($name in @('APP_ENCRYPTION_KEY','SPRING_DATASOURCE_PASSWORD')){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}}
}elseif($existingApi -and -not $apiInIdea){$managedPids+=$existingApi.ProcessId}
if($existingUi){$managedPids+=$existingUi.ProcessId;Write-Host 'Reusing the frontend.'}else{
$uiProcess=Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"'+$vite+'"'),'--host','127.0.0.1','--port',"$FrontendPort",'--strictPort') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'ui.out.log') -RedirectStandardError (Join-Path $logs 'ui.err.log') -PassThru
 $managedPids+=$uiProcess.Id
}
@{root=$projectRoot;postgresBin=$PostgresBin;pgdata=$pgdata;frontendPort=$FrontendPort;pids=@($managedPids);apiMode=$(if($apiInIdea){'IntelliJ'}elseif($existingApi -or -not $InfrastructureOnly){'launcher'}else{'infrastructure'});externalApiPid=$(if($apiInIdea){$existingApi.ProcessId}else{$null})} | ConvertTo-Json | Set-Content -LiteralPath $statePath
Set-Content -LiteralPath $frontendPortPath -Value $FrontendPort
if($InfrastructureOnly){
 Write-Host 'Infrastructure is ready: PostgreSQL, Mailpit and frontend.'
 Write-Host "App: http://127.0.0.1:$FrontendPort"
 if($existingApi){Write-Host "Existing backend preserved (PID $($existingApi.ProcessId))."}
 else{Write-Host 'Run Application or DeskFlow Local in IntelliJ to start the backend on port 18080.'}
 return
}
Write-Host 'Waiting for the backend to report ready...'
$ready=$false;$readyDeadline=(Get-Date).AddSeconds(45)
while((Get-Date) -lt $readyDeadline){
 try{if((Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 2).status -eq 'UP'){$ready=$true;break}}catch{}
 Start-Sleep -Milliseconds 500
}
if(-not $ready){throw 'Services were started, but the backend did not report ready. Check the IntelliJ Run console or .local/logs/api.out.log.'}
Write-Host 'DeskFlow is ready.'
Write-Host "App: http://127.0.0.1:$FrontendPort | Mail: http://127.0.0.1:8025"
Write-Host 'Local mode uses PostgreSQL polling; RabbitMQ transport is available through Compose.'
if($apiInIdea){Write-Host 'Backend is managed by IntelliJ. Use its Run/Stop buttons for backend changes.'}
Write-Host 'Stop with ./scripts/stop-local.ps1. Data is retained in .local/postgres.'
