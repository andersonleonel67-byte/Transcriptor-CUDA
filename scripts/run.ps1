param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $PSScriptRoot 'build.ps1'
& $buildScript

$javaExe = Join-Path $projectRoot 'tools\jdk\bin\java.exe'
$jarPath = Join-Path $projectRoot 'build\transcriptor.jar'
$resolvedRoot = (Resolve-Path $projectRoot).Path

& $javaExe "-Dapp.root=$resolvedRoot" '-jar' $jarPath @AppArgs

