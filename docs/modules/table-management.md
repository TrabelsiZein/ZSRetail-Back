# Table Management

**Status**: ✅ Complete (coffee-shop mode)

**Config gate**: `TABLE_MANAGEMENT_ENABLED` in `general_setup` (BOOLEAN, default `false`). When disabled, ALL table management UI and flows are hidden — zero regression for regular POS use.

### Overview

- Cashier opens a **Table Grid** page (instead of going straight to ItemSelection) showing all tables as colored cards.
- Each table = one **PENDING SalesHeader** with `tableNumber` set.
- Regular pending tickets (`tableNumber IS NULL`) remain completely separate — no cross-contamination.
- Supported features: open table, add/update items, pay full bill, transfer table, split bill by item.

### Database

**Migration `025_add_table_management.sql`** (SQL Server T-SQL):
- `ALTER TABLE [sales_header] ADD [table_number] INT NULL`
- Inserts `TABLE_MANAGEMENT_ENABLED=false` (BOOLEAN) and `TABLE_MANAGEMENT_TABLE_COUNT=10` (NUMBER) into `general_setup`

### Backend — Modified Files

- **`model/SalesHeader.java`** — added `@Column(name = "table_number") private Integer tableNumber`
- **`dto/ProcessSaleRequestDTO.java`** — added `private Integer tableNumber`
- **`dto/AppConfigDTO.java`** — added `tableManagementEnabled` (boolean), `tableManagementTableCount` (int)
- **`controller/AppConfigAPI.java`** — reads both new GeneralSetup keys, populates AppConfigDTO
- **`repository/SalesHeaderRepository.java`** — added:
  - `findByCashierSessionAndStatusAndTableNumberIsNull(...)` — for regular pending panel (excludes table tickets)
  - `findByCashierSessionAndStatusAndTableNumberIsNotNull(...)` — for table grid
- **`service/SalesHeaderService.java`**:
  - `savePendingSale()`: sets `tableNumber` from request DTO
  - `getPendingSalesForSession()`: uses `TableNumberIsNull` query (regular pending only)
  - `getTableTicketsForSession()`: uses `TableNumberIsNotNull` query (table tickets only)
  - `updatePendingSale(id, request, user)`: replaces lines + recalculates totals on existing PENDING ticket
  - `transferTable(salesHeaderId, targetTableNumber)`: validates target is free, updates `tableNumber`, saves
  - `splitAndPay(originalId, SplitBillRequestDTO, user)`: creates new COMPLETED sale from selected lines, removes them from original, recalculates/cancels original
- **`controller/SalesHeaderAPI.java`** — added endpoints:
  - `GET /sales-header/table-tickets` — full pending table tickets with salesLines (used by TableSelection)
  - `GET /sales-header/table-status` — lightweight map `tableNumber → { salesHeaderId, totalAmount, salesNumber }`
  - `POST /sales-header/update-pending/{id}` — update lines on existing pending ticket
  - `POST /sales-header/split-and-pay/{id}` — split bill
  - `PATCH /sales-header/{id}/table-number` — table transfer

### Backend — New Files

- **`dto/SplitBillRequestDTO.java`** — contains `selectedLineIds`, totals, `customerId`, and inner `PaymentDTO` list (mirrors `ProcessSaleRequestDTO.PaymentDTO`)

### Frontend — New Files

- **`src/views/pos/TableSelection.vue`** — Full table grid page:
  - Auto-calculating grid columns: `Math.max(2, Math.ceil(Math.sqrt(tableCount * 1.4)))`
  - No-scroll layout: `grid-auto-rows: 1fr; overflow: hidden`
  - Color-coded urgency: blue (<30 min), amber (30–60 min), red + pulse (>60 min) based on `salesHeader.createdAt`
  - Dot-grid background, dashed borders for empty tables, solid for occupied
  - Total revenue badge in header
  - Transfer mode: clicking occupied → action popover (Open / Transfer to…); transfer mode dims occupied, highlights empty; calls `PATCH /sales-header/{id}/table-number`
  - Close session: confirmation modal → navigates to ItemSelection with `?action=closeSession`
  - 30-second timer for elapsed time display
  - Fetches from `GET /sales-header/table-tickets` (NOT `/pending-sales`)
  - When opening occupied table: maps `salesLines` to cart items including `salesLineId`, `lineTotalIncludingVat`, `lineTotal`, `vatAmount` — needed for split bill

