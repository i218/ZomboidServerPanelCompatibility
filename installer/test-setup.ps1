$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$payload = Join-Path $project 'build\distributions\ServerPanelBridge-mod.zip'
$out = Join-Path $project 'build\installer-tests.exe'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /target:exe /main:SetupTests "/out:$out" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll "/resource:$payload,payload.zip" (Join-Path $PSScriptRoot 'Setup.cs') (Join-Path $PSScriptRoot 'SetupTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Installer test compilation failed' }
& $out
if ($LASTEXITCODE -ne 0) { throw 'Installer tests failed' }
