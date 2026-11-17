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

# The zip holds fukuii-<version>\{bin,lib,conf,kanela-agent}. Put fukuii on the PATH through tools\fukuii.cmd, which
# picks a Java 25 for bin\fukuii.bat (see it), and hide every .exe in the distribution from the automatic shimming.
$appDir = Join-Path $toolsDir "fukuii-$version"
if (-not (Test-Path (Join-Path $appDir 'bin\fukuii.bat'))) { throw "fukuii: bin\fukuii.bat is missing from the release zip" }
Get-ChildItem -Path $appDir -Recurse -Include *.exe | ForEach-Object { New-Item "$($_.FullName).ignore" -Type File -Force | Out-Null }
Install-BinFile -Name 'fukuii' -Path (Join-Path $toolsDir 'fukuii.cmd')
