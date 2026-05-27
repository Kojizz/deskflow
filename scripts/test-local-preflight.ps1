# Ownership checks use process fixtures. This test never starts or stops services.
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$parseErrors=$null
$launcherAst=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'start-local.ps1'),[ref]$null,[ref]$parseErrors)
if($parseErrors.Count){throw 'Launcher has syntax errors.'}
$functionAst=$launcherAst.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-ProjectListener'},$true)
if(-not $functionAst){throw 'Launcher ownership check was not found.'}
Set-Item Function:Get-ProjectListener -Value ($functionAst.Body.GetScriptBlock())
function Get-CimInstance {param($ClassName,$Filter,$ErrorAction) return $script:fixtureProcess}
function Assert-Rejected {
 $rejected=$false
 try{Get-ProjectListener -Port 18080 -Paths @($script:expectedJar) | Out-Null}catch{$rejected=$true}
 if(-not $rejected){throw 'Expected the conflicting or unidentified process to be rejected.'}
}
$script:expectedJar=Join-Path $projectRoot 'backend/target/deskflow-0.2.0.jar'
$script:listeners=@()
if($null -ne (Get-ProjectListener -Port 18080 -Paths @($expectedJar))){throw 'An unused port must have no existing owner.'}
$script:listeners=@([PSCustomObject]@{LocalPort=18080;OwningProcess=12345})
$script:fixtureProcess=[PSCustomObject]@{ProcessId=12345;CommandLine=('java -jar "'+$expectedJar.Replace('\','/')+'"')}
if((Get-ProjectListener -Port 18080 -Paths @($expectedJar)).ProcessId -ne 12345){throw 'The project backend must be reusable across slash formats.'}
$script:fixtureProcess=[PSCustomObject]@{ProcessId=12345;CommandLine='java -jar C:/another-project/backend.jar'}
Assert-Rejected
$script:fixtureProcess=[PSCustomObject]@{ProcessId=12345;CommandLine=$null}
Assert-Rejected
$script:listeners+= [PSCustomObject]@{LocalPort=18080;OwningProcess=54321}
Assert-Rejected
Write-Host 'PASS: unused port, project backend reuse, foreign process rejection, unidentified process rejection, multiple-owner rejection.'
