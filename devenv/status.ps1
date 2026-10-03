# Shows each dev instance: pid, whether /config answers, port, database, log file, artifact.
. "$PSScriptRoot\common.ps1"
$latest = Join-Path $DevRoot 'artifacts\latest.txt'
if (Test-Path $latest) { Write-Output "Latest artifact: $((Get-Content $latest -Raw).Trim())" }
foreach ($name in $DevInstances.Keys) {
	$inst = $DevInstances[$name]
	$id = Get-DevPid $name
	$up = if ($id) { Test-DevUp $name } else { $false }
	Write-Output ("{0,-11} pid {1,-7} {2,-4} port {3}  db {4,-15} log {5}" -f $name, $(if ($id) { $id } else { '-' }),
		$(if ($up) { 'UP' } else { 'DOWN' }), $inst.Port, $inst.Db, $inst.Log)
}
