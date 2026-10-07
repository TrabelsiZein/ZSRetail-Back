# Deployment modes: two types, three files

**Status**: configuration step C1 (decision of 2026-10-04 evening), replacing the six presets and the mandatory machine
file of task 9.3. An installation is a **type**, `store` or `headoffice`, plus an optional **outside file**. The rule "the ERP
owns the catalogue, the customers and the supply together" is unchanged (step C2 will revisit it).

## 1. The three files (inside the WAR)

| File | Holds |
|---|---|
| `application.properties` | common lines: context path, upload limits, SQL Server driver, Hibernate dialect, `ddl-auto=update`, log rotation, and the default type `spring.profiles.active=store` |
| `application-store.properties` | the type store |
| `application-headoffice.properties` | the type headoffice |

Each type file has two titled blocks:
- **What this installation is**: `node.type`, the five `ownership.*`, `sales.upstream`, `erp.dynamicsnav.enabled`, each with a
  comment giving its allowed values. Both types ship with every owner `LOCAL`, sales nowhere, no NAV connector. The store
  file shows the two usual variants as comments: a store on NAV (catalogue, customers, supply `ERP`, sales to `ERP`, connector
  on) and a store of a network (catalogue and supply `HEAD_OFFICE`, sales to `HEAD_OFFICE`, plus the head office link).
- **Where it runs**: database, `server.port`, log file, upload folder, `pos.pricing.enable-sales-price-group`, the NAV connection
  (commented) and, in the store file, `headoffice.url` / `headoffice.api-key` (commented). The values are the dev ones: store A
  (`pos_db_prod`, 444) and the dev head office (`pos_headoffice`, 888). The database password is not in the WAR:
  `spring.datasource.password=${ZSRETAIL_DB_PASSWORD:}`, an environment variable for an IDE start without an outside file,
  or the key in the outside file. On a developer PC the variable is set once as a Windows user variable, so `POSMainApp`
  starts directly from the IDE with nothing else to set.

So `POSMainApp` starts from the IDE with no argument as a store (or `-Dspring.profiles.active=headoffice`).

## 2. The outside file (optional)

Usually only the "where it runs" block of a real installation; it may also name the type and change any mode line. Model:
`deploy/machine-model.properties`. Loaded by `config/MachineFileEnvironmentPostProcessor`, before Spring reads its
configuration files (so its `spring.profiles.active` picks the type), when:
1. `-Dzsretail.machine-file=<path>` names it (dev scripts, IDE, or to override the Tomcat convention); a path that does not
   exist stops the startup (`Outside file not found: <path> ...`);
2. otherwise, in Tomcat, `${catalina.base}/conf/zsretail/<context name>.properties` exists (`zsretailws.war` deploys as
   `zsretailws`: `conf/zsretail/zsretailws.properties`; a head office and a store on one Tomcat are two WARs, two files).

Neither: no outside file, the type file applies as it is.

**Precedence**: command-line arguments and system properties, then the outside file, then the type file, then
`application.properties`.

