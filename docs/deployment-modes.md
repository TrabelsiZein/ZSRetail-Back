# Deployment Modes (ERP vs Standalone)

**Status**: Implemented (T1–T8 complete). T9 (guard ERP APIs) and T10 (full standalone features) optional/later.

**Overview:**
- POS can run in **ERP mode** (integrated with Dynamics NAV/Business Central) or **Standalone mode** (no ERP).
- Mode is driven by `application.standalone` (config/env). Profile `standalone` sets `application.standalone=true` and disables ERP sync.

**Backend:**
- **ApplicationModeService** (`config/ApplicationModeService.java`): Reads `application.standalone`, exposes `isStandalone()` / `isErpMode()`.
- **application-standalone.properties**: `application.standalone=true`, `erp.dynamicsnav.enabled=false`, `erp.sync.enabled=false`. Use with `spring.profiles.active=standalone` (or `standalone,dev` / `standalone,production`).
- **GET /config** (public, loaded by the frontend before login): returns `AppConfigDTO`. Fields in JSON order: `standalone`, `enableSalesPriceGroup`, `loyaltyEnabled`, `franchiseAdmin`, `franchiseCustomer`, `allowLocalItems`, `licenseStatus`, `licenseDaysUntilExpiry`, `posShowImages`, `posShowStock`, `tableManagementEnabled`, `tableManagementTableCount`, `appVersion`, `tombolaEnabled`, then `nodeType`, `ownership`, `salesUpstreams` (see "Ownership model" below). Used by frontend to hide/show UI.
- **ZZDataInitializer**: When `isStandalone()`, skips `ensureErpSyncCheckpointConfigs()` and `initErpSyncJobs()`. Payment methods, users, GeneralSetup (including DEFAULT_LOCATION, PASSENGER_CUSTOMER) still initialized.
- **NoOpErpConnector**: Active when `erp.dynamicsnav.enabled=false` (standalone). Export/push methods are no-op; no NAV calls.
- **Standalone-only APIs** (403 when not standalone):
  - POST /customer (create customer)
  - POST /item/standalone-quick-product (create product with default family/subfamily + one barcode)
  - POST /item-family (create family)
  - POST /item-subfamily (create subfamily)
- **ItemFamiliesManagement / ItemSubFamiliesManagement**: Backend allows PUT/DELETE; frontend controls visibility of Edit/Delete (standalone only).

**Frontend:**
- **appConfig store** (`store/app-config/index.js`): one state field per GET /config field, filled by `fetchAppConfig` on load (`main.js`), with a default when a field is missing or the call fails. Getters used for the modes on this page: `isStandalone`, `enableSalesPriceGroup`.
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

**Ownership model:**
- **Head office installation** (`node.type=HEAD_OFFICE`, profile `headoffice-dev`, task 1.1): no cashier session, no cashier login, no selling pages. See `docs/modules/head-office.md`.
- Head office plan tasks 0.4 and 0.5 (`docs/roadmap/head-office-design.md` sections 2.1, 2.2, 5.1). Apart from the node type (head office guards above), nothing in the application acts on these values yet: GET /config and the frontend store only expose them, and existing mode checks are unchanged.
- **ApplicationModeService** also exposes `getNodeType()`, `ownerOf(DataDomain)` and `salesUpstreams()` (empty = sales go nowhere), resolved once at startup by `config/NodeOwnership.java`. Enums in `model/enumeration`: `NodeType`, `DataDomain`, `DataOwner`, `SalesUpstream`.
- Optional keys. Only `node.type` is set in a profile file today (`application-headoffice-dev.properties`). Values are trimmed and case-insensitive.

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

- An explicit key overrides only its own value. An unknown value, or `ERP` for promotions or loyalty, stops the startup with `Invalid value '<value>' for property <key>: allowed values are [...]`. Checks across keys for a head office (franchise flags, owner `HEAD_OFFICE`, `sales.upstream`): see `docs/modules/head-office.md`.
- **Catalogue owned by the head office** (step 6): an explicit `ownership.catalogue=HEAD_OFFICE` needs `headoffice.url` (like promotions and loyalty) and stops the startup with `franchise.customer=true` or `franchise.admin=true` (`Invalid combination: ownership.catalogue=HEAD_OFFICE with franchise.customer=true ...`) or with `application.standalone=false` (`... with application.standalone=false. A store whose items come from an ERP ...`). The franchise customer row above still derives `CATALOGUE=HEAD_OFFICE` for its legacy item sync: it never gets the step 6 catalogue beans, even with `headoffice.url` (`NodeOwnership.isCatalogueFromHeadOffice`). See `docs/modules/head-office.md`, "Catalogue owned by the head office".
- **Head office link** (task 1.4): a store that calls a head office. Three optional keys; without `headoffice.url` none of the link beans exists and every profile behaves as before. The two dev profiles (`standalone-dev`, `dynamics-dev`) carry the first two lines commented out. Details, startup checks and the "Connect a store" procedure: `docs/modules/head-office.md`, "Head office link".

| Key | Value | When absent |
|---|---|---|
| `headoffice.url` | Head office base URL including the context path, e.g. `http://localhost:888/zsretail/api`; a trailing slash is tolerated | no link (blank = absent) |
| `headoffice.api-key` | The store's key, shown once on the head office Stores page | required when the URL is set |
| `headoffice.heartbeat-interval-seconds` | Whole number, at least 1 | `60` |

- **Stores page threshold** (task 1.5), head office only: `headoffice.offline-after-seconds`, whole number, at least 1, default `180`. A store whose last heartbeat is older is shown OFFLINE (exactly at the threshold it is still ONLINE). A wrong value stops the head office at startup. See `docs/modules/head-office.md`, "Status".
- **GET /config** (task 0.5) returns three more fields after the existing ones, enums as their names:
  - `nodeType`: `"STORE"` or `"HEAD_OFFICE"`.
  - `ownership`: every domain to its owner, in `DataDomain` order. ERP profile: `{"CATALOGUE":"ERP","CUSTOMERS":"ERP","PROMOTIONS":"LOCAL","LOYALTY":"LOCAL","SUPPLY":"ERP"}`.
  - `salesUpstreams`: array of `"ERP"`, `"HEAD_OFFICE"`; `[]` when sales go nowhere.
  - `headOfficeLinked` (task 1.5): `true` when `headoffice.url` is set; the frontend shows the "Head office link" page only then (`appConfig/isHeadOfficeLinked`, default `false`).
  - `catalogueFromHeadOffice` (step 6, last field): `true` on a store whose catalogue is the head office's (URL, explicit `ownership.catalogue=HEAD_OFFICE`, standalone, no franchise flag). The frontend reads this flag, not `ownership.CATALOGUE`, which a franchise customer reports as `HEAD_OFFICE`.
- **Frontend store** (`store/app-config/index.js`, task 0.5): state `nodeType` (default `'STORE'`), `ownership` (default `{}`), `salesUpstreams` (default `[]`). Getters `nodeType`, `ownerOf(domain)` (owner name, `null` when unknown) and `salesUpstreams`. Defaults when talking to an older backend, or when the call fails: a missing or unknown `nodeType` gives `'STORE'`, a missing or non-object `ownership` gives `{}`, a missing or non-array `salesUpstreams` gives `[]`. No component, route or menu reads them yet.
- Tests: `ApplicationModeOwnershipTest` (L1, task 0.4); `AppConfigAPITest` (L1, task 0.5: for the four profiles, the old /config fields keep their names, order and values, and the new fields match the table above).
