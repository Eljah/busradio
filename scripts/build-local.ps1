# Java 21 on Windows. FFmpeg must be provided for the normalization test.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force '.local/classes','dist' | Out-Null
$source = Get-ChildItem busradio-core,busradio-server,busradio-edge,busradio-tests -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& javac --release 21 -encoding UTF-8 -d .local/classes $source
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& jar --create --file dist/busradio-server-0.1.0-standalone.jar --main-class io.github.eljah.busradio.server.ServerMain -C .local/classes io/github/eljah/busradio/core -C .local/classes io/github/eljah/busradio/server -C busradio-server/src/main/resources .
if ($LASTEXITCODE -ne 0) { throw 'server packaging failed' }
& jar --create --file dist/busradio-edge-0.1.0-simulator.jar --main-class io.github.eljah.busradio.edge.EdgeMain -C .local/classes io/github/eljah/busradio/core -C .local/classes io/github/eljah/busradio/edge
if ($LASTEXITCODE -ne 0) { throw 'edge packaging failed' }
Write-Host 'Built server and simulator; Pi4J and integration tests are not included in this Windows fallback build.'
