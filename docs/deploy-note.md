# Deploy note: two applications, two WARs

**Since code cleanup steps 6.5 (head office) and 6.6 (store)** the store and the head office are two applications, each
its WAR. The application is the installation type: a store runs the store WAR, a head office the head office WAR. Each WAR is deployed **alone in its
Tomcat, under the context name `zsretailws`**, as the single WAR was.

**Version 2.3.0** (Zein, 2026-10-11) is the first release of the two applications: `zsretail-store-2.3.0.war` and
`zsretail-headoffice-2.3.0.war`, database script `db/2.3.0/update.sql`. `release/2.2.2` keeps the numbers 2.2.x for the
hotfixes of the single WAR.

| Application | Main class | Built as | Delivered as |
|---|---|---|---|
| store | `StoreApplication` (was `POSMainApp`) | `store/target/zsretail-store-<version>.war` (since step 6.6; `app/target/zsretailws.war` in step 6.5) | `store/zsretailws.war` |
| head office | `HeadOfficeApplication` | `headoffice/target/zsretail-headoffice-<version>.war` | `headoffice/zsretailws.war` |

## Release

`mvn -DskipTests clean package` at the root builds both. The delivery puts each WAR **in its own folder, named by its
application, under the deploy name `zsretailws.war`**: `store/zsretailws.war` and `headoffice/zsretailws.war`. Never two
`zsretailws.war` side by side in one folder: the name says where it is deployed, the folder which application it is.
On this PC `devenv\build.ps1` does the same for the dev instances (`C:\zsretail-dev\artifacts\store\` and
`artifacts\headoffice\`, `zsretailws-<time>-<commit>.war`).

## Installation

- **A store**: `store/zsretailws.war` into its Tomcat's `webapps\`; its outside file stays
  `<Tomcat>\conf\zsretail\zsretailws.properties`.
- **A head office**: `headoffice/zsretailws.war` into its own Tomcat's `webapps\`; the same outside file convention.
- A store and a head office cannot share a Tomcat (both are `zsretailws`); each has its own, as on every site today.
- **The outside file names no type** (code cleanup step 8): the application is the type, and a `spring.profiles.active`
  or `node.type` line stops the start (below, "Upgrade from 2.2.2 to 2.3.0"). With the old lines changed, every key resolves as
  before (`ConfigurationKeysProofTest`).
- **Upgrade of an existing installation**: change its outside file (below), then deploy the WAR of its type (a store:
  the store WAR; a head office: the head office WAR), after the database scripts of the version as before.

## Upgrade from 2.2.2 to 2.3.0: the outside file

Since code cleanup step 8 (decision of 2026-10-11) the old configuration lines are no longer read: a start that finds
one, in the outside file or anywhere else (a `-D`, an environment variable, the Tomcat options), stops, and its message
names each old line, where it is and what to write instead. Nothing else behaves differently. **Keep a copy of the 2.2.2
outside file** (a rollback needs it, below), then change the outside file
(`<Tomcat>\conf\zsretail\zsretailws.properties`) **before** deploying the new WAR:

| 2.2.2 line | Now |
|---|---|
| `spring.profiles.active=store` or `=headoffice` (or any value) | removed: the application is the type |
| `node.type=STORE` or `=HEAD_OFFICE` | removed |
| `erp.dynamicsnav.enabled=true` | `erp.connector=emtop` |
| `erp.navpospages.enabled=true` | `erp.connector=happyness` |
| `erp.dynamicsnav.enabled=false`, `erp.navpospages.enabled=false` | removed (no `erp.connector` line, or `erp.connector=none`: no connector) |
| `erp.dynamicsnav.<key>` | `erp.emtop.<key>`, same value |
| `erp.navpospages.<key>` | `erp.happyness.<key>`, same value |
| `erp.navpospages.location-code`, `.invoices.years`, `.invoices.start-number`, `.deactivate-guard-percent` (or under `erp.happyness.`) | removed: nothing reads them |
| `franchise.admin`, `franchise.customer`, `franchise.customer.allow-local-items`, `franchise.api-key`, `franchise.remote.url`, `franchise.sync.sales.cron` (any value) | removed: the franchise profiles are gone |

`erp.sync.enabled` and `erp.sync.scheduler.delay` are not old lines: they stay as they are. The values do not change;
below, `...` stands for the line's value as it is.

**An EMTOP store** (Dynamics NAV, e.g. `configs/customers/erp-prod.properties`):

```properties
# remove
spring.profiles.active=store
node.type=STORE
erp.dynamicsnav.enabled=true
erp.dynamicsnav.base-url=...
erp.dynamicsnav.company=...
erp.dynamicsnav.domain=...
erp.dynamicsnav.username=...
erp.dynamicsnav.password=...
# write
erp.connector=emtop
erp.emtop.base-url=...
erp.emtop.company=...
erp.emtop.domain=...
erp.emtop.username=...
erp.emtop.password=...
```

**A Happyness head office** (its catalogue from the ERP's POS pages, e.g. `configs/local/happyness_ho.properties`):

```properties
# remove
spring.profiles.active=headoffice
erp.navpospages.enabled=true
erp.navpospages.base-url=...
erp.navpospages.company=...
erp.navpospages.domain=...
erp.navpospages.username=...
erp.navpospages.password=...
erp.navpospages.location-code=...
erp.navpospages.default-vat=...
erp.navpospages.price-includes-vat=...
erp.navpospages.invoices.years=...
erp.navpospages.invoices.seller-name=...
# write
erp.connector=happyness
erp.happyness.base-url=...
erp.happyness.company=...
erp.happyness.domain=...
erp.happyness.username=...
erp.happyness.password=...
erp.happyness.default-vat=...
erp.happyness.price-includes-vat=...
erp.happyness.invoices.seller-name=...
```

`location-code` and `invoices.years` have not been read since 2.2 (the head office's points de stock and the General
Setup "Read ERP invoices after number" replaced them): they are under "remove" only, never renamed. They are dead
keys: under either name, `invoices.start-number` and `deactivate-guard-percent` too, they stop the start ("remove the
line"). Any other `erp.navpospages.*` line of the file (pages, packet size, timeouts, invoices) is renamed the same way.

**A Happyness store** (a store of the network, no ERP, e.g. `configs/local/happyness_store1.properties`):

```properties
# remove
spring.profiles.active=store
node.type=STORE
erp.dynamicsnav.enabled=false
```

Outside the file: remove a `-Dspring.profiles.active` (or any old line) from `CATALINA_OPTS` or the Tomcat service's Java
options, and a `SPRING_PROFILES_ACTIVE` environment variable, if the server has one. Then start: a line left over stops
the start, and the message names every old line of that start at once (`OutsideFileUpgradeTest` holds the message of
each of the three files above).

## Upgrade from 2.2.2 to 2.3.0: the catalogue state table

On a head office whose catalogue comes from the ERP (Happyness), the state table of the catalogue engine is renamed
`navpospages_state` to `erp_catalogue_state`, its primary key `pk_navpospages_state` to `pk_erp_catalogue_state`, every
row kept (the barcode cursor, the last item run, the items waiting for their barcodes). The application does it at its
first start (`JdbcCatalogueState`, one INFO line `... state table navpospages_state renamed erp_catalogue_state with its
primary key, <n> rows kept`); `core/src/main/resources/db/2.3.0/update.sql` does the same first, guarded so that it
runs once. When both tables exist the start is refused, naming both, and the script ends with an error without writing
APP_VERSION: keep the one with the head office's `barcodes.cursor` row, drop the other. A store, and a head office without that connector, have no such table: nothing happens there.

## Rollback from 2.3.0 to 2.2.2

2.2.2 is the single WAR (`zsretailws.war`) and reads the outside file of 2.2.2. Stop Tomcat, then:
1. **Put back the copy of the 2.2.2 outside file** kept before the upgrade. The new file cannot serve: 2.2.2 does not
   read `erp.connector` nor `erp.emtop.*` / `erp.happyness.*`, and without `spring.profiles.active` it runs as a store.
2. On the head office with the ERP catalogue, **rename the table back before starting 2.2.2** (otherwise 2.2.2 creates an
   empty `navpospages_state`: its barcode run waits for an items run, then reads every barcode page of the ERP again):

   ```sql
   IF OBJECT_ID(N'erp_catalogue_state', N'U') IS NOT NULL AND OBJECT_ID(N'navpospages_state', N'U') IS NULL
   BEGIN
       BEGIN TRANSACTION;
       EXEC sp_rename N'erp_catalogue_state', N'navpospages_state';
       IF OBJECT_ID(N'pk_erp_catalogue_state', N'PK') IS NOT NULL
           EXEC sp_rename N'pk_erp_catalogue_state', N'pk_navpospages_state', N'OBJECT';
       COMMIT TRANSACTION;
   END
   ```
3. Set the version back: `UPDATE APP_VERSION SET version = '2.2.2'` (`db/2.3.0/update.sql` changed nothing else
   but its own notes and the table above). Deploy the 2.2.2 WAR as `zsretailws`, start.

## Database

Each application creates and checks only its own tables: a new store database has no head office table (`ho_*`), a new
head office database no store-only table (`hol_*`, inventory counts, payment changes...); an existing database keeps the
tables it has. The start-up column check (`DecimalColumnsCheck`) reads core's columns and its application's only. The
update scripts of the released versions (`core/src/main/resources/db/`) are unchanged: they already skip a table a
database does not have.
