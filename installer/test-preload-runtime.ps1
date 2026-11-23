param([Parameter(Mandatory=$true)][string]$GameDirectory,[Parameter(Mandatory=$true)][string]$ZombieBuddyJar)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$outDir=Join-Path $projectRoot 'build/preload-runtime'
New-Item -ItemType Directory -Force $outDir | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" --release 11 -encoding UTF-8 -d $outDir "$PSScriptRoot/preload-fixtures/PreloadAgentFixture.java" "$PSScriptRoot/preload-fixtures/PreloadClientFixture.java" "$PSScriptRoot/preload-fixtures/PreloadRuntimeTests.java" "$PSScriptRoot/preload-fixtures/zombie/network/GameServer.java" "$projectRoot/src/test/java/zombie/ZomboidFileSystem.java"
if($LASTEXITCODE -ne 0){throw 'Preload fixture compilation failed'}
$manifest=Join-Path $outDir 'agent-manifest.mf'
[IO.File]::WriteAllText($manifest,"Premain-Class: PreloadAgentFixture`n`n",[Text.UTF8Encoding]::new($false))
$agent=Join-Path $outDir 'fixture-agent.jar'
& "$env:JAVA_HOME/bin/jar.exe" cfm $agent $manifest -C $outDir PreloadAgentFixture.class
if($LASTEXITCODE -ne 0){throw 'Fixture agent packaging failed'}
& "$env:JAVA_HOME/bin/java.exe" -cp $outDir PreloadRuntimeTests "$GameDirectory/jre64/bin/java.exe" $agent "$outDir;$ZombieBuddyJar;$GameDirectory/projectzomboid.jar" "$projectRoot/build/libs/ServerPanelBridge.jar" "$projectRoot/42/mod.info"
if($LASTEXITCODE -ne 0){throw 'Native preload lifecycle failed'}
