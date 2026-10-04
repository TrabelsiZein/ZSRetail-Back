# L2 of the head office plan, step 7B (supply prices and invoices), through the API and sqlcmd on the three databases.
# Needs the head office, store B and store C set up (devenv\setup-stores.ps1), an artifact of step 7B (devenv\build.ps1)
# and both stores on the head office catalogue. Store B: supplied (ownership.supply=HEAD_OFFICE in its profile) and,
# for the run, invoiced. Store C: supplied for the run only (restarted with -Set ownership.supply=HEAD_OFFICE), never
# invoiced. Everything the run changes outside its own data is restored at the end: B's invoicing settings, supply and
# selling price lists, purchase right; the head office settings SUPPLY_INVOICE_TAX_STAMP and ALLOW_NEGATIVE_STOCK;
# C restarted with its profile, after its supply traces were cleaned (its BLs, stock copies and deliveries permission,
# its stock rows at the head office; l2-supply expects none on C). Each run uses its own item codes (run tag); test data stays in the dev databases.
#   powershell -File devenv\l2-invoices.ps1
# Output: one PASS / FAIL line per scenario, also written to C:\zsretail-dev\logs\l2-invoices-<tag>.txt
param([int]$StopAfter = 99)
. "$PSScriptRoot\api.ps1"
$ErrorActionPreference = 'Stop'

$Run = Get-Date -Format 'HHmmss'
$Report = Join-Path $DevRoot "logs\l2-invoices-$Run.txt"
$Results = New-Object System.Collections.ArrayList
$Tokens = @{}
$Today = Get-Date -Format 'yyyy-MM-dd'

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
		$file = Join-Path $DevRoot "logs\l2-invoices-body-$Run.json"
		[IO.File]::WriteAllText($file, $(if ($body -is [string]) { $body } else { Json $body }), (New-Object Text.UTF8Encoding($false)))
		$curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$file")
	}
	$lines = @(& curl.exe @curlArgs ((Get-DevBaseUrl $inst) + $path))
	return ($lines[-1] + '|' + (($lines | Select-Object -First ($lines.Count - 1)) -join ' '))
}
function Sql([string]$inst, [string]$q) { return Invoke-DevSql $inst $q }
function Scalar([string]$inst, [string]$q) { $r = @(Sql $inst $q); if ($r.Count -eq 0) { return $null }; return $r[0] }
function Eq($a, $b) { return $null -ne $a -and $null -ne $b -and [math]::Abs([double]$a - [double]$b) -lt 0.0005 }

function StopInst([string[]]$inst) { & "$PSScriptRoot\stop.ps1" -Instance $inst | Out-Null; foreach ($i in $inst) { Forget $i } }
function StartInst([string[]]$inst, [string[]]$set = @()) { & "$PSScriptRoot\start.ps1" -Instance $inst -Set $set | Out-Null; foreach ($i in $inst) { Forget $i } }
function RunJob([string]$inst, [string]$code) {
	$r = Api $inst POST "/admin/holink/jobs/$code/run"
	if ($r.Status -ne 200) { return $null }
	return $r.Body
}
function Heartbeat([string]$inst) { $null = Api $inst POST '/admin/holink/check' }
function Horizon([string]$domain) { return Scalar 'headoffice' "SELECT CAST(last_version AS varchar) FROM ho_down_sequence WHERE domain = '$domain'" }
# Pulls until the store's cursor of the domain reaches the head office's committed number, then one more cycle.
function Pull([string]$inst, [string]$domain = 'CATALOGUE', [int]$max = 6) {
	$target = Horizon $domain
	for ($i = 0; $i -lt $max; $i++) {
		$null = RunJob $inst 'COPIES_DOWN'
		if ((Scalar $inst "SELECT cursor_value FROM hol_down_cursor WHERE domain = '$domain'") -eq $target) { break }
	}
	$null = RunJob $inst 'COPIES_DOWN'
}
function Push([string]$inst) { return RunJob $inst 'SUPPLY_PUSH' }

