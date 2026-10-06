# Inventory count (Inventaire)

**Status**: backend done (2.1.0, first version); frontend to do.

A store that keeps its own stock imports an Excel file of a physical count, sees the differences with the stock, and
validates: the stock of each counted item becomes the counted quantity, with one stock movement per difference.

## Where it exists

Only on a **store whose supply is not the ERP's** (`ApplicationModeService`: not `isHeadOffice()`, not
`isSupplyFromErp()`). That includes a store whose goods come from its head office by BL: it keeps its own stock. No new
property, no setting. Elsewhere every endpoint answers **403** `{"error": "Inventory counts exist only on a store that
keeps its own stock."}`; the tables exist, empty.

Nothing in the sale, return, purchase or BL paths changed, nor in `holink`: on a store linked to a head office, the
stock job sends the changed quantities as for any other stock change (`StockCopyRepository.findToSend`).

## Package

`com.digithink.zsretail.inventory`

| Class | Role |
|---|---|
| `model/InventoryCount` | `inventory_count`: number, count date, note, file name, rows read, status, validated at / by |
| `model/InventoryCountLine` | `inventory_count_line`: one item (its rows merged) or one unknown code |
| `enumeration/InventoryCountStatus` | `DRAFT`, `VALIDATED` |
| `enumeration/InventoryLineStatus` | `OK`, `NOT_FOUND`, `NOT_COUNTED`, `BAD_QUANTITY` |
| `service/InventoryFileReader` | Reads the first sheet with POI (pure, no Spring) |
| `service/InventoryCountService` | Import, summary, lines, validation, delete |
| `repository/InventoryCountRepository` | JPA: list, lock, conditional `DRAFT` → `VALIDATED` |
| `repository/InventoryCountLineRepository` | JPQL reads for the screen (lines page, summary) |
| `repository/InventoryLineStore` | JDBC batch writes of the lines (1,000 per batch) |
| `controller/InventoryCountAPI` | REST, `admin/inventory-counts` |

Shared code that changed: `StockService.applyInventoryDifferences`, `StockMovementService.recordInventory`, the new
`repository/StockBatchRepository` (JDBC batches of the stock updates and of the movement rows),
`StockMovementType.INVENTORY_IN` / `INVENTORY_OUT`, `ItemRepository.findInventorySnapshot` / `findStockByIds`,
`ItemBarcodeRepository.findActiveBarcodeItemIds`, `CashierSessionRepository.countByStatus`.

## Tables

`inventory_count`: `number` varchar(30) unique (`uk_inventory_count_number`), `count_date` date, `note` varchar(500),
`file_name` varchar(255), `rows_read` int, `status` varchar(20), `validated_at` datetime2, `validated_by` varchar(100),
plus the `_BaseEntity` columns.

`inventory_count_line`: `count_id` (FK `fk_inventory_count_line_count`, index `ix_inventory_count_line_count`),
`item_id` (no foreign key: a draft never blocks the deletion of an item), `code` varchar(100) as read in the file,
`counted_quantity`, `merged_rows`, `system_quantity_at_import`, `system_quantity_at_validation`, `difference_applied`
(all int), `status` varchar(20), `message` varchar(255).

Created by `db/2.1.0/update.sql` (guarded, re-runnable) with the names Hibernate uses; `ddl-auto` creates the same
tables when the script was not run.

## The file

- `.xlsx` or `.xls`, **first sheet**, column **A** = barcode or item code, column **B** = counted quantity.
- Empty rows are skipped. The first row with content is skipped when its column B is not a number (a header).
- A numeric cell in column A is read as a plain whole number text: an EAN-13 typed as a number gives `6191234567890`,
  never `6.19E+12` nor `6191234567890.0`. Text is trimmed (a non-breaking space counts as a space). An EAN that starts
  with 0 and was typed as a number has lost that 0 in Excel: it is not found.
- Quantity: a whole number ≥ 0, numeric cell or text (`12`, `12.0`, `4,0`). Otherwise the row keeps its reason: `no
  quantity`, `not a number: x`, `negative quantity: -2`, `not a whole number: 1.5`, `quantity too large`.
