# Franchise Module

**Status**: ✅ Implemented (franchise admin + franchise customer)

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
