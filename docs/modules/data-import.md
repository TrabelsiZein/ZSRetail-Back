# Data Import

**Status**: ✅ Complete (Standalone mode only)

**Overview:**
- Admin-only wizard for bulk-importing data from Excel files (`.xlsx` / `.xls`)
- Visible and accessible **only in standalone mode** (menu hidden + route guarded in ERP mode)
- Supports 5 entity types: **Item Families**, **Item Subfamilies**, **Items/Products**, **Vendors**, **Customers**
- Uses **Apache POI** (already in `pom.xml` at version 5.0.0) for Excel parsing
- **Upsert logic**: updates existing records if the unique code already exists, creates new ones otherwise — safe for re-imports

### UI — 4-Step Wizard (`DataImport.vue`)

**Step 1 — Entity Selection**
- 5 responsive cards with Feather icons and descriptions
- Selected card highlighted with primary color border and check icon

**Step 2 — File Upload**
- Drag-and-drop zone (accepts `.xlsx`/`.xls` only)
- On file drop/select, immediately calls `POST /admin/import/preview` to analyze the file
- Shows: total row count, column count, all detected column names as badge chips

**Step 3 — Field Mapping (configurable core)**
- Table: `DB Field` (label + Required/Optional badge) | `→` | `Excel Column` dropdown | `Actions`
- Columns auto-matched by name similarity on entry into this step
- Optional fields can be deleted (removed from table) — they appear as restore buttons below
- "Restore Defaults" button resets to auto-matched state
- Required fields cannot be deleted or left unmapped (validation blocks "Next")

**Step 4 — Preview & Execute**
- Scrollable preview table showing first 5 rows mapped to DB fields
- Import summary card: entity type, total rows, mapped fields count
- "Execute Import" button calls `POST /admin/import/execute` with file + mapping JSON
- After import: success count (green), error count (red), total count; collapsible per-row error list
- "Import Another File" button resets the wizard to Step 1

### Backend — New Files

- **`DataImportAPI.java`** (`controller/`) — Two endpoints, both return HTTP 403 if `!applicationModeService.isStandalone()`:
  - `POST /admin/import/preview` — multipart: `file` → returns `ImportPreviewDTO`
  - `POST /admin/import/execute` — multipart: `file` + `entityType` + `mapping` (JSON string) → returns `ImportResultDTO`
- **`DataImportService.java`** (`service/`) — Excel parsing and upsert logic for all 5 entity types; resolves FK for subfamilies (`familyCode` → `ItemFamily`) and items (`familyCode`, `subFamilyCode`)
- **`ImportPreviewDTO.java`** — `{ columns: List<String>, rows: List<List<String>>, totalRows: int }`
- **`ImportFieldMappingDTO.java`** — `{ dbField: String, excelColumn: String }`
- **`ImportResultDTO.java`** — `{ totalRows, successCount, errorCount, errors: List<{ row, message }> }`

### Importable Fields per Entity

| Entity | Required | Optional |
|---|---|---|
| **Families** | `code`, `name` | `description`, `displayOrder` |
| **Subfamilies** | `code`, `name`, `familyCode` | `description`, `displayOrder` |
| **Items** | `itemCode`, `name`, `unitPrice` | `defaultVAT`, `costPrice`, `stockQuantity`, `minStockLevel`, `barcode`, `unitOfMeasure`, `category`, `brand`, `familyCode`, `subFamilyCode`, `description` |
| **Vendors** | `vendorCode`, `name`, `phone` | `email`, `address`, `city`, `country`, `taxId`, `notes` |
| **Customers** | `customerCode`, `name`, `phone` | `email`, `address`, `city`, `country`, `taxId`, `creditLimit`, `notes` |

### Frontend — Modified Files

- **`navigation/vertical/index.js`** — Added `admin.dataImportMenu` entry (`DownloadCloudIcon`) under the Administration group
- **`VerticalNavMenu.vue`** — Added `admin-data-import` to `hideWhenNotStandalone` filter list so the menu entry is hidden in ERP mode
- **`router/index.js`** — Added route `/admin/data-import` (`requiredRole: 'ADMIN'`) + added `admin-data-import` to the standalone guard block
- **`Login.vue`** — Added `read` and `write` abilities for `admin-data-import` to ADMIN role only
- **`en.json` / `fr.json` / `ar.json`** — Added `admin.dataImport.*` keys (all wizard steps, entity names, field labels, errors, results) and `common.next` key in all 3 languages