- At most 100,000 rows (30,000 expected).

## Import

1. The file is read **before** the transaction (POI).
2. Every item `[id, code, type, stock]` and every active barcode (`active` true or null) are loaded once. The
   barcode filter on price of `ItemBarcodeService.getItemByBarcode` is **not** used.
3. Each code is looked up as a **barcode first, then as an item code**, without case (SQL Server compares codes
   without case).
4. Rows of the same item are merged into one line (quantities added, `merged_rows` counted), whatever code found them.
   Unknown codes are merged by value.
5. Line status:
   - `NOT_FOUND`: no barcode nor item code (`No code` when column A is empty).
   - `NOT_COUNTED`: an item of type `SERVICE` or `DISCOUNT` (same rule as the stock sent to the head office: a product,
     a pack or no type carry a stock).
   - `BAD_QUANTITY`: one of its rows has an invalid quantity (message `row 5: not a number: x; …`), or the total is
     above the int range. The whole item is not applied: a partial sum would be wrong.
   - `OK` otherwise; `system_quantity_at_import` = the stock read in step 2 (null counts as 0).
6. In one transaction: the header (`DRAFT`, number `INV-YYYYMM-000001`, the suffix following the last count's), then
   the lines in JDBC batches. One INFO log line: number, rows, lines per status, differences, duration.

Importing again a draft (`POST /{id}/file`) locks the count, deletes its lines and writes the new ones. A validated
count: 409.

## Validation

One transaction (timeout 600 s), all or nothing:

1. `DRAFT` → `VALIDATED` with `validated_at`, `validated_by` in **one conditional update** (`where status = DRAFT`).
   0 rows: 409 `This count is already validated: INV-….` — a second click waits for the first one's row lock, then
   finds it validated and changes nothing (measured: two simultaneous requests gave 200 and 409).
2. The `OK` lines are read; the stock of their items is read once (2,000 ids per query).
3. Per line: difference = counted − stock read. The differences go through `StockService.applyInventoryDifferences`:
   the same atomic relative update as every stock change (`stock_quantity = COALESCE(stock_quantity, 0) + delta`), in
   JDBC batches, zero differences skipped. Relative, not absolute: a sale between the read and the update stays
   counted, and the movements always add up to the stock. The screen shows the number of open cashier sessions as a
   warning; it blocks nothing.
4. `StockMovementService.recordInventory`: one movement per non-zero difference, `INVENTORY_IN` (direction `IN`) or
   `INVENTORY_OUT` (`OUT`), quantity = |difference|, `reference_type` `INVENTORY`, `reference_id` = the count id,
   `notes` = the count number, `created_by` = the user. No price.
5. Each `OK` line stores `system_quantity_at_validation` and `difference_applied` (0 when equal). An item deleted since
   the import: not applied, message `Item deleted since the import: not applied`.
6. One INFO log line: number, user, lines applied, differences, quantity up / down, duration.

Items not in the file are never touched. `NOT_FOUND`, `NOT_COUNTED` and `BAD_QUANTITY` lines are never applied and
never block the others. A validated count is never changed nor deleted.

## API — `admin/inventory-counts`

JWT like the other admin APIs. Errors `{"error": "…"}`: 400 invalid file or parameter, 403 not available here, 404
unknown count, 409 a validated count.

| Method | Path | Body / parameters | Answer |
|---|---|---|---|
| GET | `/admin/inventory-counts` | `page` (0), `size` (50, max 500) | page of counts, newest first |
| POST | `/admin/inventory-counts` | multipart `file`, `countDate` (yyyy-MM-dd, today when absent), `note` (≤ 500) | the draft with its summary |
| GET | `/admin/inventory-counts/{id}` | | the count with its summary |
| POST | `/admin/inventory-counts/{id}/file` | multipart `file` | the draft with its summary (lines replaced) |
| GET | `/admin/inventory-counts/{id}/lines` | `filter` all \| differences \| problems, `search`, `page`, `size` | page of lines |
| POST | `/admin/inventory-counts/{id}/validate` | | the validated count with its summary |
| DELETE | `/admin/inventory-counts/{id}` | | 204 (a draft only) |

Page: `{content, totalElements, totalPages, number, size}`.

Count:

```json
{
  "id": 1, "number": "INV-202610-000001", "countDate": "2026-10-06", "note": "Annual count",
  "fileName": "count.xlsx", "status": "DRAFT", "rowsRead": 25000,
  "createdAt": "2026-10-06 22:22:47", "createdBy": "admin", "validatedAt": null, "validatedBy": null,
  "summary": {
    "rowsRead": 25000, "lines": 25000, "linesOk": 25000, "linesNotFound": 0, "linesNotCounted": 0,
    "linesBadQuantity": 0, "linesWithDifference": 15963, "quantityUp": 19639, "quantityDown": 13002,
    "openCashierSessions": 1
  }
}
```

The list has no `summary`. For a draft the differences of the summary and of the lines are against the **stock now**;
for a validated count, what was applied.

Line:

```json
{
  "id": 1, "code": "2990000000001", "itemId": 3190, "itemCode": "PERF-000001", "itemName": "Perf item 1",
  "status": "OK", "countedQuantity": 0, "mergedRows": 1, "systemQuantityAtImport": 1,
  "systemQuantity": 1, "difference": -1, "message": null
}
```

`systemQuantity`: the stock now (draft) or at the validation (validated); `difference`: counted − `systemQuantity`
for an OK line of a draft, `difference_applied` once validated, null otherwise. Filter `differences`: OK lines with a
difference; `problems`: every line that is not OK. `search`: contains, without case, on the code as read, the item
code and the item name.

## Permissions

`read:admin-inventory-counts`, `write:admin-inventory-counts` (`ZZDataInitializer.INVENTORY_ADMIN_PERMISSIONS`, listed
by `GET /app-roles/permissions`). Given to ADMIN on a new database and added at every start (`addMissingPermissions`)
on a store whose supply is not the ERP's; never on a head office nor on an ERP store. Other roles untouched. As
everywhere, the API does not check them (the frontend menu and route do).

## Volume (measured 2026-10-06)

A copy of the dev store C database with 28,183 items and 14,060 barcodes (dev PC, SQL Server local), a file of 25,000
rows (half by barcode as numeric cells, half by item code), 15,963 differences:

| Step | Server (log) | HTTP total |
|---|---|---|
| Import (read the file, load, 25,000 lines in batches) | 3.1 s | 3.7 s |
| Validation (25,000 lines, 15,963 stock updates and movements) | 4.3 s | 4.4 s |
| Lines page (all / differences / search) | | 0.1–0.5 s |

Checked in the database after the validation: every OK item's stock equals its counted quantity, one movement per
non-zero difference with the right type and quantity, the other items unchanged.

## Tests

- `InventoryFileReaderTest`: number cell as code, header and empty rows, bad quantities, `.xls`, not Excel.
- `InventoryCountServiceTest` (real service over `InMemoryInventory`, the stock of `InMemoryStock`): lookup by barcode
  then code, merged rows, the four statuses and the summary; validation (stock = counted, a sale after the import,
  one movement per difference, items not in the file untouched); a second validation 409 with nothing changed;
  validated: no new import, no delete; a draft imported again and deleted; empty file 400; 403 on every endpoint with
  the supply from the ERP and on a head office; available on a store fed by its head office.
- `ZZDataInitializerRolesTest`: the two permissions on a new store database without an ERP, topped up once on an
  existing one, never with an ERP nor on a head office.

## To do (frontend)

The page (list, import, summary with the open sessions warning, lines with the filters, validate, delete), its route
and menu entry with the two permissions, the labels of `INVENTORY_IN` / `INVENTORY_OUT` in the stock movements report
(its type filter is a fixed list in `StockMovementsReport.vue`), and the two permissions on the Roles page.
