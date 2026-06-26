param()

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$setupScript = Join-Path $PSScriptRoot 'setup.ps1'
& $setupScript

$jdkBin = Join-Path $projectRoot 'tools\jdk\bin'
$javacExe = Join-Path $jdkBin 'javac.exe'
$jarExe = Join-Path $jdkBin 'jar.exe'
$classesDir = Join-Path $projectRoot 'build\classes'
$jarPath = Join-Path $projectRoot 'build\transcriptor.jar'
$srcDir = Join-Path $projectRoot 'src'

if (Test-Path $classesDir) {
    $resolvedProject = (Resolve-Path $projectRoot).Path
    $resolvedClasses = (Resolve-Path $classesDir).Path
    if (-not $resolvedClasses.StartsWith($resolvedProject)) {
        throw 'La ruta de compilación salió del workspace esperado.'
    }
    Remove-Item -LiteralPath $resolvedClasses -Recurse -Force
}

New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $jarPath) -Force | Out-Null

$sources = Get-ChildItem -Path $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sources) {
    throw 'No se encontraron archivos Java en src/.'
}

Write-Host 'Compilando frontend Swing...'
& $javacExe '--release' '21' '-encoding' 'UTF-8' '-d' $classesDir $sources

Write-Host 'Empaquetando JAR...'
if (Test-Path $jarPath) {
    Remove-Item -LiteralPath $jarPath -Force
}
Push-Location $classesDir
try {
    & $jarExe '--create' '--file' $jarPath '--main-class' 'transcriptor.app.Main' '.'
} finally {
    Pop-Location
}

Write-Host "Build listo: $jarPath"

