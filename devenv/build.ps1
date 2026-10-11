# Builds the two WARs on JDK 21 (code cleanup step 6.5: the store application and the head office application) and
# copies each outside target\, under its deploy name, in the folder of its application: C:\zsretail-dev\artifacts\store\
# and artifacts\headoffice\ (zsretailws-<time>-<commit>.war, exploded beside it; artifacts\latest-<type>.txt names the
# latest), so a later Maven build never touches what the running instances use. A running instance keeps its artifact
# until it is restarted.
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

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$commit = (& git -C $DevRepo rev-parse --short HEAD).Trim()
foreach ($type in $DevApplications.Keys) {
	$war = Get-ChildItem (Join-Path $DevRepo $DevApplications[$type].War) | Select-Object -First 1
	if (-not $war) { throw "No $type WAR: $($DevApplications[$type].War)" }
	$artifacts = Join-Path $DevRoot "artifacts\$type"
	New-Item -ItemType Directory -Force $artifacts | Out-Null
	# The deploy name: each WAR runs alone in its Tomcat under the context zsretailws
	$target = Join-Path $artifacts "zsretailws-$stamp-$commit.war"
	Copy-Item $war.FullName $target
	# Exploded copy: the instances run from it (WarLauncher on a directory). Loading classes out of the nested jars of the
	# 162 MB WAR made the first requests after a start take tens of seconds on this PC (L2 of steps 4 and 5).
	$exploded = $target -replace '\.war$', ''
	New-Item -ItemType Directory -Force $exploded | Out-Null
	& tar.exe -xf $target -C $exploded
	if ($LASTEXITCODE -ne 0) { throw "WAR not extracted ($LASTEXITCODE)" }
	Set-Content -Path (Join-Path $DevRoot "artifacts\latest-$type.txt") -Value $exploded -Encoding ascii
	# Keep the last 5 artifacts of the application (WAR and its exploded copy)
	Get-ChildItem $artifacts -Filter 'zsretailws-*.war' | Sort-Object LastWriteTime -Descending | Select-Object -Skip 5 |
		ForEach-Object {
			Remove-Item $_.FullName -ErrorAction SilentlyContinue
			Remove-Item ($_.FullName -replace '\.war$', '') -Recurse -Force -ErrorAction SilentlyContinue
		}
	Write-Output "Built $type $exploded"
}
