# pack-seed.ps1 — embute o conhecimento acumulado do Conectoma no APK.
#
# Uso:
#   1. No aparelho, toque em "guardar cópia da memória no aparelho" (tela Cérebro)
#      e conecte o celular por cabo/USB (ou use o app de arquivos).
#   2. Copie o arquivo `conectoma-seed.bin` de
#      Android/data/com.conectoma.app/files/ para o PC.
#   3. Rode:  powershell -ExecutionPolicy Bypass -File tools\pack-seed.ps1 -Seed "C:\caminho\conectoma-seed.bin"
#   4. Rebuild (build.ps1): o APK nasce com todo esse conhecimento —
#      e fica maior exatamente na medida em que o conectoma aprendeu.
#
# O pacote é texto: léxico aprendido (JSON) + separador + memória episódica.
# Na primeira abertura, `Brain.loadSeedSemantic`/`loadSeedMemory` fundem tudo
# sem duplicar nem apagar o que o aparelho já tinha.

param(
    [Parameter(Mandatory = $true)]
    [string]$Seed
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $repo "app\src\main\assets\conhecimento-seed.bin"

if (-not (Test-Path $Seed)) { throw "arquivo não encontrado: $Seed" }
$bytes = [System.IO.File]::ReadAllBytes($Seed)
if ($bytes.Length -lt 4) { throw "pacote vazio: $Seed" }

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
[System.IO.File]::WriteAllBytes($dest, $bytes)

$mb = [math]::Round($bytes.Length / 1MB, 2)
Write-Host "pacote embutido: $dest"
Write-Host "tamanho: $mb MB — o próximo APK carrega esse conhecimento de fábrica"
Write-Host "agora rode: powershell -ExecutionPolicy Bypass -File build.ps1"