function StoreId([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM ho_store WHERE code = '$code'") }
function ItemId([string]$inst, [string]$code) { $v = Scalar $inst "SELECT CAST(id AS varchar) FROM item WHERE item_code = '$code'"; if ($v) { return [long]$v }; return $null }
function HoFamily([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM item_family WHERE code = '$code'") }
function HoSubFamily([long]$familyId) { return [long](Scalar 'headoffice' "SELECT TOP 1 id FROM item_sub_family WHERE item_family_id = $familyId ORDER BY code") }
function HoItem([string]$code, [double]$price, [int]$vat, [long]$familyId, $subFamilyId) {
	$body = @{ itemCode = $code; name = "L2 $code"; unitPrice = $price; defaultVAT = $vat; type = 'PRODUCT'; showInPos = $true; itemFamily = @{ id = $familyId }; itemSubFamily = @{ id = $subFamilyId } }
	return Api 'headoffice' POST '/item' $body
}
function Purchase([string]$inst, $vendorId, $itemId, [int]$qty, [double]$price) {
	return Api $inst POST '/purchase-header/process-purchase' @{ vendorId = $vendorId; lines = @(@{ itemId = $itemId; quantity = $qty; unitPrice = $price; discountPercent = 0; vatPercent = 19 }) }
}
function StorePut([long]$storeId, $body) { return Api 'headoffice' PUT "/admin/headoffice/stores/$storeId" $body }
function HoStore([long]$storeId) { return (Api 'headoffice' GET "/admin/headoffice/stores/$storeId").Body }
# A BL validated at the head office for the store: its id and number.
function SendBl([long]$storeId, [object[]]$lines) {
	$draft = Api 'headoffice' POST '/admin/headoffice/deliveries' @{ storeId = $storeId; documentDate = $Today; note = "L2 $Run"; lines = $lines }
	$sent = Api 'headoffice' POST "/admin/headoffice/deliveries/$($draft.Body.id)/validate"
	return [pscustomobject]@{ Id = [long]$draft.Body.id; Number = $sent.Body.number; Status = $sent.Status }
}
function HoBl([long]$id) { return (Api 'headoffice' GET "/admin/headoffice/deliveries/$id").Body }
function StoreBl([string]$inst, [string]$number) { $v = Scalar $inst "SELECT CAST(id AS varchar) FROM hol_delivery WHERE delivery_number = '$number'"; if ($v) { return [long]$v }; return $null }
function LineNo([string]$inst, [long]$blId, [string]$code) { return [int](Scalar $inst "SELECT CAST(line_no AS varchar) FROM hol_delivery_line WHERE delivery_id = $blId AND item_code = '$code'") }
# The store pulls the BL, receives it (quantities by item code; absent: as sent) and sends its confirmation up.
function ReceiveBl([string]$inst, $bl, [hashtable]$counted = @{}) {
	Pull $inst 'SUPPLY'
	$storeBl = StoreBl $inst $bl.Number
	$lines = @($counted.Keys | ForEach-Object { @{ lineNo = (LineNo $inst $storeBl $_); quantityReceived = $counted[$_] } })
	$r = Api $inst POST "/admin/deliveries/$storeBl/receive" (Json @{ note = "L2 $Run"; lines = $lines })
	$null = Push $inst
	return $r.Status
}
function Invoice([long]$id) { return (Api 'headoffice' GET "/admin/headoffice/supply-invoices/$id").Body }
function StoreInvoices([string]$inst, [string]$number) {
	return Scalar $inst "SELECT CAST(COUNT(*) AS varchar) FROM purchase_invoice_header WHERE invoice_number = '$number'"
}
function Cost([string]$inst, [string]$code) {
	return Scalar $inst "SELECT CAST(ISNULL(cost_price, -1) AS varchar) + '|' + CAST(ISNULL(last_direct_cost, -1) AS varchar) + '|' + CAST(ISNULL(last_direct_net_cost, -1) AS varchar) FROM item WHERE item_code = '$code'"
}
# The three costs "cost|last|net" all equal to the value (SQL Server prints a float without decimals when whole).
function CostIs([string]$costs, [double]$value) { if (-not $costs) { return $false }; foreach ($c in $costs.Split('|')) { if (-not (Eq $c $value)) { return $false } }; return $true }
function Setting([string]$code) { return Scalar 'headoffice' "SELECT valeur FROM general_setup WHERE code = '$code'" }
function SetSetting([string]$code, [string]$value) { $null = Sql 'headoffice' "UPDATE general_setup SET valeur = '$value' WHERE code = '$code'" }

Say "L2 invoices (step 7B), run $Run, $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# ─── Setup ───────────────────────────────────────────────────────
foreach ($i in 'headoffice', 'store-b') { if (-not (Test-DevUp $i)) { StartInst $i } }
if (Test-DevUp 'store-c') { StopInst 'store-c' }
StartInst 'store-c' @('ownership.supply=HEAD_OFFICE'); Say '   (store C started with ownership.supply=HEAD_OFFICE for the run)'
Heartbeat 'store-b'; Heartbeat 'store-c'
$storeB = StoreId 'STORE-B'; $storeC = StoreId 'STORE-C'
$saved = (Scalar 'headoffice' "SELECT CAST(ISNULL(deliveries_invoiced, 0) AS varchar) + '|' + ISNULL(supply_price_mode, '') + '|' + ISNULL(CAST(supply_price_list_id AS varchar), '') + '|' + ISNULL(invoice_rhythm, '') + '|' + ISNULL(CAST(selling_price_list_id AS varchar), '') + '|' + CAST(ISNULL(can_purchase, 0) AS varchar) + '|' + ISNULL(CAST(supply_discount_percent AS varchar), '') FROM ho_store WHERE id = $storeB").Split('|')
$savedStamp = Setting 'SUPPLY_INVOICE_TAX_STAMP'
$negative = Setting 'ALLOW_NEGATIVE_STOCK'
SetSetting 'ALLOW_NEGATIVE_STOCK' 'false'
SetSetting 'SUPPLY_INVOICE_TAX_STAMP' 'false'
Say "   saved: B invoiced=$($saved[0]) mode=$($saved[1]) supplyList=$($saved[2]) rhythm=$($saved[3]) sellingList=$($saved[4]) canPurchase=$($saved[5]) percent=$($saved[6]); stamp=$savedStamp; owner_supply C $(Scalar 'headoffice' "SELECT ISNULL(owner_supply,'?') FROM ho_store WHERE id = $storeC")"

$fam = HoFamily 'BOIS'; $sub = HoSubFamily $fam
$p = "INV-$Run-P"; $q = "INV-$Run-Q"; $r = "INV-$Run-R"; $s = "INV-$Run-S"
$ids = @{}
$ids[$p] = (HoItem $p 20 19 $fam $sub).Body.id
$ids[$q] = (HoItem $q 8 7 $fam $sub).Body.id
$ids[$r] = (HoItem $r 5 19 $fam $sub).Body.id
$ids[$s] = (HoItem $s 10 19 $fam $sub).Body.id
$vendor = (Api 'headoffice' POST '/vendor' @{ vendorCode = "INVV-$Run"; name = "Vendor $Run"; phone = '71000000' }).Body.id
foreach ($code in $p, $q, $r, $s) { $null = Purchase 'headoffice' $vendor $ids[$code] 500 1 }
$null = StorePut $storeB @{ canPurchase = $true }   # B creates its own item below; restored at the end
Heartbeat 'store-b'
Pull 'store-b'; Pull 'store-c'
$own = "INV-$Run-OWN"
$ownRes = Api 'store-b' POST '/item' @{ itemCode = $own; name = "L2 own $own"; unitPrice = 4; costPrice = 2.5; defaultVAT = 19; type = 'PRODUCT'; showInPos = $true }
Say "   setup: items $p (20, VAT 19), $q (8, VAT 7), $r (5), $s (10) with 500 in head office stock; own item at B $own ($($ownRes.Status))"

try {
# ─── 1. Settings saved on B, kept when absent ───────────────────
$list = (Api 'headoffice' POST '/admin/headoffice/price-lists' @{ code = "SUP$Run"; name = "Supply $Run"; kind = 'SUPPLY' }).Body
$put1 = StorePut $storeB @{ deliveriesInvoiced = $true; supplyPriceMode = 'PRICE_LIST'; invoiceRhythm = 'PER_BL'; billingLegalName = "L2 B SARL $Run"; billingTaxNumber = '1234567/B/M/000'; billingAddress = 'Route de Sousse' }
$putList = Api 'headoffice' PUT "/admin/headoffice/stores/$storeB/supply-price-list" @{ priceListId = $list.id }
$name = (HoStore $storeB).name
$null = StorePut $storeB @{ name = $name }
$st = HoStore $storeB
$ok = $put1.Status -eq 200 -and $putList.Status -eq 200 -and $list.kind -eq 'SUPPLY' -and $st.deliveriesInvoiced -eq $true -and $st.supplyPriceMode -eq 'PRICE_LIST' -and
	$st.invoiceRhythm -eq 'PER_BL' -and $st.supplyPriceListId -eq $list.id -and $st.billingLegalName -eq "L2 B SARL $Run"
Result 1 $ok "B settings ($($put1.Status)), supply list $($list.code) $($list.kind) ($($putList.Status)); after a PUT with the name only: invoiced $($st.deliveriesInvoiced), $($st.supplyPriceMode), $($st.invoiceRhythm), list $($st.supplyPriceListId), billing '$($st.billingLegalName)'"
if ($StopAfter -le 1) { return }

# ─── 2. Base supply prices; P cheaper on B's supply list ────────
$put2 = Api 'headoffice' PUT '/admin/headoffice/supply-prices' (Json @(@{ itemCode = $p; supplyPrice = 16 }, @{ itemCode = $q; supplyPrice = 5 }, @{ itemCode = $s; supplyPrice = 6 }))
$line2 = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($list.id)/lines" (Json @(@{ itemCode = $p; price = 15 }))
$prices = (Api 'headoffice' GET "/admin/headoffice/supply-prices?search=INV-$Run&size=50").Body.content
$listLines = (Api 'headoffice' GET "/admin/headoffice/price-lists/$($list.id)/lines").Body.content
$pRow = @($prices | Where-Object { $_.itemCode -eq $p })[0]; $rRow = @($prices | Where-Object { $_.itemCode -eq $r })[0]
$ok = $put2.Status -eq 200 -and $line2.Status -eq 200 -and (Eq $pRow.supplyPrice 16) -and (Eq $pRow.sellingPrice 20) -and $null -eq $rRow.supplyPrice -and
	(Eq $listLines[0].price 15) -and (Eq $listLines[0].basePrice 16)
Result 2 $ok "base supply prices ($($put2.Status)): $p $($pRow.supplyPrice) (selling $($pRow.sellingPrice)), $r none; list line ($($line2.Status)) $p $($listLines[0].price), basePrice $($listLines[0].basePrice) (the base supply price)"

# ─── 3. PER_BL: invoiced by itself on the confirmed quantities ──
$bl3 = SendBl $storeB @(@{ itemCode = $p; quantity = 10 }, @{ itemCode = $q; quantity = 6 }, @{ itemCode = $s; quantity = 4 })
$rec3 = ReceiveBl 'store-b' $bl3 @{ $p = 9; $s = 0 }
$ho3 = HoBl $bl3.Id
$inv3 = if ($ho3.invoiceId) { Invoice $ho3.invoiceId } else { $null }
$l3p = @($inv3.lines | Where-Object { $_.itemCode -eq $p })[0]; $l3q = @($inv3.lines | Where-Object { $_.itemCode -eq $q })[0]
$ok = $rec3 -eq 200 -and $ho3.status -eq 'INVOICED' -and $inv3 -and $inv3.invoiceNumber -match '^FHO-\d{4}-\d{6}$' -and @($inv3.lines).Count -eq 2 -and
	$l3p.quantity -eq 9 -and (Eq $l3p.unitPrice 15) -and $l3p.vatPercent -eq 19 -and (Eq $l3p.lineTotalIncludingVat 160.65) -and
	$l3q.quantity -eq 6 -and (Eq $l3q.unitPrice 5) -and $l3q.vatPercent -eq 7 -and (Eq $l3q.lineTotalIncludingVat 32.1) -and (Eq $inv3.totalAmount 192.75) -and
	$inv3.buyerName -eq "L2 B SARL $Run"
Result 3 $ok "$($bl3.Number) received ($rec3) with $p 9 of 10 and $s 0: head office $($ho3.status), invoice $($inv3.invoiceNumber) lines $(@($inv3.lines).Count): $p 9 x $($l3p.unitPrice) VAT $($l3p.vatPercent) = $($l3p.lineTotalIncludingVat), $q 6 x $($l3q.unitPrice) VAT $($l3q.vatPercent) = $($l3q.lineTotalIncludingVat); total $($inv3.totalAmount); buyer $($inv3.buyerName)"
if ($StopAfter -le 3) { return }

# ─── 4. B pulls the invoice once ────────────────────────────────
$ownCost0 = Cost 'store-b' $own
Pull 'store-b' 'SUPPLY'
$num3 = $inv3.invoiceNumber
$hdr = Scalar 'store-b' "SELECT CAST(h.total_amount AS varchar) + '|' + v.vendor_code + '|' + ISNULL(h.origin, '') + '|' + CAST((SELECT COUNT(*) FROM purchase_invoice_line l WHERE l.purchase_invoice_id = h.id) AS varchar) FROM purchase_invoice_header h JOIN vendor v ON v.id = h.vendor_id WHERE h.invoice_number = '$num3'"
$parts = if ($hdr) { $hdr.Split('|') } else { @('', '', '', '') }
$costP = Cost 'store-b' $p; $costQ = Cost 'store-b' $q
$blInv = Scalar 'store-b' "SELECT invoice_number FROM hol_delivery WHERE delivery_number = '$($bl3.Number)'"
$null = RunJob 'store-b' 'COPIES_DOWN'; $null = RunJob 'store-b' 'COPIES_DOWN'
$count4 = StoreInvoices 'store-b' $num3
$ok = $hdr -and (Eq $parts[0] 192.75) -and $parts[1] -eq 'HEAD_OFFICE' -and $parts[2] -eq 'HEAD_OFFICE' -and $parts[3] -eq '2' -and
	(CostIs $costP 15) -and (CostIs $costQ 5) -and (Cost 'store-b' $own) -eq $ownCost0 -and $blInv -eq $num3 -and $count4 -eq '1'
Result 4 $ok "B: purchase invoice $num3 total $($parts[0]), vendor $($parts[1]), origin $($parts[2]), lines $($parts[3]); cost (cost|last|net) $p $costP, $q $costQ, own $own $(Cost 'store-b' $own) (was $ownCost0); BL $($bl3.Number) invoice $blInv; after two more pulls: $count4 invoice(s)"

# ─── 5. At B: the invoice and the HEAD_OFFICE vendor consult-only ─
$hoVendor = [long](Scalar 'store-b' "SELECT id FROM vendor WHERE vendor_code = 'HEAD_OFFICE'")
$invB = [long](Scalar 'store-b' "SELECT id FROM purchase_invoice_header WHERE invoice_number = '$num3'")
$edit5 = CurlApi 'store-b' PUT "/vendor/$hoVendor" @{ vendorCode = 'HEAD_OFFICE'; name = 'Changed'; phone = '1' }
$del5 = CurlApi 'store-b' DELETE "/vendor/$hoVendor"
$new5 = CurlApi 'store-b' POST '/vendor' @{ vendorCode = 'head_office'; name = 'Copy'; phone = '1' }
$invEdit = (Api 'store-b' PUT "/admin/purchase-invoices/$invB" @{ notes = 'changed' }).Status
$invDel = (Api 'store-b' DELETE "/admin/purchase-invoices/$invB").Status
$details = Api 'store-b' GET "/admin/purchase-invoices/$invB/details"
$ok = $edit5 -like '409|*consult-only*' -and $del5 -like '409|*consult-only*' -and $new5 -like '409|*kept*' -and $invEdit -ge 400 -and $invDel -ge 400 -and
	$details.Status -eq 200 -and (Scalar 'store-b' "SELECT name FROM vendor WHERE id = $hoVendor") -ne 'Changed'
Result 5 $ok "HEAD_OFFICE vendor: edit [$($edit5.Split('|')[0])], delete [$($del5.Split('|')[0])], a new one with its code [$($new5.Split('|')[0])]; invoice: PUT $invEdit, DELETE $invDel, details $($details.Status)"

# ─── 6. An item without a supply price ──────────────────────────
$bl6 = SendBl $storeB @(@{ itemCode = $r; quantity = 3 }, @{ itemCode = $p; quantity = 1 })
$rec6 = ReceiveBl 'store-b' $bl6
$ho6 = HoBl $bl6.Id
$toInv6 = @((Api 'headoffice' GET "/admin/headoffice/supply-invoices/to-invoice?storeId=$storeB").Body | Where-Object { $_.id -eq $bl6.Id })
$null = Api 'headoffice' PUT '/admin/headoffice/supply-prices' (Json @(@{ itemCode = $r; supplyPrice = 2 }))
$create6 = Api 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl6.Id) }
$ho6b = HoBl $bl6.Id
$ok = $rec6 -eq 200 -and $ho6.status -eq 'RECEIVED' -and $ho6.invoiceNote -like "*$r*" -and $toInv6.Count -eq 1 -and $toInv6[0].invoiceNote -like "*$r*" -and
	$create6.Status -eq 201 -and $ho6b.status -eq 'INVOICED' -and $null -eq $ho6b.invoiceNote
Result 6 $ok "$($bl6.Number) with $r (no supply price) received ($rec6): head office $($ho6.status), note '$($ho6.invoiceNote)', in to-invoice $($toInv6.Count); price set, invoiced by hand ($($create6.Status)) $($create6.Body.invoiceNumber): $($ho6b.status)"

# ─── 7. GROUPED: preview, one invoice of two BLs ────────────────
$null = StorePut $storeB @{ invoiceRhythm = 'GROUPED' }
$bl7a = SendBl $storeB @(@{ itemCode = $p; quantity = 2 })
$bl7b = SendBl $storeB @(@{ itemCode = $q; quantity = 1 })
$null = ReceiveBl 'store-b' $bl7a; $null = ReceiveBl 'store-b' $bl7b
$st7 = @((HoBl $bl7a.Id).status, (HoBl $bl7b.Id).status)
$count7 = Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM ho_supply_invoice WHERE store_id = $storeB"
$preview = Api 'headoffice' POST '/admin/headoffice/supply-invoices/preview' @{ storeId = $storeB; deliveryIds = @($bl7a.Id, $bl7b.Id) }
$count7b = Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM ho_supply_invoice WHERE store_id = $storeB"
$create7 = Api 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl7a.Id, $bl7b.Id) }
$again7 = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl7a.Id) }
$ok = $st7[0] -eq 'RECEIVED' -and $st7[1] -eq 'RECEIVED' -and $preview.Status -eq 200 -and $null -eq $preview.Body.invoiceNumber -and $count7 -eq $count7b -and
	@($preview.Body.missingPrices).Count -eq 0 -and $create7.Status -eq 201 -and @($create7.Body.deliveryNumbers).Count -eq 2 -and
	(Eq $create7.Body.totalAmount $preview.Body.totalAmount) -and $again7 -like '409|*already invoiced*'
