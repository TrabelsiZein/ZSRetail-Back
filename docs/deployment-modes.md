# Deployment Modes (with or without an ERP)

**Status**: Implemented (T1–T8 complete). T9 (guard ERP APIs) and T10 (full features without an ERP) optional/later.

**Overview:**
- POS can run in **ERP mode** (integrated with Dynamics NAV/Business Central) or **without an ERP** (the property `application.standalone=true`, called "standalone" in the code until task 9.3).
- Mode is driven by `application.standalone` (config/env). Profile `standalone` sets `application.standalone=true` and disables ERP sync.

**Backend:**
- **ApplicationModeService** (`config/ApplicationModeService.java`): resolves the installation once at startup through `NodeOwnership.resolve(env)`, the one place that reads `application.standalone`; exposes the ownership questions (`isCatalogueFromErp()`, `isCustomersFromErp()`, `isSupplyFromErp()`, `hasErp()`, `ownerOf`, ...). `isStandalone()` and `isErpMode()` were deleted at step 9 (task 9.1g).
- **application-standalone.properties**: `application.standalone=true`, `erp.dynamicsnav.enabled=false`, `erp.sync.enabled=false`. Use with `spring.profiles.active=standalone` (or `standalone,dev` / `standalone,production`).
- **GET /config** (public, loaded by the frontend before login): returns `AppConfigDTO`. Fields in JSON order: `standalone` (since step 9 computed as "no ERP owner", same value), `enableSalesPriceGroup`, `loyaltyEnabled`, `licenseStatus`, `licenseDaysUntilExpiry`, `posShowImages`, `posShowStock`, `tableManagementEnabled`, `tableManagementTableCount`, `appVersion`, `tombolaEnabled`, then `nodeType`, `ownership`, `salesUpstreams` (see "Ownership model" below). Used by frontend to hide/show UI. The three franchise fields (`franchiseAdmin`, `franchiseCustomer`, `allowLocalItems`, after `loyaltyEnabled`) left with the franchise profiles (step 9, task 9.4a).
- **ZZDataInitializer**: Without an ERP (`hasErp()` false since step 9), skips `ensureErpSyncCheckpointConfigs()` and `initErpSyncJobs()`. Payment methods, users, GeneralSetup (including DEFAULT_LOCATION, PASSENGER_CUSTOMER) still initialized.
- **NoOpErpConnector**: Active when `erp.dynamicsnav.enabled=false` (no ERP). Export/push methods are no-op; no NAV calls.
- **APIs refused with an ERP** (403; since step 9 each asks an ownership question, table "Step 9 questions" below):
  - POST /customer (create customer)
  - POST /item/quick-product (create product with default family/subfamily + one barcode)
  - POST /item-family (create family)
  - POST /item-subfamily (create subfamily)
- **ItemFamiliesManagement / ItemSubFamiliesManagement**: Backend allows PUT/DELETE; frontend controls visibility of Edit/Delete (without an ERP only).

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

**Core POS flows without an ERP:**
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

- Derivation from `application.standalone`:

| Mode flag | Type | Catalogue | Customers | Promotions | Loyalty | Supply | Sales go to |
|---|---|---|---|---|---|---|---|
| `application.standalone=true` | STORE | LOCAL | LOCAL | LOCAL | LOCAL | LOCAL | none |
| otherwise (ERP) | STORE | ERP | ERP | LOCAL | LOCAL | ERP | ERP |