**Type check** (`config/InstallationTypeEnvironmentPostProcessor`, after the configuration files are read): the active profile
in effect is exactly `store` or `headoffice`; anything else (none, two, an old preset name such as `store-erp`, an old profile
such as `dynamics-prod` left in the server's options) stops the startup: `The active profile is [<names>]: it must be one
installation type, spring.profiles.active=store | headoffice ...`. `application.standalone` stays refused.

**Startup summary**: one INFO line once the application is up (`config/InstallationSummary`), e.g.
`Installation: type STORE, database pos_store_b, catalogue HEAD_OFFICE, customers LOCAL, promotions LOCAL, loyalty HEAD_OFFICE,
supply HEAD_OFFICE, sales to [HEAD_OFFICE], outside file D:\...\deploy\dev\store-b.properties`. With the NAV connector on it
adds `NAV <base-url>`; never a password or a key.

**The `deploy/` folder** (versioned): every file names its type and, when the old preset differed from the type file, states
the mode lines that preset gave, so every key of every file resolves exactly as before step C1 (checked key by key).

| File | Type + mode lines | Was |
|---|---|---|
| `machine-model.properties` | — | the model |
| `dev/headoffice.properties` | `headoffice` | devenv instance headoffice (888) |
| `dev/headoffice-erp.properties` | `headoffice` + catalogue, customers, supply `ERP`, connector on | TEST NAV head office |
| `dev/store-a.properties` | `store` + sales to `HEAD_OFFICE` | store A (`pos_db_prod`) |
| `dev/store-a-erp.properties` | `store` + the NAV lines | **its NAV is the customer's production NAV, never start it against that NAV** |
| `dev/store-test-nav.properties` | `store` + the NAV lines | TEST NAV 192.168.10.166 |
| `dev/store-b.properties` | `store` + catalogue, supply, loyalty `HEAD_OFFICE`, sales to `HEAD_OFFICE` | devenv store-b (555) |
| `dev/store-c.properties` | `store` + catalogue, loyalty `HEAD_OFFICE`, supply `LOCAL`, sales to `HEAD_OFFICE` | devenv store-c (556) |
| `rehearsal/headoffice.properties` | `headoffice` | rehearsal (889) |
| `rehearsal/store-1.properties`, `store-2.properties` | `store` + the network lines | rehearsal (557, 558) |
| `customers/erp-prod.properties` | `store` + the NAV lines | the customer on Dynamics NAV |
| `customers/store-prod.properties` | `store` | the customer without an ERP |

"The NAV lines": catalogue, customers, supply `ERP`, sales to `ERP`, connector on. "The network lines": catalogue and supply
`HEAD_OFFICE`, sales to `HEAD_OFFICE`.

**IDE (Eclipse / STS)**: `POSMainApp` as it is is a store on the type file (set `ZSRETAIL_DB_PASSWORD` in the launch's
Environment tab). For a dev machine, VM arguments `-Dzsretail.machine-file="D:\ZS Retail\Apps\ZSRetail-Back\deploy\dev\store-b.properties"`
(the shared `eclipse/*.launch` do this). A launch with `-Dspring.profiles.active=<old profile>` is refused.

**Dev scripts** (`devenv/`): unchanged, each instance starts with `-Dzsretail.machine-file` (`common.ps1`).

**Tests**: no test starts a Spring context. `support/Installations` merges `application.properties`, the type file and an
outside file of `deploy/` like the application; it keeps the six shapes of task 9.3 as variants (type file + mode lines), so
`PresetTruthTableTest`, `NetworkPresetTruthTableTest` and `ModeQuestionTruthTableTest` check their frozen rows unchanged.
`MachineFileTest` runs real starts (the two post-processors around Spring Boot's `ConfigFileApplicationListener`).

## 3. Procedures

**New installation**
1. Create the empty SQL Server database.
2. Copy `deploy/machine-model.properties`: database, log file and image folder (their own per installation), the type
   (`headoffice` for a head office), and the mode lines when the installation is not a plain store or head office (a store on
   NAV, a store of a network: the variants in `application-store.properties`), with the NAV connection or the head office link.
3. Put it at `${catalina.base}/conf/zsretail/<context name>.properties` (or point `-Dzsretail.machine-file` to it), deploy the
   WAR, start Tomcat. Hibernate creates the tables; the logins of a new database are created at the first start.
4. A store of a network: create its row on the head office Stores page (code = the store's `DEFAULT_LOCATION`), put the key
   shown once into `headoffice.api-key` with `headoffice.url`, restart the store. Franchise network: `docs/modules/franchise.md`.
5. Check the startup summary line in the log, and `GET /config` (`nodeType`, `ownership`, `salesUpstreams`).

**Upgrade of a 2.1 installation built on the presets**: in its outside file, replace `spring.profiles.active=<preset>` with the
type and the mode lines of that preset (table above, or `support/Installations`); `store` and `headoffice` need nothing.

**Upgrade from before 2.1** (an old profile): its outside file is the matching file of `deploy/customers/` when there is one;
otherwise copy the old profile file, add the type and mode lines (standalone-* is `store`; dynamics-* is `store` + the NAV
lines; headoffice-dev is `headoffice`), remove `application.standalone`, and remove any `spring.profiles.active` from the Tomcat
options. Then the release's `update.sql`, the WAR, a start, and the checks above.

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
(`ModeQuestionTruthTableTest`: every variant and outside file, and a grid of 15,360 configurations). They replaced the old
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

Tests: `ModeGateTest` (each gate on an ERP store and on a head office with an ERP: 403 with its message), the existing guard and stock adjustment tests on a mode service built from properties (`support/TestModes`), `StockModeTest` (every stock change and movement on every variant and outside file of `deploy/`: no-op when the supply is the ERP's, applied when local or fed by the head office), `ZZDataInitializerModeTest` (the real `init()` on a first start: passenger customer without an ERP, ERP checkpoints, ERP-only settings and ERP jobs with one).

## 5. Head office link (store side)

A store that calls a head office (task 1.4): without `headoffice.url` none of the link beans exists. Details, startup checks and
the "Connect a store" procedure: `docs/modules/head-office.md`, "Head office link".

| Key | Value | When absent |
|---|---|---|
| `headoffice.url` | Head office base URL including the context path, e.g. `http://localhost:888/zsretail/api`; a trailing slash is tolerated | no link (blank = absent) |
| `headoffice.api-key` | The store's key, shown once on the head office Stores page | required when the URL is set |
| `headoffice.heartbeat-interval-seconds` | Whole number, at least 1 | `60` |

- **Head office stock**, head office only: `headoffice.stock.enabled`, `true` (default, stated in the head office type file)
  or `false` (no stock, no purchases, a BL moves no stock); any other value stops a head office at startup; a store never
  reads it. `GET /config` field `headOfficeStock`. See `docs/modules/head-office.md`, "Head office without stock".
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
  sub-family actions, purchases and vendors. `enableSalesPriceGroup` (`pos.pricing.enable-sales-price-group` of the type or outside
  file) shows the Sales Prices and Sales Discounts pages.
- The quick product endpoint is `POST /item/quick-product` since task 9.1g (it was `/item/standalone-quick-product`).
