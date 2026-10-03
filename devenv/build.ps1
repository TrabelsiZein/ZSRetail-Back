# Builds the WAR on JDK 21 and copies it outside target\ (C:\zsretail-dev\artifacts), so a later Maven build never
# touches what the running instances use. A running instance keeps its artifact until it is restarted.
#   powershell -File devenv\build.ps1
. "$PSScriptRoot\common.ps1"

$old = $env:JAVA_HOME
$env:JAVA_HOME = $DevJdk
try {
	Push-Location $DevRepo
	& mvn -q -gs $DevMvnSettings '-Dlombok.version=1.18.36' -DskipTests clean package
	if ($LASTEXITCODE -ne 0) { throw "mvn package failed ($LASTEXITCODE)" }
} finally {
	Pop-Location
	$env:JAVA_HOME = $old
}

$artifacts = Join-Path $DevRoot 'artifacts'
New-Item -ItemType Directory -Force $artifacts | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$commit = (& git -C $DevRepo rev-parse --short HEAD).Trim()
$target = Join-Path $artifacts "zsretailws-$stamp-$commit.war"
Copy-Item (Join-Path $DevRepo 'target\zsretailws.war') $target
Set-Content -Path (Join-Path $artifacts 'latest.txt') -Value $target -Encoding ascii
# Keep the last 5 artifacts
Get-ChildItem $artifacts -Filter 'zsretailws-*.war' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 5 |
	ForEach-Object { Remove-Item $_.FullName -ErrorAction SilentlyContinue }
Write-Output "Built $target"