Result 7 $ok "GROUPED: $($bl7a.Number), $($bl7b.Number) received and left $($st7 -join '/'); preview ($($preview.Status)) total $($preview.Body.totalAmount), nothing written ($count7 = $count7b); invoice ($($create7.Status)) $($create7.Body.invoiceNumber) of $(@($create7.Body.deliveryNumbers).Count) BLs total $($create7.Body.totalAmount); invoiced twice [$($again7.Split('|')[0])]"

# ─── 8. PERCENT_OFF ─────────────────────────────────────────────
$null = StorePut $storeB @{ supplyPriceMode = 'PERCENT_OFF' }
$null = Sql 'headoffice' "UPDATE ho_store SET supply_discount_percent = NULL WHERE id = $storeB"   # a percentage cannot be cleared through the API
$bl8 = SendBl $storeB @(@{ itemCode = $p; quantity = 1 }, @{ itemCode = $s; quantity = 1 })
$null = ReceiveBl 'store-b' $bl8
$none8 = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices/preview' @{ storeId = $storeB; deliveryIds = @($bl8.Id) }
$selling = (Api 'headoffice' POST '/admin/headoffice/price-lists' @{ code = "SEL$Run"; name = "Selling $Run" }).Body
$null = Api 'headoffice' PUT "/admin/headoffice/price-lists/$($selling.id)/lines" (Json @(@{ itemCode = $s; price = 12 }))
$null = Api 'headoffice' PUT "/admin/headoffice/stores/$storeB/selling-price-list" @{ priceListId = $selling.id }
$null = StorePut $storeB @{ supplyDiscountPercent = 30 }
$create8 = Api 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl8.Id) }
$l8p = @($create8.Body.lines | Where-Object { $_.itemCode -eq $p })[0]; $l8s = @($create8.Body.lines | Where-Object { $_.itemCode -eq $s })[0]
$ok = $none8 -like '409|*percentage*' -and $create8.Status -eq 201 -and (Eq $l8p.unitPrice 14) -and (Eq $l8s.unitPrice 8.4)
Result 8 $ok "PERCENT_OFF without a percentage [$($none8.Split('|')[0])]; 30%: $p base 20 -> $($l8p.unitPrice), $s on B's selling list 12 -> $($l8s.unitPrice) ($($create8.Status))"

