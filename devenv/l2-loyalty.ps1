# L2 of the head office plan, steps 4 and 5 (shared loyalty), through the API and sqlcmd on the three databases.
# Needs the head office, store B and store C started (devenv\start.ps1) and set up (devenv\setup-stores.ps1).
# Each run uses its own phone numbers and program code (run tag), so it can be replayed on the same databases.
# Store C is restarted with ownership.loyalty=LOCAL for scenario 1, then with its profile (HEAD_OFFICE).
#   powershell -File devenv\l2-loyalty.ps1
# Output: one PASS / FAIL line per scenario, also written to C:\zsretail-dev\logs\l2-loyalty-<tag>.txt
param([int]$StopAfter = 99)
. "$PSScriptRoot\api.ps1"
$ErrorActionPreference = 'Stop'

$Run = Get-Date -Format 'HHmmss'
$Report = Join-Path $DevRoot "logs\l2-loyalty-$Run.txt"
$Results = New-Object System.Collections.ArrayList
$Item = '3801701010001'        # 349 TND in the copied items
$PointValue = 10               # millimes: 100 points = 1 TND
$Sales = New-Object System.Collections.ArrayList
$Tokens = @{}

function Say([string]$text) { Write-Output $text; Add-Content -Path $Report -Value $text -Encoding UTF8 }
function Result([string]$n, [bool]$ok, [string]$text) {
	$line = ("{0} {1}. {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $n, $text)
	[void]$Results.Add($line); Say $line
}
function Phone([int]$n) { return '7' + $Run + $n }   # 8 digits, unique per run
function Tok([string]$inst, [string]$user = 'admin') {
	$key = "$inst/$user"
	if (-not $Tokens.ContainsKey($key)) { $Tokens[$key] = Get-DevToken $inst $user }
	return $Tokens[$key]
}
function Forget([string]$inst) { foreach ($k in @($Tokens.Keys)) { if ($k.StartsWith("$inst/")) { $Tokens.Remove($k) } } }
function Api([string]$inst, [string]$method, [string]$path, $body = $null, [string]$user = 'admin') {
	return Invoke-DevApi $inst $method $path $body (Tok $inst $user)
}
function Sql([string]$inst, [string]$q) { return Invoke-DevSql $inst $q }
function Scalar([string]$inst, [string]$q) { $r = @(Sql $inst $q); if ($r.Count -eq 0) { return $null }; return $r[0] }

function StopHo { & "$PSScriptRoot\stop.ps1" -Instance headoffice | Out-Null; Say '   (head office stopped)' }
function StartHo { & "$PSScriptRoot\start.ps1" -Instance headoffice | Out-Null; Forget 'headoffice'; Say '   (head office started)' }
function Restart([string]$inst, [string[]]$set = @()) {
	& "$PSScriptRoot\stop.ps1" -Instance $inst | Out-Null
	& "$PSScriptRoot\start.ps1" -Instance $inst -Set $set | Out-Null
	Forget $inst
}
function RunJob([string]$inst, [string]$code) {
	$r = Api $inst POST "/admin/holink/jobs/$code/run"
	if ($r.Status -eq 404) { return $null }
	return $r.Body
}
function Heartbeat([string]$inst) { $null = Api $inst POST '/admin/holink/check' }
function Push([string]$inst) { $null = RunJob $inst 'LOYALTY_PUSH' }
function Pull([string]$inst) { $null = RunJob $inst 'COPIES_DOWN' }
function Sync { Push 'store-b'; Push 'store-c'; Pull 'store-b'; Pull 'store-c' }

function FunctionId([string]$inst) { return [long](Scalar $inst "SELECT id FROM member_function WHERE code = 'CLIENT'") }
function Enrol([string]$inst, [string]$first, [string]$phone, [string]$user = 'cashier') {
	return Api $inst POST '/loyalty/member' @{ firstName = $first; lastName = 'L2'; phone = $phone; memberFunctionId = (FunctionId $inst) } $user
}
function Card([string]$inst, [string]$card) { return (Api $inst GET "/loyalty/member/by-card/$card").Body }
function Points([string]$inst, [string]$card) {
	$v = Scalar $inst "SELECT CAST(loyalty_points AS varchar) FROM loyalty_member WHERE card_number = '$card'"
	if ($null -eq $v) { return $null }; return [int]$v
}
function Active([string]$inst, [string]$card) {
	return (Scalar $inst "SELECT CAST(active AS varchar) FROM loyalty_member WHERE card_number = '$card'") -eq '1'
}
function Balances([string]$card) { return @((Points 'headoffice' $card), (Points 'store-b' $card), (Points 'store-c' $card)) }
function Same([object[]]$b) { return ($null -ne $b[0]) -and ($b[0] -eq $b[1]) -and ($b[1] -eq $b[2]) }

function EnsureSession([string]$inst) {
	$current = Api $inst GET '/cashier-session/current' $null 'cashier'
	if ($current.Status -ne 200 -or -not $current.Body -or -not $current.Body.id) {
		$open = Api $inst POST '/cashier-session/open' @{ openingCash = 0 } 'cashier'
		if ($open.Status -ne 200) { throw "No session on $inst : $($open.Raw)" }
	}
}
# A sale of $qty x $Item for a member, spending $redeem points (totals as the POS sends them: net of the points).
function Sale([string]$inst, $memberId, [int]$redeem = 0, [int]$qty = 1) {
	EnsureSession $inst
	$row = (Scalar $inst "SELECT CAST(id AS varchar) + '|' + CAST(unit_price AS varchar) + '|' + CAST(ISNULL(defaultvat, 0) AS varchar) FROM item WHERE item_code = '$Item'").Split('|')
	$price = [double]$row[1]; $vat = [int]$row[2]
	$gross = [math]::Round($price * $qty, 3)
	$ht = [math]::Round($gross / (1 + $vat / 100.0), 3)
	$deduction = [math]::Round($redeem * $PointValue / 1000.0, 3)
	$total = [math]::Round($gross - $deduction, 3)
	$cash = [long](Scalar $inst "SELECT id FROM payment_method WHERE code = 'CLIENT_ESPECES'")
	$body = @{
		subtotal = $ht; taxAmount = [math]::Round($gross - $ht, 3); discountAmount = 0; totalAmount = $total; paidAmount = $total; changeAmount = 0
		loyaltyMemberId = $memberId; loyaltyPointsToRedeem = $redeem
		lines = @(@{ itemId = [long]$row[0]; quantity = $qty; unitPrice = [math]::Round($price / (1 + $vat / 100.0), 3); lineTotal = $ht
				discountPercentage = 0; discountAmount = 0; vatAmount = [math]::Round($gross - $ht, 3); vatPercent = $vat
				unitPriceIncludingVat = $price; lineTotalIncludingVat = $gross })
		payments = @(@{ paymentMethodId = $cash; amount = $total })
	}
	$r = Api $inst POST '/sales-header/process-sale' $body 'cashier'
	if ($r.Status -eq 200) { [void]$Sales.Add(@{ Store = $inst; Number = $r.Body.salesNumber; Member = $memberId; Earned = $r.Body.loyaltyPointsEarned; Redeemed = $r.Body.loyaltyPointsRedeemed }) }
	return $r
}
function ReturnItems([string]$inst, [string]$salesNumber, [int]$qty) {
	EnsureSession $inst
	$line = [long](Scalar $inst "SELECT TOP 1 l.id FROM sales_line l JOIN sales_header h ON h.id = l.sales_header_id JOIN item i ON i.id = l.item_id WHERE h.sales_number = '$salesNumber' AND i.item_code = '$Item'")
	return Api $inst POST '/return-header/process-return' @{ ticketNumber = $salesNumber; returnType = 'SIMPLE_RETURN'; returnLines = @(@{ salesLineId = $line; quantity = $qty }) } 'cashier'
}
function StoreId([string]$code) { return [long](Scalar 'headoffice' "SELECT id FROM ho_store WHERE code = '$code'") }
function SetStore([string]$code, [hashtable]$fields) { return Api 'headoffice' PUT "/admin/headoffice/stores/$(StoreId $code)" $fields }

Say "L2 shared loyalty, run $Run, $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# ─── Setup ───────────────────────────────────────────────────────
Restart 'store-c' @('ownership.loyalty=LOCAL')
foreach ($code in 'STORE-B', 'STORE-C') { $null = SetStore $code @{ canEditMembers = $false; canAdjustPoints = $false; redeemRequiresOnline = $false; enrolRequiresOnline = $false } }
if (-not (@((Api 'headoffice' GET '/member-function').Body) | Where-Object { $_.code -eq 'CLIENT' })) {
	$null = Api 'headoffice' POST '/member-function' @{ code = 'CLIENT'; name = 'Client'; displayOrder = 1 }
}
$programCode = "L2-$Run"
$program = Api 'headoffice' POST '/loyalty/programs' @{ programCode = $programCode; name = "L2 $Run"; pointsPerDinar = 1; pointValueMillimes = $PointValue; minimumRedemptionPoints = 10; maximumRedemptionPercentage = 100 }
Say "   setup: head office program $programCode ($($program.Status)); store C restarted with local loyalty"
Heartbeat 'store-b'

# ─── 1. Local loyalty at C ───────────────────────────────────────
$localProgram = Api 'store-c' POST '/loyalty/programs' @{ programCode = "C-LOCAL-$Run"; name = "C local $Run"; pointsPerDinar = 1; pointValueMillimes = $PointValue; minimumRedemptionPoints = 10; maximumRedemptionPercentage = 100 }
$c1 = Enrol 'store-c' 'LOCAL1' (Phone 1)
$c2 = Enrol 'store-c' 'LOCAL2' (Phone 2)
$dup = Enrol 'store-c' 'LOCAL1' (Phone 1)
$earn1 = Sale 'store-c' $c1.Body.id 0
$spend1 = Sale 'store-c' $c1.Body.id 100
$bal1 = Points 'store-c' $c1.Body.cardNumber
$ok = $localProgram.Status -eq 201 -and $c1.Body.cardNumber -match '^LYL-\d{6}$' -and $c2.Body.cardNumber -match '^LYL-\d{6}$' -and
	$dup.Status -eq 409 -and (@($dup.Body.PSObject.Properties.Name) -join ',') -eq 'error' -and
	$earn1.Body.loyaltyPointsEarned -eq 349 -and $spend1.Body.loyaltyPointsRedeemed -eq 100 -and $bal1 -eq (349 - 100 + $spend1.Body.loyaltyPointsEarned)
Result 1 $ok "local loyalty at C: cards $($c1.Body.cardNumber), $($c2.Body.cardNumber); duplicate 409 body {$(@($dup.Body.PSObject.Properties.Name) -join ',')}; earned $($earn1.Body.loyaltyPointsEarned), spent $($spend1.Body.loyaltyPointsRedeemed) (+$($spend1.Body.loyaltyPointsEarned)), balance $bal1"
if ($StopAfter -le 1) { return }

# ─── 2. Switch C to the head office ──────────────────────────────
$switchAt = Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'
Restart 'store-c'
Heartbeat 'store-c'
Pull 'store-c'
Pull 'store-c'
$rows = @(Sql 'store-c' "SELECT error FROM hol_exchange_log WHERE job = 'COPIES_DOWN' AND error LIKE 'LOYALTY: % local records set inactive%' AND exchange_date >= '$switchAt'")
$again = Enrol 'store-c' 'AGAIN' (Phone 1)
$ok = -not (Active 'store-c' $c1.Body.cardNumber) -and -not (Active 'store-c' $c2.Body.cardNumber) -and $rows.Count -eq 1 -and
	$again.Status -eq 200 -and $again.Body.cardNumber -match '^LYL-STORE-C-\d{6}$'
Result 2 $ok "C switched: old cards inactive ($(Active 'store-c' $c1.Body.cardNumber), $(Active 'store-c' $c2.Body.cardNumber)); log rows $($rows.Count) [$($rows -join ' / ')]; phone $(Phone 1) enrolled again as $($again.Body.cardNumber) ($($again.Status))"

# ─── 3. Program at B and C, writes refused ───────────────────────
Pull 'store-b'; Pull 'store-c'
$pb = Scalar 'store-b' "SELECT CAST(active AS varchar) + '|' + ISNULL(origin, '') FROM loyalty_program WHERE program_code = '$programCode'"
$pc = Scalar 'store-c' "SELECT CAST(active AS varchar) + '|' + ISNULL(origin, '') FROM loyalty_program WHERE program_code = '$programCode'"
$activeC = Scalar 'store-c' "SELECT CAST(COUNT(*) AS varchar) FROM loyalty_program WHERE active = 1"
$wb = Api 'store-b' POST '/loyalty/programs' @{ programCode = "X-$Run"; name = 'x' }
$wc = Api 'store-c' PUT "/loyalty/programs/1" @{ name = 'x' }
Result 3 ($pb -eq '1|HEAD_OFFICE' -and $pc -eq '1|HEAD_OFFICE' -and $activeC -eq '1' -and $wb.Status -eq 409 -and $wc.Status -eq 409) "program $programCode at B [$pb] and C [$pc], active programs at C $activeC; program writes: B $($wb.Status), C $($wc.Status) ($($wb.Body.error))"

# ─── 4. Enrol at B, known everywhere ─────────────────────────────
$m4 = Enrol 'store-b' 'BRAVO' (Phone 4)
$card4 = $m4.Body.cardNumber
Sync
$ok = $m4.Status -eq 200 -and $card4 -match '^LYL-STORE-B-\d{6}$' -and $null -ne (Points 'headoffice' $card4) -and (Active 'store-c' $card4)
Result 4 $ok "enrol at B: $card4; at the head office $($null -ne (Points 'headoffice' $card4)); at C active $(Active 'store-c' $card4)"

# ─── 5. Same phone at C ──────────────────────────────────────────
$m5 = Enrol 'store-c' 'BRAVO' (Phone 4)
$search = @((Api 'store-c' GET "/loyalty/member/search?q=$(Phone 4)").Body)
$ok = $m5.Status -eq 409 -and $m5.Body.existingCardNumber -eq $card4 -and $m5.Body.existingCardActive -eq $true -and
	$m5.Body.error -like "*$card4*" -and $search.Count -eq 1 -and $search[0].cardNumber -eq $card4
Result 5 $ok "enrol at C with the same phone: $($m5.Status) existingCardNumber $($m5.Body.existingCardNumber), active $($m5.Body.existingCardActive); found at C: $(($search | ForEach-Object { $_.cardNumber }) -join ',')"

# ─── 6. Head office stopped: same phone at B and C, merged ───────
StopHo
$b6 = Enrol 'store-b' 'MERGE' (Phone 6)
$null = Sale 'store-b' $b6.Body.id 0
$c6 = Enrol 'store-c' 'MERGE' (Phone 6)
$null = Sale 'store-c' $c6.Body.id 0
StartHo
Sync
$bal6 = Balances $b6.Body.cardNumber
$ok = $b6.Status -eq 200 -and $c6.Status -eq 200 -and $bal6[0] -eq 698 -and (Same $bal6) -and -not (Active 'store-c' $c6.Body.cardNumber) -and
	(Active 'store-c' $b6.Body.cardNumber) -and $null -eq (Points 'headoffice' $c6.Body.cardNumber) -and
	(Scalar 'headoffice' "SELECT CAST(COUNT(*) AS varchar) FROM ho_loyalty_alias WHERE card_number = '$($c6.Body.cardNumber)'") -eq '1'
Result 6 $ok "offline at B $($b6.Body.cardNumber) and C $($c6.Body.cardNumber), 349 each; after sync surviving $($b6.Body.cardNumber) HO/B/C $($bal6 -join '/'); C card inactive $(-not (Active 'store-c' $c6.Body.cardNumber)), alias at the head office"

# ─── 7. Earn at B, known at C ────────────────────────────────────
$id4b = (Card 'store-b' $card4).id
$s7 = Sale 'store-b' $id4b 0
Push 'store-b'; Pull 'store-c'
$ledger = Scalar 'headoffice' "SELECT TOP 1 t.created_by + '|' + t.description FROM loyalty_transaction t JOIN loyalty_member m ON m.id = t.loyalty_member_id WHERE m.card_number = '$card4' AND t.type = 'EARNED' ORDER BY t.id DESC"
$bal7 = @((Points 'headoffice' $card4), (Points 'store-c' $card4))
$ok = $s7.Status -eq 200 -and $ledger -like "STORE:STORE-B|Store STORE-B, sale #$($s7.Body.salesNumber)*" -and $bal7[0] -eq 349 -and $bal7[1] -eq 349
Result 7 $ok "earned $($s7.Body.loyaltyPointsEarned) at B ($($s7.Body.salesNumber)); ledger [$ledger]; HO/C $($bal7 -join '/')"

# ─── 8. Spend at C what was earned at B ──────────────────────────
$id4c = (Card 'store-c' $card4).id
$s8 = Sale 'store-c' $id4c 200
Sync
$bal8 = Balances $card4
$ok = $s8.Status -eq 200 -and $s8.Body.loyaltyPointsRedeemed -eq 200 -and (Same $bal8) -and $bal8[0] -eq (349 - 200 + $s8.Body.loyaltyPointsEarned)
Result 8 $ok "spent 200 at C (+$($s8.Body.loyaltyPointsEarned) earned); HO/B/C $($bal8 -join '/')"

# ─── 9. Fresh balance ────────────────────────────────────────────
$f9 = (Api 'store-c' GET "/loyalty/member/$id4c/fresh").Body
StopHo
$f9off = (Api 'store-c' GET "/loyalty/member/$id4c/fresh").Body
$s9 = Sale 'store-c' $id4c 50
StartHo
Sync
$ok = $f9.fresh -eq $true -and $f9off.fresh -eq $false -and $f9off.canRedeem -eq $true -and $s9.Status -eq 200 -and (Same (Balances $card4))
Result 9 $ok "fresh online $($f9.fresh); stopped fresh $($f9off.fresh), canRedeem $($f9off.canRedeem) [$($f9off.message)]; spending while stopped $($s9.Status); HO/B/C $((Balances $card4) -join '/')"

# ─── 10. Strict store C ──────────────────────────────────────────
$null = SetStore 'STORE-C' @{ redeemRequiresOnline = $true }
Heartbeat 'store-c'
$net10 = (Api 'store-c' GET '/loyalty/network').Body
StopHo
$id6c = (Card 'store-c' $b6.Body.cardNumber).id   # never refreshed at C: not fresh
$f10 = (Api 'store-c' GET "/loyalty/member/$id6c/fresh").Body
Start-Sleep -Seconds 1
$refused = Sale 'store-c' $id6c 50
$noPoints = Sale 'store-c' $id6c 0
StartHo
Heartbeat 'store-c'
$f10b = (Api 'store-c' GET "/loyalty/member/$id6c/fresh").Body
$allowed = Sale 'store-c' $id6c 50
$null = SetStore 'STORE-C' @{ redeemRequiresOnline = $false }
Heartbeat 'store-c'
Sync
$ok = $net10.redeemRequiresOnline -eq $true -and $f10.canRedeem -eq $false -and $refused.Status -eq 409 -and $noPoints.Status -eq 200 -and
	$f10b.fresh -eq $true -and $f10b.canRedeem -eq $true -and $allowed.Status -eq 200
Result 10 $ok "strict C: canRedeem stopped $($f10.canRedeem); spending $($refused.Status) [$($refused.Raw)]; sale without points $($noPoints.Status); back: fresh $($f10b.fresh), spending $($allowed.Status)"

# ─── 11. Overspend ───────────────────────────────────────────────
$beforeCount = (Api 'headoffice' GET '/admin/headoffice/loyalty/overspends/count').Body
$h11 = Api 'headoffice' POST '/loyalty/member' @{ firstName = 'OVER'; lastName = 'L2'; phone = (Phone 9); memberFunctionId = (FunctionId 'headoffice') }
$card11 = $h11.Body.cardNumber
$adj11 = Api 'headoffice' POST "/loyalty/member/$($h11.Body.id)/adjust" @{ delta = 34850; reason = 'L2 overspend' }
Pull 'store-b'; Pull 'store-c'
StopHo
$sb = Sale 'store-b' (Card 'store-b' $card11).id 34850
$sc = Sale 'store-c' (Card 'store-c' $card11).id 34850
StartHo
Sync
$bal11 = Balances $card11
$rows11 = (Api 'headoffice' GET "/admin/headoffice/loyalty/overspends?search=$card11").Body
$afterCount = (Api 'headoffice' GET '/admin/headoffice/loyalty/overspends/count').Body
$ok = $adj11.Status -eq 200 -and $sb.Status -eq 200 -and $sc.Status -eq 200 -and [int]$sb.Body.loyaltyPointsEarned -eq 0 -and $bal11[0] -eq 0 -and (Same $bal11) -and
	$rows11.totalElements -eq 1 -and $rows11.content[0].overspendPoints -eq 34850 -and
	$afterCount.count -eq ($beforeCount.count + 1) -and $afterCount.points -eq ($beforeCount.points + 34850)
Result 11 $ok "$card11 with 34850 spent at B and at C while stopped; HO/B/C $($bal11 -join '/'); overspend rows $($rows11.totalElements) with $($rows11.content[0].overspendPoints) points from $($rows11.content[0].storeCode); count $($beforeCount.count)->$($afterCount.count), points $($beforeCount.points)->$($afterCount.points)"

# ─── 12. Returns at B ────────────────────────────────────────────
$s12 = Sale 'store-b' $id4b 100 2
Sync
$after12 = @(Balances $card4)
$r1 = ReturnItems 'store-b' $s12.Body.salesNumber 1
Sync
$afterR1 = @(Balances $card4)
$r2 = ReturnItems 'store-b' $s12.Body.salesNumber 1
Sync
$afterR2 = @(Balances $card4)
$ok = $s12.Status -eq 200 -and $r1.Status -eq 200 -and $r2.Status -eq 200 -and (Same $after12) -and (Same $afterR1) -and (Same $afterR2) -and
	$afterR2[0] -eq ($after12[0] + 100 - $s12.Body.loyaltyPointsEarned)
Result 12 $ok "sale $($s12.Body.salesNumber) spent 100, earned $($s12.Body.loyaltyPointsEarned): HO/B/C $($after12 -join '/'); partial return $($r1.Status): $($afterR1 -join '/'); full $($r2.Status): $($afterR2 -join '/')"

# ─── 13. Rights from B ───────────────────────────────────────────
$editBody = @{ firstName = 'BRAVO'; lastName = "RIGHTS-$Run"; phone = (Phone 4); memberFunctionId = (FunctionId 'store-b') }
$e403 = Api 'store-b' PUT "/loyalty/member/$id4b" $editBody
$a403 = Api 'store-b' POST "/loyalty/member/$id4b/adjust" @{ delta = 10; reason = 'L2 right' }
$null = SetStore 'STORE-B' @{ canEditMembers = $true; canAdjustPoints = $true }
Heartbeat 'store-b'
$before13 = Points 'headoffice' $card4
$e200 = Api 'store-b' PUT "/loyalty/member/$id4b" $editBody
$a200 = Api 'store-b' POST "/loyalty/member/$id4b/adjust" @{ delta = 10; reason = 'L2 right' }
Pull 'store-c'
$lastC = Scalar 'store-c' "SELECT last_name FROM loyalty_member WHERE card_number = '$card4'"
StopHo
$e503 = Api 'store-b' PUT "/loyalty/member/$id4b" $editBody
$a503 = Api 'store-b' POST "/loyalty/member/$id4b/adjust" @{ delta = 10; reason = 'L2 right' }
StartHo
$null = SetStore 'STORE-B' @{ canEditMembers = $false; canAdjustPoints = $false }
$bal13 = Balances $card4
$ok = $e403.Status -eq 403 -and $a403.Status -eq 403 -and $e200.Status -eq 200 -and $a200.Status -eq 200 -and $lastC -eq "RIGHTS-$Run" -and
	$bal13[0] -eq ($before13 + 10) -and (Same $bal13) -and $e503.Status -eq 503 -and $a503.Status -eq 503
Result 13 $ok "without the right: edit $($e403.Status), adjust $($a403.Status); with it: $($e200.Status), $($a200.Status), at C '$lastC', HO/B/C $($bal13 -join '/'); head office stopped: $($e503.Status), $($a503.Status)"

# ─── 14. Changes made at the head office ─────────────────────────
$hoId = (Card 'headoffice' $card4).id
$hoEdit = Api 'headoffice' PUT "/loyalty/member/$hoId" @{ firstName = 'BRAVO'; lastName = "HO-$Run"; phone = (Phone 4); memberFunctionId = (FunctionId 'headoffice') }
$hoAdj = Api 'headoffice' POST "/loyalty/member/$hoId/adjust" @{ delta = 5; reason = 'L2 head office' }
Pull 'store-b'; Pull 'store-c'
$names14 = @((Scalar 'store-b' "SELECT last_name FROM loyalty_member WHERE card_number = '$card4'"), (Scalar 'store-c' "SELECT last_name FROM loyalty_member WHERE card_number = '$card4'"))
$bal14 = Balances $card4
$ok = $hoEdit.Status -eq 200 -and $hoAdj.Status -eq 200 -and $names14[0] -eq "HO-$Run" -and $names14[1] -eq "HO-$Run" -and (Same $bal14) -and $bal14[0] -eq ($bal13[0] + 5)
Result 14 $ok "edit and +5 at the head office: names at B/C $($names14 -join '/'), HO/B/C $($bal14 -join '/')"

# ─── 15. Exchange log during a head office stop ──────────────────
Heartbeat 'store-b'; foreach ($job in 'SALES_PUSH', 'COPIES_DOWN', 'LOYALTY_PUSH') { $null = RunJob 'store-b' $job }   # close any earlier episode
$t15 = Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'
StopHo
$null = Sale 'store-b' $id4b 0
Start-Sleep -Seconds 31   # the sales push waits 30 s after a change
foreach ($i in 1, 2) { Heartbeat 'store-b'; foreach ($job in 'SALES_PUSH', 'COPIES_DOWN', 'LOYALTY_PUSH') { $null = RunJob 'store-b' $job } }
StartHo
Heartbeat 'store-b'; foreach ($job in 'SALES_PUSH', 'COPIES_DOWN', 'LOYALTY_PUSH') { $null = RunJob 'store-b' $job }
$log15 = @(Sql 'store-b' "SELECT job + '|' + result FROM hol_exchange_log WHERE exchange_date >= '$t15' ORDER BY id")
$ok = $true
$detail = @()
foreach ($job in 'HEARTBEAT', 'SALES_PUSH', 'COPIES_DOWN', 'LOYALTY_PUSH') {
	$jobRows = @($log15 | Where-Object { $_.StartsWith("$job|") })
	$errors = @($jobRows | Where-Object { $_ -eq "$job|ERROR" }).Count
	$after = if ($jobRows.Count -gt 0) { $jobRows[-1] } else { '' }
	$good = $errors -eq 1 -and $after -ne "$job|ERROR" -and $jobRows.Count -ge 2
	$ok = $ok -and $good
	$detail += "$job $errors error / $($jobRows.Count) rows, last $($after.Split('|')[1])"
}
Result 15 $ok ($detail -join '; ')

# ─── 16. Sales copies at the head office ─────────────────────────
Start-Sleep -Seconds 31
foreach ($inst in 'store-b', 'store-c') { $null = RunJob $inst 'SALES_PUSH'; $null = RunJob $inst 'SALES_PUSH' }
$missing = @()
foreach ($s in $Sales) {
	$row = Scalar 'headoffice' "SELECT ISNULL(t.loyalty_card_number, '-') + '|' + CAST(ISNULL(t.loyalty_points_earned, 0) AS varchar) + '|' + CAST(ISNULL(t.loyalty_points_redeemed, 0) AS varchar) + '|' + st.code FROM ho_ticket t JOIN ho_store st ON st.id = t.store_id WHERE t.sales_number = '$($s.Number)'"
	$expectedStore = if ($s.Store -eq 'store-b') { 'STORE-B' } else { 'STORE-C' }
	if (-not $row) { $missing += "$($s.Number) absent"; continue }
	$parts = $row.Split('|')
	if ($parts[0] -eq '-' -or [int]$parts[1] -ne [int]$s.Earned -or [int]$parts[2] -ne [int]$s.Redeemed -or $parts[3] -ne $expectedStore) { $missing += "$($s.Number) [$row]" }
}
Result 16 ($missing.Count -eq 0) "$($Sales.Count) tickets of B and C at the head office with their loyalty fields$(if ($missing) { '; problems: ' + ($missing -join ', ') })"

# ─── 17. Enrol switch at B ───────────────────────────────────────
$null = SetStore 'STORE-B' @{ enrolRequiresOnline = $true }
Heartbeat 'store-b'
$net17 = (Api 'store-b' GET '/loyalty/network').Body
StopHo
$e17 = Enrol 'store-b' 'STRICT' (Phone 7)
$s17 = Sale 'store-b' $id4b 0
StartHo
Heartbeat 'store-b'
$e17b = Enrol 'store-b' 'STRICT' (Phone 7)
$null = SetStore 'STORE-B' @{ enrolRequiresOnline = $false }
Heartbeat 'store-b'
$ok = $net17.enrolRequiresOnline -eq $true -and $e17.Status -eq 503 -and $e17.Body.error -like 'The head office cannot be reached*' -and
	$null -eq (Scalar 'store-b' "SELECT card_number FROM loyalty_member WHERE phone = '$(Phone 7)' AND card_number <> '$($e17b.Body.cardNumber)'") -and
	$s17.Status -eq 200 -and $e17b.Status -eq 200 -and $e17b.Body.cardNumber -match '^LYL-STORE-B-\d{6}$'
Result 17 $ok "strict enrol at B: network flag $($net17.enrolRequiresOnline); head office stopped: enrol $($e17.Status) [$($e17.Body.error)], sale $($s17.Status); back: enrol $($e17b.Status) $($e17b.Body.cardNumber)"

Say ''
Say ("{0} passed, {1} failed. Report: {2}" -f @($Results | Where-Object { $_.StartsWith('PASS') }).Count, @($Results | Where-Object { $_.StartsWith('FAIL') }).Count, $Report)
