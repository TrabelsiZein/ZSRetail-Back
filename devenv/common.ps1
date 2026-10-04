# Dev environment of the head office pair on this PC (head office plan, L2 from step 4).
# Dot-sourced by the other scripts. Store A (standalone-dev, pos_db_prod) is deliberately not listed: never started here.

$ErrorActionPreference = 'Stop'

$DevRepo   = Split-Path -Parent $PSScriptRoot                       # ...\Apps\ZSRetail-Back
$DevRoot   = 'C:\zsretail-dev'                                      # artifacts, pids, console output, work dirs
$DevJdk    = 'C:\Program Files\Java\jdk-21.0.10'
$DevMvnSettings = Join-Path $env:USERPROFILE '.m2\zsretail-settings.xml'
$DevSqlCmd = 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\sqlcmd.exe'
$DevResources = Join-Path $DevRepo 'src\main\resources'

$DevInstances = [ordered]@{
	'headoffice' = @{ Profile = 'headoffice-dev'; Port = 888; Db = 'pos_headoffice'; Code = $null;     Log = 'C:\zsretail-headoffice\backend.log' }
	'store-b'    = @{ Profile = 'store-b-dev';    Port = 555; Db = 'pos_store_b';    Code = 'STORE-B'; Log = 'C:\zsretail-store-b\backend.log' }
	'store-c'    = @{ Profile = 'store-c-dev';    Port = 556; Db = 'pos_store_c';    Code = 'STORE-C'; Log = 'C:\zsretail-store-c\backend.log' }
}

function Get-DevInstance([string]$Name) {
	if (-not $DevInstances.Contains($Name)) { throw "Unknown instance '$Name' (known: $($DevInstances.Keys -join ', '))" }
	return $DevInstances[$Name]
}

function Get-DevBaseUrl([string]$Name) {
	return "http://localhost:$((Get-DevInstance $Name).Port)/zsretail/api"
}

# The SQL Server password, read from the instance's profile file at run time (never copied anywhere).
function Get-DevDbPassword([string]$Name) {
	$file = Join-Path $DevResources ("application-" + (Get-DevInstance $Name).Profile + ".properties")
	$match = Select-String -Path $file -Pattern '^spring.datasource.password=(.*)$'
	if (-not $match) { throw "No spring.datasource.password in $file" }
	return $match.Matches[0].Groups[1].Value.Trim()
}

# sqlcmd on a database; the password goes through SQLCMDPASSWORD for the call only. Returns the output lines.
function Invoke-DevSql([string]$Name, [string]$Query, [string]$Database) {
	$inst = Get-DevInstance $Name
	if (-not $Database) { $Database = $inst.Db }
	$env:SQLCMDPASSWORD = Get-DevDbPassword $Name
	try {
		$out = & $DevSqlCmd -S localhost -U sa -d $Database -b -W -h -1 -s '|' -Q ("SET NOCOUNT ON; " + $Query) 2>&1
		if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed on $Database : $out" }
		return @($out | Where-Object { $_ -ne $null -and "$_".Trim() -ne '' } | ForEach-Object { "$_".Trim() })
	} finally {
		Remove-Item Env:SQLCMDPASSWORD -ErrorAction SilentlyContinue
	}
}

function Get-DevPid([string]$Name) {
	$file = Join-Path $DevRoot "pids\$Name.pid"
	if (-not (Test-Path $file)) { return $null }
	$id = [int](Get-Content $file -Raw).Trim()
	$proc = Get-Process -Id $id -ErrorAction SilentlyContinue
	if ($proc -and $proc.ProcessName -eq 'java') { return $id }
	return $null
}

function Test-DevUp([string]$Name) {
	try {
		$null = Invoke-RestMethod -Uri ((Get-DevBaseUrl $Name) + '/config') -TimeoutSec 3
		return $true
	} catch {
		return $false
	}
}
