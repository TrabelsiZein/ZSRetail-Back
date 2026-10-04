# Stops dev instances started by devenv\start.ps1 (by their saved pid; only a java process is stopped).
#   powershell -File devenv\stop.ps1                   # all three
#   powershell -File devenv\stop.ps1 -Instance headoffice
param([string[]]$Instance = @('headoffice', 'store-b', 'store-c'))
. "$PSScriptRoot\common.ps1"

foreach ($name in $Instance) {
	$null = Get-DevInstance $name
	$id = Get-DevPid $name
	if ($id) {
		Stop-Process -Id $id -Force
		Wait-Process -Id $id -Timeout 30 -ErrorAction SilentlyContinue
		Write-Output "$name stopped (pid $id)"
	} else {
		Write-Output "$name not running"
	}
	Remove-Item (Join-Path $DevRoot "pids\$name.pid") -ErrorAction SilentlyContinue
}