# ─── 9. Nothing received ────────────────────────────────────────
$null = StorePut $storeB @{ supplyPriceMode = 'PRICE_LIST'; invoiceRhythm = 'PER_BL' }
$bl9 = SendBl $storeB @(@{ itemCode = $p; quantity = 2 }, @{ itemCode = $q; quantity = 1 })
$null = ReceiveBl 'store-b' $bl9 @{ $p = 0; $q = 0 }
$ho9 = HoBl $bl9.Id
$toInv9 = @((Api 'headoffice' GET "/admin/headoffice/supply-invoices/to-invoice?storeId=$storeB").Body | Where-Object { $_.id -eq $bl9.Id }).Count
$create9 = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl9.Id) }
$ok = $ho9.status -eq 'RECEIVED' -and $null -eq $ho9.invoiceId -and $null -eq $ho9.invoiceNote -and $toInv9 -eq 0 -and $create9 -like '409|*Nothing was received*'
Result 9 $ok "$($bl9.Number) received at 0 (PER_BL): $($ho9.status), invoice $($ho9.invoiceId), note '$($ho9.invoiceNote)'; in to-invoice $toInv9; create [$($create9.Split('|')[0])]"

# ─── 10. Invoice dates ──────────────────────────────────────────
$null = StorePut $storeB @{ invoiceRhythm = 'GROUPED' }
$bl10 = SendBl $storeB @(@{ itemCode = $q; quantity = 2 })
$null = ReceiveBl 'store-b' $bl10
$future = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl10.Id); invoiceDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd') }
$past = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl10.Id); invoiceDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd') }
$pastPreview = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices/preview' @{ storeId = $storeB; deliveryIds = @($bl10.Id); invoiceDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd') }
$ok = $future -like '400|*future*' -and $past -like '400|*before the date of the last invoice*' -and $pastPreview -like '400|*' -and (HoBl $bl10.Id).status -eq 'RECEIVED'
Result 10 $ok "date tomorrow [$($future.Split('|')[0])], yesterday after today's invoices [$($past.Split('|')[0])], preview yesterday [$($pastPreview.Split('|')[0])]; BL still $((HoBl $bl10.Id).status)"

