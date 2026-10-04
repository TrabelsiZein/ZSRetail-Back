# Franchise Module

**Status**: ✅ Implemented (franchise admin + franchise customer). Legacy since the head office plan, step 8 (2026-10-04): no install runs these two profiles with real data, and a new franchise network is installed on the head office model instead (head office plan, step 8). The profiles and `/franchise/**` stay unchanged until they are removed with step 9.

### Overview
- Goal: allow launching multiple independent franchise local stores (franchise customers) while centralizing product catalog and supply invoices (franchise admin).
- Works alongside POS `standalone`/`erp` modes without impacting non-franchise scenarios.

### Configuration / Profiles
- Franchise Admin profile: `spring.profiles.active=franchise-admin`
  - `franchise.admin=true`, `franchise.customer=false`
  - Exposes franchise sync APIs under `/franchise/**` secured with `X-Franchise-Api-Key`
  - Behaves like standalone for normal admin POS operations
- Franchise Customer profile: `spring.profiles.active=franchise-customer`
  - `application.standalone=true`, `franchise.admin=false`, `franchise.customer=true`
  - `franchise.remote.url` points to the admin base URL (no trailing slash)
  - Automatic sales push enabled by scheduler cron: `franchise.sync.sales.cron`
  - Local items allowed toggle: `franchise.customer.allow-local-items` (default `false`)

### Mode Detection & Frontend Store
- Backend `ApplicationModeService` exposes franchise flags (admin/customer) and `isLocalItemsAllowed()`.
- `AppConfigDTO` + `AppConfigAPI` provide these flags to the frontend store (`src/store/app-config/index.js`).

### Menu Visibility & ACL
- `VerticalNavMenu.vue`
  - Hides the whole “Franchise” group when not in franchise mode
  - Shows:
    - `Franchise Sales Tracking` only for franchise admins
    - `Franchise Sync Dashboard` only for franchise customers
- `Login.vue`
  - Added abilities so ACL can correctly show routes:
    - `admin-franchise`
    - `admin-franchise-sales-tracking`
    - `admin-franchise-sync-dashboard`

### Local Items Rule (Franchise Customer)
- Items synced from admin are marked read-only using a persisted flag:
  - `Item.fromFranchiseAdmin = true` for synced items
- If `franchise.customer.allow-local-items=true`:
  - Customers can create/update/delete only local items (`fromFranchiseAdmin=false`)
  - Synced items remain read-only in UI and are enforced server-side in `ItemAPI` (403 when unauthorized)

### Sync Dashboard (Franchise Customer)
- Persisted timestamps in `GeneralSetup`:
  - `FRANCHISE_LAST_ITEM_SYNC`
  - `FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC`
- Endpoint used by the dashboard:
  - `GET /franchise/client/sync/status` returns `lastItemSync` + `lastSupplySync`
- `SyncDashboard.vue`
  - Always shows last sync dates (removed misleading “Never synced...”)
  - Refreshes status after sync actions

### Item Synchronization (Admin -> Customer)
- Admin endpoint (API-key secured):
  - `GET /franchise/items?modifiedAfter=...`
- Customer performs incremental sync based on the last stored timestamp.
- Sync DTO includes franchise-specific pricing + barcodes used by franchise customers.

### Supply Reception (Admin Invoice -> Customer Purchase)
- Admin endpoints:
  - `GET /franchise/invoices?locationCode=...` returns pending invoices for the client location
  - `POST /franchise/invoices/{id}/acknowledge` sets `franchiseReceivedAt` when received
- Customer reception creates local purchase receptions automatically after pulling pending invoices.
- Reception reports missing item codes when items are not present locally (so the customer can sync items first).

### Sales Push & Tracking (Customer -> Admin)
- Customer pushes completed sales to admin using:
  - `POST /franchise/sales` (API-key secured)
- Admin stores tracking data in dedicated tables:
  - `franchise_sales_header` + `franchise_sales_line`
- Scheduler:
  - `FranchiseSalesPushScheduler` pushes unsent sales automatically on interval (`franchise.sync.sales.cron`).

### Sales Tracking UI (Franchise Admin)
- Admin endpoint (JWT secured):
  - `GET /admin/franchise/sales`
- `SalesTracking.vue` refactored to use `AdminListPage` standard layout:
  - location filter + date-from/to filters
  - row details show `lines` breakdown
- Serialization fix:
  - Added `@JsonIgnore` on `FranchiseSalesLine.header` to prevent recursive Jackson serialization when returning JPA entities.

### Franchise Fields in Invoice List (Franchise Admin)
- `InvoiceManagement.vue` (admin sales invoice list) shows:
  - `Franchise Location` (`franchiseLocationCode`)
  - `Received At` (`franchiseReceivedAt`)
- Backend list serialization fixed by extending:
  - `InvoiceListDTO` + `InvoiceAPI.toListDTO()` to include these fields.

### Installing a franchise network on the model
Head office plan, step 8 (2026-10-04). A new franchise network runs as a head office and stores, from the presets `application-network-headoffice.properties` and `application-network-store.properties` (settings: `docs/deployment-modes.md`, "Presets"). It never uses the two profiles above. The store-row invoicing settings and the supply prices come with step 7B; they are on its branch (`feature/ho-step-7b`), not yet in `release/2.1.0`, and the step 7B pages (Supply prices, store invoicing settings, supply invoices) are still to be built.

1. **Head office.** Create an empty database. Copy `application-network-headoffice.properties`, replace `CHANGE_ME` (database password) and start with `spring.profiles.active=network-headoffice` (port 888, its own log and image folder, so a store can run on the same server). Log in as `admin`. Fill **Company information**: it is the seller on the supply invoices. Tax stamp on supply invoices: head office setting `SUPPLY_INVOICE_TAX_STAMP` (off by default).
2. **Items at the head office.** Create families, sub-families and items with their barcodes. The item's price is the **base selling price**, imposed on every store. Enter the **base supply price** of each item on the Supply prices page: it is what a store pays. Buy the goods from vendors at the head office (purchases, stock).
3. **One store row per store** on **Network → Stores**:
   - code = the store's future `DEFAULT_LOCATION`;
   - copy the key from the dialog (shown once, otherwise use regenerate-key);
   - price right and purchase right **off** (the default): imposed selling price, no own suppliers;
   - deliveries invoiced **on**, with the billing details (legal name, tax number, address);
   - supply price mode `PRICE_LIST` with no supply price list (base supply price), invoice rhythm `PER_BL`;
   - no selling price list (base selling price).
4. **Each store.** Create an empty database. Copy `application-network-store.properties`, replace `CHANGE_ME` (database password, head office host in `headoffice.url`, the key of step 3 in `headoffice.api-key`) and start with `spring.profiles.active=network-store`. Set `DEFAULT_LOCATION` to the store's code in General Setup, and upload the license. The **Head office link** page shows ONLINE; the items arrive with the copies down.
5. **Check the chain once.** Validate a BL for the store at the head office. The store confirms the received quantities on its reception page. The invoice is created at the confirmation, at the supply price, and arrives at the store as a consult-only purchase invoice from vendor `HEAD_OFFICE`. The store's sales appear in the head office tickets.

Every answer of step 3 is a setting of the store row and can be changed per store later: a selling price list, a supply price list or a percentage off the selling price, grouped invoices, or the rights.
