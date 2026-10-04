# Dev environment of the head office pair on this PC (head office plan, L2 from step 4).
# Dot-sourced by the other scripts. Store A (deploy/dev/store-a.properties, pos_db_prod) is deliberately not listed:
# never started here. Each instance runs from its machine file in deploy/ (task 9.3), given with -Dzsretail.machine-file.

$ErrorActionPreference = 'Stop'

$DevRepo   = Split-Path -Parent $PSScriptRoot                       # ...\Apps\ZSRetail-Back
$DevRoot   = 'C:\zsretail-dev'                                      # artifacts, pids, console output, work dirs
$DevJdk    = 'C:\Program Files\Java\jdk-21.0.10'
$DevMvnSettings = Join-Path $env:USERPROFILE '.m2\zsretail-settings.xml'
$DevSqlCmd = 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\sqlcmd.exe'
$DevDeploy = Join-Path $DevRepo 'deploy'                            # machine files (task 9.3)

$DevInstances = [ordered]@{
	'headoffice' = @{ Machine = 'dev\headoffice.properties'; Port = 888; Db = 'pos_headoffice'; Code = $null;     Log = 'C:\zsretail-headoffice\backend.log' }
	'store-b'    = @{ Machine = 'dev\store-b.properties';    Port = 555; Db = 'pos_store_b';    Code = 'STORE-B'; Log = 'C:\zsretail-store-b\backend.log' }
	'store-c'    = @{ Machine = 'dev\store-c.properties';    Port = 556; Db = 'pos_store_c';    Code = 'STORE-C'; Log = 'C:\zsretail-store-c\backend.log' }
}

function Get-DevInstance([string]$Name) {
	if (-not $DevInstances.Contains($Name)) { throw "Unknown instance '$Name' (known: $($DevInstances.Keys -join ', '))" }
	return $DevInstances[$Name]
}

function Get-DevBaseUrl([string]$Name) {
	return "http://localhost:$((Get-DevInstance $Name).Port)/zsretail/api"
}

# The machine file of an instance (under deploy): its preset, database, port, log, head office address and key.
function Get-DevMachineFile([string]$Name) {
	return Join-Path $DevDeploy (Get-DevInstance $Name).Machine
}

# The SQL Server password, read from the instance's machine file at run time (never copied anywhere).
function Get-DevDbPassword([string]$Name) {
	$file = Get-DevMachineFile $Name
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

# Writes a key=value line into an instance's machine file (replaced when the key is there, appended otherwise). Used to
# put the store's key (shown once by the head office) into its machine file; the instance reads it at its next start.
function Set-DevMachineValue([string]$Name, [string]$Key, [string]$Value) {
	$file = Get-DevMachineFile $Name
	$text = [IO.File]::ReadAllText($file)
	$pattern = '(?m)^' + [Text.RegularExpressions.Regex]::Escape($Key) + '=.*$'
	if ([Text.RegularExpressions.Regex]::IsMatch($text, $pattern)) {
		$text = [Text.RegularExpressions.Regex]::Replace($text, $pattern, "$Key=$Value")
	} else {
		if (-not $text.EndsWith("`n")) { $text += "`n" }
		$text += "$Key=$Value`n"
	}
	[IO.File]::WriteAllText($file, $text, (New-Object Text.UTF8Encoding($false)))
	return $file
}
