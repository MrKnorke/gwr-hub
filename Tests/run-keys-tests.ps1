# Kompiliert die reine Tastennamen-Logik (ohne Android) zusammen mit den Tests und führt sie aus.
# Voraussetzung: JDK 11+ im PATH (z. B. das OpenJDK aus Unitys Android Build Support).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path ([System.IO.Path]::GetTempPath()) 'gwrhub-keys-tests'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = @(
    (Join-Path $root 'Assets/Plugins/Android/KeyNames.java'),
    (Join-Path $root 'Tests/keys/KeyNamesTests.java')
)

& javac -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $out com.gwrhub.overlay.KeyNamesTests
exit $LASTEXITCODE
