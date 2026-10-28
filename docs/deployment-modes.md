# Deployment modes: two applications (two types), three files

**Status**: configuration step C1 (decision of 2026-10-04 evening), replacing the six presets and the mandatory machine
file of task 9.3. An installation is a **type**, `store` or `headoffice`, plus an optional **outside file**. The rule "the ERP
owns the catalogue, the customers and the supply together" is unchanged (step C2 will revisit it), with one exception on a
head office since ERP catalogue step 1: the catalogue alone from the ERP (section 4).

**Two applications** (code cleanup step 6.5): the store and the head office are two applications, each its WAR
(`docs/deploy-note.md`). The application is the type: it activates its type file and gives its type as default
properties (`core/config/ZsRetailApplication`: `spring.profiles.active`, `node.type`, `zsretail.application.type`), so
an installation names no type. Since code cleanup step 8 (decision of 2026-10-11) a `spring.profiles.active` or
`node.type` line from any other source stops the start (below, **Type check**). Every key of every file of `configs/`
resolves as before (`ConfigurationKeysProofTest`); the old lines of 2.2.2 and their upgrade: `docs/deploy-note.md`,
"Upgrade from 2.2.2 to 2.3.0: the outside file".

## 1. The three files (inside the WARs)

| File | Holds |
|---|---|
| `application.properties` (core, in both WARs) | common lines: context path, upload limits, SQL Server driver, Hibernate dialect, `ddl-auto=update`, log rotation (until step 6.5 also the default type `spring.profiles.active=store`) |
| `application-store.properties` (the store WAR) | the type store |
| `application-headoffice.properties` (the head office WAR) | the type headoffice |

Each type file has two titled blocks:
- **What this installation is**: the five `ownership.*`, `sales.upstream`, a comment naming `erp.connector`
  (section 2), each with a comment giving its allowed values. Both types ship with every owner `LOCAL`, sales nowhere, no ERP connector. The store
  file shows the two usual variants as comments: a store on NAV (catalogue, customers, supply `ERP`, sales to `ERP`, connector
  on) and a store of a network (catalogue and supply `HEAD_OFFICE`, sales to `HEAD_OFFICE`, plus the head office link).
- **Where it runs**: database, `server.port`, log file, upload folder, `pos.pricing.enable-sales-price-group`, the NAV connection
  (commented) and, in the store file, `headoffice.url` / `headoffice.api-key` (commented). The values are the dev ones: store A
  (`pos_db_prod`, 444) and the dev head office (`pos_headoffice`, 888). The database password is not in the WAR:
  `spring.datasource.password=${ZSRETAIL_DB_PASSWORD:}`, an environment variable for an IDE start without an outside file,
  or the key in the outside file. On a developer PC the variable is set once as a Windows user variable, so `StoreApplication`
  starts directly from the IDE with nothing else to set.

So the store application (`StoreApplication`, was `POSMainApp`) starts from the IDE with no argument as a
store, and `HeadOfficeApplication` as a head office.

## 2. The outside file (optional)

Usually only the "where it runs" block of a real installation; it may also change any mode line, never the type.
Model:
`configs/machine-model.properties`. Loaded by `core/config/MachineFileEnvironmentPostProcessor`, before Spring reads its
configuration files, when:
1. `-Dzsretail.machine-file=<path>` names it (dev scripts, IDE, or to override the Tomcat convention); a path that does not
   exist stops the startup (`Outside file not found: <path> ...`);
2. otherwise, in Tomcat, `${catalina.base}/conf/zsretail/<context name>.properties` exists (`zsretailws.war` deploys as
   `zsretailws`: `conf/zsretail/zsretailws.properties`; since step 6.5 each application is deployed alone in its Tomcat as
`zsretailws`).

Neither: no outside file, the type file applies as it is.

**Precedence**: command-line arguments and system properties, then the outside file, then the type file, then
`application.properties`.

