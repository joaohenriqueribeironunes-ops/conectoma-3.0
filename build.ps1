# Compila o APK de release.
# Uso: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not (Test-Path "$PSScriptRoot\keystore\conectoma.jks")) {
    Write-Host "Keystore ausente — gerando um novo (o APK terá outra assinatura)."
    & "$PSScriptRoot\tools\make_keystore.ps1"
}

& "$PSScriptRoot\gradlew.bat" assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "build falhou" }

$apk = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "APK não encontrado em $apk" }
Copy-Item $apk "$PSScriptRoot\Conectoma.apk" -Force
Write-Host "APK gerado: $PSScriptRoot\Conectoma.apk"
