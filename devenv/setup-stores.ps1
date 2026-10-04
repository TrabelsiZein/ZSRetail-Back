# One-time setup of stores B and C (L2 of the head office plan). Run in this order:
#   1. powershell -File devenv\setup-stores.ps1 -Phase databases   # creates pos_store_b, pos_store_c (empty)
#   2. build.ps1, start.ps1 -Instance headoffice
#   3. powershell -File devenv\setup-stores.ps1 -Phase register    # STORE-B, STORE-C at the head office; keys into their machine files
#   4. start.ps1 -Instance store-b,store-c (Hibernate creates the tables; the machine files are read at start, no rebuild)
#   5. powershell -File devenv\setup-stores.ps1 -Phase seed        # code, loyalty on, items, license, member function
# pos_db_prod (store A) is only read (items and families copied by SQL), never written.
param([Parameter(Mandatory = $true)][ValidateSet('databases', 'register', 'seed')][string]$Phase,
	[string[]]$Store = @('store-b', 'store-c'))
. "$PSScriptRoot\api.ps1"

$LicenseFile = Join-Path $DevRepo 'LicenseGenerator\license.json'

switch ($Phase) {
	'databases' {
		foreach ($name in $Store) {
			$db = (Get-DevInstance $name).Db
			Invoke-DevSql $name "IF DB_ID('$db') IS NULL CREATE DATABASE [$db]; SELECT name FROM sys.databases WHERE name = '$db'" 'master'
		}
	}

	'register' {
		$token = Get-DevToken 'headoffice'
		$existing = (Invoke-DevApi 'headoffice' GET '/admin/headoffice/stores' $null $token).Body
		foreach ($name in $Store) {
			$inst = Get-DevInstance $name
			$found = @($existing | Where-Object { $_.code -eq $inst.Code })
			if ($found.Count -gt 0) {
				$answer = Invoke-DevApi 'headoffice' POST "/admin/headoffice/stores/$($found[0].id)/regenerate-key" $null $token
			} else {
				$answer = Invoke-DevApi 'headoffice' POST '/admin/headoffice/stores' @{ code = $inst.Code; name = "Store $($inst.Code.Substring(6))"; kind = 'OWN'; active = $true } $token
			}
			if ($answer.Status -notin 200, 201) { throw "Store $($inst.Code) not registered: $($answer.Status) $($answer.Raw)" }
			$file = Set-DevMachineValue $name 'headoffice.api-key' $answer.Body.apiKey
			Write-Output "$($inst.Code) registered at the head office (id $($answer.Body.store.id)); key written to $file"
		}
		Write-Output 'Now: start.ps1 -Instance store-b,store-c (they read their machine files at start), then -Phase seed'
	}

	'seed' {
		foreach ($name in $Store) {
			$inst = Get-DevInstance $name
			# Store code and loyalty (read at each use: no restart needed)
			Invoke-DevSql $name ("UPDATE general_setup SET valeur = '$($inst.Code)' WHERE code = 'DEFAULT_LOCATION'; " +
				"UPDATE general_setup SET valeur = 'true' WHERE code = 'LOYALTY_ENABLED'; " +
				"SELECT code + '=' + valeur FROM general_setup WHERE code IN ('DEFAULT_LOCATION', 'LOYALTY_ENABLED')")
			# About 20 items with their families and sub-families, copied from pos_db_prod (read only), by code
			$copy = @"
IF OBJECT_ID('tempdb..#src') IS NOT NULL DROP TABLE #src;
SELECT TOP 20 i.id INTO #src FROM pos_db_prod.dbo.item i
 WHERE i.active = 1 AND i.show_in_pos = 1 AND i.unit_price > 0 AND i.item_family_id IS NOT NULL AND i.item_sub_family_id IS NOT NULL
 ORDER BY i.item_code;
INSERT INTO item_family (active, created_at, created_by, updated_at, updated_by, code, description, display_order, erp_external_id, name, image_filename)
 SELECT f.active, SYSDATETIME(), 'L2', SYSDATETIME(), 'L2', f.code, f.description, f.display_order, f.erp_external_id, f.name, f.image_filename
 FROM pos_db_prod.dbo.item_family f
 WHERE f.id IN (SELECT i.item_family_id FROM pos_db_prod.dbo.item i JOIN #src s ON s.id = i.id)
   AND NOT EXISTS (SELECT 1 FROM item_family t WHERE t.code = f.code);
INSERT INTO item_sub_family (active, created_at, created_by, updated_at, updated_by, code, description, display_order, erp_external_id, name, item_family_id, image_filename)
 SELECT sf.active, SYSDATETIME(), 'L2', SYSDATETIME(), 'L2', sf.code, sf.description, sf.display_order, sf.erp_external_id, sf.name,
   (SELECT t.id FROM item_family t JOIN pos_db_prod.dbo.item_family pf ON pf.code = t.code WHERE pf.id = sf.item_family_id), sf.image_filename
 FROM pos_db_prod.dbo.item_sub_family sf
 WHERE sf.id IN (SELECT i.item_sub_family_id FROM pos_db_prod.dbo.item i JOIN #src s ON s.id = i.id)
   AND NOT EXISTS (SELECT 1 FROM item_sub_family t WHERE t.code = sf.code);
INSERT INTO item (active, created_at, created_by, updated_at, updated_by, barcode, brand, category, cost_price, defaultvat, description,
   erp_external_id, image_url, item_code, item_disc_group, maximum_authorized_discount, min_stock_level, name, show_in_pos, stock_quantity,
   type, unit_of_measure, unit_price, item_family_id, item_sub_family_id, franchise_sales_price, last_direct_cost, last_direct_net_cost, from_franchise_admin)
 SELECT i.active, SYSDATETIME(), 'L2', SYSDATETIME(), 'L2', i.barcode, i.brand, i.category, i.cost_price, i.defaultvat, i.description,
   i.erp_external_id, i.image_url, i.item_code, i.item_disc_group, i.maximum_authorized_discount, i.min_stock_level, i.name, i.show_in_pos, 1000,
   i.type, i.unit_of_measure, i.unit_price,
   (SELECT t.id FROM item_family t JOIN pos_db_prod.dbo.item_family pf ON pf.code = t.code WHERE pf.id = i.item_family_id),
   (SELECT t.id FROM item_sub_family t JOIN pos_db_prod.dbo.item_sub_family ps ON ps.code = t.code WHERE ps.id = i.item_sub_family_id),
   i.franchise_sales_price, i.last_direct_cost, i.last_direct_net_cost, 0
 FROM pos_db_prod.dbo.item i JOIN #src s ON s.id = i.id
 WHERE NOT EXISTS (SELECT 1 FROM item t WHERE t.item_code = i.item_code);
SELECT 'families ' + CAST((SELECT COUNT(*) FROM item_family) AS varchar) + ', sub-families ' + CAST((SELECT COUNT(*) FROM item_sub_family) AS varchar)
 + ', items ' + CAST((SELECT COUNT(*) FROM item WHERE created_by = 'L2') AS varchar);
"@
			Invoke-DevSql $name $copy
			# License (machine-bound, the same file as the other instances of this PC)
			$token = Get-DevToken $name
			Write-Output ("license upload: " + ((Send-DevLicense $name $token $LicenseFile) -join ' '))
			$status = Invoke-DevApi $name GET '/license/status' $null $token
			Write-Output "license status: $($status.Body.status)"
			# A member function, required to enrol a member
			$functions = Invoke-DevApi $name GET '/member-function' $null $token
			if (-not (@($functions.Body) | Where-Object { $_.code -eq 'CLIENT' })) {
				$created = Invoke-DevApi $name POST '/member-function' @{ code = 'CLIENT'; name = 'Client'; displayOrder = 1 } $token
				Write-Output "member function CLIENT: $($created.Status)"
			}
		}
	}
}
