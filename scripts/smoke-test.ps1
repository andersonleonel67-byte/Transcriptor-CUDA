param(
    [string]$Model = 'tiny'
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$setupScript = Join-Path $PSScriptRoot 'setup.ps1'
& $setupScript

$pythonExe = Join-Path $projectRoot '.venv\Scripts\python.exe'
$sampleDir = Join-Path $projectRoot 'build\smoke'
$wavPath = Join-Path $sampleDir 'sample.wav'
$outputDir = Join-Path $sampleDir 'output'
$cacheDir = Join-Path $projectRoot 'cache\models'

New-Item -ItemType Directory -Path $sampleDir -Force | Out-Null
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $speaker.SetOutputToWaveFile($wavPath)
    $speaker.Speak('Hola mundo. This is a local transcription smoke test in Spanish and English.')
} finally {
    $speaker.Dispose()
}

& $pythonExe (Join-Path $projectRoot 'backend\transcribe.py') `
    --input $wavPath `
    --output-dir $outputDir `
    --language auto `
    --model $Model `
    --device auto `
    --model-cache $cacheDir

if ($LASTEXITCODE -ne 0) {
    throw "El smoke test del backend terminó con código $LASTEXITCODE."
}

Write-Host ''
Write-Host 'Smoke test completado. Revisa:'
Write-Host "  $outputDir"
