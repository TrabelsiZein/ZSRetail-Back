# Admin Management Pages

## Admin Management Pages
- Returns: list with validity, remaining amount, linked tickets, duplicate voucher print.
- Locations: searchable paginated list for admins.
- General Setup: editable when `readOnly` is false, with modal form and validation.
- Item Families/Subfamilies: each on dedicated page with CRUD modals and pagination.
- **Sales Invoices** (`InvoiceManagement.vue`): admin module under the Sales menu group that manages **sales invoices** (formerly just "Invoices"). Provides date range, invoice number, and customer filters, server-side pagination, a detailed invoice modal, and uses `admin.invoiceModule.*` i18n keys (e.g. `title`, `subtitle`, `tabList`, `filters`, `refresh`).
- **Inventory counts** (`InventoryCounts.vue`, `InventoryCountDetail.vue`, routes `admin-inventory-counts` `/admin/inventory-counts` and `admin-inventory-count` `/admin/inventory-counts/:id`): menu Stock (`admin.inventory`), after Print product labels, and the search bar. Only on a store that keeps its own stock: `STOCK_ROUTES` of `navigation/store-mode-routes.js`, hidden with `appConfig/supplyFromErp` by the router guard, the menu and `hiddenStoreRoutes`; no head office route. Resource `admin-inventory-counts`: `read` shows the pages, `write` shows New count, Import the file again, Delete and Validate (Roles page, group Stock). List: number, count date, note, status, rows read, created by, validated at and by, newest first, paged. New count (`components/admin/inventory/InventoryImportModal.vue`): count date (today), note, file (.xlsx / .xls), a model file made in the browser (Code, Quantity; codes as text); the count opens after the import. One count: summary cards, a yellow banner when cashier sessions are open and one when lines will not be applied, filter all / differences / problems, search, the lines paged by the backend (difference green up, red down). A draft can be imported again, deleted, validated (a confirmation with the items that change, the totals up and down, cannot be undone); a validated count is read only with who and when. Import and validation: overlay or spinner, buttons disabled, timeout 600 s; the requests pass `skipErrorPopup` and the backend's `{"error"}` shows once in a toast. API and rules: `modules/inventory-count.md`.
- **RFID** (3.1.0, `views/admin/rfid/RfidReaders.vue`, `RfidPosMachines.vue`, routes `admin-rfid-readers` `/admin/rfid/readers` and `admin-rfid-pos-machines` `/admin/rfid/pos-machines`): store menu RFID, only when `/config` `rfidEnabled` (`RFID_ROUTES` of `navigation/store-mode-routes.js`: menu, router guard, search bar); permissions `read:admin-rfid-readers`, `read:admin-rfid-pos-machines`. Readers: CRUD, Test connection, Test read (popup with the tags, Read again). POS machines: CRUD, the reader among the active ones, "This PC" from `GET /rfid/my-machine` and "Add this machine". Every request `skipErrorPopup`, the backend's `{"error"}` shown as it comes. Details: `modules/rfid.md`.
- **Articles** (store, `views/admin/ItemManagement.vue`, route `admin-item-management` `/admin/item-management`, resource `admin-item-management`). A store without an item carrying a NAV number (`navItems` false in `/config`: EMTOP, Happyness, standalone) keeps the flat list as before, unchanged. 3.1.1, a store whose items carry NAV numbers (`navItems` true): the same route shows the list **grouped by NAV item**, the head office's `views/admin/headoffice/ItemCards.vue` in `context="store"` (`GET /item-catalogue`, core `ItemCatalogueService`): one line per NAV item (code, name, classification, variants count, price or price range with VAT, stock where the store keeps its own, status), the items the store made itself (no NAV number) plain lines in the same list; filters search (name, NAV code, a variant's code, barcode; debounced), status (All by default, Active, Inactive), price with VAT, the classification levels (catégorie and section only where they exist, each narrowing the ones below), kept in the URL; no card, pack, stock adjustment, point de stock, division or "has variants" filter here. A NAV item's line opens and closes its variants in the list (`GET /nav-item/variants`, the status filter's); a search that found some of its variants but not all (a variant code, a barcode: `matchedItemIds` of the line) opens the line by itself with those variants highlighted. A variant or a local item opens the Articles page's own item form (`GET /item/{id}`, consult-only for a head office or NAV item as before); its Variant block's "Toutes les variantes de cet item NAV" lists that NAV item, opened. The Add button stays where the page offers it. The old "Articles ERP" page (`NavItems.vue`) is gone: no menu or search bar entry, its route `admin-nav-items` (`/admin/nav-items?q=`) sends on to Articles with its search (`libs/item-catalogue-view.js` `navItemsRedirect`). Rules checked by `tools/item-catalogue-check.mjs`. Head office pages unchanged.
- **Commandes centrale** (3.1.1, `views/admin/store-orders/StoreOrderCatalogue.vue`, `StoreOrders.vue`, `StoreOrderDetail.vue`, routes `admin-store-orders-catalogue` `/admin/store-orders/catalogue`, `admin-store-orders` `/admin/store-orders`, `admin-store-order` `/admin/store-orders/:id`): store menu "Head office" (Siège): entries Catalogue and Mes commandes, after the BL reception and before Jobs, Communications and Data exchanged (the "Head office" group is the last of the store sidebar), only when `/config` gives `supplyFromHeadOffice` and `navItems` (`STORE_ORDER_ROUTES` and `storeOrdersShown` of `navigation/store-mode-routes.js`: menu, router guard, search bar, Roles page); no head office route. Resource `admin-store-orders`: `read` shows the pages, `write` the quantities, add, send, cancel and note (Roles page, group Achats & Fournisseurs). A purchase page: **no selling price** anywhere (tiles, list, matrix, cart; the window's summary is "n variants · n pieces"), a variant's purchase price only in its cell's tooltip (the connector's details, ZEN's "Prix net"); and **no "NAV"** in the store's texts ("Code", "N° commande", fr / en / ar). Catalogue: from `GET /store-order/catalogue`, tiles or a list (a toggle remembered in the browser), grey placeholder tiles on the first read, an empty answer saying why with "Clear filters"; a tile in the draft shows "Dans la commande : n". The filters (search debounced 400 ms and Enter, levels narrowed by `libs/item-classification`, sort, page size) are in the URL query (`components/admin/store-orders/store-orders-api.js`) and sit in one **sticky bar** under the navbar on the large screens, with the count and the active filters as removable **chips**; a famille or section whose name repeats reads with its parents' names ("SECTION — SOUS-FAMILLE", then " · FAMILLE" as needed; `choiceLabels`), its code on hover. A tile opens `StoreOrderVariantsModal.vue` (spinner while the head office reads the quantities, timeout 30 s; 503: its message, nothing to add; the sizes × colours matrix with the quantity available and the quantity to order, sized to the matrix; Enter / Shift+Enter and the arrows move between the cells; "Ajouter à la commande" once a cell changed, only the changed cells posted, one after another); `StoreOrderCart.vue` beside it at the bar's height, a drawer under the large breakpoint. Mes commandes: `admin-list-page`, status badges, filters search (order number, N° commande, a line's code or name; debounced, Enter at once), status and dates, kept in the URL; the order's page as the head office's (`OrderHeaderCard`: Document, Suivi, Totaux, the note apart; `OrderLinesTable`: N°, Code sortable); the order's page with its lines and "Ouvrir dans le catalogue" for a draft; once the head office posted it, its answer ("Réponse de la centrale") and, when a line carries one, the columns "Acceptée" and "Message", a line accepted below its quantity highlighted (`hasNavAnswer`, `acceptedShort`). Every request `skipErrorPopup`. Checked by `tools/store-orders-check.mjs` and `tools/item-classification-check.mjs`. API and rules: `modules/store-orders.md`.
- **Commandes des magasins** (3.1.1, head office, `views/admin/headoffice/StoreOrders.vue`, `StoreOrderDetail.vue`, routes `admin-headoffice-store-orders` `/headoffice/network/store-orders` and `admin-headoffice-store-order` `/headoffice/network/store-orders/:id`): head office menu Network, after Network stock (the order's page has no link). Only when `/config` gives `storeOrdersPost` (route `meta.storeOrders`: menu, guard `byStoreOrders`, search bar, Roles page). Resource `admin-headoffice-store-orders`: `read` the pages, `write` the actions (`meta.writePermission`). List (`admin-list-page`): store, number, received, status badge, lines, quantity, NAV order, and in ERP mode attempts, last attempt and the hint linking to the Jobs page; filters search (order number, NAV order, a line's code or name), store (`/admin/headoffice/stores`), status, dates, kept in the URL (`q`, `storeId`, `status`, `from`, `to`); columns sorted by the back end. The order's page: a header card (`OrderHeaderCard`) in groups Document (number, store, sent, received, posted), Traitement (status, NAV order, posted mode, attempts and last attempt) and Totaux (lines, pieces), the note apart; the lines (`OrderLinesTable`: N°, Code NAV sortable, name, variant, quantity); the NAV error or the rejection message, NAV's message once posted, the note, the lines (once NAV answered: "Acceptée (NAV)" and "Message NAV" per line, a line accepted below its quantity highlighted); actions by `orderActions` of `components/admin/store-orders/ho-store-orders-api.js`: ERP "Relancer" (timeout 60 s) and "Rejeter", MANUAL "Marquer postée" and "Rejeter", on SENT / ERROR, each confirmed (a required text for the NAV number and the message), the order read again after. API and rules: `modules/store-orders.md`.
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
