# L2 of the head office plan, step 6 (items and selling prices decided by the head office), through the API and
# sqlcmd on the three databases. Needs the head office, store B and store C set up (devenv\setup-stores.ps1) and an
# artifact of step 6 (devenv\build.ps1); the store profiles carry ownership.catalogue=HEAD_OFFICE.
# Scenario 1 runs B and C with -Set ownership.catalogue=LOCAL; scenario 2 resets the catalogue copies (head office
# change rows and sequence of CATALOGUE, store cursors and tracking rows) so every run measures a full first pull.
# Each run uses its own codes (run tag); test data stays in the dev databases.
#   powershell -File devenv\l2-catalogue.ps1
# Output: one PASS / FAIL line per scenario, also written to C:\zsretail-dev\logs\l2-catalogue-<tag>.txt
param([int]$StopAfter = 99)
. "$PSScriptRoot\api.ps1"
$ErrorActionPreference = 'Stop'

$Run = Get-Date -Format 'HHmmss'
$Report = Join-Path $DevRoot "logs\l2-catalogue-$Run.txt"
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
function Json($value) { return ConvertTo-Json -InputObject $value -Depth 10 -Compress }   # keeps a one-element array
function Sql([string]$inst, [string]$q) { return Invoke-DevSql $inst $q }
function Scalar([string]$inst, [string]$q) { $r = @(Sql $inst $q); if ($r.Count -eq 0) { return $null }; return $r[0] }
function Now { return (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fff') }

function StopInst([string[]]$inst) { & "$PSScriptRoot\stop.ps1" -Instance $inst | Out-Null; foreach ($i in $inst) { Forget $i } }
function StartInst([string[]]$inst, [string[]]$set = @()) { & "$PSScriptRoot\start.ps1" -Instance $inst -Set $set | Out-Null; foreach ($i in $inst) { Forget $i } }
function Restart([string[]]$inst, [string[]]$set = @()) { StopInst $inst; StartInst $inst $set }
function RunJob([string]$inst, [string]$code) {
	$r = Api $inst POST "/admin/holink/jobs/$code/run"
	if ($r.Status -ne 200) { return $null }
	return $r.Body
}
function Heartbeat([string]$inst) { $null = Api $inst POST '/admin/holink/check' }
# Pulls until the store's catalogue cursor is the head office's last change number (at most $max runs).
function Horizon { return Scalar 'headoffice' "SELECT CAST(last_version AS varchar) FROM ho_down_sequence WHERE domain = 'CATALOGUE'" }
function Pull([string]$inst, [int]$max = 6) {
	$target = Horizon
	for ($i = 0; $i -lt $max; $i++) {
		$null = RunJob $inst 'COPIES_DOWN'
		if ((Scalar $inst "SELECT cursor_value FROM hol_down_cursor WHERE domain = 'CATALOGUE'") -eq $target) { break }
	}
}
function PullAll { foreach ($s in $Stores) { Pull $s } }
function PushSales([string]$inst) { $null = RunJob $inst 'SALES_PUSH' }

function StoreId([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM ho_store WHERE code = '$code'") }
function SetStore([string]$code, [hashtable]$fields) { return Api 'headoffice' PUT "/admin/headoffice/stores/$(StoreId $code)" $fields }
function SetList([string]$code, $listId) { return Api 'headoffice' PUT "/admin/headoffice/stores/$(StoreId $code)/selling-price-list" (Json @{ priceListId = $listId }) }

function ItemId([string]$inst, [string]$code) { $v = Scalar $inst "SELECT CAST(id AS varchar) FROM item WHERE item_code = '$code'"; if ($v) { return [long]$v }; return $null }
function ItemRow([string]$inst, [string]$code) {
	$v = Scalar $inst ("SELECT ISNULL(name,'') + '|' + ISNULL(CAST(unit_price AS varchar),'') + '|' + ISNULL(CAST(defaultvat AS varchar),'') + '|' + " +
		"ISNULL((SELECT code FROM item_family f WHERE f.id = i.item_family_id),'') + '|' + ISNULL(CAST(active AS varchar),'') + '|' + ISNULL(origin,'') + '|' + " +
		"ISNULL(CAST(stock_quantity AS varchar),'') + '|' + ISNULL(CAST(cost_price AS varchar),'') + '|' + ISNULL(CAST(last_direct_cost AS varchar),'') + '|' + " +
		"ISNULL(CAST(own_price AS varchar),'') + '|' + ISNULL(CAST(head_office_price AS varchar),'') + '|' + ISNULL(barcode,'') FROM item i WHERE item_code = '$code'")
	if (-not $v) { return $null }
	$p = $v.Split('|')
	return [pscustomobject]@{ Name = $p[0]; Price = $p[1]; Vat = $p[2]; Family = $p[3]; Active = $p[4]; Origin = $p[5]; Stock = $p[6]; Cost = $p[7]
		LastCost = $p[8]; OwnPrice = $p[9]; HoPrice = $p[10]; Legacy = $p[11] }
}
function Price([string]$inst, [string]$code) { $r = ItemRow $inst $code; if ($r) { return [double]$r.Price }; return $null }
function Record([string]$inst, [string]$code) { return Scalar $inst "SELECT status + '|' + ISNULL(reason,'') + '|' + CONVERT(varchar, received_at, 126) FROM hol_down_record WHERE domain = 'CATALOGUE' AND record_code = '$code'" }
function LogRows([string]$inst, [string]$since, [string]$like) {
	return @(Sql $inst "SELECT result + ': ' + ISNULL(error,'') FROM hol_exchange_log WHERE job = 'COPIES_DOWN' AND exchange_date >= '$since' AND error LIKE '$like' ORDER BY id")
}
function GetJson([string]$inst, [string]$path) { return (Api $inst GET $path).Raw }
# GET then PUT the same object with changes ($edit gets the parsed object).
function Edit([string]$inst, [string]$path, [scriptblock]$edit) {
	$object = (Api $inst GET $path).Body
	& $edit $object
	return Api $inst PUT $path (Json $object)
}

function HoFamily([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM item_family WHERE code = '$code'") }
function HoSubFamily([long]$familyId) { return [long](Scalar 'headoffice' "SELECT TOP 1 id FROM item_sub_family WHERE item_family_id = $familyId ORDER BY code") }
function HoItem([string]$code, [double]$price, [long]$familyId, $subFamilyId = $null, [int]$vat = 19) {
	$body = @{ itemCode = $code; name = "L2 $code"; unitPrice = $price; defaultVAT = $vat; type = 'PRODUCT'; showInPos = $true; itemFamily = @{ id = $familyId } }
	if ($subFamilyId) { $body.itemSubFamily = @{ id = $subFamilyId } }
	return Api 'headoffice' POST '/item' $body
}

function EnsureSession([string]$inst) {
	$current = Api $inst GET '/cashier-session/current' $null 'cashier'
	if ($current.Status -ne 200 -or -not $current.Body -or -not $current.Body.id) {
		$open = Api $inst POST '/cashier-session/open' @{ openingCash = 0 } 'cashier'
		if ($open.Status -ne 200) { throw "No session on $inst : $($open.Raw)" }
	}
}
# A cash sale of $qty x the item at the store's price (TTC), as the POS sends it.
function Sale([string]$inst, [string]$code, [int]$qty = 1) {
	EnsureSession $inst
	$row = (Scalar $inst "SELECT CAST(id AS varchar) + '|' + CAST(unit_price AS varchar) + '|' + CAST(ISNULL(defaultvat, 0) AS varchar) FROM item WHERE item_code = '$code'").Split('|')
	$price = [double]$row[1]; $vat = [int]$row[2]
	$gross = [math]::Round($price * $qty, 3)
	$ht = [math]::Round($gross / (1 + $vat / 100.0), 3)
	$cash = [long](Scalar $inst "SELECT id FROM payment_method WHERE code = 'CLIENT_ESPECES'")
	$body = @{
		subtotal = $ht; taxAmount = [math]::Round($gross - $ht, 3); discountAmount = 0; totalAmount = $gross; paidAmount = $gross; changeAmount = 0
		lines = @(@{ itemId = [long]$row[0]; quantity = $qty; unitPrice = [math]::Round($price / (1 + $vat / 100.0), 3); lineTotal = $ht
				discountPercentage = 0; discountAmount = 0; vatAmount = [math]::Round($gross - $ht, 3); vatPercent = $vat
				unitPriceIncludingVat = $price; lineTotalIncludingVat = $gross })
		payments = @(@{ paymentMethodId = $cash; amount = $gross })
	}
	return Api $inst POST '/sales-header/process-sale' $body 'cashier'
}
function HoTicket([string]$storeCode, [string]$number) {
	return Scalar 'headoffice' ("SELECT t.status + '|' + STRING_AGG(l.item_code, ',') FROM ho_ticket t JOIN ho_store s ON s.id = t.store_id " +
		"LEFT JOIN ho_ticket_line l ON l.ticket_id = t.id WHERE s.code = '$storeCode' AND t.sales_number = '$number' GROUP BY t.status")
}
function Vendor([string]$inst, [string]$code) { return Api $inst POST '/vendor' @{ vendorCode = $code; name = "L2 vendor $code"; phone = '71000000' } }
function Purchase([string]$inst, $vendorId, $itemId, [int]$qty, [double]$price) {
	return Api $inst POST '/purchase-header/process-purchase' @{ vendorId = $vendorId; lines = @(@{ itemId = $itemId; quantity = $qty; unitPrice = $price; discountPercent = 0; vatPercent = 19 }) }
}

# A minimal .xlsx (inline strings, one sheet) for the data import.
function New-Xlsx([string]$path, [string[]]$headers, [object[]]$rows) {
	Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
	function Esc([string]$s) { return [Security.SecurityElement]::Escape($s) }
	function Col([int]$i) { return [string][char](65 + $i) }
	$sb = New-Object Text.StringBuilder
	[void]$sb.Append('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>')
	$all = @(, $headers) + $rows
	for ($r = 0; $r -lt $all.Count; $r++) {
		[void]$sb.Append("<row r=""$($r + 1)"">")
		$cells = $all[$r]
		for ($c = 0; $c -lt $cells.Count; $c++) {
			[void]$sb.Append("<c r=""$(Col $c)$($r + 1)"" t=""inlineStr""><is><t>$(Esc ([string]$cells[$c]))</t></is></c>")
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
			$entry = $zip.CreateEntry($name)
			$writer = New-Object IO.StreamWriter($entry.Open(), (New-Object Text.UTF8Encoding($false)))
			$writer.Write($parts[$name]); $writer.Close()
		}
	} finally { $zip.Dispose() }
}
# POST /admin/import/execute (multipart, curl.exe). Returns "<status>|<body>".
function Import([string]$inst, [string]$file, [string]$type, [string]$mapping) {
	$mapFile = Join-Path $DevRoot "logs\l2-mapping-$Run.json"
	Set-Content -Path $mapFile -Value $mapping -Encoding ascii
	$out = & curl.exe -s -o - -w "`n%{http_code}" -H "Authorization: Bearer $(Tok $inst)" -F "file=@$file" -F "entityType=$type" `
		-F "mapping=<$mapFile" ((Get-DevBaseUrl $inst) + '/admin/import/execute')
	$lines = @($out)
	return ($lines[-1] + '|' + (($lines[0..($lines.Count - 2)]) -join ' '))
}

# The time of a log line (yyyy-MM-dd HH:mm:ss.SSS at the start of the line).
function LogTime([string]$line) { return [datetime]::ParseExact($line.Substring(0, 23), 'yyyy-MM-dd HH:mm:ss.fff', $null) }

Say "L2 catalogue (step 6), run $Run, $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# ─── Setup ───────────────────────────────────────────────────────
if (-not (Test-DevUp 'headoffice')) { StartInst 'headoffice' }
foreach ($code in 'STORE-B', 'STORE-C') { $null = SetStore $code @{ mayChangePrices = $false; canPurchase = $false }; $null = SetList $code $null }
Restart $Stores @('ownership.catalogue=LOCAL', 'ownership.supply=LOCAL')   # since step 7A store B gets its goods from the head office, which needs the catalogue
foreach ($s in $Stores) { Heartbeat $s }
$famBois = HoFamily 'BOIS'; $subBois = HoSubFamily $famBois
$famBrique = HoFamily 'BRIQUE'; $subBrique = HoSubFamily $famBrique
Say "   setup: rights off, no list, B and C restarted with ownership.catalogue=LOCAL (and supply LOCAL)"

# ─── 1. Local catalogue at B and C ───────────────────────────────
$details = @(); $ok = $true
$noted = @('3801701010002', '3801701010003')
foreach ($s in $Stores) {
	$tag = $s.Substring(6).ToUpper()
	$family = [long](Scalar $s "SELECT TOP 1 id FROM item_family ORDER BY id")
	$create = Api $s POST '/item' @{ itemCode = "L2C-$Run-$tag"; name = "Local $Run"; unitPrice = 10; defaultVAT = 19; itemFamily = @{ id = $family } }
	$edit = Edit $s "/item/$($create.Body.id)" { param($o) $o.name = "Local $Run edited"; $o.unitPrice = 11 }
	$delete = Api $s DELETE "/item/$($create.Body.id)"
	$vendor = Vendor $s "V-$Run-$tag"
	$item = ItemId $s '3801701010003'
	$stockBefore = [int](ItemRow $s '3801701010003').Stock
	$purchase = Purchase $s $vendor.Body.id $item 5 100
	$stockAfter = [int](ItemRow $s '3801701010003').Stock
	$good = $create.Status -eq 201 -and $edit.Status -eq 200 -and $delete.Status -eq 204 -and $vendor.Status -eq 201 -and $purchase.Status -eq 201 -and $stockAfter -eq $stockBefore + 5
	$ok = $ok -and $good
	$details += "$tag item $($create.Status)/$($edit.Status)/$($delete.Status), vendor $($vendor.Status), purchase $($purchase.Status) stock $stockBefore->$stockAfter"
}
# C: LOCAL-C1 with stock, a barcode row and the old barcode field; the cost of a noted item
$bcRow = "2000$Run" + '01'; $bcLegacy = "2000$Run" + '02'
$local = ItemId 'store-c' 'LOCAL-C1'
if (-not $local) {
	$family = [long](Scalar 'store-c' "SELECT TOP 1 id FROM item_family ORDER BY id")
	$local = (Api 'store-c' POST '/item' @{ itemCode = 'LOCAL-C1'; name = 'Local item of C'; unitPrice = 12.5; defaultVAT = 19; itemFamily = @{ id = $family } }).Body.id
}
$null = Edit 'store-c' "/item/$local" { param($o) $o.active = $true; $o.barcode = $bcLegacy }
$adjust = Api 'store-c' POST "/item/$local/adjust-stock" @{ delta = 25; reason = 'COUNT' }
$bc = Api 'store-c' POST '/item-barcode' @{ barcode = $bcRow; item = @{ id = $local }; isPrimary = $false }
$costEdit = Edit 'store-c' "/item/$(ItemId 'store-c' '3801701010002')" { param($o) $o.costPrice = 1200.5 }
$notedBefore = @{}
foreach ($code in $noted) { $notedBefore[$code] = ItemRow 'store-c' $code }
$localRow = ItemRow 'store-c' 'LOCAL-C1'
$ok = $ok -and $adjust.Status -eq 200 -and $bc.Status -eq 201 -and $costEdit.Status -eq 200 -and $localRow.Legacy -eq $bcLegacy
Result 1 $ok ("local catalogue: " + ($details -join '; ') + "; C: LOCAL-C1 stock $($localRow.Stock), barcode $bcRow ($($bc.Status)), old field $($localRow.Legacy); noted " +
	(($noted | ForEach-Object { "$_ stock $($notedBefore[$_].Stock) cost $($notedBefore[$_].Cost) last $($notedBefore[$_].LastCost)" }) -join ', '))
if ($StopAfter -le 1) { return }

# ─── 2. Switch: first pull ───────────────────────────────────────
StopInst @('headoffice', 'store-b', 'store-c')
$null = Sql 'headoffice' "DELETE FROM ho_down_change WHERE domain = 'CATALOGUE'; DELETE FROM ho_down_sequence WHERE domain = 'CATALOGUE'"
foreach ($s in $Stores) { $null = Sql $s "DELETE FROM hol_down_cursor WHERE domain = 'CATALOGUE'; DELETE FROM hol_down_record WHERE domain = 'CATALOGUE'" }
$hoStart = Get-Date
StartInst 'headoffice'
function HoLinesSinceStart { return @(Get-Content 'C:\zsretail-headoffice\backend.log' -Tail 4000 | Where-Object { $_.Length -gt 23 -and $_.Substring(0, 19) -ge $hoStart.ToString('yyyy-MM-dd HH:mm:ss') }) }
$wait = (Get-Date).AddMinutes(5)   # the backfill runs at ApplicationReadyEvent, possibly after /config answers
while ((Get-Date) -lt $wait -and -not (HoLinesSinceStart | Where-Object { $_ -match 'copies down: \d+ CATALOGUE records' })) { Start-Sleep -Seconds 2 }
$logLines = HoLinesSinceStart
$started = $logLines | Where-Object { $_ -match 'Started POSMainApp' } | Select-Object -Last 1
$backfill = $logLines | Where-Object { $_ -match 'copies down: (\d+) CATALOGUE records' } | Select-Object -Last 1
$backfillSeconds = if ($started -and $backfill) { [math]::Round(((LogTime $backfill) - (LogTime $started)).TotalSeconds, 1) } else { $null }
$hoRecords = [int](Scalar 'headoffice' "SELECT COUNT(DISTINCT record_code) FROM ho_down_change WHERE domain = 'CATALOGUE'")
$horizon = Scalar 'headoffice' "SELECT CAST(last_version AS varchar) FROM ho_down_sequence WHERE domain = 'CATALOGUE'"
Say "   head office backfill: $hoRecords records, about $backfillSeconds s after 'Started' ($backfill)"
StartInst $Stores
$storesUp = Get-Date
$pullSeconds = @{}
$deadline = (Get-Date).AddMinutes(15)
while ($pullSeconds.Count -lt 2 -and (Get-Date) -lt $deadline) {
	foreach ($s in $Stores) {
		if ($pullSeconds.ContainsKey($s)) { continue }
		$null = RunJob $s 'COPIES_DOWN'
		$cursor = Scalar $s "SELECT cursor_value FROM hol_down_cursor WHERE domain = 'CATALOGUE'"
		if ($cursor -eq $horizon) { $pullSeconds[$s] = [math]::Round(((Get-Date) - $storesUp).TotalSeconds, 1) }
	}
}
foreach ($s in $Stores) { Heartbeat $s }
$counts = @{}
foreach ($s in $Stores) { $counts[$s] = (@(Sql $s "SELECT status + '=' + CAST(COUNT(*) AS varchar) FROM hol_down_record WHERE domain = 'CATALOGUE' GROUP BY status ORDER BY status") -join ' ') }
$notedAfter = @{}; $kept = $true
foreach ($code in $noted) {
	$a = ItemRow 'store-c' $code; $b = $notedBefore[$code]; $notedAfter[$code] = $a
	$kept = $kept -and $a.Origin -eq 'HEAD_OFFICE' -and $a.Stock -eq $b.Stock -and $a.Cost -eq $b.Cost -and $a.LastCost -eq $b.LastCost
}
$localRow = ItemRow 'store-c' 'LOCAL-C1'
$localSale = Sale 'store-c' 'LOCAL-C1'
$tax = ItemRow 'store-c' 'TAX_STAMP'
$taxTracked = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM hol_down_record WHERE record_code = 'ITEM:TAX_STAMP'"
$hoItems = [int](Scalar 'headoffice' "SELECT COUNT(*) FROM item WHERE item_code <> 'TAX_STAMP'")
$bItems = $hoItems - [int](Scalar 'store-b' "SELECT COUNT(*) FROM pos_headoffice.dbo.item h WHERE h.item_code <> 'TAX_STAMP' AND NOT EXISTS (SELECT 1 FROM item i WHERE i.item_code = h.item_code AND i.origin = 'HEAD_OFFICE')")   # items deleted at the head office stay at B, inactive
$ok = $pullSeconds.Count -eq 2 -and $kept -and $localRow.Active -eq '1' -and $localRow.Origin -eq '' -and $localSale.Status -eq 200 -and
	$tax.Origin -eq '' -and $taxTracked -eq '0' -and $bItems -eq $hoItems -and $counts['store-b'] -notmatch 'WAITING|ERROR' -and $counts['store-c'] -notmatch 'WAITING|ERROR'
Result 2 $ok ("first pull: head office backfill $hoRecords records in about $backfillSeconds s; full first pull B $($pullSeconds['store-b']) s, C $($pullSeconds['store-c']) s after the stores were up " +
	"(B: $($counts['store-b']); C: $($counts['store-c'])); head office items at B $bItems / $hoItems; noted items at C " +
	(($noted | ForEach-Object { "$_ origin $($notedAfter[$_].Origin) stock $($notedAfter[$_].Stock) cost $($notedAfter[$_].Cost) last $($notedAfter[$_].LastCost) price $($notedAfter[$_].Price)" }) -join ', ') +
	"; LOCAL-C1 active $($localRow.Active) origin '$($localRow.Origin)', sale $($localSale.Status); TAX_STAMP origin '$($tax.Origin)', tracked $taxTracked")
if ($StopAfter -le 2) { return }

# ─── 3. Created at the head office, sold at B and C ─────────────
$s3 = "S3-$Run"
$created = HoItem $s3 25.5 $famBois $subBois
$s3Ho = $created.Body.id
PullAll
$sales = @{}
foreach ($s in $Stores) {
	$null = Api $s POST "/item/$(ItemId $s $s3)/adjust-stock" @{ delta = 10; reason = 'COUNT' }
	$sales[$s] = Sale $s $s3
}
Start-Sleep -Seconds 32
foreach ($s in $Stores) { PushSales $s }
$tB = HoTicket 'STORE-B' $sales['store-b'].Body.salesNumber
$tC = HoTicket 'STORE-C' $sales['store-c'].Body.salesNumber
$ok = $created.Status -eq 201 -and (Price 'store-b' $s3) -eq 25.5 -and (Price 'store-c' $s3) -eq 25.5 -and $sales['store-b'].Status -eq 200 -and
	$sales['store-c'].Status -eq 200 -and $tB -like "COMPLETED|*$s3*" -and $tC -like "COMPLETED|*$s3*"
Result 3 $ok "item $s3 created ($($created.Status)), price B $(Price 'store-b' $s3) C $(Price 'store-c' $s3); sales B $($sales['store-b'].Body.salesNumber) ($($sales['store-b'].Status)), C $($sales['store-c'].Body.salesNumber) ($($sales['store-c'].Status)); at the head office: B [$tB], C [$tC]"
if ($StopAfter -le 3) { return }

# ─── 4. Changed, deactivated, reactivated, deleted ──────────────
$change = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.name = "S3 changed $Run"; $o.unitPrice = 27; $o.defaultVAT = 7; $o.itemFamily = @{ id = $famBrique }; $o.itemSubFamily = @{ id = $subBrique } }
PullAll
$rows = $Stores | ForEach-Object { ItemRow $_ $s3 }
$changedOk = @($rows | Where-Object { $_.Name -eq "S3 changed $Run" -and [double]$_.Price -eq 27 -and $_.Vat -eq '7' -and $_.Family -eq 'BRIQUE' }).Count -eq 2
$off = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.active = $false }
PullAll
$offOk = @($Stores | Where-Object { (ItemRow $_ $s3).Active -eq '0' }).Count -eq 2
$on = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.active = $true }
PullAll
$onOk = @($Stores | Where-Object { (ItemRow $_ $s3).Active -eq '1' }).Count -eq 2
$d4 = "D4-$Run"
$d4Ho = (HoItem $d4 4 $famBois $subBois).Body.id
PullAll
$d4Before = @($Stores | Where-Object { (ItemRow $_ $d4).Active -eq '1' }).Count
$del = Api 'headoffice' DELETE "/item/$d4Ho"
PullAll
$d4After = $Stores | ForEach-Object { $r = ItemRow $_ $d4; if ($r) { $r.Active } else { 'gone' } }
$ok = $change.Status -eq 200 -and $changedOk -and $off.Status -eq 200 -and $offOk -and $on.Status -eq 200 -and $onOk -and $d4Before -eq 2 -and $del.Status -eq 204 -and ($d4After -join ',') -eq '0,0'
Result 4 $ok "changed (name, price 27, VAT 7, family BRIQUE) at B and C: $changedOk; deactivated $offOk; reactivated $onOk; $d4 deleted at the head office ($($del.Status)): at B and C active=[$($d4After -join ',')] (rows kept)"
if ($StopAfter -le 4) { return }

# ─── 5 and 7. Family and pack component received after the item (store B), import of 121 items ──
$null = Api 'store-b' PUT '/admin/holink/jobs/COPIES_DOWN/interval' @{ intervalSeconds = 86400 }
$f5 = "F5-$Run"; $sf5 = "SF5-$Run"; $i5 = "I5-$Run"; $c7 = "C7-$Run"; $p7 = "P7-$Run"
$f5Id = (Api 'headoffice' POST '/item-family' @{ code = $f5; name = "Family $f5"; displayOrder = 0 }).Body.id
$sf5Id = (Api 'headoffice' POST '/item-sub-family' @{ code = $sf5; name = "Sub $sf5"; displayOrder = 0; itemFamily = @{ id = $f5Id } }).Body.id
$c7Id = (HoItem $c7 3 $famBois $subBois).Body.id
$i5Id = (HoItem $i5 5 $f5Id $sf5Id).Body.id
$p7Id = (HoItem $p7 0 $famBois $subBois).Body.id
$flag = Api 'headoffice' PUT "/item/$p7Id/package-flag" @{ isPackage = $true }
$comp = Api 'headoffice' POST '/item-composition' @{ parentItem = @{ id = $p7Id }; componentItem = @{ id = $c7Id }; quantity = 2 }
$xlsx = Join-Path $DevRoot "logs\l2-import-$Run.xlsx"
$importRows = @(1..121 | ForEach-Object { , @(("IMP-$Run-{0:D3}" -f $_), "Imported $_", '9.5', 'BOIS') })
New-Xlsx $xlsx @('itemCode', 'name', 'unitPrice', 'familyCode') $importRows
$mapping = Json @(@{ dbField = 'itemCode'; excelColumn = 'itemCode' }, @{ dbField = 'name'; excelColumn = 'name' }, @{ dbField = 'unitPrice'; excelColumn = 'unitPrice' }, @{ dbField = 'familyCode'; excelColumn = 'familyCode' })
$hoImport = Import 'headoffice' $xlsx 'ITEMS' $mapping
$null = Edit 'headoffice' "/item-family/$f5Id" { param($o) $o.name = "Family $f5 renamed" }
$null = Edit 'headoffice' "/item/$c7Id" { param($o) $o.name = "Component $c7" }
$since5 = Now
$job5 = RunJob 'store-b' 'COPIES_DOWN'
$job5b = RunJob 'store-b' 'COPIES_DOWN'   # the retry of a cycle skips the codes its own pull just tried
$null = Api 'store-b' PUT '/admin/holink/jobs/COPIES_DOWN/interval' @{ intervalSeconds = $null }
$warn5 = LogRows 'store-b' $since5 '%not in this store%'
$i5Rec = Record 'store-b' "ITEM:$i5"; $p7Rec = Record 'store-b' "ITEM:$p7"; $sf5Rec = Record 'store-b' "SUBFAMILY:$sf5"
$i5Row = ItemRow 'store-b' $i5
$ok = $hoImport -like '200|*' -and $job5.lastMessage -match '(\d+) waiting' -and [int]$Matches[1] -ge 2 -and $warn5.Count -ge 1 -and
	$i5Rec -like 'APPLIED|*' -and $sf5Rec -like 'APPLIED|*' -and $i5Row.Family -eq $f5
Result 5 $ok "item $i5 with its family and sub-family received after it at B: jobs [$($job5.lastMessage)] then [$($job5b.lastMessage)]; page row [$($warn5 -join ' / ')]; now $i5Rec, $sf5Rec, family $($i5Row.Family)"
$compB = Scalar 'store-b' "SELECT ci.item_code + ' x' + CAST(c.quantity AS varchar) FROM item_composition c JOIN item p ON p.id = c.parent_item_id JOIN item ci ON ci.id = c.component_item_id WHERE p.item_code = '$p7'"
Pull 'store-c'
$null = RunJob 'store-c' 'COPIES_DOWN'   # C took the same pages: the pack waited for its component until the next cycle
$compC = Scalar 'store-c' "SELECT ci.item_code + ' x' + CAST(c.quantity AS varchar) FROM item_composition c JOIN item p ON p.id = c.parent_item_id JOIN item ci ON ci.id = c.component_item_id WHERE p.item_code = '$p7'"
$typeC = Scalar 'store-c' "SELECT type FROM item WHERE item_code = '$p7'"
$ok7 = $flag.Status -eq 200 -and $comp.Status -eq 201 -and $p7Rec -like 'APPLIED|*' -and $compB -eq "$c7 x2" -and $compC -eq "$c7 x2" -and $typeC -eq 'PACKAGE'
$job5Waiting = if ($job5.lastMessage -match '(\d+) waiting') { $Matches[1] } else { '?' }
if ($StopAfter -le 5) { Result 7 $ok7 "pack ${p7}: B $p7Rec, components B [$compB], C [$compC] ($typeC)"; return }

# ─── 6. Barcodes ─────────────────────────────────────────────────
$newBc = "619$Run" + '01'
$bcNew = Api 'headoffice' POST '/item-barcode' @{ barcode = $newBc; item = @{ id = $s3Ho }; isPrimary = $true }
PullAll
$scanB = Api 'store-b' GET "/item-barcode/barcode/$newBc"
$since6 = Now
$clash1 = Api 'headoffice' POST '/item-barcode' @{ barcode = $bcRow; item = @{ id = $s3Ho }; isPrimary = $false }
$clash2 = Api 'headoffice' POST '/item-barcode' @{ barcode = $bcLegacy; item = @{ id = $s3Ho }; isPrimary = $false }
Pull 'store-c'
$warn6 = LogRows 'store-c' $since6 '%barcode%head office item%'
$rowOwner = Scalar 'store-c' "SELECT i.item_code + '|' + ISNULL(b.origin,'') FROM item_barcode b JOIN item i ON i.id = b.item_id WHERE b.barcode = '$bcRow'"
$legacyHolders = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM item WHERE barcode IN ('$bcRow', '$bcLegacy')"
$scan1 = Api 'store-c' GET "/item-barcode/barcode/$bcRow"
$scan2 = Api 'store-c' GET "/item-barcode/barcode/$bcLegacy"
$namesBoth = @($warn6 | Where-Object { $_ -like 'WARNING:*LOCAL-C1*' -and $_ -like "*$s3*" }).Count
$ok = $bcNew.Status -eq 201 -and $scanB.Body.itemCode -eq $s3 -and $clash1.Status -eq 201 -and $clash2.Status -eq 201 -and $warn6.Count -eq 2 -and $namesBoth -eq 2 -and
	$rowOwner -eq "$s3|HEAD_OFFICE" -and $legacyHolders -eq '0' -and $scan1.Body.itemCode -eq $s3 -and $scan2.Body.itemCode -eq $s3
Result 6 $ok "new barcode $newBc scanned at B: $($scanB.Body.itemCode); clash with LOCAL-C1 ($bcRow row, $bcLegacy old field): row now [$rowOwner], old fields holding them $legacyHolders, scans $($scan1.Body.itemCode) / $($scan2.Body.itemCode); rows: $($warn6 -join ' / ')"
Result 7 $ok7 "pack $p7 with component ${c7}: at B it waited (job counted $job5Waiting waiting), now $p7Rec, components B [$compB]; at C [$compC] type $typeC"
if ($StopAfter -le 7) { return }

# ─── 8. Price lists ──────────────────────────────────────────────
$pl = Api 'headoffice' POST '/admin/headoffice/price-lists' @{ code = "PL-$Run"; name = "L2 list $Run" }
$line = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($pl.Body.id)/lines" (Json @(@{ itemCode = $s3; price = 30 }))
$set = SetList 'STORE-C' $pl.Body.id
PullAll
$a1 = "C $(Price 'store-c' $s3) B $(Price 'store-b' $s3)"
$ok1 = (Price 'store-c' $s3) -eq 30 -and (Price 'store-b' $s3) -eq 27
$recB = Record 'store-b' "ITEM:$s3"
$null = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($pl.Body.id)/lines" (Json @(@{ itemCode = $s3; price = 31 }))
PullAll
$a2 = "C $(Price 'store-c' $s3) B $(Price 'store-b' $s3)"
$ok2 = (Price 'store-c' $s3) -eq 31 -and (Price 'store-b' $s3) -eq 27 -and (Record 'store-b' "ITEM:$s3") -eq $recB
$lineId = [long](Scalar 'headoffice' "SELECT id FROM ho_price_list_line WHERE price_list_id = $($pl.Body.id)")
$delLine = Api 'headoffice' DELETE "/admin/headoffice/price-lists/$($pl.Body.id)/lines/$lineId"
PullAll
$a3 = "C $(Price 'store-c' $s3)"
$ok3 = $delLine.Status -eq 204 -and (Price 'store-c' $s3) -eq 27
$null = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($pl.Body.id)/lines" (Json @(@{ itemCode = $s3; price = 30 }))
$pl2 = Api 'headoffice' POST '/admin/headoffice/price-lists' @{ code = "PL2-$Run"; name = "L2 list 2 $Run" }
$null = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($pl2.Body.id)/lines" (Json @(@{ itemCode = '3801701010004'; price = 199 }))
PullAll
$before4 = "C $s3 $(Price 'store-c' $s3), 3801701010004 $(Price 'store-c' '3801701010004')"
$set2 = SetList 'STORE-C' $pl2.Body.id
PullAll
$after4 = "C $s3 $(Price 'store-c' $s3), 3801701010004 $(Price 'store-c' '3801701010004'), B 3801701010004 $(Price 'store-b' '3801701010004')"
$ok4 = $set2.Status -eq 200 -and (Price 'store-c' $s3) -eq 27 -and (Price 'store-c' '3801701010004') -eq 199 -and (Price 'store-b' '3801701010004') -eq 220
$delUsed = Api 'headoffice' DELETE "/admin/headoffice/price-lists/$($pl2.Body.id)"
$offUsed = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($pl2.Body.id)" @{ active = $false }
$null = SetList 'STORE-C' $null
PullAll
$back = Price 'store-c' '3801701010004'
$ok = $pl.Status -eq 201 -and $line.Status -eq 200 -and $set.Status -eq 200 -and $ok1 -and $ok2 -and $ok3 -and $ok4 -and $delUsed.Status -eq 409 -and $offUsed.Status -eq 409 -and $back -eq 220
Result 8 $ok "list on C: [$a1]; line 31: [$a2] (B record unchanged: $((Record 'store-b' "ITEM:$s3") -eq $recB)); line deleted: [$a3]; C to a second list: before [$before4], after [$after4]; used list delete $($delUsed.Status), deactivate $($offUsed.Status); list removed: C 3801701010004 $back"
if ($StopAfter -le 8) { return }

# ─── 9. Guards ───────────────────────────────────────────────────
$s3C = ItemId 'store-c' $s3
$famC = [long](Scalar 'store-c' "SELECT id FROM item_family WHERE code = 'BRIQUE'")
$subC = [long](Scalar 'store-c' "SELECT TOP 1 id FROM item_sub_family WHERE origin = 'HEAD_OFFICE' ORDER BY id")
$bcC = [long](Scalar 'store-c' "SELECT id FROM item_barcode WHERE barcode = '$newBc'")
$g = [ordered]@{
	'item PUT' = (Edit 'store-c' "/item/$s3C" { param($o) $o.name = 'hacked' }).Status
	'item DELETE' = (Api 'store-c' DELETE "/item/$s3C").Status
	'family PUT' = (Edit 'store-c' "/item-family/$famC" { param($o) $o.name = 'hacked' }).Status
	'family DELETE' = (Api 'store-c' DELETE "/item-family/$famC").Status
	'sub-family PUT' = (Edit 'store-c' "/item-sub-family/$subC" { param($o) $o.name = 'hacked' }).Status
	'sub-family DELETE' = (Api 'store-c' DELETE "/item-sub-family/$subC").Status
	'barcode PUT' = (Edit 'store-c' "/item-barcode/$bcC" { param($o) $o.description = 'hacked' }).Status
	'barcode DELETE' = (Api 'store-c' DELETE "/item-barcode/$bcC").Status
}
$stock = Api 'store-c' POST "/item/$s3C/adjust-stock" @{ delta = 3; reason = 'COUNT' }
$codeChange = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.itemCode = "$s3-X" }
$ok = @($g.Values | Where-Object { $_ -ne 409 }).Count -eq 0 -and $stock.Status -eq 200 -and $codeChange.Status -eq 409 -and (ItemRow 'store-c' $s3).Name -ne 'hacked'
Result 9 $ok ("guards at C: " + (($g.Keys | ForEach-Object { "$_ $($g[$_])" }) -join ', ') + "; adjust-stock $($stock.Status); code change at the head office $($codeChange.Status) [$($codeChange.Raw)]")
if ($StopAfter -le 9) { return }

# ─── 10. Own price at C ──────────────────────────────────────────
$null = SetStore 'STORE-C' @{ mayChangePrices = $true }; Heartbeat 'store-c'
$own = Api 'store-c' PUT "/item/$s3C/own-price" @{ unitPrice = 50 }
Pull 'store-c'
$afterPull = Price 'store-c' $s3
$null = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.unitPrice = 28 }
Pull 'store-c'
$r10 = ItemRow 'store-c' $s3
$give = Api 'store-c' DELETE "/item/$s3C/own-price"
$given = Price 'store-c' $s3
$null = Api 'store-c' PUT "/item/$s3C/own-price" @{ unitPrice = 55 }
$null = SetStore 'STORE-C' @{ mayChangePrices = $false }; Heartbeat 'store-c'
$refused = Api 'store-c' PUT "/item/$s3C/own-price" @{ unitPrice = 60 }
StopInst 'headoffice'; Say '   (head office stopped)'
$since10 = Now
$null = RunJob 'store-c' 'COPIES_DOWN'
$r10b = ItemRow 'store-c' $s3
$rows10 = LogRows 'store-c' $since10 '%own prices replaced%'
StartInst 'headoffice'; Say '   (head office started)'
$ok = $own.Status -eq 200 -and $afterPull -eq 50 -and [double]$r10.Price -eq 50 -and [double]$r10.HoPrice -eq 28 -and $give.Status -eq 200 -and $given -eq 28 -and
	$refused.Status -eq 409 -and [double]$r10b.Price -eq 28 -and $r10b.OwnPrice -eq '' -and $rows10.Count -eq 1
Result 10 $ok "own price 50 ($($own.Status)), after a pull $afterPull, after a head office price of 28: price $($r10.Price) beside $($r10.HoPrice); given back ($($give.Status)): $given; right off: $($refused.Status); head office stopped, next cycle: price $($r10b.Price) own '$($r10b.OwnPrice)', rows [$($rows10 -join ' / ')]"
if ($StopAfter -le 10) { return }

# ─── 11. Purchase right ──────────────────────────────────────────
foreach ($s in $Stores) { Heartbeat $s }
$bVendor = [long](Scalar 'store-b' "SELECT TOP 1 id FROM vendor ORDER BY id DESC")
$bPurchase = [long](Scalar 'store-b' "SELECT TOP 1 id FROM purchase_header ORDER BY id DESC")
$fam = [long](Scalar 'store-b' "SELECT TOP 1 id FROM item_family ORDER BY id")
$w = [ordered]@{
	'item' = (Api 'store-b' POST '/item' @{ itemCode = "OWN-B-$Run"; name = 'Own B'; unitPrice = 5; defaultVAT = 19; itemFamily = @{ id = $fam } }).Status
	'vendor' = (Vendor 'store-b' "VB-$Run").Status
	'purchase' = (Purchase 'store-b' $bVendor (ItemId 'store-b' 'LOCAL-C1') 1 1).Status
	'purchase invoice' = (Api 'store-b' POST '/admin/purchase-invoices' @{ vendorId = $bVendor; purchaseIds = @($bPurchase) }).Status
}
$reads = @((Api 'store-b' GET '/purchase-header/history?page=0&size=5').Status, (Api 'store-b' GET '/vendor/admin/paginated?page=0&size=5').Status,
	(Api 'store-b' GET '/admin/purchase-invoices?page=0&size=5').Status)
$null = SetStore 'STORE-C' @{ canPurchase = $true }; Heartbeat 'store-c'
$famCc = [long](Scalar 'store-c' "SELECT TOP 1 id FROM item_family ORDER BY id")
$ownC = Api 'store-c' POST '/item' @{ itemCode = "OWN-C-$Run"; name = 'Own C'; unitPrice = 8; defaultVAT = 19; itemFamily = @{ id = $famCc } }
$vC = Vendor 'store-c' "VC-$Run"
$pC = Purchase 'store-c' $vC.Body.id $ownC.Body.id 7 4
$stockC = (ItemRow 'store-c' "OWN-C-$Run").Stock
$saleC = Sale 'store-c' "OWN-C-$Run"
$hoLine = Purchase 'store-c' $vC.Body.id $s3C 1 1
$hoCode = Api 'store-c' POST '/item' @{ itemCode = $s3; name = 'dup'; unitPrice = 1; defaultVAT = 19; itemFamily = @{ id = $famCc } }
Start-Sleep -Seconds 32
PushSales 'store-c'
$tOwn = HoTicket 'STORE-C' $saleC.Body.salesNumber
$ok = @($w.Values | Where-Object { $_ -ne 409 }).Count -eq 0 -and @($reads | Where-Object { $_ -ne 200 }).Count -eq 0 -and $ownC.Status -eq 201 -and $vC.Status -eq 201 -and
	$pC.Status -eq 201 -and $stockC -eq '7' -and $saleC.Status -eq 200 -and $tOwn -like "COMPLETED|*OWN-C-$Run*" -and $hoLine.Status -eq 409 -and $hoCode.Status -eq 409
Result 11 $ok ("B without the right: " + (($w.Keys | ForEach-Object { "$_ $($w[$_])" }) -join ', ') + "; reads $($reads -join '/'); C with it: own item $($ownC.Status), vendor $($vC.Status), purchase $($pC.Status), stock $stockC, sale $($saleC.Status), at the head office [$tOwn]; " +
	"purchase of $s3 $($hoLine.Status) [$($hoLine.Raw)]; item with code $s3 $($hoCode.Status) [$($hoCode.Raw)]")
if ($StopAfter -le 11) { return }

# ─── 12. Rights kept through a restart with the head office stopped ──
StopInst 'headoffice'; Say '   (head office stopped)'
Restart 'store-c'
$v12c = Vendor 'store-c' "VC2-$Run"
$v12b = Vendor 'store-b' "VB2-$Run"
$ok = $v12c.Status -eq 201 -and $v12b.Status -eq 409
Result 12 $ok "head office stopped, C restarted: vendor at C $($v12c.Status), at B $($v12b.Status)"
if ($StopAfter -le 12) { return }

# ─── 13. Head office stopped: sales with the last copy; back: the changes arrive ──
$s13 = @{}
foreach ($s in $Stores) { $s13[$s] = Sale $s $s3 }
StartInst 'headoffice'; Say '   (head office started)'
$null = Edit 'headoffice' "/item/$s3Ho" { param($o) $o.name = "S3 after outage $Run" }
PullAll
Start-Sleep -Seconds 32
foreach ($s in $Stores) { PushSales $s }
$t13 = $Stores | ForEach-Object { HoTicket $(if ($_ -eq 'store-b') { 'STORE-B' } else { 'STORE-C' }) $s13[$_].Body.salesNumber }
$names13 = $Stores | ForEach-Object { (ItemRow $_ $s3).Name }
$ok = $s13['store-b'].Status -eq 200 -and $s13['store-c'].Status -eq 200 -and @($t13 | Where-Object { $_ -like 'COMPLETED|*' }).Count -eq 2 -and
	@($names13 | Where-Object { $_ -eq "S3 after outage $Run" }).Count -eq 2
Result 13 $ok "sales while stopped: B $($s13['store-b'].Status), C $($s13['store-c'].Status); back: tickets at the head office [$($t13 -join ' / ')], new name at B and C: $($names13 -join ' / ')"
if ($StopAfter -le 13) { return }

# ─── 14. sales_price, data import ───────────────────────────────
$null = Sql 'store-c' ("INSERT INTO sales_price (active, created_at, created_by, currency_code, item_no, minimum_quantity, price_includes_vat, responsibility_center, " +
	"sales_code, sales_type, starting_date, unit_of_measure_code, unit_price, variant_code) VALUES (1, SYSDATETIME(), 'L2', '', '$s3', 0, 1, '', '', 'ALL_CUSTOMERS', '2026-01-01', '', 99999, '')")
$expected = [long](Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM sales_price p JOIN item i ON i.item_code = p.item_no WHERE i.origin = 'HEAD_OFFICE'")
$status = (Api 'store-c' GET '/admin/holink/status').Body.catalogue
$network = (Api 'store-c' GET '/catalogue/network').Body
$spId = [long](Scalar 'store-c' "SELECT TOP 1 id FROM sales_price WHERE item_no = '$s3' ORDER BY id DESC")
$spWrites = @((Api 'store-c' POST '/sales-price' @{ itemNo = $s3; salesType = 'ALL_CUSTOMERS'; salesCode = ''; unitPrice = 1 }).Status,
	(Api 'store-c' PUT "/sales-price/$spId" @{ itemNo = $s3; unitPrice = 1 }).Status, (Api 'store-c' DELETE "/sales-price/$spId").Status)
$imports = @('ITEMS', 'FAMILIES', 'SUBFAMILIES', 'BARCODES', 'SALES_PRICES') | ForEach-Object { (Import 'store-c' $xlsx $_ $mapping).Split('|')[0] }
$impB = [int](Scalar 'store-b' "SELECT COUNT(*) FROM item WHERE item_code LIKE 'IMP-$Run-%' AND origin = 'HEAD_OFFICE'")
$impC = [int](Scalar 'store-c' "SELECT COUNT(*) FROM item WHERE item_code LIKE 'IMP-$Run-%' AND origin = 'HEAD_OFFICE'")
$ok = $status.salesPriceRowsOnHeadOfficeItems -eq $expected -and $expected -ge 1 -and $network.salesPriceRowsOnHeadOfficeItems -eq $expected -and
	@($spWrites | Where-Object { $_ -ne 409 }).Count -eq 0 -and @($imports | Where-Object { $_ -ne '409' }).Count -eq 0 -and $impB -eq 121 -and $impC -eq 121
Result 14 $ok "sales_price rows on head office items: status $($status.salesPriceRowsOnHeadOfficeItems), network $($network.salesPriceRowsOnHeadOfficeItems), SQL $expected; writes $($spWrites -join '/'); store imports $($imports -join '/'); head office import [$hoImport] reached B $impB, C $impC of 121"
if ($StopAfter -le 14) { return }

# ─── 15. Barcode over 90 characters ──────────────────────────────
$long = 'B' * 91
$r15 = Api 'headoffice' POST '/item-barcode' @{ barcode = $long; item = @{ id = $s3Ho }; isPrimary = $false }
$saved = Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM item_barcode WHERE barcode = '$long'"
Result 15 ($r15.Status -eq 400 -and $saved -eq '0') "barcode of 91 characters at the head office: $($r15.Status) [$($r15.Raw)], saved $saved"

# ─── End: rights off, list none ──────────────────────────────────
foreach ($code in 'STORE-B', 'STORE-C') { $null = SetStore $code @{ mayChangePrices = $false; canPurchase = $false } }
foreach ($s in $Stores) { Heartbeat $s }
Say ""
Say "Summary: $(@($Results | Where-Object { $_ -like 'PASS*' }).Count) passed, $(@($Results | Where-Object { $_ -like 'FAIL*' }).Count) failed. Report: $Report"
function HoLinesSinceStart { return @(Get-Content 'C:\zsretail-headoffice\backend.log' -Tail 4000 | Where-Object { $_.Length -gt 23 -and $_.Substring(0, 19) -ge $hoStart.ToString('yyyy-MM-dd HH:mm:ss') }) }
$wait = (Get-Date).AddMinutes(5)   # the backfill runs at ApplicationReadyEvent, possibly after /config answers
while ((Get-Date) -lt $wait -and -not (HoLinesSinceStart | Where-Object { $_ -match 'copies down: \d+ CATALOGUE records' })) { Start-Sleep -Seconds 2 }
$logLines = HoLinesSinceStart
