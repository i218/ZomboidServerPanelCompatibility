param(
    [Parameter(Mandatory=$true)][string]$GameDirectory,
    [Parameter(Mandatory=$true)][string]$ZombieBuddyJar
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 11 or newer.' }
$outDir = Join-Path $projectRoot 'build/bridge-runtime'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
& "$jdk/bin/javac.exe" --release 11 -encoding UTF-8 -d $outDir "$PSScriptRoot/BridgeRuntimeTests.java" "$projectRoot/src/test/java/zombie/network/GameServer.java" "$projectRoot/src/test/java/zombie/ZomboidFileSystem.java"
if ($LASTEXITCODE -ne 0) { throw 'Runtime test compilation failed.' }
# Test stubs share game packages. Use a disposable unsigned fixture copy so signed
# game packages can coexist with these unsigned fixtures; never edit the game JAR.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixtureGame = Join-Path $outDir 'game-fixture.jar'
if (Test-Path -LiteralPath $fixtureGame) { Remove-Item -LiteralPath $fixtureGame }
$sourceJar = [IO.Compression.ZipFile]::OpenRead((Join-Path $GameDirectory 'projectzomboid.jar'))
$testJar = [IO.Compression.ZipFile]::Open($fixtureGame, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $sourceJar.Entries) {
        if ($entry.FullName -match '^META-INF/([^/]+\.(SF|RSA|DSA|EC)|SIG-[^/]+|MANIFEST.MF)$') { continue }
        $copy = $testJar.CreateEntry($entry.FullName)
        $inputStream = $entry.Open(); $outputStream = $copy.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
    }
} finally { $testJar.Dispose(); $sourceJar.Dispose() }
$classpath = "$outDir;$projectRoot/build/libs/ServerPanelBridge.jar;$fixtureGame;$ZombieBuddyJar"
& "$GameDirectory/jre64/bin/java.exe" -cp $classpath BridgeRuntimeTests $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Native bridge runtime test failed.' }
