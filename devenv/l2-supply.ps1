# L2 of the head office plan, step 7A (BLs and stock), through the API and sqlcmd on the three databases.
# Needs the head office, store B and store C set up (devenv\setup-stores.ps1) and an artifact of step 7A
# (devenv\build.ps1). Store B carries ownership.catalogue=HEAD_OFFICE and ownership.supply=HEAD_OFFICE in its profile;
# store C the catalogue only (supply local). The head office's ALLOW_NEGATIVE_STOCK is set to false for the run and
# restored at the end. Each run uses its own item codes (run tag); test data stays in the dev databases.
#   powershell -File devenv\l2-supply.ps1
# Output: one PASS / FAIL line per scenario, also written to C:\zsretail-dev\logs\l2-supply-<tag>.txt
param([int]$StopAfter = 99)
. "$PSScriptRoot\api.ps1"
$ErrorActionPreference = 'Stop'

$Run = Get-Date -Format 'HHmmss'
$Report = Join-Path $DevRoot "logs\l2-supply-$Run.txt"
$Results = New-Object System.Collections.ArrayList
$Tokens = @{}
$Stores = @('store-b', 'store-c')

function Say([string]$text) { Write-Output $text; Add-Content -Path $Report -Value $text -Encoding UTF8 }
function Result([string]$n, [bool]$ok, [string]$text) {
	$line = ("{0} {1}. {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $n, $text)
	[void]$Results.Add($line); Say $line
}
function Tok([string]$inst, [string]$user = 'admin') {
	$key = "$inst/$user"
	if (-not $Tokens.ContainsKey($key)) { $Tokens[$key] = Get-DevToken $inst $user }
	return $Tokens[$key]
}
function Forget([string]$inst) { foreach ($k in @($Tokens.Keys)) { if ($k.StartsWith("$inst/")) { $Tokens.Remove($k) } } }
function Api([string]$inst, [string]$method, [string]$path, $body = $null, [string]$user = 'admin') {
	return Invoke-DevApi $inst $method $path $body (Tok $inst $user)
}
function Json($value) { return ConvertTo-Json -InputObject $value -Depth 10 -Compress }
# The same call through curl.exe, to read the body of an error answer: "<status>|<body>".
function CurlApi([string]$inst, [string]$method, [string]$path, $body = $null) {
	$curlArgs = @('-s', '-o', '-', '-w', "`n%{http_code}", '-X', $method, '-H', "Authorization: Bearer $(Tok $inst)")
	if ($null -ne $body) {
		$file = Join-Path $DevRoot "logs\l2-supply-body-$Run.json"
		[IO.File]::WriteAllText($file, $(if ($body -is [string]) { $body } else { Json $body }), (New-Object Text.UTF8Encoding($false)))
		$curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$file")
	}
	$lines = @(& curl.exe @curlArgs ((Get-DevBaseUrl $inst) + $path))
	return ($lines[-1] + '|' + (($lines | Select-Object -First ($lines.Count - 1)) -join ' '))
}
function Sql([string]$inst, [string]$q) { return Invoke-DevSql $inst $q }
function Scalar([string]$inst, [string]$q) { $r = @(Sql $inst $q); if ($r.Count -eq 0) { return $null }; return $r[0] }
function Now { return (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fff') }

function StopInst([string[]]$inst) { & "$PSScriptRoot\stop.ps1" -Instance $inst | Out-Null; foreach ($i in $inst) { Forget $i } }
function StartInst([string[]]$inst, [string[]]$set = @()) { & "$PSScriptRoot\start.ps1" -Instance $inst -Set $set | Out-Null; foreach ($i in $inst) { Forget $i } }
function RunJob([string]$inst, [string]$code) {
	$r = Api $inst POST "/admin/holink/jobs/$code/run"
	if ($r.Status -ne 200) { return $null }
	return $r.Body
}
function Heartbeat([string]$inst) { $null = Api $inst POST '/admin/holink/check' }
function Horizon { return Scalar 'headoffice' "SELECT CAST(last_version AS varchar) FROM ho_down_sequence WHERE domain = 'CATALOGUE'" }
function Pull([string]$inst, [int]$max = 6) {
	$target = Horizon
	for ($i = 0; $i -lt $max; $i++) {
		$null = RunJob $inst 'COPIES_DOWN'
		if ((Scalar $inst "SELECT cursor_value FROM hol_down_cursor WHERE domain = 'CATALOGUE'") -eq $target) { break }
	}
	$null = RunJob $inst 'COPIES_DOWN'   # the supply and the retries of a last cycle
}
function Push([string]$inst) { return RunJob $inst 'SUPPLY_PUSH' }

function StoreId([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM ho_store WHERE code = '$code'") }
function ItemId([string]$inst, [string]$code) { $v = Scalar $inst "SELECT CAST(id AS varchar) FROM item WHERE item_code = '$code'"; if ($v) { return [long]$v }; return $null }
function Stock([string]$inst, [string]$code) { $v = Scalar $inst "SELECT CAST(ISNULL(stock_quantity, 0) AS varchar) FROM item WHERE item_code = '$code'"; if ($null -eq $v) { return $null }; return [int]$v }
function Moves([string]$inst, [string]$code, [string]$type) {
	return [int](Scalar $inst "SELECT CAST(COUNT(*) AS varchar) FROM stock_movement m JOIN item i ON i.id = m.item_id WHERE i.item_code = '$code' AND m.movement_type = '$type'")
}
function HoFamily([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM item_family WHERE code = '$code'") }
function HoSubFamily([long]$familyId) { return [long](Scalar 'headoffice' "SELECT TOP 1 id FROM item_sub_family WHERE item_family_id = $familyId ORDER BY code") }
function HoItem([string]$code, [double]$price, [long]$familyId, $subFamilyId = $null) {
	$body = @{ itemCode = $code; name = "L2 $code"; unitPrice = $price; defaultVAT = 19; type = 'PRODUCT'; showInPos = $true; itemFamily = @{ id = $familyId } }
	if ($subFamilyId) { $body.itemSubFamily = @{ id = $subFamilyId } }
	return Api 'headoffice' POST '/item' $body
}
function Purchase([string]$inst, $vendorId, $itemId, [int]$qty, [double]$price) {
	return Api $inst POST '/purchase-header/process-purchase' @{ vendorId = $vendorId; lines = @(@{ itemId = $itemId; quantity = $qty; unitPrice = $price; discountPercent = 0; vatPercent = 19 }) }
}
function Draft([long]$storeId, [object[]]$lines, [string]$note = "L2 $Run") {
	return Api 'headoffice' POST '/admin/headoffice/deliveries' @{ storeId = $storeId; documentDate = (Get-Date -Format 'yyyy-MM-dd'); note = $note; lines = $lines }
}
function Validate([long]$id) { return Api 'headoffice' POST "/admin/headoffice/deliveries/$id/validate" }
function HoBl([long]$id) { return (Api 'headoffice' GET "/admin/headoffice/deliveries/$id").Body }
function StoreBl([string]$number) { $v = Scalar 'store-b' "SELECT CAST(id AS varchar) FROM hol_delivery WHERE delivery_number = '$number'"; if ($v) { return [long]$v }; return $null }
function Receive([long]$id, [object[]]$lines) { return Api 'store-b' POST "/admin/deliveries/$id/receive" (Json @{ note = "L2 $Run"; lines = $lines }) }
function LineNo([long]$blId, [string]$code) { return [int](Scalar 'store-b' "SELECT CAST(line_no AS varchar) FROM hol_delivery_line WHERE delivery_id = $blId AND item_code = '$code'") }
function EnsureSession([string]$inst) {
	$current = Api $inst GET '/cashier-session/current' $null 'cashier'
	if ($current.Status -ne 200 -or -not $current.Body -or -not $current.Body.id) {
		$open = Api $inst POST '/cashier-session/open' @{ openingCash = 0 } 'cashier'
		if ($open.Status -ne 200) { throw "No session on $inst : $($open.Raw)" }
	}
}
function Sale([string]$inst, [string]$code, [int]$qty = 1) {
	EnsureSession $inst
	$row = (Scalar $inst "SELECT CAST(id AS varchar) + '|' + CAST(unit_price AS varchar) + '|' + CAST(ISNULL(defaultvat, 0) AS varchar) FROM item WHERE item_code = '$code'").Split('|')
	$price = [double]$row[1]; $vat = [int]$row[2]
	$gross = [math]::Round($price * $qty, 3); $ht = [math]::Round($gross / (1 + $vat / 100.0), 3)
	$cash = [long](Scalar $inst "SELECT id FROM payment_method WHERE code = 'CLIENT_ESPECES'")
	$body = @{ subtotal = $ht; taxAmount = [math]::Round($gross - $ht, 3); discountAmount = 0; totalAmount = $gross; paidAmount = $gross; changeAmount = 0
		lines = @(@{ itemId = [long]$row[0]; quantity = $qty; unitPrice = [math]::Round($price / (1 + $vat / 100.0), 3); lineTotal = $ht; discountPercentage = 0
				discountAmount = 0; vatAmount = [math]::Round($gross - $ht, 3); vatPercent = $vat; unitPriceIncludingVat = $price; lineTotalIncludingVat = $gross })
		payments = @(@{ paymentMethodId = $cash; amount = $gross }) }
	return Api $inst POST '/sales-header/process-sale' $body 'cashier'
}
# A minimal .xlsx (inline strings, one sheet) for the data import (same as l2-catalogue.ps1).
function New-Xlsx([string]$path, [string[]]$headers, [object[]]$rows) {
	Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
	$sb = New-Object Text.StringBuilder
	[void]$sb.Append('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>')
	$all = @(, $headers) + $rows
	for ($r = 0; $r -lt $all.Count; $r++) {
		[void]$sb.Append("<row r=""$($r + 1)"">")
		$cells = $all[$r]
		for ($c = 0; $c -lt $cells.Count; $c++) {
			[void]$sb.Append("<c r=""$([char](65 + $c))$($r + 1)"" t=""inlineStr""><is><t>$([Security.SecurityElement]::Escape([string]$cells[$c]))</t></is></c>")
		}
		[void]$sb.Append('</row>')
	}
	[void]$sb.Append('</sheetData></worksheet>')
	$parts = [ordered]@{
		'[Content_Types].xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'
		'_rels/.rels' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'
		'xl/workbook.xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'
		'xl/_rels/workbook.xml.rels' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'
		'xl/worksheets/sheet1.xml' = $sb.ToString()
	}
	if (Test-Path $path) { Remove-Item $path }
	$zip = [IO.Compression.ZipFile]::Open($path, 'Create')
	try {
		foreach ($name in $parts.Keys) {
			$writer = New-Object IO.StreamWriter($zip.CreateEntry($name).Open(), (New-Object Text.UTF8Encoding($false)))
			$writer.Write($parts[$name]); $writer.Close()
		}
	} finally { $zip.Dispose() }
}
function Import([string]$inst, [string]$file, [string]$type, [string]$mapping) {
	$mapFile = Join-Path $DevRoot "logs\l2-supply-mapping-$Run.json"
	Set-Content -Path $mapFile -Value $mapping -Encoding ascii
	$lines = @(& curl.exe -s -o - -w "`n%{http_code}" -H "Authorization: Bearer $(Tok $inst)" -F "file=@$file" -F "entityType=$type" `
		-F "mapping=<$mapFile" ((Get-DevBaseUrl $inst) + '/admin/import/execute'))
	return $lines[-1]
}

Say "L2 supply (step 7A), run $Run, $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# ─── Setup ───────────────────────────────────────────────────────
foreach ($i in 'headoffice', 'store-b', 'store-c') { if (-not (Test-DevUp $i)) { StartInst $i } }
foreach ($s in $Stores) { Heartbeat $s }
$negative = Scalar 'headoffice' "SELECT valeur FROM general_setup WHERE code = 'ALLOW_NEGATIVE_STOCK'"
$null = Sql 'headoffice' "UPDATE general_setup SET valeur = 'false' WHERE code = 'ALLOW_NEGATIVE_STOCK'"
$storeB = StoreId 'STORE-B'; $storeC = StoreId 'STORE-C'
$fam = HoFamily 'BOIS'; $sub = HoSubFamily $fam
$x = "SUP-$Run-X"; $y = "SUP-$Run-Y"
$xHo = (HoItem $x 25 $fam $sub).Body.id
$yHo = (HoItem $y 12 $fam $sub).Body.id
$vendor = (Api 'headoffice' POST '/vendor' @{ vendorCode = "HOV-$Run"; name = "Head office vendor $Run"; phone = '71000000' }).Body.id
Say "   setup: ALLOW_NEGATIVE_STOCK at the head office false (was $negative); items $x, $y; head office vendor $vendor; owner_supply B $(Scalar 'headoffice' "SELECT ISNULL(owner_supply,'?') FROM ho_store WHERE id = $storeB"), C $(Scalar 'headoffice' "SELECT ISNULL(owner_supply,'?') FROM ho_store WHERE id = $storeC")"

try {
# ─── 1. The head office buys ─────────────────────────────────────
$p1 = Purchase 'headoffice' $vendor $xHo 100 10
$p1y = Purchase 'headoffice' $vendor $yHo 20 5
$ok = $p1.Status -eq 201 -and (Stock 'headoffice' $x) -eq 100 -and (Moves 'headoffice' $x 'PURCHASE_RECEPTION') -eq 1 -and (Stock 'headoffice' $y) -eq 20
Result 1 $ok "head office purchase of 100 $x ($($p1.Status)): stock $(Stock 'headoffice' $x), movements $(Moves 'headoffice' $x 'PURCHASE_RECEPTION'); 20 $y ($($p1y.Status))"
foreach ($s in $Stores) { Pull $s }

# ─── 2. Draft edited, deleted, created again; validated once ────
$d = Draft $storeB @(@{ itemCode = $x; quantity = 40 })
$e = Api 'headoffice' PUT "/admin/headoffice/deliveries/$($d.Body.id)" @{ storeId = $storeB; documentDate = (Get-Date -Format 'yyyy-MM-dd'); note = 'edited'; lines = @(@{ itemCode = $x; quantity = 45 }) }
$edited = $e.Body.note -eq 'edited' -and $e.Body.lines[0].quantitySent -eq 45 -and $null -eq $e.Body.number
$del = Api 'headoffice' DELETE "/admin/headoffice/deliveries/$($d.Body.id)"
$bl1 = Draft $storeB @(@{ itemCode = $x; quantity = 50 }, @{ itemCode = $y; quantity = 10 })
$v1 = Validate $bl1.Body.id
$number1 = $v1.Body.number
$outX = Moves 'headoffice' $x 'DELIVERY_OUT'; $outY = Moves 'headoffice' $y 'DELIVERY_OUT'
$v1b = CurlApi 'headoffice' POST "/admin/headoffice/deliveries/$($bl1.Body.id)/validate"
$ok = $d.Status -eq 201 -and $e.Status -eq 200 -and $edited -and $del.Status -eq 204 -and $bl1.Status -eq 201 -and $v1.Status -eq 200 -and $number1 -match '^BL-\d{6}$' -and
	$v1.Body.status -eq 'SENT' -and (Stock 'headoffice' $x) -eq 50 -and (Stock 'headoffice' $y) -eq 10 -and $outX -eq 1 -and $outY -eq 1 -and $v1b -like '409|*' -and
	(Stock 'headoffice' $x) -eq 50 -and (Moves 'headoffice' $x 'DELIVERY_OUT') -eq 1
Result 2 $ok "draft $($d.Status), edited $($e.Status) ($edited), deleted $($del.Status); created again $($bl1.Status), validated $($v1.Status): $number1 $($v1.Body.status); head office stock $x $(Stock 'headoffice' $x), $y $(Stock 'headoffice' $y), DELIVERY_OUT $outX + $outY; second validate [$v1b], stock $(Stock 'headoffice' $x)"

# ─── 3. Shortage ─────────────────────────────────────────────────
$short = Draft $storeB @(@{ itemCode = $x; quantity = 1000 }, @{ itemCode = $y; quantity = 11 })
$vs = CurlApi 'headoffice' POST "/admin/headoffice/deliveries/$($short.Body.id)/validate"
$after = HoBl $short.Body.id
$ok = $vs -like "409|*$x*1000*" -and $vs -like "*$y*" -and $after.status -eq 'DRAFT' -and $null -eq $after.number -and (Stock 'headoffice' $x) -eq 50 -and (Stock 'headoffice' $y) -eq 10
$null = Api 'headoffice' DELETE "/admin/headoffice/deliveries/$($short.Body.id)"
Result 3 $ok "shortage: [$vs]; the draft still $($after.status), no number; stock $x $(Stock 'headoffice' $x), $y $(Stock 'headoffice' $y)"

# ─── 4. B receives, C sees nothing ───────────────────────────────
Pull 'store-b'; Pull 'store-c'
$bBl = StoreBl $number1
$bStatus = Scalar 'store-b' "SELECT status FROM hol_delivery WHERE delivery_number = '$number1'"
$cRows = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM hol_delivery"
$cApi = (Api 'store-c' GET '/admin/deliveries').Status
$forC = CurlApi 'headoffice' POST '/admin/headoffice/deliveries' @{ storeId = $storeC; documentDate = (Get-Date -Format 'yyyy-MM-dd'); lines = @(@{ itemCode = $x; quantity = 1 }) }
$ok = $bBl -and $bStatus -eq 'TO_RECEIVE' -and $cRows -eq '0' -and $cApi -eq 404 -and $forC -like '409|*'
Result 4 $ok "B: $number1 $bStatus; C: BLs $cRows, reception API $cApi; a BL for C [$forC]"

# ─── 5. B confirms 48 of 50 ──────────────────────────────────────
$xB0 = Stock 'store-b' $x; $yB0 = Stock 'store-b' $y
$r5 = Receive $bBl @(@{ lineNo = (LineNo $bBl $x); quantityReceived = 48 })
$xB1 = Stock 'store-b' $x; $yB1 = Stock 'store-b' $y
$inX = Moves 'store-b' $x 'DELIVERY_IN'
$r5b = CurlApi 'store-b' POST "/admin/deliveries/$bBl/receive" (Json @{ lines = @(@{ lineNo = 1; quantityReceived = 48 }) })
$null = Push 'store-b'
$ho5 = HoBl $bl1.Body.id
$lineX = @($ho5.lines | Where-Object { $_.itemCode -eq $x })[0]
$ok = $r5.Status -eq 200 -and $xB1 -eq $xB0 + 48 -and $yB1 -eq $yB0 + 10 -and $inX -eq 1 -and $r5b -like '409|*' -and (Stock 'store-b' $x) -eq $xB1 -and
	$ho5.status -eq 'RECEIVED' -and $lineX.quantityReceived -eq 48 -and $lineX.difference -eq -2 -and $ho5.difference -ne 0
Result 5 $ok "B confirmed 48 of 50 ($($r5.Status)): $x $xB0->$xB1, $y $yB0->$yB1 (as sent), DELIVERY_IN $inX; second confirm [$r5b]; head office $($ho5.status), $x received $($lineX.quantityReceived) difference $($lineX.difference)"

# ─── 6. Head office stopped while B confirms ─────────────────────
$bl2 = Draft $storeB @(@{ itemCode = $x; quantity = 5 })
$number2 = (Validate $bl2.Body.id).Body.number
Pull 'store-b'
$bBl2 = StoreBl $number2
StopInst 'headoffice'; Say '   (head office stopped)'
$x6 = Stock 'store-b' $x
$r6 = Receive $bBl2 @()
$x6b = Stock 'store-b' $x
$push6 = Push 'store-b'
$pending6 = Scalar 'store-b' "SELECT push_status FROM hol_delivery WHERE delivery_number = '$number2'"
StartInst 'headoffice'; Say '   (head office started)'
$null = Push 'store-b'
$ho6 = HoBl $bl2.Body.id
$at6 = Scalar 'headoffice' "SELECT CONVERT(varchar, confirmation_received_at, 126) FROM ho_delivery WHERE id = $($bl2.Body.id)"
$null = Push 'store-b'
$at6b = Scalar 'headoffice' "SELECT CONVERT(varchar, confirmation_received_at, 126) FROM ho_delivery WHERE id = $($bl2.Body.id)"
$ok = $r6.Status -eq 200 -and $x6b -eq $x6 + 5 -and $pending6 -eq 'PENDING' -and $ho6.status -eq 'RECEIVED' -and $at6 -and $at6 -eq $at6b -and
	(Scalar 'store-b' "SELECT push_status FROM hol_delivery WHERE delivery_number = '$number2'") -eq 'SENT'
Result 6 $ok "head office stopped: $number2 confirmed ($($r6.Status)), stock $x6->$x6b at once, push [$($push6.lastMessage)] status $pending6; back: head office $($ho6.status) at $at6, after another push $at6b"

# ─── 7. A confirmation sent twice ────────────────────────────────
$key = (Select-String -Path (Join-Path $DevResources 'application-store-b-dev.properties') -Pattern '^headoffice.api-key=(.*)$').Matches[0].Groups[1].Value.Trim()
$conf = @(@{ number = $number1; receivedAt = (Scalar 'store-b' "SELECT CONVERT(varchar, received_at, 126) FROM hol_delivery WHERE delivery_number = '$number1'").Substring(0, 19)
		receivedBy = 'admin'; note = "L2 $Run"; lines = @($ho5.lines | ForEach-Object { @{ lineNo = $_.lineNo; itemCode = $_.itemCode; quantityReceived = $_.quantityReceived } }) })
$before7 = Scalar 'headoffice' "SELECT CONVERT(varchar, confirmation_received_at, 126) FROM ho_delivery WHERE id = $($bl1.Body.id)"
function HoConfirm($body) {
	return Invoke-RestMethod -Uri ((Get-DevBaseUrl 'headoffice') + '/ho/supply/confirmations') -Method Post -ContentType 'application/json' `
		-Headers @{ 'X-Store-Code' = 'STORE-B'; 'X-Store-Key' = $key } -Body ([Text.Encoding]::UTF8.GetBytes((Json $body)))
}
$c1 = HoConfirm $conf; $c2 = HoConfirm $conf
$conf[0].lines[0].quantityReceived = 47
$c3 = HoConfirm $conf
$after7 = Scalar 'headoffice' "SELECT CONVERT(varchar, confirmation_received_at, 126) FROM ho_delivery WHERE id = $($bl1.Body.id)"
$ho7 = HoBl $bl1.Body.id
$ok = $c1.results[0].accepted -and $c2.results[0].accepted -and -not $c3.results[0].accepted -and $before7 -eq $after7 -and
	(@($ho7.lines | Where-Object { $_.itemCode -eq $x })[0].quantityReceived) -eq 48
Result 7 $ok "the same confirmation twice: accepted $($c1.results[0].accepted)/$($c2.results[0].accepted) [$($c2.results[0].message)], other quantities: accepted $($c3.results[0].accepted) [$($c3.results[0].message)]; head office unchanged ($before7 = $after7, $x received 48)"

# ─── 8. A line whose item is missing at B ───────────────────────
$null = Api 'store-b' PUT '/admin/holink/jobs/COPIES_DOWN/interval' @{ intervalSeconds = 86400 }
$f8 = "F8-$Run"; $z = "SUP-$Run-Z"
$f8Id = (Api 'headoffice' POST '/item-family' @{ code = $f8; name = "Family $f8"; displayOrder = 0 }).Body.id
$zHo = (HoItem $z 9 $f8Id).Body.id
$null = Purchase 'headoffice' $vendor $zHo 10 3
$xlsx = Join-Path $DevRoot "logs\l2-supply-import-$Run.xlsx"
New-Xlsx $xlsx @('itemCode', 'name', 'unitPrice', 'familyCode') @(1..121 | ForEach-Object { , @(("SIMP-$Run-{0:D3}" -f $_), "Imported $_", '4.5', 'BOIS') })
$imported = Import 'headoffice' $xlsx 'ITEMS' (Json @(@{ dbField = 'itemCode'; excelColumn = 'itemCode' }, @{ dbField = 'name'; excelColumn = 'name' }, @{ dbField = 'unitPrice'; excelColumn = 'unitPrice' }, @{ dbField = 'familyCode'; excelColumn = 'familyCode' }))
$f8Obj = (Api 'headoffice' GET "/item-family/$f8Id").Body; $f8Obj.name = "Family $f8 renamed"
$null = Api 'headoffice' PUT "/item-family/$f8Id" (Json $f8Obj)   # the family comes after the item: the item waits at B
$bl3 = Draft $storeB @(@{ itemCode = $z; quantity = 4 })
$number3 = (Validate $bl3.Body.id).Body.number
$null = RunJob 'store-b' 'COPIES_DOWN'
$bBl3 = StoreBl $number3
$here = (Api 'store-b' GET "/admin/deliveries/$bBl3").Body.lines[0].itemHere
$r8 = Receive $bBl3 @()
$applied8 = Scalar 'store-b' "SELECT CAST(stock_applied AS varchar) FROM hol_delivery_line WHERE delivery_id = $bBl3"
$null = RunJob 'store-b' 'COPIES_DOWN'
$z1 = Stock 'store-b' $z; $zMoves1 = Moves 'store-b' $z 'DELIVERY_IN'
$null = RunJob 'store-b' 'COPIES_DOWN'
$z2 = Stock 'store-b' $z; $zMoves2 = Moves 'store-b' $z 'DELIVERY_IN'
$null = Api 'store-b' PUT '/admin/holink/jobs/COPIES_DOWN/interval' @{ intervalSeconds = $null }
$ok = $imported -eq '200' -and $here -eq $false -and $r8.Status -eq 200 -and $applied8 -eq '0' -and $z1 -eq 4 -and $zMoves1 -eq 1 -and $z2 -eq 4 -and $zMoves2 -eq 1
Result 8 $ok "$number3 with $z missing at B (item here: $here), confirmed ($($r8.Status)), stock applied $applied8; next cycle: stock $z1, DELIVERY_IN $zMoves1; one more cycle: $z2, $zMoves2"

# ─── 9. More received than sent ──────────────────────────────────
$bl4 = Draft $storeB @(@{ itemCode = $x; quantity = 2 })
$number4 = (Validate $bl4.Body.id).Body.number
Pull 'store-b'
$bBl4 = StoreBl $number4
$x9 = Stock 'store-b' $x
$r9 = Receive $bBl4 @(@{ lineNo = 1; quantityReceived = 3 })
$null = Push 'store-b'
$ho9 = HoBl $bl4.Body.id
$ok = $r9.Status -eq 200 -and (Stock 'store-b' $x) -eq $x9 + 3 -and $ho9.status -eq 'RECEIVED' -and $ho9.lines[0].quantityReceived -eq 3 -and $ho9.lines[0].difference -eq 1
Result 9 $ok "${number4}: 2 sent, 3 received ($($r9.Status)): B stock $x9->$(Stock 'store-b' $x); head office $($ho9.status) received $($ho9.lines[0].quantityReceived) difference $($ho9.lines[0].difference)"

# ─── 10. Stock of B at the head office ──────────────────────────
$null = Push 'store-b'
$sale = Sale 'store-b' $x
$xB = Stock 'store-b' $x
$p10 = Push 'store-b'
$net = (Api 'headoffice' GET "/admin/headoffice/stock?storeId=$storeB&search=$x").Body
$atHo = $net.content[0].byStore."$storeB"
$p10b = Push 'store-b'
$cStock = Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM ho_store_stock WHERE store_id = $storeC"
$ok = $sale.Status -eq 200 -and $atHo -eq $xB -and $net.content[0].headOffice -eq (Stock 'headoffice' $x) -and $p10.lastMessage -match 'stock: [1-9]\d* items sent' -and
	$p10b.lastMessage -eq 'nothing to send' -and $cStock -eq '0'
Result 10 $ok "sale at B ($($sale.Status)): B $x $xB, at the head office $atHo (head office $($net.content[0].headOffice)); push [$($p10.lastMessage)], then [$($p10b.lastMessage)]; rows of C at the head office $cStock"

# ─── 11. Adjustment movement, item edit keeps the stock ─────────
$adjB = Api 'store-b' POST "/item/$(ItemId 'store-b' $x)/adjust-stock" @{ delta = 2; reason = 'COUNT' }
$adjMoves = Moves 'store-b' $x 'ADJUSTMENT_IN'
$stale = (Api 'headoffice' GET "/item/$xHo").Body
$adjHo = Api 'headoffice' POST "/item/$xHo/adjust-stock" @{ delta = 5; reason = 'COUNT' }
$hoStock = Stock 'headoffice' $x
$stale.name = "L2 $x edited"
$edit = Api 'headoffice' PUT "/item/$xHo" (Json $stale)
$ok = $adjB.Status -eq 200 -and $adjMoves -eq 1 -and $adjHo.Status -eq 200 -and $edit.Status -eq 200 -and (Stock 'headoffice' $x) -eq $hoStock -and
	(Moves 'headoffice' $x 'ADJUSTMENT_IN') -eq 1
Result 11 $ok "adjustment at B ($($adjB.Status)): ADJUSTMENT_IN $adjMoves; head office: +5 ($($adjHo.Status)) to $hoStock, an edit sending the old stock $($stale.stockQuantity) ($($edit.Status)) keeps $(Stock 'headoffice' $x)"

# ─── 12. Reception permission on the store ADMIN ────────────────
$perm = Scalar 'store-b' "SELECT CAST(COUNT(*) AS varchar) FROM app_role_permission p JOIN app_role r ON r.id = p.role_id WHERE r.name = 'ADMIN' AND p.permission_key = 'read:admin-holink-deliveries'"
$permC = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM app_role_permission p JOIN app_role r ON r.id = p.role_id WHERE r.name = 'ADMIN' AND p.permission_key = 'read:admin-holink-deliveries'"
Result 12 ($perm -eq '1' -and $permC -eq '0') "ADMIN of B has read:admin-holink-deliveries: $perm (topped up at start); C (supply local): $permC"
} finally {
	$null = Sql 'headoffice' "UPDATE general_setup SET valeur = '$negative' WHERE code = 'ALLOW_NEGATIVE_STOCK'"
	Say "   ALLOW_NEGATIVE_STOCK at the head office restored to $negative"
}
Say ""
Say "Summary: $(@($Results | Where-Object { $_ -like 'PASS*' }).Count) passed, $(@($Results | Where-Object { $_ -like 'FAIL*' }).Count) failed. Report: $Report"
