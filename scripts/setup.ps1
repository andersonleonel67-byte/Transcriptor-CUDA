param(
    [switch]$ForceJdk
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot 'tools'
$jdkDir = Join-Path $toolsDir 'jdk'
$venvDir = Join-Path $projectRoot '.venv'
$pythonExe = Join-Path $venvDir 'Scripts\python.exe'
$requirementsFile = Join-Path $projectRoot 'requirements.txt'

New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null

if ($ForceJdk -or -not (Test-Path (Join-Path $jdkDir 'bin\java.exe'))) {
    Write-Host 'Descargando JDK portable (Temurin 21)...'
    $response = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?image_type=jdk&os=windows&architecture=x64&heap_size=normal&vendor=eclipse'
    if (-not $response -or -not $response[0].binary.package.link) {
        throw 'No se pudo obtener el enlace oficial del JDK desde Adoptium.'
    }

    $downloadUrl = $response[0].binary.package.link
    $zipPath = Join-Path $toolsDir 'temurin-jdk.zip'
    $extractRoot = Join-Path $toolsDir 'jdk-extract'

    if (Test-Path $extractRoot) {
        $resolvedExtract = (Resolve-Path $extractRoot).Path
        $resolvedProject = (Resolve-Path $projectRoot).Path
        if (-not $resolvedExtract.StartsWith($resolvedProject)) {
            throw 'La carpeta temporal del JDK salió del workspace esperado.'
        }
        Remove-Item -LiteralPath $resolvedExtract -Recurse -Force
    }

    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot -Force

    $expandedJdk = Get-ChildItem -Path $extractRoot -Directory | Select-Object -First 1
    if (-not $expandedJdk) {
        throw 'La extracción del JDK no produjo ninguna carpeta utilizable.'
    }

    if (Test-Path $jdkDir) {
        $resolvedJdk = (Resolve-Path $jdkDir).Path
        $resolvedProject = (Resolve-Path $projectRoot).Path
        if (-not $resolvedJdk.StartsWith($resolvedProject)) {
            throw 'La carpeta del JDK salió del workspace esperado.'
        }
        Remove-Item -LiteralPath $resolvedJdk -Recurse -Force
    }

    Move-Item -LiteralPath $expandedJdk.FullName -Destination $jdkDir
    Remove-Item -LiteralPath $zipPath -Force
    Remove-Item -LiteralPath $extractRoot -Recurse -Force
}

if (-not (Test-Path $pythonExe)) {
    Write-Host 'Creando entorno virtual de Python...'
    python -m venv $venvDir
}

Write-Host 'Instalando dependencias de Python...'
& $pythonExe -m pip install --upgrade pip
& $pythonExe -m pip install -r $requirementsFile

Write-Host ''
Write-Host 'Entorno listo.'
Write-Host "JDK: $jdkDir"
Write-Host "Python: $pythonExe"

