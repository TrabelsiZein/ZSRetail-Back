# Deployment Modes (ERP vs Standalone)

**Status**: Implemented (T1–T8 complete). T9 (guard ERP APIs) and T10 (full standalone features) optional/later.

**Overview:**
- POS can run in **ERP mode** (integrated with Dynamics NAV/Business Central) or **Standalone mode** (no ERP).
- Mode is driven by `application.standalone` (config/env). Profile `standalone` sets `application.standalone=true` and disables ERP sync.

**Backend:**
- **ApplicationModeService** (`config/ApplicationModeService.java`): Reads `application.standalone`, exposes `isStandalone()` / `isErpMode()`.
- **application-standalone.properties**: `application.standalone=true`, `erp.dynamicsnav.enabled=false`, `erp.sync.enabled=false`. Use with `spring.profiles.active=standalone` (or `standalone,dev` / `standalone,production`).
- **GET /config** (public): Returns `{ standalone: boolean, enableSalesPriceGroup: boolean }`. Used by frontend to hide/show UI.
- **ZZDataInitializer**: When `isStandalone()`, skips `ensureErpSyncCheckpointConfigs()` and `initErpSyncJobs()`. Payment methods, users, GeneralSetup (including DEFAULT_LOCATION, PASSENGER_CUSTOMER) still initialized.
- **NoOpErpConnector**: Active when `erp.dynamicsnav.enabled=false` (standalone). Export/push methods are no-op; no NAV calls.
- **Standalone-only APIs** (403 when not standalone):
  - POST /customer (create customer)
  - POST /item/standalone-quick-product (create product with default family/subfamily + one barcode)
  - POST /item-family (create family)
  - POST /item-subfamily (create subfamily)
- **ItemFamiliesManagement / ItemSubFamiliesManagement**: Backend allows PUT/DELETE; frontend controls visibility of Edit/Delete (standalone only).

**Frontend:**
- **appConfig store** (`store/app-config/index.js`): State `standalone`, `enableSalesPriceGroup`. Fetched via GET config on load (`main.js`). Getters: `isStandalone`, `enableSalesPriceGroup`.
- **VerticalNavMenu**: Hides "admin.erp" menu group when `standalone === true`. Hides "Sales Prices" and "Sales Discounts" when `enableSalesPriceGroup === false`.
- **TicketsHistory.vue**: Sync status filter, Sync Status column, ERP doc #, ERP sync card in modal, and Synced column in lines hidden when `isStandalone`.
- **ReturnsManagement.vue**: Same sync-related UI hidden when `isStandalone` (sync filter, Sync Status column, ERP sync card in modal, Synced column in return lines).
- **CustomerManagement.vue**: "Add Customer" button only when `isStandalone`.
- **ItemBarcodes.vue**: "Add Product" (quick product modal) only when `isStandalone`.
- **ItemFamiliesManagement.vue / ItemSubFamiliesManagement.vue**: "Add Family" / "Add SubFamily" only when `isStandalone`; Edit and Delete row actions only when `isStandalone` (otherwise actions column shows "-").
- **Route guard**: `/admin/sales-prices` and `/admin/sales-discounts` redirect to home when `enableSalesPriceGroup` is false.

**Sales Price Group visibility:**
- `pos.pricing.enable-sales-price-group` (false in application-standalone.properties) exposed as `enableSalesPriceGroup` in GET /config.
- When false: Sales Prices and Sales Discounts admin menu entries and routes are hidden/redirected.

**Core POS flows in Standalone:**
- Sales, payment, returns, sessions, and printing work without ERP. Ticket/return export and sync jobs are disabled; SessionExportService still creates PaymentHeader/PaymentLine records locally (export to ERP is no-op with NoOpErpConnector). No code path blocks sale/payment/return when standalone.

**Ownership model (not used yet):**
- Head office plan task 0.4 (`docs/roadmap/head-office-design.md` sections 2.1, 2.2, 5.1). Nothing in the application reads these values yet; existing mode checks are unchanged.
- **ApplicationModeService** also exposes `getNodeType()`, `ownerOf(DataDomain)` and `salesUpstreams()` (empty = sales go nowhere), resolved once at startup by `config/NodeOwnership.java`. Enums in `model/enumeration`: `NodeType`, `DataDomain`, `DataOwner`, `SalesUpstream`.
- Optional keys, not present in any `application*.properties` file. Values are trimmed and case-insensitive.

| Key | Values | When absent |
|---|---|---|
| `node.type` | `STORE`, `HEAD_OFFICE` | `STORE` |
| `ownership.catalogue` | `LOCAL`, `HEAD_OFFICE`, `ERP` | derived (table below) |
| `ownership.customers` | `LOCAL`, `HEAD_OFFICE`, `ERP` | derived |
| `ownership.promotions` | `LOCAL`, `HEAD_OFFICE` | derived |
| `ownership.loyalty` | `LOCAL`, `HEAD_OFFICE` | derived |
| `ownership.supply` | `LOCAL`, `HEAD_OFFICE`, `ERP` | derived |
| `sales.upstream` | comma list of `ERP`, `HEAD_OFFICE`; empty = none | derived |

- Derivation from the existing flags (first matching row wins):

| Mode flags | Type | Catalogue | Customers | Promotions | Loyalty | Supply | Sales go to |
|---|---|---|---|---|---|---|---|
| `franchise.customer=true` | STORE | HEAD_OFFICE | LOCAL | LOCAL | LOCAL | HEAD_OFFICE | HEAD_OFFICE |
| `franchise.admin=true` (legacy until step 8) | STORE | LOCAL | LOCAL | LOCAL | LOCAL | LOCAL | none |
| `application.standalone=true` | STORE | LOCAL | LOCAL | LOCAL | LOCAL | LOCAL | none |
| otherwise (ERP) | STORE | ERP | ERP | LOCAL | LOCAL | ERP | ERP |

- An explicit key overrides only its own value. An unknown value, or `ERP` for promotions or loyalty, stops the startup with `Invalid value '<value>' for property <key>: allowed values are [...]`. Checks across keys (e.g. a head office that sells) come with step 1.
- Tests: `ApplicationModeOwnershipTest` (L1).