- The two franchise rows (`franchise.customer=true`, `franchise.admin=true`) left with the franchise profiles (step 9, task 9.4a): `franchise.admin` or `franchise.customer` set to `true` now stops the startup (`Invalid value 'true' for property franchise.customer: the franchise profiles were removed (head office plan, step 9). ...`), on a store and on a head office. See `docs/modules/franchise.md`.
- An explicit key overrides only its own value. An unknown value, or `ERP` for promotions or loyalty, stops the startup with `Invalid value '<value>' for property <key>: allowed values are [...]`. Checks across keys for a head office (owner `HEAD_OFFICE`, `sales.upstream`): see `docs/modules/head-office.md`.
- **Owners agree with `application.standalone`** (step 9, task 9.1a), on a store and on a head office. With `application.standalone=true`, an explicit owner `ERP` stops the startup (`Invalid combination: ownership.supply=ERP with application.standalone=true. Without an ERP nothing is owned by the ERP; ...`). With `application.standalone=false` (or absent), an explicit `ownership.catalogue`, `ownership.customers` or `ownership.supply` other than `ERP` stops it (`Invalid combination: ownership.customers=LOCAL with application.standalone=false. With an ERP the catalogue, the customers and the supply are the ERP's; ...`). Promotions and loyalty are not concerned. No profile file sets such a combination. Every configuration that starts then has an owner `ERP` exactly when `application.standalone=false`, which the step 9 questions rely on.
- **Step 9 questions** (task 9.1b): `NodeOwnership` and `ApplicationModeService` answer `isCatalogueFromErp()`, `isCustomersFromErp()`, `isSupplyFromErp()` and `hasErp()` (some owner is the ERP). They replace the `isStandalone()` checks group by group (tasks 9.1c and after). Each one equals `application.standalone=false` for every configuration that starts. `ModeQuestionTruthTableTest` proves it over every real profile file (found by pattern, `application.properties` underneath, and alone) and over a grid of 61,440 configurations of the mode keys, the five owners, `node.type`, `headoffice.url` and `sales.upstream` (1,700 start, none answers differently; 872 since task 9.4a, which refuses every configuration with a franchise flag). It also checks `isHeadOfficeErpSet` / `isHeadOfficeStandaloneSet` against a head office with / without an ERP owner. Before task 9.1a the same grid had 10,418 accepted configurations and 8,580 that answered differently.

  | Gate (task) | Question | Answer with an ERP |
  |---|---|---|
  | `POST /item`, `PUT` / `DELETE /item/{id}`, `POST /item/quick-product`, `POST /item-family`, `POST /item-sub-family`, `POST /admin/import/preview` and `/execute` (9.1c) | `isCatalogueFromErp()` | 403, same messages as before |
  | `POST /customer`; invoices from POS tickets: `GET /admin/invoices/eligible-tickets`, `POST /admin/invoices`, `POST /admin/invoices/from-ticket/{ticketId}` (9.1c) | `isCustomersFromErp()` | 403, same messages |
  | Purchases: `GET /purchase-header/vendor-balance`, `/history`, `/{id}/details`, `POST /process-purchase`, `PATCH /{id}/set-paid`; purchase invoices: every `/admin/purchase-invoices` endpoint; `POST`, `PUT`, `DELETE /vendor`; `POST`, `PUT`, `DELETE /location` (decision: locations follow the supply until D8); `POST /item/{id}/adjust-stock` (9.1d) | `isSupplyFromErp()` | 403, same messages (purchase invoices: a 403 `ResponseStatusException`, as before) |
  | Startup (`ZZDataInitializer`, 9.1e): passenger customer on a first run | `!isCustomersFromErp()` | not created |
  | Startup: ERP checkpoints and sync jobs; ERP-only settings (9.1e) | `hasErp()` | created |
  | `GET /config` field `standalone` (9.1e): kept, with its name and value, for the frontend | `!hasErp()` | `false` |
  | Stock (9.1f): `StockService` (sale, return, purchase, adjustment, the two BL movements) and `StockMovementService` (their seven movements), called by the till at every sale and return | `isSupplyFromErp()` | no-op (no stock change, no movement), as before |

  Tests: `ModeGateTest` (each gate on an ERP store and on a head office with an ERP: 403 with its message), the existing guard and stock adjustment tests on a mode service built from properties (`support/TestModes`), `StockModeTest` (every stock change and movement on every real profile file: no-op when the supply is the ERP's, applied when local or fed by the head office), `ZZDataInitializerModeTest` (the real `init()` on a first start: passenger customer without an ERP, ERP checkpoints, ERP-only settings and ERP jobs with one).
- **Catalogue owned by the head office** (step 6): an explicit `ownership.catalogue=HEAD_OFFICE` needs `headoffice.url` (like promotions and loyalty) and stops the startup with `application.standalone=false` (`... with application.standalone=false. A store whose items come from an ERP ...`). See `docs/modules/head-office.md`, "Catalogue owned by the head office".
- **Supply from the head office** (step 7A): an explicit `ownership.supply=HEAD_OFFICE` needs `headoffice.url` and stops the startup with `application.standalone=false`, or without `ownership.catalogue=HEAD_OFFICE` (a BL names head office items by code). The store's supply beans need the explicit value (`NodeOwnership.isSupplyFromHeadOffice`). See `docs/modules/head-office.md`, "BLs at the store".
- **Presets** (head office plan, task 8.1): two profile files that give a network without an ERP; a franchise network is installed from them (design 2.3, franchise column; the franchise profiles were removed at step 9). Each install copies its file and replaces every `CHANGE_ME` (database password, head office host, store key). Procedure: `docs/modules/franchise.md`, "Installing a franchise network on the model".

| | `network-headoffice` | `network-store` |
|---|---|---|
| `node.type` | `HEAD_OFFICE` | STORE |
| `application.standalone`, ERP off, `pos.pricing.enable-sales-price-group=false` | yes | yes |
| Link (`headoffice.url`, `headoffice.api-key`) | — (a head office never links) | set, one key per store |
| Catalogue / supply | LOCAL / LOCAL | `HEAD_OFFICE` / `HEAD_OFFICE` (explicit) |
| Customers / promotions / loyalty | LOCAL | LOCAL (promotions and loyalty explicit) |
| Sales go to | nowhere | `HEAD_OFFICE` (explicit `sales.upstream`) |
| Heartbeat, sales copies, copies down | — (serves them) | yes |
| Step 6 catalogue and step 7A supply beans of a store (`catalogueFromHeadOffice`, `supplyFromHeadOffice` in `/config`) | no | yes |
| Head office beans without ERP (price lists, BLs) | yes | no |
| Port, log, images | 888, `C:/zsretail-headoffice/...` (a store may run on the same server) | 444 |

- Presets, continued: set on the store's row at the head office, not in the file: the two rights (price, purchase), whether its deliveries are invoiced, the billing details, the supply price mode and the invoice rhythm (step 7B). Test: `NetworkPresetTruthTableTest` (L1) reads the two real files and checks this table (until task 9.4a it also locked the two franchise profiles).
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
  - `catalogueFromHeadOffice` (step 6): `true` on a store whose catalogue is the head office's (URL, explicit `ownership.catalogue=HEAD_OFFICE`, no ERP). Since step 9 the same as `ownership.CATALOGUE` = `HEAD_OFFICE` (no franchise customer derives it any more); the frontend reads this flag.
  - `supplyFromHeadOffice` (step 7A, last field): `true` on a store whose goods come from the head office by BL (URL, explicit `ownership.supply=HEAD_OFFICE`, no ERP). Since step 9 the same as `ownership.SUPPLY` = `HEAD_OFFICE`; the frontend reads this flag.
- **Frontend store** (`store/app-config/index.js`, task 0.5): state `nodeType` (default `'STORE'`), `ownership` (default `{}`), `salesUpstreams` (default `[]`). Getters `nodeType`, `ownerOf(domain)` (owner name, `null` when unknown) and `salesUpstreams`. Defaults when talking to an older backend, or when the call fails: a missing or unknown `nodeType` gives `'STORE'`, a missing or non-object `ownership` gives `{}`, a missing or non-array `salesUpstreams` gives `[]`. No component, route or menu reads them yet.
- Tests: `ApplicationModeOwnershipTest` (L1, task 0.4); `AppConfigAPITest` (L1, task 0.5: for the four profiles, the old /config fields keep their names, order and values, and the new fields match the table above).
