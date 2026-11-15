$ErrorActionPreference = 'Stop'

# Filled in by .github/workflows/chocolatey.yml from the release's SHA256SUMS.txt.
$version  = '__VERSION__'
$checksum = '__SHA256__'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

$packageArgs = @{
  packageName    = $env:ChocolateyPackageName
  unzipLocation  = $toolsDir
  url64bit       = "https://github.com/chippr-robotics/fukuii/releases/download/v$version/fukuii-$version.zip"
  checksum64     = $checksum
  checksumType64 = 'sha256'
}
Install-ChocolateyZipPackage @packageArgs

# The zip holds fukuii-<version>\{bin,lib,conf,kanela-agent}. The launcher is a .bat, which Chocolatey does not shim
# by itself; shim it, and hide the other files so only fukuii is put on the PATH.
$appDir = Join-Path $toolsDir "fukuii-$version"
$launcher = Join-Path $appDir 'bin\fukuii.bat'
if (-not (Test-Path $launcher)) { throw "fukuii: $launcher is missing from the release zip" }
Get-ChildItem -Path $appDir -Recurse -Include *.exe | ForEach-Object { New-Item "$($_.FullName).ignore" -Type File -Force | Out-Null }
Install-BinFile -Name 'fukuii' -Path $launcher
