# Starts dev instances from the latest built artifact (devenv\build.ps1), on JDK 21, in the background.
#   powershell -File devenv\start.ps1                         # head office, store B, store C
#   powershell -File devenv\start.ps1 -Instance store-c -Set 'ownership.loyalty=LOCAL'
# Each instance runs from its machine file in deploy\ (task 9.3), given with -Dzsretail.machine-file; the machine file
# names the preset. -Set: properties given on the command line for this start only (they win over the machine file).
# Log: the machine file's logging.file.name (one per instance); console output in C:\zsretail-dev\logs\<instance>.console.log.
param(
	[string[]]$Instance = @('headoffice', 'store-b', 'store-c'),
	[string[]]$Set = @(),
	[int]$TimeoutSeconds = 240
)
. "$PSScriptRoot\common.ps1"

$latest = Join-Path $DevRoot 'artifacts\latest.txt'
if (-not (Test-Path $latest)) { throw "No artifact: run devenv\build.ps1 first" }
$artifact = (Get-Content $latest -Raw).Trim()
if (-not (Test-Path (Join-Path $artifact 'WEB-INF'))) { throw "$artifact is not an exploded WAR: run devenv\build.ps1" }

foreach ($name in $Instance) {
	$inst = Get-DevInstance $name
	if (Get-DevPid $name) { Write-Output "$name already running (pid $(Get-DevPid $name))"; continue }
	foreach ($dir in 'pids', 'logs', "work\$name") { New-Item -ItemType Directory -Force (Join-Path $DevRoot $dir) | Out-Null }
	New-Item -ItemType Directory -Force (Split-Path -Parent $inst.Log) | Out-Null
	# The exploded WAR, started by Spring Boot's WarLauncher (classes read from plain files, not nested jars)
	$machine = Get-DevMachineFile $name
	if (-not (Test-Path $machine)) { throw "No machine file for $name : $machine" }
	$arguments = @("-Dzsretail.machine-file=`"$machine`"", '-cp', "`"$artifact`"", 'org.springframework.boot.loader.WarLauncher')
	foreach ($pair in $Set) { $arguments += "--$pair" }
	$proc = Start-Process -FilePath (Join-Path $DevJdk 'bin\java.exe') -ArgumentList $arguments `
		-WorkingDirectory (Join-Path $DevRoot "work\$name") -WindowStyle Hidden -PassThru `
		-RedirectStandardOutput (Join-Path $DevRoot "logs\$name.console.log") `
		-RedirectStandardError (Join-Path $DevRoot "logs\$name.console.err.log")
	Set-Content -Path (Join-Path $DevRoot "pids\$name.pid") -Value $proc.Id -Encoding ascii
	Write-Output "$name starting (pid $($proc.Id), port $($inst.Port), machine file $($inst.Machine)$(if ($Set) { ', ' + ($Set -join ', ') }))"
}

foreach ($name in $Instance) {
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	while (-not (Test-DevUp $name)) {
		if (-not (Get-DevPid $name)) { throw "$name stopped during the start: see $((Get-DevInstance $name).Log) and $DevRoot\logs\$name.console.log" }
		if ((Get-Date) -gt $deadline) { throw "$name not up after $TimeoutSeconds s" }
		Start-Sleep -Seconds 2
	}
	Write-Output "$name up on $(Get-DevBaseUrl $name)"
}