# ─── 11. Tax stamp on ───────────────────────────────────────────
SetSetting 'SUPPLY_INVOICE_TAX_STAMP' 'true'
$create11 = Api 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl10.Id) }
$stamp = @($create11.Body.lines | Where-Object { $_.itemCode -eq 'TAX_STAMP' })[0]
SetSetting 'SUPPLY_INVOICE_TAX_STAMP' 'false'
Pull 'store-b' 'SUPPLY'
$num11 = $create11.Body.invoiceNumber
$stampB = Scalar 'store-b' "SELECT i.item_code + '|' + CAST(l.unit_price AS varchar) + '|' + CAST(ISNULL(l.vat_percent, -1) AS varchar) FROM purchase_invoice_line l JOIN purchase_invoice_header h ON h.id = l.purchase_invoice_id LEFT JOIN item i ON i.id = l.item_id WHERE h.invoice_number = '$num11' AND l.line_description LIKE '%TAX_STAMP%'"
$bl11b = SendBl $storeB @(@{ itemCode = $q; quantity = 1 })
$null = ReceiveBl 'store-b' $bl11b
$off11 = Api 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeB; deliveryIds = @($bl11b.Id) }
$ok = $create11.Status -eq 201 -and $stamp -and (Eq $stamp.unitPrice 1) -and $stamp.vatPercent -eq 0 -and (Eq $stamp.vatAmount 0) -and $null -eq $stamp.deliveryNumber -and
	$stampB -and $stampB.Split('|')[0] -eq 'TAX_STAMP' -and (Eq $stampB.Split('|')[1] 1) -and (Eq $stampB.Split('|')[2] 0) -and $off11.Status -eq 201 -and @($off11.Body.lines | Where-Object { $_.itemCode -eq 'TAX_STAMP' }).Count -eq 0
