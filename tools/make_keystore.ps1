# Gera o keystore de assinatura usado pelos builds de release.
# Uso: powershell -ExecutionPolicy Bypass -File tools/make_keystore.ps1
param(
    [string]$Password = "conectoma",
    [string]$Alias = "conectoma",
    [string]$Out = "$PSScriptRoot\..\keystore\conectoma.jks"
)

$ErrorActionPreference = "Stop"
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\keytool.exe" } else { "keytool.exe" }
New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null

& $keytool -genkeypair -v `
    -keystore $Out `
    -storepass $Password -keypass $Password `
    -alias $Alias -keyalg RSA -keysize 2048 -validity 10950 `
    -dname "CN=Conectoma, OU=Neuroscience, O=Conectoma, C=BR"

if ($LASTEXITCODE -ne 0) { throw "keytool falhou" }
Write-Host "Keystore criado em $Out"
Write-Host "Para usar outras senhas: `$env:CONECTOMA_STORE_PASSWORD / CONECTOMA_KEY_PASSWORD"
