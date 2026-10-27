# Admin Management Pages

## Admin Management Pages
- Returns: list with validity, remaining amount, linked tickets, duplicate voucher print.
- Locations: searchable paginated list for admins.
- General Setup: editable when `readOnly` is false, with modal form and validation.
- Item Families/Subfamilies: each on dedicated page with CRUD modals and pagination.
- **Sales Invoices** (`InvoiceManagement.vue`): admin module under the Sales menu group that manages **sales invoices** (formerly just "Invoices"). Provides date range, invoice number, and customer filters, server-side pagination, a detailed invoice modal, and uses `admin.invoiceModule.*` i18n keys (e.g. `title`, `subtitle`, `tabList`, `filters`, `refresh`).
- **Inventory counts** (`InventoryCounts.vue`, `InventoryCountDetail.vue`, routes `admin-inventory-counts` `/admin/inventory-counts` and `admin-inventory-count` `/admin/inventory-counts/:id`): menu Stock (`admin.inventory`), after Print product labels, and the search bar. Only on a store that keeps its own stock: `STOCK_ROUTES` of `navigation/store-mode-routes.js`, hidden with `appConfig/supplyFromErp` by the router guard, the menu and `hiddenStoreRoutes`; no head office route. Resource `admin-inventory-counts`: `read` shows the pages, `write` shows New count, Import the file again, Delete and Validate (Roles page, group Stock). List: number, count date, note, status, rows read, created by, validated at and by, newest first, paged. New count (`components/admin/inventory/InventoryImportModal.vue`): count date (today), note, file (.xlsx / .xls), a model file made in the browser (Code, Quantity; codes as text); the count opens after the import. One count: summary cards, a yellow banner when cashier sessions are open and one when lines will not be applied, filter all / differences / problems, search, the lines paged by the backend (difference green up, red down). A draft can be imported again, deleted, validated (a confirmation with the items that change, the totals up and down, cannot be undone); a validated count is read only with who and when. Import and validation: overlay or spinner, buttons disabled, timeout 600 s; the requests pass `skipErrorPopup` and the backend's `{"error"}` shows once in a toast. API and rules: `modules/inventory-count.md`.
- **RFID** (3.1.0, `views/admin/rfid/RfidReaders.vue`, `RfidPosMachines.vue`, routes `admin-rfid-readers` `/admin/rfid/readers` and `admin-rfid-pos-machines` `/admin/rfid/pos-machines`): store menu RFID, only when `/config` `rfidEnabled` (`RFID_ROUTES` of `navigation/store-mode-routes.js`: menu, router guard, search bar); permissions `read:admin-rfid-readers`, `read:admin-rfid-pos-machines`. Readers: CRUD, Test connection, Test read (popup with the tags, Read again). POS machines: CRUD, the reader among the active ones, "This PC" from `GET /rfid/my-machine` and "Add this machine". Every request `skipErrorPopup`, the backend's `{"error"}` shown as it comes. Details: `modules/rfid.md`.
- **Commandes centrale** (3.1.1, `views/admin/store-orders/StoreOrderCatalogue.vue`, `StoreOrders.vue`, `StoreOrderDetail.vue`, routes `admin-store-orders-catalogue` `/admin/store-orders/catalogue`, `admin-store-orders` `/admin/store-orders`, `admin-store-order` `/admin/store-orders/:id`): store menu group "Commandes centrale" (Catalogue, Mes commandes), only when `/config` gives `supplyFromHeadOffice` and `navItems` (`STORE_ORDER_ROUTES` and `storeOrdersShown` of `navigation/store-mode-routes.js`: menu, router guard, search bar, Roles page); no head office route. Resource `admin-store-orders`: `read` shows the pages, `write` the quantities, add, send, cancel and note (Roles page, group Achats & Fournisseurs). Catalogue: tiles from `GET /store-order/catalogue`, the filters (search debounced 400 ms and Enter, levels narrowed by `libs/item-classification`, sort, page size) in the URL query (`components/admin/store-orders/store-orders-api.js`); a tile opens `StoreOrderVariantsModal.vue` (spinner while the head office asks NAV, timeout 30 s; 503: its message, nothing to add; the sizes × colours matrix with stock, price with VAT and quantity; only the changed cells posted, one after another); `StoreOrderCart.vue` beside it, a drawer under the large breakpoint. Mes commandes: `admin-list-page`, status badges, filters status and dates; the order's page with its lines and "Ouvrir dans le catalogue" for a draft. Every request `skipErrorPopup`. Checked by `tools/store-orders-check.mjs`. API and rules: `modules/store-orders.md`.
- **Purchase Invoices** (`PurchaseInvoiceManagement.vue`): admin module under the Purchases menu group that manages **purchase invoices** (AP). Lists and filters purchase invoices by date, invoice number, and vendor, shows a detailed invoice modal, and uses `admin.purchaseInvoiceModule.*` i18n keys. Integrated with `PurchaseHistory.vue` to create purchase invoices from eligible purchases via `/admin/purchase-invoices` APIs.


## Admin UI Enhancements

### Tickets History Page (`TicketsHistory.vue`)
**Features:**
- **Synchronization Status Display:**
  - Sync status column in tickets table with color-coded badges (green: Totally Synced, yellow: Partially Synced, gray: Not Synced)
  - ERP document number displayed below sync status badge
  - Sync status filter dropdown (All, Not Synced, Partially Synced, Totally Synced)
  - Synchronization info section in ticket details modal showing:
    - Sync status with icon
    - ERP document number
    - Lines synchronized count (e.g., "3 / 5")
  
- **Enhanced Sales Lines Display:**
  - Shows discount percentage and amount
  - Shows VAT percentage and amount
  - Shows unit price TTC (including VAT)
  - Shows line total TTC (including VAT)
  - Synced indicator for each line (green "Synced" or gray "Not Synced" badge)
  
- **Currency:**
  - All prices displayed in TND (Tunisian Dinar) instead of USD ($)
  - Format: "XXX.XX TND"
  
- **Filter Panel:**
  - Collapsible filter section (closed by default)
  - Two-row layout for better organization:
    - Row 1: Search (larger), Date From, Date To
    - Row 2: Status, Sync Status, Payment Method (all equal width)
  - Icons for each filter field (Search, Calendar, Tag, Cloud, Credit Card)
  - Ticket count moved to filter header: "Filters (Total: X tickets)"
  - Compact padding and spacing
  
- **UI Improvements:**
  - Removed Cashier column from tickets table (still shown in details modal)
  - Cleaner, more focused table layout
  - Enhanced details modal with better organization
  - Responsive design for all screen sizes

### ERP Communications Page (`ErpCommunications.vue`)
**Features:**
- **Enhanced Details Modal:**
  - URL section at top with copy button
  - Operation details section with icons
  - Error messages with icons
  - Request/Response payloads with copy buttons
  - Improved visual hierarchy and styling
  
- **Table:**
  - URL column removed from list (only shown in details modal)
  - Cleaner table layout
  - Search includes URL field
  
- **Field Changes:**
  - `externalReference` field replaced with `url` field
  - URL stores the actual HTTP endpoint URL used for the operation
  - Better tracking of which ERP endpoint was called

**Backend Changes:**
- `ErpCommunication` entity: `externalReference` → `url` (String, length 512)
- `ErpCommunicationViewDTO`: Updated to use `url` field
- `ErpCommunicationService.logOperation()`: Accepts `url` parameter instead of `externalReference`
- All ERP connectors capture and return URL in `ErpOperationResult`