**Type check** (`core/config/InstallationTypeEnvironmentPostProcessor`, after the configuration files are read; code
cleanup step 8): a `spring.profiles.active` or `node.type` line from any source but the application's own defaults (the
outside file, a configuration file, a `-D`, an environment variable such as `SPRING_PROFILES_ACTIVE`, Tomcat's
options), whatever its value (the application's type, the other type, an old profile such as `dynamics-prod`), stops
the startup. The message names each line and where it is, says to remove it, and, when a line names the other type,
which application that installation needs; the old lines of the connectors found at the same start follow in the same
message, so one start names every old line:

```
Old lines no longer read: spring.profiles.active=store in the outside file C:\...\zsretailws.properties; node.type=STORE
in the outside file C:\...\zsretailws.properties. An installation no longer names its type: the application is its type
(this is the head office application, HeadOfficeApplication). Remove the lines. It names a store: a store runs the
store application, deploy its WAR in this Tomcat (under zsretailws).
```

A profile added another way (`spring.profiles.include`) is refused too: `The active profiles are [store, x]: the store
application runs with its profile store only. ...`. `application.standalone` stays refused. Until code cleanup step 8
an old file that named the application's own type kept working; only the other type was refused.

**ERP connector** (code cleanup step 1; since code cleanup step 8 the only way): one line chooses it,
`erp.connector=none | emtop | happyness | zen` (trimmed, any case), in the installation's outside file. Absent or `none`: no
connector. The conditions, the connectors and the startup summary read it (`ErpConnectorEnvironmentPostProcessor.inEffect`).
`core/config/ErpConnectorEnvironmentPostProcessor` checks it once the configuration files are read: an unknown value
(an empty `erp.connector=` included) stops the startup, `Invalid value '<v>' for property erp.connector: none | emtop |
happyness | zen.`

**The old enabled lines are refused** (code cleanup step 8, decision of 2026-10-11): `erp.dynamicsnav.enabled` and
`erp.navpospages.enabled`, found in any property source (the outside file, a configuration file, a `-D`, an environment
variable such as `ERP_DYNAMICSNAV_ENABLED`), whatever their value, even beside an `erp.connector` that agrees, stop the
startup. One message names every old line, where it is and what to write instead:

```
Old line no longer read: erp.navpospages.enabled=true in the outside file C:\...\zsretailws.properties: write
erp.connector=happyness instead. The ERP connector is chosen by erp.connector only (none | emtop | happyness; no line: none).
```

| Old line | Write instead |
|---|---|
| `erp.dynamicsnav.enabled=true` | `erp.connector=emtop` |
| `erp.navpospages.enabled=true` | `erp.connector=happyness` |
| either, `false` | nothing: remove the line |

Each connector declares its old prefix (`ErpConnectorDescriptor.oldKeyPrefix()`), so the refusal names no connector in
core. The upgrade of an outside file: `docs/deploy-note.md`, "Upgrade from 2.2.2 to 2.3.0: the outside file".

Code cleanup step 5: the values are the customers' names, one connector per customer even when two ERPs look alike:
`emtop` (EMTOP's Dynamics NAV, keys `erp.emtop.*`), `happyness` (Happyness's POS pages, keys `erp.happyness.*`) and, since 3.1.0, `zen` (ZEN's NAV custom API, keys `erp.zen.*`, below);
they were `dynamicsnav` and `navpospages`, which no installed file used. The names and `@ConditionalOnErpConnector` are in
`erp/api` (`ErpConnectors`); erp-api asks the application which connector is in effect through `ErpConnectorResolver`
(`core/config/ErpConnectorInEffect`, registered in `META-INF/spring.factories`), so it names no key of the application.

Until code cleanup step 8, `erp.connector` was translated into the old lines, which were still accepted alone; the
type files said `erp.dynamicsnav.enabled=false` (gone). Every bean of a connector exists by `@ConditionalOnErpConnector`.

**Abilities and the rule "the application never names a connector"** (code cleanup step 2, `docs/modules/erp-sync.md`,
"Abilities"): the application asks `erp/api/ErpAbilities` what the connector in effect can do (`HEAD_OFFICE_STOCK_POINTS`,
`SUPPLY_INVOICES`: `happyness` only), whichever way the connector was chosen. Exactly one `ErpConnector` bean exists, the
one of the connector in effect. Outside the connector packages, no code names `erp.emtop` or `erp.happyness`, nor their
old prefixes; the guard test `erp/ConnectorNamesGuardTest` checks it.

**The keys of each connector are `erp.<its name>.*`** (code cleanup step 8): `erp.emtop.*` (was `erp.dynamicsnav.*`) and
`erp.happyness.*` (was `erp.navpospages.*`), the rest of each key unchanged (`erp.dynamicsnav.base-url` is now
`erp.emtop.base-url`). Any key of an old block, found in any property source, stops the startup; the message lists the
old keys of each source with their new names (an environment variable as written: `ERP_DYNAMICSNAV_BASEURL is now
ERP_EMTOP_BASEURL`):

```
Old keys no longer read, in the outside file C:\...\zsretailws.properties: erp.dynamicsnav.base-url is now
erp.emtop.base-url, erp.dynamicsnav.company is now erp.emtop.company. Rename them: the keys of a connector are
erp.<its name>.*.
```

With an old enabled line as well, its refusal comes first, in the same message. The old names are declared by each
connector's descriptor only (`oldKeyPrefix()`); the guard test `OldKeysGuardTest` checks that no main code, resource
(the released SQL scripts apart), file of `configs/` (`configs/local` apart), `eclipse/` or `devenv/` names them.

**Dead keys are refused too** (code cleanup step 8, Zein's rule: no old line is accepted or ignored silently): a key
that nothing reads any more, whatever its value, stops the startup with "remove the line", never a new name. A
connector declares its own (`ErpConnectorDescriptor.deadKeys()`, refused under its prefix and its old one): Happyness's
`location-code`, `invoices.years`, `invoices.start-number`, `deactivate-guard-percent`. The application's are declared
in core (`core/config/OldLines`): the keys of the removed franchise profiles, `franchise.admin`, `franchise.customer`,
`franchise.customer.allow-local-items`, `franchise.api-key`, `franchise.remote.url`, `franchise.sync.sales.cron`. A dead
key is named, never its value (`franchise.api-key` is a secret). They come last in the one message of the start, after
the type lines and the connectors' lines (`OldLines`):

```
Lines no longer read: in the outside file C:\...\zsretailws.properties, erp.navpospages.invoices.years,
erp.navpospages.location-code. Remove the lines: nothing reads them any more.
```

**Startup summary**: one INFO line once the application is up (`core/config/InstallationSummary`), e.g.
`Installation: type STORE, database pos_store_b, catalogue HEAD_OFFICE, customers LOCAL, promotions LOCAL, loyalty HEAD_OFFICE,
supply HEAD_OFFICE, sales to [HEAD_OFFICE], ERP connector none, outside file D:\...\configs\dev\store-b.properties`. The connector
(`none` or a connector found: `emtop`, `happyness`) is the value of `erp.connector`. With `emtop` it adds `NAV <base-url>` (what
its descriptor declares); never a password or a key.

**The `configs/` folder** (versioned; `deploy/` joined it at code cleanup step 7): no file names its type (code cleanup
step 8; each file is a store's or a head office's, as its name says, and runs with that WAR) and, when the old preset differed
from the type file, a file states the mode lines that preset gave, so every key of every file resolves exactly as before step C1
(checked key by key, `ConfigurationKeysProofTest`).

| File | Type + mode lines | Was |
|---|---|---|
| `machine-model.properties` | — | the model |
| `dev/headoffice.properties` | `headoffice` | devenv instance headoffice (888) |
| `dev/headoffice-erp.properties` | `headoffice` + catalogue, customers, supply `ERP`, `erp.connector=emtop` | TEST NAV head office |
| `dev/store-a.properties` | `store` + sales to `HEAD_OFFICE` | store A (`pos_db_prod`) |
| `dev/store-a-erp.properties` | `store` + the NAV lines | **its NAV is the customer's production NAV, never start it against that NAV** |
| `dev/store-test-nav.properties` | `store` + the NAV lines | TEST NAV 192.168.10.166 |
| `dev/store-b.properties` | `store` + catalogue, supply, loyalty `HEAD_OFFICE`, sales to `HEAD_OFFICE` | devenv store-b (555) |
| `dev/store-c.properties` | `store` + catalogue, loyalty `HEAD_OFFICE`, supply `LOCAL`, sales to `HEAD_OFFICE` | devenv store-c (556) |
| `rehearsal/headoffice.properties` | `headoffice` | rehearsal (889) |
| `rehearsal/store-1.properties`, `store-2.properties` | `store` + the network lines | rehearsal (557, 558) |
| `customers/erp-prod.properties` | `store` + the NAV lines | the customer on Dynamics NAV |
| `customers/store-prod.properties` | `store` | the customer without an ERP |
| `happyness-from-scratch/headoffice.properties`, `store-1.properties`, `store-2.properties` | `headoffice`, `store` + the network lines | the Happyness franchise first set up on this PC (889, 557, 558), without the ERP catalogue |
| `customers/happyness-headoffice.properties` | `headoffice` + the catalogue only from the ERP, `erp.connector=happyness`, invoices from the ERP, no stock | the Happyness head office (was `local/happyness_ho.properties`, its own PC file, until 2026-10-11; `configs/local` is no longer in the repository) |
| `customers/zen-headoffice.properties`, `customers/zen-store.properties` (3.1.0, item variants step 10) | `headoffice` as the Happyness head office with `erp.connector=zen` (supply `HEAD_OFFICE` until the live test of step 9b; the real installation `ERP`), `store` + the network lines with `erp.connector=none` | the ZEN head office and a store of its network, placeholders (`CHANGE_ME`) |

"The NAV lines": catalogue, customers, supply `ERP`, sales to `ERP`, `erp.connector=emtop`. "The network lines": catalogue and supply
`HEAD_OFFICE`, sales to `HEAD_OFFICE`.

**IDE (Eclipse / STS)**: `StoreApplication` as it is is a store on the type file (`HeadOfficeApplication` a head office on its own) (set `ZSRETAIL_DB_PASSWORD` in the launch's
Environment tab). For a dev machine, VM arguments `-Dzsretail.machine-file="D:\ZS Retail\Apps\ZSRetail-Back\configs\dev\store-b.properties"`
(the shared `eclipse/*.launch` do this). A launch with `-Dspring.profiles.active` (any value) is refused.

**Dev scripts** (`devenv/`): unchanged, each instance starts with `-Dzsretail.machine-file` (`common.ps1`).

**Tests**: no test starts a Spring context. `support/Installations` merges `application.properties`, the type file and an
outside file of `configs/` like the application; it keeps the six shapes of task 9.3 as variants (type file + mode lines), so
`PresetTruthTableTest`, `NetworkPresetTruthTableTest` and `ModeQuestionTruthTableTest` check their frozen rows unchanged.
`MachineFileTest` runs real starts (the two post-processors around Spring Boot's `ConfigFileApplicationListener`).

## 3. Procedures

**New installation**
1. Create the empty SQL Server database.
2. Copy `configs/machine-model.properties`: database, log file and image folder (their own per installation), no type
   (the application is the type), and the mode lines when the installation is not a plain store or head office (a store on
   NAV, a store of a network: the variants in `application-store.properties`), with the NAV connection or the head office link.
   The ERP connector with `erp.connector` (section 2), the only line that chooses it.
3. Put it at `${catalina.base}/conf/zsretail/<context name>.properties` (or point `-Dzsretail.machine-file` to it), deploy the
   WAR of its application as `zsretailws` (`docs/deploy-note.md`), start Tomcat. Hibernate creates the tables; the logins of a new database are created at the first start.
4. A store of a network: create its row on the head office Stores page (code = the store's `DEFAULT_LOCATION`), put the key
   shown once into `headoffice.api-key` with `headoffice.url`, restart the store. Franchise network: `docs/modules/franchise.md`.
5. Check the startup summary line in the log, and `GET /config` (`nodeType`, `ownership`, `salesUpstreams`).

**Upgrade of a 2.1 installation built on the presets**: in its outside file, replace `spring.profiles.active=<preset>` with the
mode lines of that preset (table above, or `support/Installations`), and no type line (code cleanup step 8);
`store` and `headoffice` only lose the line.
Since code cleanup step 6.5 the installation runs the WAR of its type.

**Upgrade from before 2.1** (an old profile): its outside file is the matching file of `configs/customers/` when there is one;
otherwise copy the old profile file, add the mode lines (standalone-* is a store; dynamics-* is a store + the NAV
lines; headoffice-dev is a head office), remove `application.standalone`, and remove any `spring.profiles.active` from the Tomcat
options. Then as 2.2.2 below. Then the release's `update.sql`, the WAR of its type, a start, and the checks above.

**Upgrade from 2.2.2 to 2.3.0** (code cleanup step 8): in the outside file, remove the type lines, write `erp.connector` instead
of the old enabled line and rename the connector's keys, as `docs/deploy-note.md` lists them ("Upgrade from 2.2.2 to 2.3.0: the
outside file") for an EMTOP store, a Happyness head office and a Happyness store. A line left over stops the start and
the message names it.

## 4. Ownership model (resolved at startup)

`core/config/NodeOwnership.java`, exposed by `ApplicationModeService` (`getNodeType()`, `ownerOf(DataDomain)`, `salesUpstreams()`,
`isHeadOffice()`, `isHeadOfficeLinked()`, `isCatalogueFromHeadOffice()`, `isSupplyFromHeadOffice()`, and the step 9
questions). Values are trimmed and case-insensitive.

| Key | Values | When absent |
|---|---|---|
| `node.type` | `STORE`, `HEAD_OFFICE`: given by the application (its default properties); a line in any file is refused (code cleanup step 8) | `STORE` |
| `ownership.catalogue`, `ownership.customers`, `ownership.supply` | `LOCAL`, `HEAD_OFFICE`, `ERP` | `LOCAL` |
| `ownership.promotions`, `ownership.loyalty` | `LOCAL`, `HEAD_OFFICE` | `LOCAL` |
| `sales.upstream` | comma list of `ERP`, `HEAD_OFFICE`; empty = none | none |

Startup checks (the application does not start, the message names the key): an unknown value; `ERP` for promotions or
loyalty; the ERP owning only part of the catalogue, the customers and the supply (`Invalid combination: ownership.catalogue=ERP
with ownership.customers=LOCAL. The ERP owns the catalogue, the customers and the supply together ...`), except the one
combination below on a head office (on a store the catalogue alone from the ERP stays refused); an owner
`HEAD_OFFICE` on a store without `headoffice.url`; `ownership.supply=HEAD_OFFICE` without `ownership.catalogue=HEAD_OFFICE`;
on a head office, an owner `HEAD_OFFICE`, a non-empty `sales.upstream` or `headoffice.url`; a key of the franchise profiles,
whatever its value (removed at task 9.4a; since code cleanup step 8 refused with the other dead keys, section 2); `application.standalone` (removed,
task 9.3). Head office details: `docs/modules/head-office.md`.

**Catalogue only from the ERP, head office only** (ERP catalogue, step 1: ownership only, no connector yet):
a head office (the head office application), `ownership.catalogue=ERP`, `ownership.customers` and `ownership.supply` not `ERP` (absent or
`LOCAL`). Every other partial combination is refused with the message above, on a head office and on a store.

| Question | This head office | Every other configuration |
|---|---|---|
| `isErpCatalogueOnly()` (`ApplicationModeService`, `NodeOwnership`; static `NodeOwnership.isErpCatalogueOnlySet(env)` for conditions) | `true` | `false` |
| `hasErp()`, `isCatalogueFromErp()` | `true` | as before |
| `isCustomersFromErp()`, `isSupplyFromErp()` | `false` | as before |
| `isHeadOfficeWithoutErpSet` (head office AND (no ERP owner OR catalogue only)): catalogue feed, price lists, BLs, supply prices and invoices, network stock | `true` | as before |
| `isHeadOfficeErpSet` (head office AND an ERP owner AND NOT catalogue only): ERP reference location | `false` | as before |

The startup summary line adds `only the catalogue from the ERP (customers and supply kept here)`. The existing gates
follow the questions: item, family, sub-family creation and the data import answer 403 (`isCatalogueFromErp`); customers,
purchases, vendors, locations and stock are not refused by the ERP gates (purchases and stock by the no-stock filter when
`headoffice.stock.enabled=false`). Steps 2 to 4 added the refusal of the other catalogue writes, the ERP jobs offered and the
frontend (`docs/modules/head-office.md`).

**The connector of this head office: `erp.happyness.*`** (ERP catalogue, step 5; package `erp/navpospages`, since code cleanup step 6.3 `connector/happyness` in the project connectors/happyness; GET only, it never
writes to the ERP). The lines go in the head office's **outside file** (e.g. `configs/customers/happyness-headoffice.properties`), never in
the WARs' resources. Every bean of the package exists only with `erp.connector=happyness`.

| Key | Value | Default |
|---|---|---|
| `erp.connector` | `happyness` (section 2; the old `erp.navpospages.enabled` is refused) | `none` |
| `erp.happyness.base-url` | OData V4 address, e.g. `http://host:7048/BC140/ODataV4` | required |
| `erp.happyness.company` | Company name | required |
| `erp.happyness.domain`, `.username`, `.password` | NTLM account (domain may be blank) | username and password required |
| `erp.happyness.location-code` | Removed in 2.2, read nowhere; since code cleanup step 8 a leftover line stops the startup (remove it). The head office creates its points de stock before any items run (`docs/modules/head-office.md`, "Stock points") | — |
| `erp.happyness.page.categories`, `.page.items`, `.page.barcodes` | Web service names | `ItemCategory`, `PointStockPOS`, `ItemBarCodePOS` |
| `erp.happyness.barcode-page-size` | Barcodes per call, 1 to 5000 | `1000` |
| `erp.happyness.default-vat` | The VAT of every item, whole number 0 to 100 | required |
| `erp.happyness.price-includes-vat` | `true` (Unit_Price includes the VAT) \| `false` | required |
| `erp.happyness.connect-timeout-seconds`, `.read-timeout-seconds` | At least 1 | `10`, `60` |
| `erp.happyness.packet-size` (step 6) | Rows applied per transaction, 1 to 5000: an items run applies all its changes packet after packet, a barcode run too, page after page until it has caught up; families and sub-families one packet per run. Renamed from `max-changes-per-run` (2026-10-08): the old key stops the startup | `500` |
| `erp.happyness.dry-run` (step 6) | `true` (read, compare, summary only) \| `false` | `false` |
| `erp.happyness.page.invoices` (invoices from the ERP) | Web service name of the posted franchise invoices; read when `headoffice.supply.source=ERP`. Where the reading starts is the General Setup "Read ERP invoices after number" (release 2.2) | `FactureFranchise` |
| `erp.happyness.invoices.lines-expand` | Navigation property of the lines (`$expand`) | `FactureFranchiseSalesInvLines` |
| `erp.happyness.invoices.customer-field` | Header field of the customer, letters, digits, `_`. The invoices are mapped by `Sell_to_Customer_No` (on the Happyness page since 2026-10-09): a store's "ERP customer number" is its BC customer number | `Sell_to_Customer_No` |
| `erp.happyness.invoices.number-prefix` | Start of every number, no spaces; the year follows in 2 digits (`FVV` + `26`) | `FVV` |
| `erp.happyness.invoices.years`, `.start-number` | Removed in release 2.2; since code cleanup step 8 a line left in a file stops the startup (remove it). The years and the starting point come from the General Setup "Read ERP invoices after number" | — |
| `erp.happyness.invoices.max-per-run` | Invoices read per year and run, 1 to 1000 | `50` |
| `erp.happyness.invoices.seller-name` (step c) | The seller named on the stores' purchase invoices; a store's vendor `HEAD_OFFICE` is created with it | `Head office` |

Startup refusals with `erp.connector=happyness` (the message names the key, the application does not start): not a head
office whose catalogue only comes from the ERP; a blank address, company, username or password; a wrong `default-vat`, `price-includes-vat`, `barcode-page-size`, `packet-size` or timeout; the old `max-changes-per-run`; a blank
`page.invoices`, a wrong prefix, field name or `max-per-run`. One INFO line at startup: `ERP connector navpospages (read only, GET): <address>,
company <company>, pages <three>`, followed by `, invoices <page>` when `headoffice.supply.source=ERP`.
Step 6: the four catalogue import jobs hand the changes only (`docs/modules/head-office.md`); they run when `erp.sync.enabled=true`
and the jobs are enabled on the ERP jobs page (none is switched on by these files). Items of the ERP no longer in the location
are made inactive at the head office whatever their number; only an empty answer for the location deactivates nothing (a
warning in the summary). The key `erp.happyness.deactivate-guard-percent` is gone (2026-10-08): since code cleanup step 8 a leftover line stops the startup.

**The connector of ZEN's head office: `erp.zen.*`** (item variants, 3.1.0, step 8; project connectors/zen; GET only, it
never writes to the ERP). A head office whose catalogue only comes from the ERP (`ownership.catalogue=ERP`), as
Happyness's. ZEN's NAV gives a custom API, `api/sat/zsretail/v1.0`, with the pages `stockPoints` (one row per location,
item and variant), `itemCategories` (the tree catégorie > famille > sous-famille > section) and `barCodes` (step 12a, read
by `lastModifiedDateTime`, `docs/modules/erp-sync.md`). One item per variant, code `itemNo~variantCode`; the rows of a location are read as the
changes since its checkpoint (`docs/modules/erp-sync.md`, "Items run of changes only"). Step 9: its supply invoices, on
NAV's standard OData V4 page `SalesInvoice` under `Company('<company>')`, only those of the stores' ERP customer numbers
(`docs/modules/head-office.md`, "Invoices from the ERP, ZEN"): `headoffice.supply.source=ERP` is accepted with it.

| Key | Meaning | Default |
|---|---|---|
| `erp.zen.base-url` | The NAV instance, e.g. `http://host:7048/ZEN` (the API path and the company follow it) | required |
| `erp.zen.company` | The company's name, as for Happyness (`ESP` at ZEN); its id in the API (`companies(<id>)`) is looked up once by `GET <api>/companies?$filter=name eq '<name>'` and kept until the application stops (step 10). Step 9: the same id addresses the invoices API | required unless `company-id` is set |
| `erp.zen.company-id` | Optional: the company's id in the API, e.g. `89c8b192-3f2e-f111-9f24-7ced8db3f120`; when set it is used as it is and nothing is looked up (it wins over `company`) | — |
| `erp.zen.domain`, `.username`, `.password` | NTLM account (domain may be blank) | username and password required |
| `erp.zen.default-vat` | The VAT of a row that gives none (the API has no VAT field yet), whole number 0 to 100 | required |
| `erp.zen.api-path` | The path of the custom API under base-url | `api/sat/zsretail/v1.0` |
| `erp.zen.page.stock-points`, `.page.categories`, `.page.barcodes` | The pages under `companies(<id>)`, or a whole address (`http...`) for a page published elsewhere | `stockPoints`, `itemCategories`, `barCodes` (step 12a; `ItemBarCodePOS` before) |
| `erp.zen.barcode-page-size` | Barcodes per call, 1 to 5000 | `1000` |
| `erp.zen.connect-timeout-seconds`, `.read-timeout-seconds` | At least 1 | `10`, `60` |
| `erp.zen.packet-size` | Rows applied per transaction, 1 to 5000 | `500` |
| `erp.zen.dry-run` | `true` (read, compare, summary only; the invoices too) \| `false` | `false` |
| `erp.zen.invoices.page` (step 9) | The posted sales invoices, a page under `<base-url>/<invoices.api-path>/companies(<id>)/` (the id of `company` / `company-id`), or a whole address; read when `headoffice.supply.source=ERP`. Where the reading starts is the General Setup "Read ERP invoices after number" | `salesInvoices` |
| `erp.zen.invoices.lines-expand` | Navigation property of the lines (`$expand`), letters, digits and `_` | `salesInvoiceLines` |
| `erp.zen.invoices.api-path` | The path of ZEN's franchise API under base-url (2026-10-25; `invoices.odata-path` and NAV's standard page `SalesInvoice` before) | `api/sat/franchise/v1.0` |
| `erp.zen.invoices.number-prefix` | Start of every number, no spaces; the year follows in 2 digits (`FVE` + `26`) | `FVE` |
| `erp.zen.invoices.page-size` | Invoices read per year and run, 1 to 1000 (the cancelled ones not counted) | `50` |
| `erp.zen.invoices.seller-name` | The seller named on the stores' purchase invoices | `ZEN` |
| `erp.zen.products.url` (3.1.1) | ZEN's products API (outside NAV), read for the item images by the catalogue job's last part; both products keys or neither (neither: no images) | (none) |
| `erp.zen.products.api-key` (3.1.1) | Its `X-Api-Key`; a secret, in the installation's file only (`CHANGE_ME` in `configs/customers`) | (none) |

Startup refusals with `erp.connector=zen` (the message names the key): not a head office whose catalogue only comes from
the ERP; a blank address, username or password; neither a company nor a company id; an address that is not `http(s)://`; a wrong `default-vat`,
`barcode-page-size`, `packet-size`, `dry-run` or timeout; a blank `api-path` or page. Step 9: a blank `invoices.page`,
`invoices.api-path` or `invoices.seller-name` line, a `lines-expand` that is not an OData name, a prefix with a space, a
`page-size` out of 1 to 1000. One INFO line at startup: `ERP
connector zen (read only, GET): <address>, API <path>, company <name> (or `company id <id>`), pages <three>` (with the ERP
supply also `, invoices api/sat/franchise/v1.0 salesInvoices (lines ..., prefix FVE, 50 per year and run, seller ZEN)`);
the installation summary shows `NAV <address>`.

**Step 9 questions** (tasks 9.1b to 9.1g): `isCatalogueFromErp()`, `isCustomersFromErp()`, `isSupplyFromErp()`, `hasErp()` (some
owner is the ERP). With the ERP owning all or nothing they answer alike for every configuration that starts, except the
head office above (`ModeQuestionTruthTableTest`: every variant, every outside file of `configs/`, and a grid
of 15,360 configurations whose counts before ERP catalogue step 1 are frozen, 497 accepted of which 25 with an ERP; the
only rows added are the 8 head offices of that combination; `ErpCatalogueHeadOfficeTest`). They replaced the old
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

Tests: `ModeGateTest` (each gate on an ERP store and on a head office with an ERP: 403 with its message), the existing guard and stock adjustment tests on a mode service built from properties (`support/TestModes`), `StockModeTest` (every stock change and movement on every variant and outside file of `configs/`: no-op when the supply is the ERP's, applied when local or fed by the head office), `ZZDataInitializerModeTest` (the real `init()` on a first start: passenger customer without an ERP, ERP checkpoints, ERP-only settings and ERP jobs with one).

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
- **Supply source** (invoices from the ERP), head office only: `headoffice.supply.source`, `HEAD_OFFICE` (default: the head
  office makes the BLs, supply prices and supply invoices of its stores) or `ERP` (the stores' invoices are read from the
  ERP by the connector navpospages; the head office makes no BL, supply price or supply invoice). Read on a head office
  only, like `headoffice.stock.enabled`; a store never reads it. Startup refusals: a value other than `HEAD_OFFICE` or
  `ERP` (blank included); `ERP` on a head office whose catalogue does not come from the ERP alone (without an ERP, or
  with the ERP owning all three); `ERP` without a connector that has the ability `SUPPLY_INVOICES` (the message names
  `erp.connector=happyness`). `GET /config` field `supplySource`
  (`HEAD_OFFICE` or `ERP` on a head office, `null` on a store). See `docs/modules/head-office.md`, "Invoices from the ERP".
- **Stores page threshold** (task 1.5), head office only: `headoffice.offline-after-seconds`, whole number, at least 1, default `180`. A store whose last heartbeat is older is shown OFFLINE (exactly at the threshold it is still ONLINE). A wrong value stops the head office at startup. See `docs/modules/head-office.md`, "Status".
- **GET /config** (task 0.5) returns three more fields after the existing ones, enums as their names:
  - `nodeType`: `"STORE"` or `"HEAD_OFFICE"`.
  - `ownership`: every domain to its owner, in `DataDomain` order. ERP profile: `{"CATALOGUE":"ERP","CUSTOMERS":"ERP","PROMOTIONS":"LOCAL","LOYALTY":"LOCAL","SUPPLY":"ERP"}`.
  - `salesUpstreams`: array of `"ERP"`, `"HEAD_OFFICE"`; `[]` when sales go nowhere.
  - `headOfficeLinked` (task 1.5): `true` when `headoffice.url` is set; the frontend shows the "Head office link" page only then (`appConfig/isHeadOfficeLinked`, default `false`).
  - `catalogueFromHeadOffice` (step 6): `true` on a store whose catalogue is the head office's (URL, explicit `ownership.catalogue=HEAD_OFFICE`, no ERP). Since step 9 the same as `ownership.CATALOGUE` = `HEAD_OFFICE` (no franchise customer derives it any more); the frontend reads this flag.
  - `supplyFromHeadOffice` (step 7A): `true` on a store whose goods come from the head office by BL (URL, explicit `ownership.supply=HEAD_OFFICE`, no ERP). Since step 9 the same as `ownership.SUPPLY` = `HEAD_OFFICE`; the frontend reads this flag.
  - `rfidEnabled` (RFID step 2, last field): `true` on a store with `rfid.enabled=true`; the frontend shows the RFID pages and buttons only then (a missing field reads as `false`).
- **Frontend store** (`store/app-config/index.js`, task 0.5): state `nodeType` (default `'STORE'`), `ownership` (default `{}`), `salesUpstreams` (default `[]`). Getters `nodeType`, `ownerOf(domain)` (owner name, `null` when unknown) and `salesUpstreams`. Defaults when talking to an older backend, or when the call fails: a missing or unknown `nodeType` gives `'STORE'`, a missing or non-object `ownership` gives `{}`, a missing or non-array `salesUpstreams` gives `[]`. No component, route or menu reads them yet.

## 6. Frontend

- `GET /config` is public, loaded before login (`store/app-config/index.js`, one state field per field, with a default when a
  field is missing or the call fails). `standalone` (no ERP owner) drives the ERP-only and no-ERP-only screens: the ERP menu
  group, the sync columns of the tickets and returns history, "Add customer", "Add product" (quick product), the family and
  sub-family actions, purchases and vendors. `enableSalesPriceGroup` (`pos.pricing.enable-sales-price-group` of the type or outside
  file) shows the Sales Prices and Sales Discounts pages.
- The quick product endpoint is `POST /item/quick-product` since task 9.1g (it was `/item/standalone-quick-product`).

## 7. Which part exists where (installation questions)

Code cleanup step 4: every yes/no question about the installation that decides whether a part of the application exists
is one value of `core/config/Installation`, used as `@ConditionalOnInstallation(Installation.X)` (condition
`OnInstallationCondition`). Each answer comes from `NodeOwnership`, as the startup resolves the configuration. The ERP's
own questions stay `@ConditionalOnErpAbility` and `@ConditionalOnErpConnector` (section 2). The thirteen
`@ConditionalOnHeadOffice...` annotations are gone; `InstallationBeansProofTest` shows the same beans in every file of
`configs/` and the old shapes as before.

| Question | True when | Classes |
|---|---|---|
| `HEAD_OFFICE` | This installation is a head office. | 23 (one a `@Bean` method) |
| `HEAD_OFFICE_WITH_ERP` | A head office whose ERP owns the catalogue, the customers and the supply. | 2 |
| `HEAD_OFFICE_WITHOUT_ERP` | A head office without an ERP, or whose catalogue only comes from the ERP. | 6 |
| `HEAD_OFFICE_ERP_CATALOGUE_ONLY` | A head office whose catalogue only comes from the ERP; it keeps its customers and its supply. | 6 |
| `HEAD_OFFICE_OWN_SUPPLY` | A head office without an ERP, or with the catalogue only from the ERP, that makes the BLs and supply invoices of its stores itself. | 6 |
| `HEAD_OFFICE_ERP_SUPPLY` | A head office with the catalogue only from the ERP whose stores' supply documents are the ERP's invoices. | 2 |
| `HEAD_OFFICE_WITHOUT_STOCK` | A head office that keeps no stock and makes no purchases. | 1 |
| `HEAD_OFFICE_STORE_ORDERS` | A head office that takes its stores' orders: `headoffice.storeorders.post` is MANUAL or ERP (`docs/modules/store-orders.md`). | 3 |
| `HEAD_OFFICE_STORE_ORDERS_ERP` | A head office whose job posts its stores' orders into the ERP: `headoffice.storeorders.post=ERP`. | 1 |
| `LINKED_TO_HEAD_OFFICE` | A store that calls a head office: `headoffice.url` is set. | 7 |
| `SALES_TO_HEAD_OFFICE` | A linked store that copies its sales to the head office. | 5 |
| `COPIES_FROM_HEAD_OFFICE` | A linked store with at least one domain owned by the head office: it pulls the copies down. | 3 |
| `CATALOGUE_FROM_HEAD_OFFICE` | A linked store whose catalogue and selling prices are the head office's. | 5 |
| `PROMOTIONS_FROM_HEAD_OFFICE` | A linked store whose promotions are the head office's. | 1 |
| `LOYALTY_FROM_HEAD_OFFICE` | A linked store whose loyalty program and members are the head office's. | 8 |
| `SUPPLY_FROM_HEAD_OFFICE` | A linked store whose goods come from the head office by BL (`ownership.supply=HEAD_OFFICE` written in its file). | 11 (3.1.1: the orders to the head office, 4) |
| `RFID` | A store that reads RFID tags: `rfid.enabled=true` (`docs/modules/rfid.md`). | 7 (and its 2 repositories) |

Three answers combine `NodeOwnership` methods as the old conditions did: `HEAD_OFFICE` (`nodeTypeOf == HEAD_OFFICE`),
`HEAD_OFFICE_OWN_SUPPLY` (without ERP and not supply from the ERP), `HEAD_OFFICE_ERP_SUPPLY` (catalogue only from the ERP
and supply from the ERP). A new question is a new value with its sentence; never a new annotation.