- **`src/views/pos/SplitBillModal.vue`** — 2-step split bill modal:
  - Step 1: Line selection with checkboxes, per-line amounts, selected total + remaining on table
  - Step 2: Payment method selection (`GET /payment-method` — singular, not plural) + amount input + change display + confirm
  - Calls `POST /sales-header/split-and-pay/{pendingTicketId}`
  - Emits: `split-done(sale)`, `split-error(message)`, `close`

### Frontend — Modified Files

- **`src/store/app-config/index.js`** — added `tableManagementEnabled`, `tableManagementTableCount` state/getters/mutations; `fetchAppConfig` reads both
- **`src/store/pos/index.js`** — added `selectedTableNumber` state, `SET_SELECTED_TABLE_NUMBER` / `CLEAR_SELECTED_TABLE_NUMBER` mutations, `setSelectedTableNumber` / `clearSelectedTableNumber` actions
- **`src/router/index.js`** — added route `pos-table-selection` → `TableSelection.vue` (requiresAuth, POS_USER role, requiresSession, hideNavMenu)
- **`src/views/pos/OpenSession.vue`** — both redirect points check `isTableManagementEnabled`; go to `pos-table-selection` or `pos-item-selection`
- **`src/views/pos/ItemSelection.vue`**:
  - Table indicator bar above cart (blue gradient, GridIcon) — shown only when table mode enabled AND `selectedTableNumber` set
  - "Back to Tables" button — saves or updates pending ticket if cart has items, then navigates to table grid
  - `mounted()`: detects `?action=closeSession` query param and opens close-session modal
- **`src/views/pos/Payment.vue`**:
  - `selectedTableNumber` and `isTableMode` computed from store
  - Both `saleRequest` objects include `tableNumber: this.selectedTableNumber || null`
  - After payment/save: clears `selectedTableNumber`, redirects to `pos-table-selection` when in table mode
  - "Split Bill" button — shown only when `isTableMode && pendingTicketId`; opens `SplitBillModal`
  - `splitBillLines` computed — maps cart items to SalesLine-like objects using `item.salesLineId` as `id`
  - `onSplitDone(sale)` — prints receipt, shows toast, clears store state, navigates to table grid
  - `onSplitError(msg)` — shows error toast
- **i18n** (`en.json`, `fr.json`, `ar.json`) — added:
  - `pos.tableSelection.*` keys (title, session, refresh, closeSession, loading, empty, occupied, tableLabel, total, items, justNow, action.*, transfer.*, closeConfirm.*)
  - `pos.itemSelection.backToTables`, `pos.itemSelection.tableIndicator`
  - `pos.splitBill.*` keys (title, hint, selected, toPay, remainingOnTable, proceedToPayment, back, amountDue, amountPaid, change, confirmPayment, success, successText, error)

### Key Design Decisions

- **Clean separation**: `TableNumberIsNull` / `TableNumberIsNotNull` Spring Data derived queries guarantee table tickets never appear in the regular pending panel and vice versa
- **No ticket on click**: Table number is stored in Vuex only when opened — backend ticket created only on first "Back to Tables" (auto-save) or on payment; avoids "Sale must have at least one item" error on empty-cart clicks
- **salesLineId in cart**: When loading an occupied table, `salesLineId` (backend SalesLine PK) is stored on each cart item so the split bill modal can send correct line IDs to backend
- **split-and-pay pattern**: No new entity — creates a new COMPLETED SalesHeader from selected lines, removes them from original, recalculates original or cancels if empty
- **Payment method API**: `GET /payment-method` (singular) — NOT `/payment-methods`