Result 11 $ok "stamp on: $num11 line TAX_STAMP $($stamp.unitPrice) VAT $($stamp.vatPercent); at B [$stampB]; stamp off: $($off11.Body.invoiceNumber) without a stamp line"

# ─── 12. Paid, unpaid, balances ─────────────────────────────────
function BalanceB { return @((Api 'headoffice' GET '/admin/headoffice/supply-invoices/balances').Body | Where-Object { $_.storeId -eq $storeB })[0] }
$b0 = BalanceB
$paid = Api 'headoffice' PATCH "/admin/headoffice/supply-invoices/$($inv3.id)/paid" @{ paid = $true; note = "L2 $Run" }
$b1 = BalanceB
$unpaid = Api 'headoffice' PATCH "/admin/headoffice/supply-invoices/$($inv3.id)/paid" @{ paid = $false }
$b2 = BalanceB
$ok = $paid.Status -eq 200 -and $paid.Body.paid -eq $true -and $paid.Body.paidDate -and (Eq ($b0.unpaid - $b1.unpaid) $inv3.totalAmount) -and (Eq ($b1.paid - $b0.paid) $inv3.totalAmount) -and
	$unpaid.Status -eq 200 -and $null -eq $unpaid.Body.paidDate -and (Eq $b2.unpaid $b0.unpaid) -and $b1.unpaidCount -eq $b0.unpaidCount - 1
