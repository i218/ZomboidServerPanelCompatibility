$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$payload = Join-Path $project 'build\distributions\ServerPanelBridge-mod.zip'
if (!(Test-Path -LiteralPath $payload)) { throw 'Build modZip before compiling the installer.' }
$destination = Join-Path $project 'build\distributions\ServerPanelBridge-Setup.exe'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /target:winexe /optimize+ "/out:$destination" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll "/resource:$payload,payload.zip" (Join-Path $PSScriptRoot 'Setup.cs')
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed' }
Write-Output $destination
& (Join-Path $PSScriptRoot 'test-setup.ps1')
