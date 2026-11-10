param(
    [string]$ServerJar
)

$ErrorActionPreference = 'Stop'
if (!$ServerJar) {
    $game = if ($env:HYTALE_HOME) { $env:HYTALE_HOME } else { 'D:\Games\Hytale' }
    $ServerJar = Join-Path $game 'install/release/package/game/latest/Server/HytaleServer.jar'
}
if (!(Test-Path -LiteralPath $ServerJar -PathType Leaf)) {
    throw 'HytaleServer.jar não encontrado. Informe o caminho com -ServerJar.'
}
$ServerJar = (Resolve-Path -LiteralPath $ServerJar).Path
$classes = Join-Path $PSScriptRoot 'build/classes'
$dist = Join-Path $PSScriptRoot 'dist'
$source = Join-Path $PSScriptRoot 'src/main'
New-Item -ItemType Directory -Path $classes, $dist -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$source/java" -Recurse -File -Filter '*.java' | ForEach-Object FullName)
& javac --release 25 -encoding UTF-8 -classpath $ServerJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o mod.' }
$version = (Get-Content -Raw -LiteralPath "$source/resources/manifest.json" | ConvertFrom-Json).Version
$output = Join-Path $dist "AdvancedEquivalentExchange-$version.jar"
& jar --create --file $output -C $classes br -C "$source/resources" .
if ($LASTEXITCODE -ne 0) { throw 'Falha ao empacotar o mod.' }
Write-Output $output