Result 12 $ok "$num3 paid ($($paid.Status), $($paid.Body.paidDate)): unpaid $($b0.unpaid) -> $($b1.unpaid), paid $($b0.paid) -> $($b1.paid); unpaid again ($($unpaid.Status)): $($b2.unpaid)"

# ─── 13. Store C: supplied, not invoiced ────────────────────────
$blC = SendBl $storeC @(@{ itemCode = $p; quantity = 1 })
$recC = ReceiveBl 'store-c' $blC
Pull 'store-c' 'SUPPLY'
$hoC = HoBl $blC.Id
$invC = Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM ho_supply_invoice WHERE store_id = $storeC"
$createC = CurlApi 'headoffice' POST '/admin/headoffice/supply-invoices' @{ storeId = $storeC; deliveryIds = @($blC.Id) }
$cPurchase = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM purchase_invoice_header WHERE invoice_number LIKE 'FHO-%'"
$cRecords = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM hol_down_record WHERE domain = 'SUPPLY' AND record_code LIKE 'INV:%'"
$ok = $recC -eq 200 -and $hoC.status -eq 'RECEIVED' -and $null -eq $hoC.invoiceId -and $invC -eq '0' -and $createC -like '409|*not invoiced*' -and $cPurchase -eq '0' -and $cRecords -eq '0'
Result 13 $ok "C: $($blC.Number) received ($recC): $($hoC.status), invoices of C $invC, create [$($createC.Split('|')[0])]; at C head office invoices $cPurchase, INV records $cRecords"
} finally {
	$restore = @{ deliveriesInvoiced = ($saved[0] -eq '1'); supplyPriceMode = $(if ($saved[1]) { $saved[1] } else { 'PRICE_LIST' }); invoiceRhythm = $(if ($saved[3]) { $saved[3] } else { 'PER_BL' }); canPurchase = ($saved[5] -eq '1') }
	$null = StorePut $storeB $restore
	$null = Api 'headoffice' PUT "/admin/headoffice/stores/$storeB/supply-price-list" @{ priceListId = $(if ($saved[2]) { [long]$saved[2] } else { $null }) }
	$null = Api 'headoffice' PUT "/admin/headoffice/stores/$storeB/selling-price-list" @{ priceListId = $(if ($saved[4]) { [long]$saved[4] } else { $null }) }
	$percent = if ($saved[6]) { $saved[6] } else { 'NULL' }
	$null = Sql 'headoffice' "UPDATE ho_store SET supply_discount_percent = $percent WHERE id = $storeB"
	SetSetting 'SUPPLY_INVOICE_TAX_STAMP' $savedStamp
	SetSetting 'ALLOW_NEGATIVE_STOCK' $negative
	Heartbeat 'store-b'
	StopInst 'store-c'
	# C is never supplied in its profile: no trace of the run's supply (l2-supply counts them). Its SUPPLY cursor and
	# tracking rows stay, so a later supplied C never pulls these BLs again; without its stock copy it sends all again.
	$cleanC = Sql 'store-c' "SET NOCOUNT ON; DECLARE @d int, @s int, @p int; DELETE FROM hol_delivery_line; DELETE FROM hol_delivery; SET @d = @@ROWCOUNT; DELETE FROM hol_stock_copy; SET @s = @@ROWCOUNT; DELETE FROM app_role_permission WHERE permission_key = 'read:admin-holink-deliveries'; SET @p = @@ROWCOUNT; SELECT CAST(@d AS varchar) + ' BLs, ' + CAST(@s AS varchar) + ' stock copies, ' + CAST(@p AS varchar) + ' deliveries permission'"
	$cleanHo = Sql 'headoffice' "SET NOCOUNT ON; DELETE FROM ho_store_stock WHERE store_id = $storeC; SELECT CAST(@@ROWCOUNT AS varchar)"
	StartInst 'store-c'; Heartbeat 'store-c'
	Say "   cleaned C: $(@($cleanC)[-1]); its stock rows at the head office: $(@($cleanHo)[-1])"
	Say "   restored: B invoiced=$($restore.deliveriesInvoiced) $($restore.supplyPriceMode) $($restore.invoiceRhythm) canPurchase=$($restore.canPurchase), supply list '$($saved[2])', selling list '$($saved[4])', percent $percent; SUPPLY_INVOICE_TAX_STAMP $savedStamp; ALLOW_NEGATIVE_STOCK $negative; store C restarted with its profile (owner_supply $(Scalar 'headoffice' "SELECT ISNULL(owner_supply,'?') FROM ho_store WHERE id = $storeC"))"
}
Say ""
Say "Summary: $(@($Results | Where-Object { $_ -like 'PASS*' }).Count) passed, $(@($Results | Where-Object { $_ -like 'FAIL*' }).Count) failed. Report: $Report"
