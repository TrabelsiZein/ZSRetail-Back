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
# Exploded copy: the instances run from it (WarLauncher on a directory). Loading classes out of the nested jars of the
# 162 MB WAR made the first requests after a start take tens of seconds on this PC (L2 of steps 4 and 5).
$exploded = $target -replace '\.war$', ''
New-Item -ItemType Directory -Force $exploded | Out-Null
& tar.exe -xf $target -C $exploded
if ($LASTEXITCODE -ne 0) { throw "WAR not extracted ($LASTEXITCODE)" }
Set-Content -Path (Join-Path $artifacts 'latest.txt') -Value $exploded -Encoding ascii
# Keep the last 5 artifacts (WAR and its exploded copy)
Get-ChildItem $artifacts -Filter 'zsretailws-*.war' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 5 |
	ForEach-Object {
		Remove-Item $_.FullName -ErrorAction SilentlyContinue
		Remove-Item ($_.FullName -replace '\.war$', '') -Recurse -Force -ErrorAction SilentlyContinue
	}
Write-Output "Built $exploded"
