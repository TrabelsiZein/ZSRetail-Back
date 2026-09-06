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
