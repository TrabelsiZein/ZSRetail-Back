# Rehearsal environment (head office plan, step 9): a head office and two stores of a franchise network, on new empty
# databases, beside the L2 instances (other ports and databases; the L2 databases are never touched). Built from the latest
# artifact (devenv\build.ps1). The stores are NOT registered at the head office: create them on the head office Stores
# page (codes STORE-1 and STORE-2), then give each store its key with -Action set-key.
#
#   powershell -File devenv\rehearsal.ps1 -Action setup      # creates the 3 databases (when absent), starts, license, store codes
#   powershell -File devenv\rehearsal.ps1 -Action start      # starts the 3 instances (after a stop)
#   powershell -File devenv\rehearsal.ps1 -Action stop
#   powershell -File devenv\rehearsal.ps1 -Action status
#   powershell -File devenv\rehearsal.ps1 -Action set-key -Store store-1 -Key <key shown once on the Stores page>
#   powershell -File devenv\rehearsal.ps1 -Action reset      # stop, drop the 3 rehearsal databases, keys back to none, setup
#
# Instances (devenv\common.ps1): reh-headoffice 889 pos_rehearsal_ho, reh-store-1 557 pos_rehearsal_s1 (STORE-1),
# reh-store-2 558 pos_rehearsal_s2 (STORE-2). Machine files: deploy\rehearsal\. Logs: C:\zsretail-rehearsal\<name>\backend.log.
# Logins (a new database): head office admin / P@ssw0rd; stores admin / P@ssw0rd, responsible / 123.0, cashier / cashier.
param(
	[Parameter(Mandatory = $true)][ValidateSet('setup', 'start', 'stop', 'status', 'set-key', 'reset')][string]$Action,
	[ValidateSet('store-1', 'store-2')][string]$Store,
	[string]$Key
)
. "$PSScriptRoot\api.ps1"

$Rehearsal = @('reh-headoffice', 'reh-store-1', 'reh-store-2')
$Stores = @('reh-store-1', 'reh-store-2')
$NotRegistered = 'NOT-REGISTERED-YET'
$LicenseFile = Join-Path $DevRepo 'LicenseGenerator\license.json'

function Assert-RehearsalDb([string]$Db) {
	if ($Db -notlike 'pos_rehearsal_*') { throw "Refused: $Db is not a rehearsal database" }
}

function Start-Rehearsal([string[]]$Names) {
	& "$PSScriptRoot\start.ps1" -Instance $Names
}

function Stop-Rehearsal([string[]]$Names) {
	& "$PSScriptRoot\stop.ps1" -Instance $Names
}

function Initialize-Rehearsal {
	foreach ($name in $Rehearsal) {
		$db = (Get-DevInstance $name).Db
		Assert-RehearsalDb $db
		Invoke-DevSql $name "IF DB_ID('$db') IS NULL CREATE DATABASE [$db]; SELECT name FROM sys.databases WHERE name = '$db'" 'master' | Out-Null
		New-Item -ItemType Directory -Force (Split-Path -Parent (Get-DevInstance $name).Log) | Out-Null
	}
	Start-Rehearsal $Rehearsal
	foreach ($name in $Rehearsal) {
		$token = Get-DevToken $name
		$license = Invoke-DevApi $name GET '/license/status' $null $token
		if ($license.Body.status -ne 'VALID') {
			$null = Send-DevLicense $name $token $LicenseFile
			$license = Invoke-DevApi $name GET '/license/status' $null $token
		}
		Write-Output "$name license: $($license.Body.status)"
	}
	foreach ($name in $Stores) {
		$inst = Get-DevInstance $name
		# The store's code (it must equal the code created on the head office Stores page) and loyalty, read at each use
		Invoke-DevSql $name ("UPDATE general_setup SET valeur = '$($inst.Code)' WHERE code = 'DEFAULT_LOCATION'; " +
			"UPDATE general_setup SET valeur = 'true' WHERE code = 'LOYALTY_ENABLED'") | Out-Null
		$token = Get-DevToken $name
		$functions = Invoke-DevApi $name GET '/member-function' $null $token
		if (-not (@($functions.Body) | Where-Object { $_.code -eq 'CLIENT' })) {
			$null = Invoke-DevApi $name POST '/member-function' @{ code = 'CLIENT'; name = 'Client'; displayOrder = 1 } $token
		}
		Write-Output "$name code $($inst.Code), loyalty on, member function CLIENT"
	}
	Show-Rehearsal
}

function Show-Rehearsal {
	foreach ($name in $Rehearsal) {
		$inst = Get-DevInstance $name
		$state = if (Test-DevUp $name) { 'UP  ' } else { 'DOWN' }
		$link = ''
		if ($inst.Code -and (Test-DevUp $name)) {
			$machineKey = (Select-String -Path (Get-DevMachineFile $name) -Pattern '^headoffice.api-key=(.*)$').Matches[0].Groups[1].Value.Trim()
			$registered = if ($machineKey -eq $NotRegistered) { 'no key yet' } else { 'key set' }
			$status = Invoke-DevApi $name GET '/admin/holink/status' $null (Get-DevToken $name)
			$link = "  code $($inst.Code), $registered, link $($status.Body.state)"
		}
		Write-Output ("{0,-15} {1} {2}  db {3}{4}" -f $name, $state, (Get-DevBaseUrl $name), $inst.Db, $link)
	}
}

switch ($Action) {
	'setup' { Initialize-Rehearsal }
	'start' { Start-Rehearsal $Rehearsal; Show-Rehearsal }
	'stop' { Stop-Rehearsal $Rehearsal }
	'status' { Show-Rehearsal }
	'set-key' {
		if (-not $Store -or -not $Key) { throw '-Action set-key needs -Store store-1|store-2 and -Key <key>' }
		$name = "reh-$Store"
		$file = Set-DevMachineValue $name 'headoffice.api-key' $Key.Trim()
		Write-Output "key written to $file"
		Stop-Rehearsal @($name)
		Start-Rehearsal @($name)
		Start-Sleep -Seconds 20   # the first heartbeat comes about 15 s after the start
		Show-Rehearsal
	}
	'reset' {
		Stop-Rehearsal $Rehearsal
		foreach ($name in $Rehearsal) {
			$db = (Get-DevInstance $name).Db
			Assert-RehearsalDb $db
			Invoke-DevSql $name ("IF DB_ID('$db') IS NOT NULL BEGIN ALTER DATABASE [$db] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; " +
				"DROP DATABASE [$db]; END; SELECT 'dropped $db'") 'master' | Out-Null
			Write-Output "$name database $db dropped"
			$uploads = Join-Path (Split-Path -Parent (Get-DevInstance $name).Log) 'uploads'
			if (Test-Path $uploads) { Remove-Item -Recurse -Force $uploads }
		}
		foreach ($name in $Stores) { $null = Set-DevMachineValue $name 'headoffice.api-key' $NotRegistered }
		Initialize-Rehearsal
	}
}
