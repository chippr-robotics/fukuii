# An upgrade or uninstall replaces the files a running node has open; say so instead of failing half way.
$running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'com\.chipprbots\.ethereum' }
if ($running) { Write-Warning "fukuii is running (pid $($running.ProcessId -join ', ')); stop it before upgrading or uninstalling." }
