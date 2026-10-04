# Deployment modes: presets and machine files

**Status**: since the head office plan, step 9 (task 9.3, 2026-10-04). An installation is a **preset** (what it is) plus
a **machine file** (where it runs). The old profile files (`standalone-dev|prod`, `dynamics-dev|test|prod`,
`headoffice-dev`, ...) and the property `application.standalone` are gone; their values live on as machine files in
`deploy/` (every value kept).

## 1. Presets (inside the WAR)

`src/main/resources/application-<preset>.properties`: mode keys only, every owner stated.

| Preset | What it is | Catalogue | Customers | Promotions | Loyalty | Supply | Sales go to | ERP connector |
|---|---|---|---|---|---|---|---|---|
| `store` | a store without an ERP (one shop alone) | LOCAL | LOCAL | LOCAL | LOCAL | LOCAL | nowhere | off |
| `store-erp` | a store on Dynamics NAV / Business Central | ERP | ERP | LOCAL | LOCAL | ERP | ERP | on |
| `headoffice` | a head office without an ERP (never sells; replaces step 8's `network-headoffice`) | LOCAL | LOCAL | LOCAL | LOCAL | LOCAL | nowhere | off |
| `headoffice-erp` | a head office with an ERP (imports only) | ERP | ERP | LOCAL | LOCAL | ERP | nowhere | on |
| `network-store` | a store fed by its head office (own store or franchise store) | HEAD_OFFICE | LOCAL | LOCAL | LOCAL | HEAD_OFFICE | HEAD_OFFICE | off |
| `network-store-erp` | an ERP store linked to a head office (ParaFendri) | ERP | ERP | HEAD_OFFICE | HEAD_OFFICE | ERP | ERP, HEAD_OFFICE | on |

`node.type` is `HEAD_OFFICE` for the two head office presets, `STORE` otherwise. "ERP connector" is
`erp.dynamicsnav.enabled`. A store's own answer may differ from its preset: an `ownership.*` or `sales.upstream` key in
its machine file wins (the dev stores B and C take their loyalty from the head office this way).

Each preset gives exactly the answers of the old profile it replaces: `PresetTruthTableTest` holds the rows frozen from
the old files (commit 596f1bb, task 9.3a) and checks every preset with its machine file against them.

## 2. Machine file (outside the WAR)

One per installation: database, port, log, uploads, NAV settings, head office address and key, sales-push start date,
pricing groups, and the preset (`spring.profiles.active=<preset>`). Model with comments: `deploy/machine-model.properties`.

**Where it is found** (`config/MachineFileEnvironmentPostProcessor`):
1. the system property `-Dzsretail.machine-file=<path>` (dev scripts, the IDE; on a server it overrides the convention);
2. otherwise, in Tomcat, `${catalina.base}/conf/zsretail/<context name>.properties`, the context name of the WAR
   (`zsretailws.war` deploys as `zsretailws`, so `conf/zsretail/zsretailws.properties`). A head office and a store can run
   on one Tomcat as two WARs with two context names, so two files.

**Precedence**: command-line arguments and system properties, then the machine file, then the preset, then
`application.properties`. The machine file is read before Spring reads its configuration files, so its
`spring.profiles.active` picks the preset.

**No default**: the application refuses to start, with a message that says what is missing, when there is no machine
file (`No machine file: start with -Dzsretail.machine-file=<path of the file> ...`), when the file is not there
(`Machine file not found: <path> ...`), when it names no preset or an unknown one (`The machine file <path> must name one
preset: spring.profiles.active=store | store-erp | headoffice | headoffice-erp | network-store | network-store-erp`),
when a `spring.profiles.active` left in the server's options names something else (`The active profile is '<name>' while
the machine file <path> names a preset: remove spring.profiles.active from the server's options ...`), and when any
source still sets `application.standalone` (`The property application.standalone was removed (head office plan, step 9,
task 9.3): name a preset in the machine file instead, spring.profiles.active=store | ...`). `mvn test` needs none: no test
starts a Spring context; the tests read the presets and the machine files of `deploy/` directly (`support/Installations`).

**The `deploy/` folder** (versioned in this repository):

| File | Preset | Was |
|---|---|---|
| `machine-model.properties` | — | the model, every key commented |
| `dev/headoffice.properties` | `headoffice` | `application-headoffice-dev.properties` (devenv instance headoffice, 888) |
| `dev/headoffice-erp.properties` | `headoffice-erp` | `application-headoffice-dynamics-dev.properties` (TEST NAV) |
| `dev/store-b.properties` | `network-store` + loyalty from the head office | `application-store-b-dev.properties` (555) |
| `dev/store-c.properties` | `network-store` + loyalty from the head office, supply local | `application-store-c-dev.properties` (556) |
| `dev/store-a.properties` | `store` + sales copied to the dev head office | `application-standalone-dev.properties` (store A, `pos_db_prod`) |
| `dev/store-a-erp.properties` | `store-erp` | `application-dynamics-dev.properties`: **its NAV is the customer's production NAV, never start it against that NAV** |
| `dev/store-test-nav.properties` | `store-erp` | `application-dynamics-test.properties` (TEST NAV 192.168.10.166) |
| `customers/erp-prod.properties` | `store-erp` | `application-dynamics-prod.properties` |
| `customers/store-prod.properties` | `store` | `application-standalone-prod.properties` |

In each moved file the mode keys the preset gives are commented out (`# key=value (given by the preset ...)`), a mode key
that differs stays as an override, and `application.standalone` is commented out (`# ... (removed at task 9.3 ...)`).

**From the IDE (Eclipse / STS)**: one setting picks the dev machine. Run Configurations, `POSMainApp` (Spring Boot App or
Java Application), Arguments, VM arguments:
`-Dzsretail.machine-file="D:\ZS Retail\Apps\ZSRetail-Back\deploy\dev\store-a.properties"` (store A; or `dev\headoffice.properties`
for the head office, and so on). Nothing else changes: the WAR, `application.properties` and the presets are the same everywhere.
A launch configuration from before 2.1 with `-Dspring.profiles.active=<old profile>` no longer starts (the profile is gone and
the startup refuses it): replace that argument with the machine file. Shared launch configurations of the rehearsal environment:
`eclipse/*.launch` (`docs/modules/head-office.md`, "Rehearsal environment").

**Dev scripts** (`devenv/`): each instance runs from its machine file (`common.ps1`: `Machine = 'dev\store-b.properties'`),
started with `-Dzsretail.machine-file`. A key written into a machine file (the store's key by `setup-stores.ps1 -Phase register`)
is read at the next start: no rebuild.

## 3. Procedures

**New installation**
1. Create the empty SQL Server database.
2. Copy `deploy/machine-model.properties`, set `spring.profiles.active` to the preset, the database, the log file and image
   folder (their own per installation), and what the preset needs (NAV settings for an `-erp` preset).
3. Put it at `${catalina.base}/conf/zsretail/<context name>.properties` (or point `-Dzsretail.machine-file` to it), deploy the
   WAR, start Tomcat. Hibernate creates the tables; the logins of a new database are created at the first start.
4. A store of a network (`network-store`, `network-store-erp`): create its row on the head office Stores page (code = the
   store's `DEFAULT_LOCATION`), put the key shown once into `headoffice.api-key` with `headoffice.url`, restart the store.
   Franchise network: `docs/modules/franchise.md`, "Installing a franchise network on the model".
5. Check `GET /config`: `nodeType`, `ownership` and `salesUpstreams` are those of the preset.

**Upgrade of an existing installation to 2.1** (it ran an old profile)
1. Before stopping it, note the profile it runs (`spring.profiles.active` in its `application.properties`, the Tomcat options or
   `SPRING_PROFILES_ACTIVE`). Its machine file: the matching file of `deploy/customers/` when there is one; otherwise copy the
   old profile file, add `spring.profiles.active=<preset>` (standalone-* gives `store`, dynamics-* gives `store-erp`,
   headoffice-dev gives `headoffice`), and remove `application.standalone`.
2. Put the machine file at `${catalina.base}/conf/zsretail/<context name>.properties`, and remove any `spring.profiles.active`
   and `application.standalone` from the Tomcat options (the startup refuses them).
3. Run the release's `update.sql`, deploy the 2.1 WAR, start, check `GET /config` as above.

## 4. Ownership model (resolved at startup)

`config/NodeOwnership.java`, exposed by `ApplicationModeService` (`getNodeType()`, `ownerOf(DataDomain)`, `salesUpstreams()`,
`isHeadOffice()`, `isHeadOfficeLinked()`, `isCatalogueFromHeadOffice()`, `isSupplyFromHeadOffice()`, and the step 9
questions). Values are trimmed and case-insensitive.

| Key | Values | When absent |
|---|---|---|
| `node.type` | `STORE`, `HEAD_OFFICE` | `STORE` |
| `ownership.catalogue`, `ownership.customers`, `ownership.supply` | `LOCAL`, `HEAD_OFFICE`, `ERP` | `LOCAL` |
| `ownership.promotions`, `ownership.loyalty` | `LOCAL`, `HEAD_OFFICE` | `LOCAL` |
| `sales.upstream` | comma list of `ERP`, `HEAD_OFFICE`; empty = none | none |

Startup checks (the application does not start, the message names the key): an unknown value; `ERP` for promotions or
loyalty; the ERP owning only part of the catalogue, the customers and the supply (`Invalid combination: ownership.catalogue=ERP
with ownership.customers=LOCAL. The ERP owns the catalogue, the customers and the supply together ...`); an owner
`HEAD_OFFICE` on a store without `headoffice.url`; `ownership.supply=HEAD_OFFICE` without `ownership.catalogue=HEAD_OFFICE`;
on a head office, an owner `HEAD_OFFICE`, a non-empty `sales.upstream` or `headoffice.url`; `franchise.admin` or
`franchise.customer` set to `true` (the franchise profiles were removed, task 9.4a); `application.standalone` (removed,
task 9.3). Head office details: `docs/modules/head-office.md`.

**Step 9 questions** (tasks 9.1b to 9.1g): `isCatalogueFromErp()`, `isCustomersFromErp()`, `isSupplyFromErp()`, `hasErp()` (some
owner is the ERP). With the ERP owning all or nothing they answer alike for every configuration that starts
(`ModeQuestionTruthTableTest`: every preset and machine file, and a grid of 15,360 configurations). They replaced the old
standalone checks:

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

## 5. Head office link (store side)

A store that calls a head office (task 1.4): without `headoffice.url` none of the link beans exists. Details, startup checks and
the "Connect a store" procedure: `docs/modules/head-office.md`, "Head office link".

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

## 6. Frontend

- `GET /config` is public, loaded before login (`store/app-config/index.js`, one state field per field, with a default when a
  field is missing or the call fails). `standalone` (no ERP owner) drives the ERP-only and no-ERP-only screens: the ERP menu
  group, the sync columns of the tickets and returns history, "Add customer", "Add product" (quick product), the family and
  sub-family actions, purchases and vendors. `enableSalesPriceGroup` (`pos.pricing.enable-sales-price-group` of the machine
  file) shows the Sales Prices and Sales Discounts pages.
- The quick product endpoint is `POST /item/quick-product` since task 9.1g (it was `/item/standalone-quick-product`).
