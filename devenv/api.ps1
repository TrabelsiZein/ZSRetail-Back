# API helpers of the dev scripts: login and calls that return the status and the body without throwing on 4xx/5xx.
. "$PSScriptRoot\common.ps1"

# Default logins seeded by ZZDataInitializer on a new database (a head office seeds admin only).
$DevLogins = @{ admin = 'P@ssw0rd'; responsible = '123.0'; cashier = 'cashier' }

function Invoke-DevApi([string]$Name, [string]$Method, [string]$Path, $Body = $null, [string]$Token = $null,
		[int]$TimeoutSec = 60) {
	$uri = (Get-DevBaseUrl $Name) + $Path
	$headers = @{}
	if ($Token) { $headers['Authorization'] = "Bearer $Token" }
	$params = @{ Uri = $uri; Method = $Method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = $TimeoutSec }
	if ($null -ne $Body) {
		$json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 -Compress }
		$params['Body'] = [Text.Encoding]::UTF8.GetBytes($json)
		$params['ContentType'] = 'application/json; charset=utf-8'
	}
	$status = 0
	$content = $null
	try {
		$response = Invoke-WebRequest @params
		$status = [int]$response.StatusCode
		$content = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
	} catch [System.Net.WebException] {
		$resp = $_.Exception.Response
		if (-not $resp) { return [pscustomobject]@{ Status = 0; Body = $null; Raw = $_.Exception.Message } }
		$status = [int]$resp.StatusCode
		$reader = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
		$content = $reader.ReadToEnd()
	}
	$parsed = $null
	if ($content) { try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $null } }
	return [pscustomobject]@{ Status = $status; Body = $parsed; Raw = $content }
}

function Get-DevToken([string]$Name, [string]$User = 'admin') {
	$uri = (Get-DevBaseUrl $Name) + '/login'
	$response = Invoke-WebRequest -Uri $uri -Method Post -UseBasicParsing -TimeoutSec 30 `
		-Headers @{ username = $User; password = $DevLogins[$User] }
	return ($response.Content | ConvertFrom-Json).token
}

# Multipart upload of the license file (curl.exe: PowerShell 5.1 has no -Form).
function Send-DevLicense([string]$Name, [string]$Token, [string]$File) {
	$out = & curl.exe -s -o - -w "`n%{http_code}" -H "Authorization: Bearer $Token" -F "file=@$File;type=application/json" `
		((Get-DevBaseUrl $Name) + '/admin/license/upload')
	return $out
}
