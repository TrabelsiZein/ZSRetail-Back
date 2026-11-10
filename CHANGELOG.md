# Changelog — ZSRetail-Back

One section per product version, newest first. Every commit that changes behaviour, a DB script or a config key adds
its line here, under the version of its branch. The screens are in `ZSRetail-Front/CHANGELOG.md`. The French notes shown
in the application (`APP_RELEASE_NOTES`) are written in `db/<version>/update.sql` from this file when a version is closed.

## 4.0.0 — in progress (branch `feature/boot4-migration`)

The Spring Boot 4 migration. Decisions, steps and progress: `docs/migration/boot4-migration.md`. Not deployable before its
last step.

### Enhancements
- Step 1.1: the WARs lose the libraries nothing used: springdoc, springfox, spring-security-oauth2, JasperReports (with
  iText, castor, jfreechart), firebase-admin, jsoup, ModelMapper, commons-io 1.4, WebFlux, Reactor, mail, Thymeleaf,
  jackson-dataformat-xml, and what only they brought. Store WAR from 263 to 130 jars (173 MB to 91 MB), head office WAR from
  262 to 128 (170 MB to 88 MB). `commons-lang3` is now declared by the store: the RFID SDK's USB path needs it.
- Step 1.1: no more API documentation pages. `/swagger-ui`, `/v3/api-docs` and `OpenApiConfig` are gone, and the public and
  licence entries of those paths and of the dead `/thymeleaf`.
- Step 1.1: the API answers JSON only (a request accepting only XML gets a 406), and the connectors' reads no longer ask the
  ERP for XML first: `Accept` was `application/xml, text/xml, application/json, ...`, it is now JSON only
  (`ConnectorAcceptHeaderTest`). NAV's real answer is checked live at step 2.7.
- Step 1.3: **SQL Server 2017 is the minimum.** On an older server the start stops with a message that says so, before
  Hibernate touches the database (`SqlServerVersionCheck`, `SqlServerVersionRefusalTest`).
- Step 1.3: a badly written ERP or head office address (no `http://`, or blank) still fails, but with the HTTP layer's
  message instead of "is not a valid HTTP URL": `UriComponentsBuilder.fromHttpUrl`, gone in Spring 7, became `fromUriString`.
  Every well-formed address builds the same URI. The connectors `zen` and `parafendri` still refuse such an address at
  startup; `emtop`, `happyness` and `headoffice.url` refuse nothing, and since step 1.3b write one clear ERROR line at the
  start naming the key (`ConfiguredAddress`). An empty value stays allowed and silent (`happyness` keeps refusing an empty
  one, as before), and `HTTP://` in capitals is valid.
- Step 1.3: the unused `@EnableGlobalMethodSecurity` (no `@PreAuthorize` exists), the `Principal` bean, the second
  `@EnableScheduling` of each application (`SchedulingConfig` has it) and the head office's `@EnableAsync` (it has no
  `@Async`) are gone. No behaviour change: the security matrix and the startup tests are unchanged.
- Step 1.4: Spring Boot 2.7.18 (Spring 5.3, Security 5.7, Hibernate 5.6, Jackson 2.13, Tomcat 9.0.83, mssql-jdbc 10.2). An
  internal stop, never deployed.
- Step 1.4: POI 5.5.1. The WARs lose what only POI 5.0.0 brought: batik, Bouncy Castle, xmlsec, PDFBox, graphics2d,
  xmlgraphics, woodstox, stax2. Store WAR from 130 to 76 jars (91 MB to 66 MB), head office WAR from 128 to 75 (88 MB to 63 MB).
  `commons-lang3` 3.18.0 above Boot's BOM: commons-compress 1.28.0, which POI reads a .xlsx with, calls classes the pinned
  3.12.0 does not have, and a rare file would have stopped an import on an `Error` (`ExcelLibrariesTest`).
- Step 1.4: the empty `"message":""` leaves Boot's error answers (Boot 2.5). The front reads the field only when it is a
  non-empty string, so nothing changes on a screen.
- Step 1.4: the path `/error` is public on the main chain, or Boot 2.6 would blank the body of every error, the ERROR dispatch
  having no logged user. Called directly it answers 500 with no error to show.
- Step 1.5: Java 21. The code compiles with `--release 21`, so the WARs need JDK 21 or later. Lombok 1.18.48 is in the pom (no
  more `-Dlombok.version` on this branch), and the annotation processors are named in the parent, so a JDK 23 or later does not
  skip them.
- Step 1.6: Spring Security 5.8.16. Both chains are `SecurityFilterChain` beans on `authorizeHttpRequests`, every dispatch goes
  through authorization, and the main chain permits the ERROR and FORWARD dispatches. Nothing changes for a caller: the status
  matrices, the error bodies and new rows on how a path matches (`/config/`, `/CONFIG`, `/login/x`, `/hox`, `/ho`, written on the
  old chains) pass unchanged.
- `/sse-endpoint/**` is no longer public, nor let through without a licence: no handler ever answered it, and no front calls
  it. It now answers like any unknown path (403 anonymous, 402 without a licence).
- Step 1.7: Spring Boot 3.5.16 (Spring 6.2, Security 6.5, Hibernate 6.6, Jakarta EE, Tomcat 10.1, mssql-jdbc 12.10, HttpClient
  5). An internal stop, never deployed: the WARs are Jakarta, Tomcat 10.1 or later.
- Step 1.7: the connectors' HTTP client is `erp.api.ErpHttp`, on HttpClient 5: NTLM registered and preferred by name (5 answers
  no NTLM challenge by default), the connect and read timeouts on the connections with the same values. HttpClient 5 retries
  an idempotent call once on an I/O error (4: three times), and once on a 429 or 503 answer. The store's client to its head
  office gets the same timeouts on HttpClient 5 and still honours the JVM's proxy settings.
- Step 1.7: `/error` is no longer public: called directly it asks for a logged user (403 anonymous). The bodies of real errors
  still come, through the rule that lets the ERROR dispatch pass.
- Step 1.7: a URL with a trailing slash no longer reaches its mapping (Spring 6): `/config/` answers 404 to a logged user (200
  before). No caller writes one.
- Step 1.7: the next run of an ERP job comes from `CronExpression` (`CronSequenceGenerator` is gone from Spring 6), in the JVM's
  zone as before. An expression may now also use `L`, `#` and the `@daily` forms.
- Step 1.7: two derived queries that compared a date with text, unused, are gone (Hibernate 6 refused them at the start). A
  refused save's message keeps Hibernate's wording, now without a space before the colon.
- **Step 1.8: the schema is Flyway's.** The start runs the scripts of `db/migration/common` and of its type's folder before
  Hibernate, which only checks the entities against the tables (`ddl-auto=validate`); a schema change is a new V script. An
  empty database gets the baseline, the schema of 3.1.2, then the catch-ups. A database at 3.1.2 is taken over as it is (a
  baseline marker, then the catch-ups): **no `update.sql` to run by hand**. Any other database is refused before anything is
  written, with a message that says why and what to run (an older version, an empty or missing `APP_VERSION`, a 3.1.2 stamp
  without one of its parts); a database a newer version ran on is refused too. `AppVersionGuard` is gone; `APP_VERSION` now
  holds the version of the WAR that last started. A new database and an upgraded one end with the same schema
  (`StoreSchemaStartupTest`, `HeadOfficeSchemaStartupTest`, `docs/migration/diffs/step-1.8-schema.txt`).
- Step 1.8: a record created with an id the table does not have (`POST` of the generic controllers) is refused, nothing
  written (Hibernate 6.6); 3.1.2 inserted it under a new id. Step 1.9: the answer is a 400 "Not created: there is no X with the
  id N. A new record is sent without an id." (a 500 at 1.8). Without an id, or `id: null` as the front sends, it is created;
  no head office and store call, job or connector posts on that path.
- Step 1.9: both security chains match their paths with Security's path pattern matchers (the ant matchers are deprecated for
  removal): no answer changed, every matrix row the same. No deprecated call is left but the NTLM classes of HttpClient 5
  (`ErpHttp`); the replacements are their exact equals, the ticket hash and the ERP invoice decimals included
  (`InvoiceRowDecimalsTest`). Boot's properties migrator found nothing to rename in the tracked configurations.

### DB script
- Step 1.8, Flyway, run by the start: `db/migration/store/V4.0.0__baseline.sql` and `db/migration/headoffice/V4.0.0__baseline.sql`
  (a new database only: the schema `release/3.1.2`'s code makes, and its startup T-SQL's unique indexes),
  `db/migration/common/V4.0.0.1__indexes_of_the_old_scripts.sql` (every database: the five indexes only the scripts of
  `db/<version>/` made, where missing), `db/migration/common/beforeBaseline.sql` (before a database is taken over: refuses one
  that is not at 3.1.2). The `db/<version>/` folders stay as they are: the way up to 3.1.2.

### Fixes
- Step 1.3: the one row of `company_information` is created without an id, so IDENTITY gives it, and is read as the first
  row. It was saved with `id=1` and no row: Hibernate 5 inserted it under a generated id anyway, Hibernate 6.6 refuses that,
  and a new database would not have started. A database with its row at id 1 sees no difference. Step 1.3b: the seller of
  a supply invoice is read the same way, ordered by id (it was the first of an unordered `findAll`).

### Config
- Step 1.4: `;encrypt=true;trustServerCertificate=true` on every `jdbc:sqlserver` line: the driver encrypts by default since
  10.2, so a server with its own certificate would refuse the connection. **An installation's outside file needs them too.**
- Step 1.4: `logging.file.{max-size,max-history,total-size-cap,clean-history-on-start}` become
  `logging.logback.rollingpolicy.*`, same values (`StoreStartupTest.logRollingPolicy`).
- Step 1.7: the `spring.jpa.properties.hibernate.dialect` line leaves `application.properties` and the 13 tracked outside files:
  Hibernate 6 picks `SQLServerDialect` from the connection, with the server's version. `SQLServer2012Dialect` still works on
  6.6 (deprecated, with a warning), so a site's file that keeps the line still starts.
- Step 1.7: `logging.level.org.hibernate.type.descriptor.sql.BasicBinder` becomes `logging.level.org.hibernate.orm.jdbc.bind`
  (Hibernate 6's logger of the bound parameters), same values.
- `logging.pattern.dateformat=yyyy-MM-dd HH:mm:ss.SSS` in `application.properties`: a log line keeps the date Boot 2 wrote
  (`2026-11-10 12:40:28.064`), where Boot 3 writes ISO-8601 with the offset. The rest of the line is unchanged.
- Step 1.8: `spring.jpa.hibernate.ddl-auto=validate` in `application.properties` and the 13 tracked outside files (`update`
  before). **A site's outside file must not keep `update`**: it would win, and Hibernate would alter columns at every start
  (step 2.8). New in `application.properties`: `spring.flyway.baseline-on-migrate=true`, `spring.flyway.baseline-version=4.0.0`,
  `spring.flyway.baseline-description`, `spring.flyway.ignore-migration-patterns=` (empty: a newer database stops the start);
  in each type file `spring.flyway.locations` (`db/migration/common` and its type's folder).

## 3.1.2 — in progress (branch `release/3.1.2`)

### Before deployment
- Visual check, running: the per-point block of the item card ("À ce point", Stock points tab, a point's own name and
  family when they differ) on a Happyness-type head office (catalogue from the ERP, points de stock whose rows differ).
- Visual check, running: the clearance range of Item cards (the point's price range, the normal price struck, "N
  soldés") on a ZEN-type head office.
- The job `IMPORT_CATALOGUE` tested on an ERP store test instance before any ERP-store deployment.
- A database at 3.1.1: `db/3.1.2/sales-line-unit-cost.sql`, `db/3.1.2/item-cost-history.sql` and
  `db/3.1.2/bulk-sale.sql`, then `db/3.1.2/update.sql`, before the 3.1.2 WAR (`AppVersionGuard`).

### Features
- Bulk sale ("vente en vrac"), step 1 (`docs/modules/bulk-sale.md`; the till's popup is step 2): `Item.soldInBulk`
  ("vendu en vrac", null = false, never read from JSON: the full `PUT /item/{id}` keeps it) set by the new
  `PUT /item/{id}/bulk-flag` `{"soldInBulk": true}`, built as `package-flag` (every mode, an ERP catalogue included)
  but each store's own decision: a head office item of a store fed by its head office takes it too (no catalogue
  guard). The item copy carries `soldInBulk` (true or false): the store writes it only on an item the copy creates,
  never on an item it has, and never counts it in "unchanged", so no pull rewrites the catalogue or undoes the store's
  choice.
  The head office item card gives `soldInBulk` per variant. The store's quick quantities: entity `QuickQuantity`
  (label, quantity with 3 decimals, optional linked container item, active, display order) and `/quick-quantity`, the
  generic CRUD (400 with the reason for no label, a quantity not above 0, a 4th decimal, an unknown item); an item used
  as a linked item is not deleted (409, `QuickQuantityItemDeletionCheck`). No connector reads the flag; every item
  stays null, so EMTOP, ZEN and ParaFendri behave as before (`ItemAPICatalogueGuardTest`, `CatalogueCopyBulkFlagTest`,
  `QuickQuantityServiceTest`).
- Bulk sale, step 2 (the till, front end): checked that the four sale paths (sale, park, update, complete parked) take
  two lines of one item as two lines, never merged, the stock taking both (`SaleDecimalQuantityTest`
  `twoLinesOfOneItemSold`, `twoLinesOfOneItemParkedUpdatedCompleted`); no server change.
- Initial cost of the items without a CMP: the item import's cost column (`costPrice`) now also gives an existing item
  without a CMP its starting one (a cost above 0, an `EXCEL_IMPORT` history row, old cost null); an item with a CMP
  keeps it, the cell ignored and counted in `ImportResultDTO.costsIgnored`; the CMP read as stored, the row locked.
  A file without the column imports as before (`DataImportCostTest`). `GET /item-catalogue?withoutCost=true`: the
  items without a CMP, services, discount items and packs left out, only with `withCost` (ignored otherwise,
  `StoreItemCatalogueCostTest`). "Ajuster le CMP" from no CMP was already allowed (history row old cost null, tested).
- Statistics, the margin: `GET /admin/analytics/summary` adds `margin`, `marginRate` (the Journal's margin %, one
  decimal; absent with a line without cost or an HT of 0) and `linesWithoutCost`, the Journal's gains over the period's
  days (`StoreDaysService.periodGains`, the rule of the month total), so the margin is the sum of the Journal's days.
  Only where the store keeps its own stock and for `read:admin-margins` (`AnalyticsAPI.withMargins`), else absent and
  the lines not read; absent too for a period without costed line. The other fields unchanged (`JournalMarginsTest`).
- Stock value at a date: `GET /report/stock?date=yyyy-MM-dd` (optional) gives the stock report at the end of a past
  day, same grouping (item, family, sub-family), columns and order: qty(D) = the stock now minus the movements after D
  (one grouped query), CMP(D) = the new cost of the item's last cost change up to D, else the old cost of its first
  change after D, else its CMP now (counted in `itemsAtCurrentCmp`); value = qty(D) x CMP(D). Four queries whatever
  the number of items. 400 for a date after today, and for a past date where the installation keeps no stock of its
  own. No date or today: the report of before. Every active item listed, a stock of 0 included
  (`docs/modules/reporting.md`, "Stock report").
- Stock report rebuilt (back end, step 1 of 2; the page next): one Java path for now and a past date (now one query,
  was two; a past date four, as before; same rows, labels and order). `GET /report/stock` adds the groupings `CATEGORY`
  and `SECTION` (the name of the category / section row, else the code, else "No Category" / "No Section"), the
  optional filters `search` (code or name contains, case ignored), `familyId`, `subFamilyId`, `categoryCode`,
  `sectionCode` and `status` (a list of `OK`, `LOW`, `OUT`, `NEGATIVE`, `WITHOUT_COST`, any of them, on the item's own
  status at the date before the grouping; 400 for another value), and per row `stockValueTtc`, `sellingValue` (qty x
  `Item.unitPrice`, HT, today's price even at a past date), `sellingValueTtc`, `itemsWithoutPrice`, and on an item row
  `costPrice` (the CMP the value used), `costPriceTtc`, `unitPrice`, `unitPriceTtc`. VAT: the item's `defaultVAT`, none
  0 as at the till; values computed per item, the row rounded to 3 decimals. **`belowMinStock=true` now means the
  statuses `LOW`, `OUT`, `NEGATIVE` of each item**: an item at 0 with no minimum is listed (it was not), and a family
  row sums only its items below the minimum (it compared its summed qty with its summed minimum)
  (`docs/modules/reporting.md`, "Stock report").
- Stock report rows: `itemCount`, `itemsLow` and `itemsOut` (`OUT` or `NEGATIVE`) on every row, the items the filters
  kept counted by their own status at the date (an item row: 1 and its own); `familyName` and `subFamilyName` on an item
  row. Read in the same loop, no new query (for the page's items column and totals in every grouping).
- Stock movements report rebuilt (back end): `GET /report/stock-movements` adds the groupings `SUBFAMILY`, `CATEGORY`
  and `SECTION`, the optional filters `search`, `familyId`, `subFamilyId`, `categoryCode`, `sectionCode` (the stock
  report's) and `types` (a list, any of them; `movementType` still read), and per row `itemCount`, and on an item row
  `itemCode` and `familyName` (its label is now the name, was "code - name"). Days `yyyy-MM-dd` and months `yyyy-MM`
  zero-padded, in time order (were "2026-3-5"). `COST_ADJUSTMENT` ("Ajuster le CMP", quantity 0) is no longer counted
  as a movement. 400 for an unknown type and for `dateFrom` after `dateTo`. One query (`docs/modules/reporting.md`,
  "Stock movements report").
- Stock card of an item: `GET /report/stock-movements/item/{itemId}?dateFrom&dateTo`, its stock at the start and the end
  of the period (counted back from the stock now), the period's entries and exits, and its movements in time order with
  the stock after each one (at most 2,000, the latest), each with its document number as printed (ticket, return,
  purchase, BL, inventory count), its note and who made it (the session's cashier for a sale, whose movement says
  "System"). 404 for an unknown item. The movements report's rows carry `itemId` (an item row) and `groupKey` (a group
  row: the family's or sub-family's id, the category's or section's code, the type, the day, the month); families and
  sub-families are grouped by id, a sub-family row carries its family's name.
- Cost history of the items: table `item_cost_history`, one row per change of an item's CMP (date, old and new cost,
  source, document type and number, the entry's quantity and unit cost, the user's name), written in the transaction of
  the change: a local purchase (`LOCAL_PURCHASE`), an ERP invoice received (`INVOICE_RECEPTION`), a BL with its head
  office invoice (`SUPPLY_INVOICE`), "Ajuster le CMP" (`MANUAL`), a new item of the Excel import (`EXCEL_IMPORT`) and
  `POST /item` with a cost (`ITEM_CREATION`); none when the cost does not change, none on a sale, nothing where the
  installation keeps no stock of its own (`CostHistoryService`). The entry's CMP update locks the item's row first
  and reads the old cost as stored (`ItemRepository.lockForCostChange`, `findCostPriceById`). `GET
  /item/{id}/cost-history?size` (20, at most 100), newest first, for the item card: 403 without
  `write:admin-item-cost` or `read:admin-margins`, and where the installation keeps no stock of its own.
- Head office that supplies no store: `headoffice.supply.source=NONE` beside `HEAD_OFFICE` and `ERP` (unchanged). No
  supply bean: BLs, supply prices, supply invoices, what the stores owe, the ERP invoices, `/ho/supply/**` and the
  network stock (new question `HEAD_OFFICE_SUPPLIES_STORES`); every price list refused on a head office whose
  catalogue comes from the ERP. `GET /config` `supplySource` `NONE`. A store with `ownership.supply=HEAD_OFFICE` is
  refused at its heartbeat, 409 `{"error"}` with the reason; the store (also changed) shows it as `REFUSED` with that
  text in its Communications log (`docs/modules/head-office.md`, "Head office that supplies no store").
- ERP connector `parafendri`, head office catalogue: a ParaFendri head office whose catalogue comes from the ERP
  (`ownership.catalogue=ERP`, its points de stock the six points de vente) runs the catalogue engine, job
  `SYNC_CATALOGUE`: `ParafendriCatalogueSource` (the tree, `stockPoints` per point from its checkpoint, `barCodes` by a
  cursor on `lastModifiedDateTime` in epoch milliseconds, ZEN's contract, and by item), the ability
  `HEAD_OFFICE_STOCK_POINTS`. The engine's 7 beans in core now also need the installation
  `HEAD_OFFICE_ERP_CATALOGUE_ONLY` (as the 4 of the head office already did), so a ParaFendri store keeps its job
  `IMPORT_CATALOGUE` and starts as before.
- ERP connector `parafendri`: the locations (`fetchLocations`, every location of the company, code and name, none
  marked as the default) and the point de vente's customer (`fetchCustomers`, one row only: the account of
  `DEFAULT_LOCATION`, its `customerNo` from `stockPoints`, then `customers?$filter=number eq`, the page read whole if
  `number` is not filterable; blocked as inactive), both read whole each run. Install: `IMPORT_LOCATIONS`, the default
  location on the Emplacements page, `IMPORT_CUSTOMERS`, `PASSENGER_CUSTOMER` = the customer number (e.g. 4110001).
- ERP connector `parafendri`, step 7: the invoice of a ticket prepared for one, asked from Business Central
  (`shipAndInvoice`, POST `{ orderNo }`, looked up first by `orderNo` so never twice): its number kept on the ticket
  (`invoiceNumber`), its PDF written to `<app.upload.dir>/erp-invoices/<invoiceNo>.pdf`, the ticket `TOTALLY_SYNCHED`;
  a failure leaves it `PARTIALLY_SYNCHED`, asked again next run. New `ErpConnector.requestInvoice` / `ErpInvoiceResult`
  (covered by `WHOLE_TICKET_EXPORT`), new communications operation `REQUEST_INVOICE`. The fiscal registration and name
  cannot be sent yet (no field in `shipAndInvoice`): in the message, one warning per run. A ticket with its `erpNo` is
  never posted again (before, a ticket prepared for an invoice after its post would have been posted a second time).
  EMTOP and `prepareInvoice` unchanged.
- ERP connector `parafendri`, step 0 (ParaFendri's Business Central 28 on-prem, its custom API
  `api/sat/zsretail/v1.0`, a store on the ERP as EMTOP's): project `connectors/parafendri`, accepted as
  `erp.connector=parafendri`, no ability; the settings, the startup check, an NTLM GET-only client whose login goes to
  the host of `base-url` only, and the connection test `ParafendriRestClient.checkCompany()` (the companies of the API,
  `company-id` among them). Every `ErpConnector` method a stub: no job yet (`docs/modules/erp-sync.md`).
- ERP connector `parafendri`, step 6: the tickets posted to Business Central as sales orders, header and lines in one
  POST (`salesOrders`, deep insert), looked up first by `ticketNo` so never twice (Business Central does not filter
  `ticketNo` today: posted anyway, one warning per export run in the log and the communications log). New
  `ErpAbility.WHOLE_TICKET_EXPORT`: with it `TicketExportService` sends each ticket whole (all or nothing); every
  other connector unchanged. `ErpOperationResult.addWarning` (written once per run). With such a connector
  `EXPORT_RETURNS` and `EXPORT_SESSIONS` are seeded disabled. The mapping, to confirm with ParaFendri's BC contact, in
  `docs/modules/erp-sync.md`.
- ERP connector `parafendri`, step 4: the barcodes (`fetchItemBarcodes`, `barCodes`, company-wide): every row on a
  first run, then from the checkpoint `ERP_SYNC_LAST_ITEM_BARCODE` plus the barcodes of the items the same run's items
  part got, so that an item added to a point de vente later gets its barcode; the row's GUID as the barcode's ERP id (a
  changed barcode replaces the old one); blank barcode or item left out with their reason in the communications log.
- ERP connector `parafendri`, step 3: the items (`fetchItems`, `stockPoints` of the store's `DEFAULT_LOCATION`, from the
  checkpoint `ERP_SYNC_LAST_ITEM`): the price before VAT as received (never rounded), the VAT, the unit, inactive when
  blocked or without a price, the four links checked against the tree, left-out rows with their reason in the
  communications log. `ErpItemDTO.unitOfMeasure` (erp-api, optional): the import writes `item.unit_of_measure` only when
  the ERP gives one (emtop and zen give none: unchanged).
- ERP connector `parafendri`, step 2: the tree `itemCategories` for the job `IMPORT_CATALOGUE` (`fetchItemCategories`,
  `fetchItemFamilies`, `fetchItemSubFamilies`, `fetchItemSections` by `indentation` 0 to 3, `ParafendriMapper` as ZEN's):
  an orphan famille kept without its catégorie (noted), an orphan sous-famille or section, a deeper row and a blank code
  left out with their reason, shown in the communications log; no ability.
- ERP connector `parafendri`, step 1: the company's locations (`readLocations()`, `checkLocation(code)`), and a
  connection check after the start (`ParafendriConnectionCheck`, its own thread): the company and the store's General
  Setup `DEFAULT_LOCATION` checked in Business Central, one INFO line, or one WARNING naming the problem; it never stops
  or delays the start.
- Tickets history, Excel export: `GET /sales-header/history/export` with the list's filters (no page): every ticket,
  newest first, at most 20,000 (`truncated`, `totalTickets`), each with its cashier, session, customer, loyalty card,
  totals, line count and quantity and its payments by method (`paymentMethods`: the methods in display order), and
  their lines (item, family, quantity, prices, discount, VAT, totals; the unit cost only with `read:admin-margins`).
  One query for the tickets, the lines and payments by blocks of 1,000 (`TicketsHistoryExport`); the list's filters
  moved into `SalesHeaderService.ticketsHistorySpec`, the list unchanged (`TicketsHistoryExportTest`).
- Store Articles, Excel export: `GET /item-catalogue/export` with the list's filters and sort (no page): the items of the
  lines the list shows, one row each (a NAV item's variants of the status), at most 60,000 (`truncated`): codes,
  variant, name, barcode, levels, prices before / with VAT, clearance price, stock and minimum (own stock), CMP (the
  list's cost right), maximum discount, unit, origin, status. One query (`ItemCatalogueQuery.items`, the list's joins
  and WHERE, line conditions on window values; 0.22 s for 21,907 ZEN variants); `StoreItemCatalogueAPI.criteria`
  shared by the list and the export (`ItemCatalogueExportQueryTest`).

### Enhancements
- Cost history: the reason typed in "Ajuster le CMP" is kept on its row (`item_cost_history.note`, varchar(500), cut
  there; null for the other sources) and sent with `GET /item/{id}/cost-history`.
- Stock report: items without cost are no longer valued at 0. An item without CMP and with a stock other than 0 has
  `stockValue` null (was 0) and `itemsWithoutCost` 1; a family or sub-family row counts its items without cost
  (`itemsWithoutCost`), its value the sum of the others as before. Today's queries are unchanged, one more query finds
  the items without cost. The same rule at a past date.
- Item cards list (head office), with a point de stock chosen: `GET /admin/headoffice/item-catalogue` takes
  `stockPointStatus` `ACTIVE` (default, the list as before) | `INACTIVE` | `ALL`, the row at that point, and
  `priceMin` / `priceMax` filter the point's row selling price with VAT (the clearance price when it applies) instead
  of the item's own price; without a point nothing changes (`HoItemCatalogueQuery.stockPoint`, head office only; core's
  query and the store's Articles page unchanged). Step 1 of retiring "Items by point de stock".
- Item cards (head office), step 2: with a point chosen, each list line's `priceMin` / `priceMax` are its rows'
  selling prices there with VAT (`pointPrice`, the price sort by them), with `clearanceVariants` and the normal range
  `normalPriceMin` / `normalPriceMax`; core's `ItemCatalogueQuery.Criteria` gains `linePrice` / `linePriceJoin` (unset:
  the SQL is unchanged). The card's point rows carry the row's own name, description, family and sub-family (codes and
  names) and `differences` / `differs` against the item's.
- ERP catalogue import, every connector: the barcodes are imported by windows of 1,000 rows, their items and existing
  barcodes read in four queries per window, written by JDBC batches of 100, the session emptied after each window
  (before: three queries per row, each flushing the whole packet; 13,165 ParaFendri barcodes ran more than 27 minutes).
  Same rules and warnings. New log line `ERP item barcodes import: N rows handed, ...`.
- ERP jobs: the job type `EXPORT_CUSTOMERS` is removed (never implemented: it only logged "not yet implemented"), with
  its operation `EXPORT_CUSTOMER`, `ErpConnector.pushCustomer` (and its five stubs) and
  `ErpSynchronizationManager.pushCustomer`. The rows of that type and operation are deleted at startup
  (`ErpSyncJobTypesUpgrade`, before any read of the tables) and by `db/3.1.2/update.sql`. `IMPORT_CUSTOMERS` unchanged.
- Item costs, the weighted average cost (docs/modules/head-office.md, "Item costs (CMP)"): `Item.costPrice` is the CMP
  of an installation that keeps its own stock (no-op where the ERP keeps it and on a head office with
  `headoffice.stock.enabled=false`), no longer the last net cost; `lastDirectCost` / `lastDirectNetCost` unchanged.
  `ItemRepository.applyEntryCost` (one native row update, `DECIMAL(19,5)`, 3 decimals) through
  `StockService.applyEntryCost`, after the stock of every entry that carries a cost: a local purchase (per line, the
  net price), an ERP invoice received (per item, its paid lines together; a free line's quantity outside the entry, a
  line received at 0 leaves it), a BL and its head office invoice (the invoice stamps the BL lines' costs; applied at
  the invoice for a BL received, else at the reception; a BL saved after its invoice takes them from it). `updateCost`
  renamed `updateLastCosts`, without `costPrice`. `PURCHASE_RECEPTION` and `DELIVERY_IN` movements carry the net unit
  cost (null on a BL received before its invoice). `PUT /item/{id}` keeps the stored CMP; `POST /item/{id}/adjust-cost`
  `{costPrice, reason}` ("Ajuster le CMP") with a `COST_ADJUSTMENT` movement (quantity 0, `CMP <old> → <new> :
  <reason>`, the user), 403 without `write:admin-item-cost` (checked by the API, listed by `GET /app-roles/permissions`,
  given to ADMIN at every start wherever the stock is kept). The Excel import writes the cost of new items only.
- Unit cost frozen on the ticket line, so the margin of a past day never moves with the CMP: `sales_line.unit_cost`
  (`SalesLine.unitCost`, net, 3 decimals) takes the item's CMP, half up, when its ticket is completed (a sale, a held
  ticket paid, a split bill paid), the free line of a FREE_QUANTITY promotion included; null on a held ticket, for the
  tax stamp and for an item without CMP; the same in every configuration (where the ERP keeps the stock the item's
  cost is copied as it is). A pack's components are lines of their own (the till splits the pack), each with its own
  CMP. `return_line.unit_cost` takes its sales line's cost, null included. Written on the insert only
  (`updatable = false`: a later save, the generic `PUT /sales-line/{id}` included, never changes it) and never in
  JSON (`@JsonIgnore`): `GET /sales-header/{id}/details` sends `lineCosts` (line id to cost) only to a role with the new
  right `read:admin-margins` (checked by the API, listed by `GET /app-roles/permissions`, given to ADMIN at every start
  wherever the stock is kept, with `write:admin-item-cost`). Stock movements unchanged (selling prices). The lines
  written before 3.1.2 stay without cost (no estimate).
- Unit cost on the ticket line: an item that carries no cost by nature, a service or a discount item
  (`ItemType.carriesNoCost`), freezes 0 on its sale and return lines, never null ("unknown"); a product and a pack keep
  the rule (the CMP, or null without one), the tax stamp stays null (`SaleUnitCostTest`, `ReturnRefundLoyaltyTest`).
- Journal, the gains (docs/modules/cash-sessions.md, "Journal", "Gains"): `GET /cashier-session/store-days` (each day),
  `/day-journal` (each ticket and return, and the day's totals) and their .xlsx carry `cost`, `margin`,
  `marginPercent` and `linesWithoutCost`, from the unit costs frozen on the lines (`JournalCosts`): the HT of the
  Journal (ticket discount, cart promotions and loyalty points in it) over the costed lines minus their cost, a return
  negative, the stamp left out, a service or a discount item at 0; all null without a costed line; margin % only when
  no line is without cost; a day (and the month total of the .xlsx) counts the lines without cost of all its tickets and
  returns, those without any costed line included. Only where the store keeps its own stock and for `read:admin-margins`
  (`CashierSessionAPI.withMargins`), else not computed and absent. The day list reads the month's lines by one
  projection per kind (`findCostLinesOfFinishedBetween`, `findCostLinesOfReturnsBetween`); the day journal from the
  lines it reads. Nothing existing changes (`JournalMarginsTest`, `JournalCostLinesQueryTest`).
- Store Articles page, the list on every store (docs/admin-pages.md, "Articles"): `GET /item-catalogue` gives each line
  what the old flat list showed (`ItemCatalogueService.storeDetails`, one read for the page, `ItemCatalogueQuery.details`):
  a single item's `origin`, `ownPrice` and the head office's price with VAT (`headOfficePrice`), its price before VAT and
  VAT (`unitPrice`, `vatPercent`), its public price with VAT when it is in clearance below it (`publicPrice`), its
  `minStockLevel` and the ERP's `maximumAuthorizedDiscount`; and, with `withCost` in the answer, the line's weighted
  average cost before VAT (`costMin` / `costMax`, a NAV item's range) only where the store keeps its own stock and for a
  role with `write:admin-item-cost` or `read:admin-margins` (checked by the API). Every new field is left out of the
  JSON when null (the head office's list answers as before). New sort `stock` (the line's stock, none read as 0), on
  the store's and the head office's list (`ItemCatalogueStoreDetailsTest`, `StoreItemCatalogueCostTest`).
- Store item card (docs/admin-pages.md, "Articles"): `GET /item/{id}/stock-movements?size` (20 by default, at most 100),
  an item's last stock movements newest first (`ItemStockMovementDTO`: date, type, direction, quantity, unit price
  before VAT, reference, notes, user); the unit prices and the CMP adjustments only for a role with
  `write:admin-item-cost` or `read:admin-margins`, checked by the API (`ItemStockMovementsTest`).
- Till item tiles, images given by address (ZEN): an item without `Item.imageUrl` is sent with its main URL image of
  `item_image` (its first URL row, the main one first: ZEN's `frontImg`) in `imageUrl`, a full address, on the six POS
  reads of `ItemAPI` (`/item/by-sub-family/{id}`, `/item/pos/by-section/{id}`, `/pos/without-section/{id}`,
  `/pos/without-sub-family/{id}`, `/pos/without-family`, `/pos/search`). An uploaded file stays first; `POS_SHOW_IMAGES`
  false: no image, `item_image` not read; without `item_image` rows nothing changes. Every item whose `imageUrl` the
  answer changes is detached first, so the change is never saved (images off included). One rule with the store order
  catalogue: `core.service.ItemMainImages` (`ItemMainImagesTest`).

### Fixes
- **Security: `/user-account` is behind the permissions of the Users pages.** It checked none: on the running application a
  cashier read every badge code (`GET /user-account`, then a till right from `POST /badge/scan`), generated the
  administrator's badge and read it, and set the administrator's password and logged in with it. Now reads need
  `read:admin-users`, creating, editing, switching off and the badge actions `write:admin-users`, deleting
  `delete:admin-users`, else 403; on a head office `read:admin-headoffice-users`, its page's one key, opens them too.
  `/cashiers` (names, for the history filters) and `/current` (one's own account) stay open to any logged user, and so does
  `POST /badge/scan`: the till needs it with a real badge. No screen lets a normal user change his own password here.
- **Security: the badge code no longer travels in an entity.** Like the password hash below, `UserAccount.badgeCode` is read
  from JSON only and left out of `toString`; the Users pages read it through `UserAccountDTO`
  (`UserAccountApiAccessTest`, `UserAccountSecretsJsonTest`).
- **Security: `GET /user-account/current` no longer answers one's own badge code.** Open to any logged user, it carried the
  caller's code, which no screen reads there (`UserAccountDTO.ownAccount`). The answers behind `read:admin-users` keep the
  code: the Users page shows it, and sends it back on a save (`UserAccountApiAccessTest`).
- **Security: `PUT /company-info` needs the page's permission:** `write:admin-company-information` on a store,
  `read:admin-headoffice-company-information` on a head office, else 403 (`CompanyInformationApiAccessTest`). Any logged user
  could save, and an anonymous call answered 500. The `GET` stays public. A responsible no longer fills in the company name.
- The keys the API now checks reach the ADMIN of an older store database at its start (`API_CHECKED_ADMIN_PERMISSIONS`), so
  no administrator is locked out. Only ADMIN: no other role changes.
- **Security: the password hash no longer reaches the browser.** `UserAccount.password` was written in every answer that
  serves an entity embedding its user: `POST /cashier-session/open`, `GET /cashier-session/current` and the list
  `GET /cashier-session` (open to any logged user) answered with `cashier.password`, a bcrypt hash, and so did tickets,
  returns, payments, purchases and invoices. It is now read from JSON only (`@JsonProperty(access = WRITE_ONLY)`) and left
  out of `toString`; the front never read it, and no relation to a user cascades, so an entity sent back without it
  changes no password (`UserAccountSecretsJsonTest`).
- Journal gains: a day's (and a month's) cost and margin are the sum of its tickets' and returns' figures rounded to
  the millime as shown, not the rounding of the unrounded sums (could differ by a millime, 28.782 vs 28.781); the day
  list and the day journal share the rule (`JournalCosts.day`, `JournalMarginsTest.daySumsTheRowsAsShown`).
- Stock movements, two gaps (for the stock value at a date): the item import (Excel) writes the movement of the stock
  it sets, in the row's transaction (a row in error leaves nothing): a new item's `OPENING_STOCK` (note "Import
  Excel", created by `Import`), an existing item's difference as `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` (note "Import
  Excel"), nothing when it does not change or without a stock column. `POST /item` with a stock writes its
  `OPENING_STOCK` (note "Création de l'article", the user) with the item (`ItemService.create`). Before, both set the
  stock with no movement. Nothing when the supply is the ERP's.
- Store linked to a head office after its database was created (ParaFendri Gremda): its ADMIN role now gets
  `read:admin-holink-status` (the "Head office" menu: Jobs, Communications, Data exchanged) at the next start; before,
  only a role created with `headoffice.url` already set had it, so the menu stayed hidden. Nothing is removed.
- ERP connector `parafendri`, step 6: `amountIncludingVAT` is no longer sent on `salesOrders`, header nor lines
  (read-only in Business Central: the live POST answered HTTP 400 "Control 'amountIncludingVAT' is read-only"); BC
  computes it, the header's is read back from the answer and shown in the log message next to our total TTC.
- Split bill of a table ticket (`POST /sales-header/split-and-pay/{id}`, `SalesHeaderService.splitAndPay`): the lines
  paid apart never left the stock and wrote no SALE movement (the rest, paid later, did): they now go out with their
  movements as every completed sale (`decrementStockForSalesLines`), and keep their discount source and promotion,
  which the copy dropped (docs/modules/table-management.md, `SplitBillStockTest`).
- Local purchase with the same item on two lines: the second line saved the item it had loaded (its last costs), which
  put back the stock of before the first line's increment, so the first line's quantity was lost. The last costs are
  now a bulk update and the item is never saved (`PurchaseHeaderServiceCostTest`, the mechanism in
  `EntryCostQueryTest`).
- Head office invoice at the store: the last costs were written by saving the whole item, which could put back a stock
  read before a sale made at the same time; now a bulk update (`SupplyInvoiceWriter`).

### Removed
- Head office page "Items by point de stock", back end: `GET /admin/headoffice/stock-points/items` and `/items/{rowId}`,
  `HoStockPointItemsService`, `StockPointItemDTO`, the queries `findRows` / `findRowsSearched`; the rest of the Stock
  points API stays. The permission `read:admin-headoffice-stock-point-items` is no longer given to ADMIN (checked first
  on the ZEN, Happyness and ParaFendri test head offices: only ADMIN had it, with the Items permission); a role row
  that has it stays, unread. Item cards' point filter replaces the page. `GET /config` gains `headOfficeStockPoints`
  (the head office keeps points de stock: catalogue only from the ERP and a connector with `HEAD_OFFICE_STOCK_POINTS`).

### DB script (`db/3.1.2/sales-line-unit-cost.sql`, `db/3.1.2/item-cost-history.sql` and `db/3.1.2/bulk-sale.sql`, then `db/3.1.2/update.sql`, store and head office, on a database at 3.1.1)
- `bulk-sale.sql`: column `item.sold_in_bulk` bit NULL (store and head office) and, on a store only (no `ho_store`),
  table `quick_quantity` (`label` varchar(100), `quantity` numeric(18,3), `linked_item_id` FK `item`, `display_order`,
  the base columns), each created when missing (a start creates them too); no data changed. `update.sql` stops while
  the column, or on a store the table, is missing.
- `item-cost-history.sql`: column `item_cost_history.note` varchar(500) NULL, in the table and added when missing (a
  database that ran the script before it; a start adds it too).
- `item-cost-history.sql`: table `item_cost_history` (no foreign key to `item`) and its index `ix_item_cost_history_item`
  `(item_id, changed_at)`, each created when missing (a start creates them too); empty at the start. `update.sql` stops
  while the table is missing.
- `update.sql`: index `ix_stock_movement_created` on `stock_movement (created_at) INCLUDE (item_id, direction,
  quantity)` (re-runnable, store and head office): the movements after a day, summed for the stock value at a date,
  read through it instead of a scan of every movement.
- `sales-line-unit-cost.sql`: columns `sales_line.unit_cost` and `return_line.unit_cost` DECIMAL(19,3) NULL, each added
  when missing (a start adds them too); no data changed, the lines of before stay NULL. `update.sql` stops while they
  are missing.
- `update.sql`: the rows of the removed job type deleted, `erp_sync_job` of type `EXPORT_CUSTOMERS` and
  `erp_communication` of operation `EXPORT_CUSTOMER` (each table guarded; re-runnable; the same statements at startup).
- `update.sql`: `APP_VERSION` 3.1.1 → 3.1.2 (it stops while APP_VERSION is not 3.1.1 or 3.1.2, and while
  `sales-line-unit-cost.sql` has not run); notes written when the version is closed. `db/3.1.1/` is unchanged, as
  released: the `EXPORT_CUSTOMERS` deletion and the unit cost columns, first put there by the branches, live in 3.1.2.
- `update.sql`: index `ix_stock_movement_item` on `stock_movement (item_id, id)` (re-runnable, store and head office):
  an item's last movements (the item card) read through it instead of a scan of every movement.

### Config
- `headoffice.supply.source=NONE` (head office): accepted only with `headoffice.stock.enabled=false` and without
  `headoffice.storeorders.post`, else the startup stops naming both keys.
- `erp.connector=parafendri` and its keys `erp.parafendri.base-url`, `company-id` (a GUID), `domain`, `username`,
  `password` (required but domain), `connect-timeout-seconds` (30), `read-timeout-seconds` (600). New file
  `configs/customers/parafendri-store.properties` (placeholders, `erp.sync.enabled=false`).
- `configs/customers/zen-headoffice.properties`: `headoffice.supply.source=ERP` (was `HEAD_OFFICE`, kept until the live
  test), as the real installation runs it; the records of `ConfigurationKeysProofTest` and `InstallationBeansProofTest`
  follow (that line only).

## 3.1.1 — released 2026-10-29 (branch `release/3.1.1`)

### Features
- Till's right rail (catalogue hidden): `GET /cashier-session/current/ticket-count` `{"ticketCount": n}`, the tickets of
  the current user's open session (completed, refunded since included; 0 without a session), a count and never an
  amount (the closing count stays blind; `CashierSessionService.countSessionTickets`, `SessionTicketCountTest`).
  `GET /loyalty/config` sends the active program's `earningTiers` (sorted), so the till shows the points a ticket will
  earn with the sale's rule (`LoyaltyEarningTiersTest`).
- POS screen, catalogue visible by default: a store General Setup row `POS_CATALOGUE_VISIBLE` (BOOLEAN, `false`, POS
  behaviour, created at start-up on a store only), sent by `GET /config` as `posCatalogueVisible` (last key; `false`
  when off or absent). How the till opens when it has no choice of its own (`docs/modules/general-setup.md`).
- Store Articles page, grouped by NAV item: `GET /item-catalogue` and `GET /item-catalogue/filters` on a store, the head
  office's item catalogue list read at the store (one line per NAV item number, else per item; price range, variants
  count, stock, the classification filters, the price with VAT). The query moved to core (`ItemCatalogueQuery`,
  `ItemCatalogueService`, `ItemCatalogueLineDTO`, `ItemCatalogueFiltersDTO`); the head office keeps its point de stock
  filter and count in `HoItemCatalogueQuery` (an item condition of the criteria) and its endpoint unchanged. With a
  search each line gives `matchedItemIds` (its items found: NAV number, item code, name or a barcode), on both.
- Orders to the head office ("Commandes centrale"), step 1, the store's back end (docs/modules/store-orders.md), on a
  store whose goods come from its head office only (elsewhere the URLs answer 404): `hol_order` / `hol_order_line`, one
  draft at a time numbered `<store code>-<year>-<6 digits>` at its creation, one line per variant (the same item again
  replaces its quantity, 0 removes it), only active ERP items; `GET /store-order/catalogue` (the NAV items grouped
  read, filters search / barcode / catégorie / famille / sous-famille / section, sort by number or name, the first
  variant's price and image), `GET /store-order/variants` (the variants with NAV's live stock at the store's location,
  asked to the head office: 503 without it, no partial answer), the draft (`GET`, `POST /draft/lines`, `DELETE
  /draft/lines/{itemId}`, `PUT /draft/note`), `POST /store-order/{id}/send` (synchronous `POST /ho/orders`, the row
  locked meanwhile: accepted SENT; refused 409, not answered 503, nothing saved), `POST /store-order/{id}/cancel` (a
  draft only), `GET /store-order` (status, date range) and `GET /store-order/{id}`. What the head office does with an
  order comes down with the SUPPLY copies (record `ORD:<number>`, kind `STORE_ORDER`: SENT, POSTED with the NAV
  number, ERROR with the reason). The head office side is step 2.
- Item images: `item_image` (any number per item, `FILE` or `URL`, sort order, main), entity and `GET /item/{id}/images`
  (store and head office), read only; `item.imageUrl` stays the main image everywhere.
- Permissions `read:admin-store-orders`, `write:admin-store-orders` ("Commandes centrale"), listed by
  `GET /app-roles/permissions`, given to ADMIN at every start of a store whose goods come from the head office.
- Orders to the head office, step 2, the head office's back end (docs/modules/store-orders.md), only with
  `headoffice.storeorders.post` (MANUAL or ERP; absent: no bean, 404): `POST /ho/orders` takes a store's order once per
  store and number (the same number again: accepted, the order already here), refused with `accepted: false` and the
  reason without the store's ERP customer number, for another location, without lines or with a quantity not above 0;
  `GET /ho/orders/variant-stock?navItemNo=` (the connector, the store's code as the location; 503 without it);
  `ho_store_order` statuses SENT, POSTED, ERROR, REJECTED. ERP mode: the job `POST_STORE_ORDERS` (ERP jobs page, seeded
  disabled, every 5 minutes) posts the orders SENT and ERROR as sales orders (external document number = the order's,
  the store's location and ERP customer number), success POSTED with NAV's number, failure ERROR retried at each run (no
  limit), one communications log row per posting. Admin `admin/headoffice/store-orders`: list (store, status, dates,
  sort), one order, "mark posted" (MANUAL), "reject" (both: REJECTED, never posted), "retry now" (ERP). Every change the
  store sees records `ORD:<number>` for that store (REJECTED goes down as ERROR with its message).
- erp-api: `ErpConnector.fetchVariantStock` and `postSalesOrder` (defaults: not supported), `ErpAbility.STORE_ORDERS`,
  `ErpStoreOrderPosting`. EMTOP and Happyness unchanged.
- connector-zen (replaced by ZEN's real pages, below): the store orders on the contract sent to ZEN (`ZenStoreOrders`, the
  fields in `ZenOrderFields`): `GET
  variantStock?itemNo=&locationCode=`; the lookup `GET salesOrders?$filter=externalDocumentNo eq '..'` first (an order
  found is reused; a lookup that fails posts nothing), then `POST salesOrders` → `orderNo`. The RestTemplate still
  refuses every method but GET, except a POST to the sales orders page.
- Orders to the head office, step 4 (the head office's screens): `GET /config` gives `storeOrdersPost`, `ERP` or `MANUAL`
  on a head office with `headoffice.storeorders.post`, null otherwise and on every store; permissions
  `read:admin-headoffice-store-orders` and `write:admin-headoffice-store-orders`, given to ADMIN at every start only on
  such a head office (`ZZDataInitializer.HEAD_OFFICE_STORE_ORDER_PERMISSIONS`).
- Store orders, ZEN's real page `commandesVente` (docs/modules/store-orders.md): the lookup `GET
  commandesVente?$filter=referenceZS eq '..'&$expand=lignesCommande` (required); none found: one POST with the
  lines nested (`referenceZS` = the order number, `commandeZS` true, `client`, `dateCommande`;
  `lignesCommande` `article`, `codeVariante`, `mode` when set, `quantiteDemandee`), and when NAV answers a 4xx refusing
  the nested lines, the header then one POST per line on `commandesVente(id)/lignesCommande`; an order found reused, the
  lines it lacks posted. NAV's answer kept (`ErpSalesOrderResult`, the return of `ErpConnector.postSalesOrder`):
  `numero` as the ERP number, its `message` on `ho_store_order.nav_message`, per line `quantite` and `message` on
  `ho_store_order_line.nav_accepted_qty` and `nav_line_message`, sent down with `ORD:` (`navMessage`, `lines`) and
  written on `hol_order` / `hol_order_line`; both order views give them. The communications log message names the path
  taken. The page names are constants of `ZenOrderFields`. Sample
  `connectors/zen/src/test/resources/zen/commandesVente.json`.
- Item images from ZEN's products API (docs/modules/erp-sync.md, "Item images"): the job `SYNC_CATALOGUE` has a last
  part `images`, after the barcodes (`ItemImagesEngine`; skipped when the connector reads none: EMTOP and Happyness
  unchanged). Every run asks the NAV item numbers of the items the items part changed (calls of 100); a full run asks
  every NAV item number, at most once per 24 h (the checkpoint `images.since`, no setting), which also catches a
  picture replaced on ZEN's side. Every variant of a NAV item gets `frontImg` (main, sort 0) and `detailsImg` (sort 1)
  as `item_image` URL rows, an empty answer removes them, FILE rows untouched. A failure is a WARNING of the run, never
  an ERROR. erp-api: `ErpCatalogueSource.readsItemImages`, `itemImages`, `ErpItemImagesDTO`. connector-zen:
  `ZenProductsClient` (`{"skus": [..]}` only, never `updatedAt`, which ZEN answers with the whole catalogue; answer
  under `data`), its own RestTemplate (no NAV login, only a POST to that address), header `X-Api-Key`.
- The item copies down carry the item's URL images (`CatalogueItemCopyDTO.images`: url, main, sort); the store replaces
  its URL rows with them (its FILE rows kept; absent: kept). The store order catalogue's tile takes the first variant's
  main URL image when no variant has an `image_url`.
- Store orders, status DELIVERED: on a head office that reads the ERP's invoices (`headoffice.supply.source=ERP`) and
  takes store orders, an invoice saved by the import whose `orderNo` is a POSTED order's `nav_order_no` makes it
  DELIVERED (`invoice_no` = the invoice number, `delivered_at` = its posting date) in the invoice's own transaction,
  for the store whose ERP customer number is the invoice's; the run counts `ordersDelivered`. The `ORD:` copy carries
  `invoiceNo` and `deliveredAt` and the store writes DELIVERED. erp-api: `ErpSupplyInvoiceDTO.orderNo` (null for the
  connectors that give none: Happyness and EMTOP unchanged); connector-zen: `orderNo` of `salesInvoices`. Store:
  `GET /admin/deliveries/by-number?number=` (a received document by its number, 404 when not here).
- Store orders, colis (ZEN's série): an order holds colis of an ERP item apart from its lines (`hol_order_colis` /
  `ho_store_order_colis`, one row per item: nb_colis, contenance, colis_possibles, NAV's colis accepted and message;
  `total_colis` on both orders, `total_qty` the pieces of the lines only). Store `POST /store-order/draft/colis` (0
  removes; above the colis possibles 409, checked again at the send) and `DELETE /store-order/draft/colis?navItemNo=`;
  `draftColis` in the variants answer; an order of colis alone is sent and taken. The head office posts each as a colis
  line (erp-api `ErpSalesOrderDTO.Line.nbColis`; answer `ErpSalesOrderResult.Line.colis` / `acceptedColis`), keeps
  NAV's answer on the colis row and sends it down (`StoreOrderStatusCopyDTO.colis`). connector-zen: a colis line
  `{article, mode: "colis", nbColis}` (pieces lines keep `erp.zen.order.mode`, vrac), NAV's `colisAcceptes` read on
  colis lines, a colis line missing until NAV has one of the item; details gain "Types de série" (`qteSerieType`).
- Store orders, colis per colour row (NAV refused an item-level colis line: "Le n° article et le code variante sont
  obligatoires sur les lignes"; ZEN's série is per variant): each colis row is of a variant, the first of its colour row
  (`variant_code`, `color_description` on `hol_order_colis` / `ho_store_order_colis`, one row per order, item and
  variant); `POST /store-order/draft/colis` takes `variantCode` (an active variant of the item, 400 otherwise), `DELETE`
  too; `draftColis` and the last order's `colis` by variant code; the head office refuses a colis without a variant;
  connector-zen sends `{article, codeVariante, mode: "colis", nbColis, quantiteDemandee}` (the pieces: nbColis × the
  row's contenance; NAV refused 0 with "La quantité demandée doit être positive"), a colis line missing until NAV has
  one of its variant. The store drops the first build's key `uk_hol_order_colis_item` at each start. A colis row of the first build (no
  variant) is left out of the window's colis (its null key broke the variants answer: the window read "head office
  unreachable") and stops the send with what to do (remove it, enter the colis on a colour row).
- Store orders, colis by item (ZEN's final rules; NAV took `{article, mode: "colis", nbColis}`, CV26000008): a colis is
  the whole article, one série of every colour, replacing the per-colour colis. One colis row per order and item,
  `colis_content` (Σ `qteSerieType`) and `colis_disponibles` (ZEN's `colisDisponibles`) kept on it, its composition
  (each variant's pieces per colis, size and colour) in `hol_order_colis_detail` / `ho_store_order_colis_detail`.
  `POST /store-order/draft/colis {navItemNo, nbColis, colisAvailable?, composition?}` (409 above the colis available,
  at the add and at the send), `DELETE ?navItemNo=`. Pieces of a variant whose `vracAutorise` is false are refused
  (409, at the add and at the send; `hol_order_line.vrac_allowed`). erp-api: `ErpVariantStockDTO` `qtyPerColis`,
  `vracAllowed`, `colisAvailable` (generic, optional); `ErpSalesOrderDTO.Line.colis(item, nbColis, pieces)`;
  `ErpSalesOrderResult.Line` `acceptedQty` and `colisNumber` of a colis line, carried down with the order's status
  (NAV's accepted colis, pieces, numéro colis, message). connector-zen: reads `qteSerieType`, `contenanceSerie`,
  `colisDisponibles`, `colisTheoriques`, `colisConformesSerie`, `colisConformesQte`, `vracAutorise`, `qteVrac`
  (details in that order), posts a colis line `{article, mode: "colis", nbColis}` (no variant, no quantity), reads
  `numeroColis`; the infoArticles sample is 43ZFCHD17 read live. The store drops `uk_hol_order_colis_variant` too at
  each start.
- Store orders, `GET /store-order/last?navItemNo=`: the last order of this store sent with a line of that ERP item (not
  a draft, not cancelled): number, status, date, and its quantity of each variant (the variants window's "Dernière
  commande"); 204 without one.
- Store orders, DELIVERED also when the invoice came first: the import keeps each invoice's `orderNo`
  (`ho_erp_invoice.order_no`); an order that becomes POSTED (the job, "retry now", "mark posted") looks up the invoices
  already imported under its ERP number (native, a seek) and is DELIVERED by the first of its store's customer in the
  same transaction.

### Enhancements
- ERP jobs, every customer: one job `IMPORT_CATALOGUE` ("catalogue import") replaces `IMPORT_ITEM_FAMILIES`,
  `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS` and `IMPORT_ITEM_BARCODES`. It runs, in this order, catégories, families,
  sub-families, sections, items, barcodes (`ImportCatalogueJob`), each part with today's read, import and checkpoint, and
  stops at the first failing part (the next run goes on). Barcodes now always come after their items: in one scheduler
  tick the barcodes job used to run before the items job, a new item's barcodes were skipped and their checkpoint moved
  past them. New `ErpConnector.fetchItemCategories` / `fetchItemSections` (default: none; emtop unchanged). The rows of
  the four jobs are converted at startup (`ErpSyncJobTypesUpgrade`, before any read of the table): the cron of
  `IMPORT_ITEMS`, enabled when any of the four was. EMTOP: one 12 h schedule instead of four (`docs/modules/erp-sync.md`).
- Store orders, the lists: a `search` parameter on `GET /store-order` and `GET admin/headoffice/store-orders`, in the
  order number, the ERP (head office) order number and the lines' item code, ERP item number and name (any case, the
  wildcards searched as characters; `ItemBarcodeService.containsPattern`).
- NAV items grouped read (`NavItemService.page`, `ItemRepository.NAV_ITEMS`): optional level filters (catégorie, the
  item's own else its famille's; famille; sous-famille; section), the NAV items of the barcodes matching the search, and
  a sort (number, or name then number, either way). The "NAV items" page sends none of them: same lines, same order.
- Head office copies down: several providers per domain (`CopiesDownFeed`), each answering its own codes: the SUPPLY
  domain carries the supply documents and the store orders' statuses together.
- Store orders, for the store's screens (step 3): a catalogue line gives `mainImageItemId` (the item of its image, read
  at `item-image/item/{id}`); a variant NAV gives no row for has `stock` null (unknown) instead of 0.
- Store orders, the variants' stock on ZEN's real page `infoArticles` (docs/modules/store-orders.md): the head office asks
  the connector with the store's code **and ERP customer number** (`ErpConnector.fetchVariantStock(itemNo, locationCode,
  customerNo)`); a store without an ERP customer number is refused before the ERP is asked, with the reception's reason
  (`/ho/orders/variant-stock` 409; the store relays a 409 with it instead of a 503). connector-zen reads `infoArticles?$filter=
  article eq '..' and client eq '..'` (no location): the quantity is `disponible`, never `stock`; null when a row has none
  (unknown, shown "—"). `VariantStockDTO` / `ErpVariantStockDTO` and the store's variants answer gain the optional
  `details` (ordered label → value; ZEN: Prix unitaire, Remise %, Prix net, Stock, Qté vrac, Vrac autorisé, Contenance
  série, Colis possibles, Calculé le) and `sizeOrder` (ZEN: `ordreTaille`); EMTOP and Happyness leave them empty. Test
  sample `connectors/zen/src/test/resources/zen/infoArticles.json` (ZEN's test NAV, 43ZBPND01 for 4110001).

- Store orders, quantity ≤ available (docs/modules/store-orders.md): `POST /store-order/draft/lines` takes an optional
  `availableQty` (the variants window: the quantity available shown on the cell, null for none) that the line keeps
  (`hol_order_line.available_qty`); a quantity above the availability sent, or else the line's (the cart), is refused
  with 409 and nothing saved; the send refuses a draft with any line above its availability (409). The lines answer
  `availableQty`.

### Fixes
- `GET /sales-header/pending-sales` sends each ticket's `discountSource` (MANUAL or PROMOTION): the till taking a held
  ticket back no longer shows its cart offer a second time as a "Remise" (front CHANGELOG).
- Copies down, head office: a store's pull of LOYALTY, PROMOTIONS or SUPPLY answered HTTP 500 (query timeout after
  15 s) while a catalogue writer was recording (seen during the first ZEN images run): the read of
  `ho_down_sequence` scanned the table (the driver's nvarchar against the varchar column) and waited on the locked
  CATALOGUE row. `HoDownSequenceRepository` reads and increments with native queries, the domain cast to varchar: a
  seek on the domain's own row.
- Copies down, head office: a data import or the item images run recorded its codes one by one (a JPA lookup that
  read every row of the domain per code, about 100 ms each), holding the CATALOGUE sequence row about 40 s per chunk of
  500: the stores' CATALOGUE pulls timed out meanwhile (HTTP 500), and the first images run took 31 minutes for 17,140
  codes. `CatalogueHeadOfficeHooks.afterImport` now records each chunk in bulk: `CopiesDownFeed.recordChanges` for every
  store reserves the numbers in one update and writes the every-store rows with `JdbcDownChangeBatch.writeEveryStore`
  (casts kept); the same rows and numbers as before.
- connector-zen, `commandesVente`: NAV refused every order ("Control 'numeroDocExterne' is read-only"). The order number
  now goes in `referenceZS` (ZEN's field for it) and the lookup filters on `referenceZS eq '..'`; `numeroDocExterne` is
  never sent, and the store code is no longer sent (no writable header field fits it; `client` names the store).
- connector-zen, `commandesVente`: when NAV answers the lookup with "Field 'referenceZS' is not filterable", the lookup
  is skipped and the order posted; the communications log message ends with "duplicate check skipped: referenceZS not
  filterable". Any other lookup error still stops the posting.
- connector-zen, `commandesVente`: every line sends `mode` `vrac` by default (`erp.zen.order.mode` overrides it, set
  empty leaves it out); NAV refused lines without it ("Le mode de la ligne … doit être colis ou vrac").
- Store orders lists: `GET admin/headoffice/store-orders` and the store's `GET /store-order` answered 500 ("Parameter
  value [1] did not match expected type [java.lang.Integer]"): the "every store / every status" flags are `long` and the
  statement compared them with an `int` literal; now `1L`, as the BL reception list. `HoStoreOrderPageQueryTest` and
  `StoreOrderPageQueryTest` run the real statements on H2 with and without each filter.

### DB script (`db/3.1.1/store-orders.sql`, then `db/3.1.1/update.sql`, store and head office)
- `store-orders.sql`: tables `hol_order` (unique `number`, index `ix_hol_order_status`, filtered unique index
  `ux_hol_order_draft`: one DRAFT) and `hol_order_line` (unique `order_id`, `item_id`; FK to `hol_order`) on a store
  only; `item_image` (index `ix_item_image_item`) on both. Re-runnable, no data changed. The filtered index is also made
  at each start when missing (`StoreOrderDraftIndex`: a new database runs no script).
- `update.sql`: the ERP jobs' rows `IMPORT_ITEM_FAMILIES`, `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`,
  `IMPORT_ITEM_BARCODES` converted into one `IMPORT_CATALOGUE` (the cron of `IMPORT_ITEMS`, enabled when any of the four
  was, then the four deleted; one transaction, re-runnable; the same batch the application runs at startup).
- `update.sql`: `APP_VERSION` 3.1.1 (it stops while `store-orders.sql` has not run); notes written when the version is
  closed. The till's index `ix_sales_header_session_status` on `sales_header (cashier_session_id, status)` (re-runnable,
  store and head office): the session's ticket count and its pending tickets read through it instead of a scan of every
  sale.
- `store-orders.sql`: column `hol_order_line.available_qty` numeric(18,3) NULL (added when missing; a start adds it too).
- `store-orders.sql`: columns `hol_order.nav_message` varchar(500), `hol_order_line.nav_accepted_qty` numeric(18,3) and
  `nav_line_message` varchar(500) (store), `ho_store_order.nav_message`, `ho_store_order_line.nav_accepted_qty` and
  `nav_line_message` (head office), all NULL, each added when missing (a start adds them too).
- `store-orders.sql`: columns `invoice_no` varchar(30) and `delivered_at` date, NULL, on `hol_order` (store) and
  `ho_store_order` (head office), each added when missing (a start adds them too): the invoice of a DELIVERED order.
- `store-orders.sql`: tables `hol_order_colis` (store; unique order and item; FK to `hol_order`) and
  `ho_store_order_colis` (head office; FK to `ho_store_order`, index on the order), columns `hol_order.total_colis` and
  `ho_store_order.total_colis` int NULL, each when missing.
- `store-orders.sql`: columns `variant_code` varchar(50) and `color_description` varchar(255) NULL on `hol_order_colis`
  and `ho_store_order_colis` (in the tables and added to those of an earlier run); `uk_hol_order_colis_item` (order,
  item) dropped and `uk_hol_order_colis_variant` (order, item, variant) added, each when needed.
- `store-orders.sql`, colis by item: columns `colis_content`, `colis_disponibles` int, `nav_accepted_qty` numeric(18,3),
  `nav_numero_colis` varchar(50) NULL on `hol_order_colis` and `ho_store_order_colis` (`variant_code`,
  `color_description`, `contenance`, `colis_possibles` stay, unused); the per-colour rows merged into one per order and
  item (colis summed, colour cleared, no composition); `uk_hol_order_colis_variant` dropped, `uk_hol_order_colis_order_item`
  (order, item) added; tables `hol_order_colis_detail` and `ho_store_order_colis_detail` (FK to the colis row, index
  on it); `hol_order_line.vrac_allowed` bit NULL. Each when missing.
- `store-orders.sql`: column `ho_erp_invoice.order_no` varchar(50) NULL and index `ix_ho_erp_invoice_order` (head
  office), each added when missing: the sales order an ERP invoice names. The invoices read before keep none.
- `store-orders.sql`, step 2: tables `ho_store_order` (unique `store_id`, `number`; FK to `ho_store`; index
  `ix_ho_store_order_status`) and `ho_store_order_line` (FK to `ho_store_order`) on a head office only. Re-runnable, no
  data changed.

### Config
- `headoffice.storeorders.post` (head office, optional): `MANUAL` or `ERP` (needs a connector with
  `ErpAbility.STORE_ORDERS`: zen); absent, the head office takes no store order. Set to `ERP` in
  `configs/customers/zen-headoffice.properties`.
- `erp.zen.order.mode` (head office with the connector zen, optional): the `mode` of every line of a sales order on
  `commandesVente`; absent: `vrac` (NAV requires `colis` or `vrac`), set empty: the field is not sent. (The pages are no
  longer settings: `erp.zen.variantstock.path` and `erp.zen.salesorders.path`, added earlier in 3.1.1 for the mock, are
  gone; a line left in a file is ignored.)
- `erp.zen.products.url` and `erp.zen.products.api-key` (head office with the connector zen, optional, both or
  neither): ZEN's products API for the item images. Set in `configs/customers/zen-headoffice.properties` (the key
  `CHANGE_ME`: the real one in the installation's file only).

## 3.1.0 — 2026-10-27 (tag `v3.1.0`)

### Features
- RFID at the store (`rfid.enabled`, docs/modules/rfid.md): readers (Hopeland SDK, a fake reader for tests) and POS
  machines, one read at a time per reader, `GET /rfid/my-machine`; tags table, Tags and Tag assignment pages API, the tag
  button of a reception line; the till reads pieces into the cart (`POST /rfid/pos-read`), the tags go with the payment,
  the pending ticket and the return (`rfid_sale_tag`, `GET /rfid/ticket-tags`); a weak tag is below the reader's minimum
  signal; a pack holds no tag.
- Ticket X / ticket Z of a cashier session (`GET /cashier-session/{id}/report-ticket`) and of the store's day, every
  session (`GET /cashier-session/store-report-ticket/x` and `/z`); the cashier closing prints the ticket Z.
- Journal: the days of a month, a day's journal, Excel exports.
- Payment method "Retenue à la source" (type `RETENUE_SOURCE`), inactive by default.
- Item variants for ZEN (docs/variants-zen-analysis-2026-10-14.md): one item per ERP variant (code
  `itemNo~variantCode`) with its 14 variant fields; catégories and sections (the ERP's tree around families and
  sub-families); clearance and clearance price per point de stock row; an items run of changes only, with a checkpoint
  per point de stock; the head office copies carry catégories, sections and variant fields to the stores; ZEN's
  connector `connectors/zen` (`erp.connector=zen`, catalogue only, head office).
- NAV items page API (step 11a): `GET /nav-item`, `GET /nav-item/variants`, one line per NAV item number, the variant ×
  point de stock price grid at the head office, the stock only where the installation keeps its own.
- Catégories and Sections pages API (step 11b): `GET /item-category` (with the number of families),
  `GET /item-category/families?code`, `GET /item-section` (with the path catégorie › famille › sous-famille); GET only.
- Fiches items, head office page API (step 1, the list): `GET /admin/headoffice/item-catalogue?page&size&search&status`
  on every head office, one line per NAV item number, else one per item; search on NAV number, item code, name and
  barcode; GET only, the other item pages and APIs unchanged.
- Fiches items follows the customer's data: `GET /admin/headoffice/item-catalogue/filters` (flags hasVariants,
  hasFamilies, hasDivision, hasCategory, hasSection, stockPointCount, hasBarcodes and the filter choices); the list
  filters on family, sub-family, division, catégorie, section, point de stock, has variants and price with VAT, sorts by
  code (natural for the codes of digits only), name or family, and gives each line its price range with VAT.
- Fiches items, step 2, the item card: `GET /admin/headoffice/item-catalogue/card?navItemNo|itemId[&search]`, one
  answer with the header, every variant (active or not) with its active barcodes and point de stock rows, the points,
  and the item form's read-only details; the variant whose code or barcode equals the search is highlighted.
- Item cards, step 3: the list's `status=INACTIVE` (the lines without an active variant); the card gives each variant's
  `minStockLevel` and, in its details, `minStockLevel` and `lastDirectCost` (only where the head office keeps its
  stock). Headoffice project only.
- Promotions by catégorie and section (ZEN): scopes `CATEGORY` and `SECTION`, targets `promotion.item_category_id` /
  `item_section_id`. Matched at ticket time on the item's codes (catégorie: the item's own code, else its famille's);
  order item > group > section > sous-famille > famille > catégorie > all items, each new branch only for an item
  with the code (no change where the codes are empty: EMTOP, Happyness). Cross-product buy side too. Save: each new
  scope needs its target, other scopes keep neither; both locked once used. Head office copy by code
  (`itemCategoryCode`, `itemSectionCode`), missing = waiting as a famille; a store of an older build skips such a
  promotion (error). `GET /item-category` and `GET /item-section` entries carry the row `id`.
- Promotions on every variant of a NAV item, step 1 (ZEN): an ITEM promotion targets one item row (`item`) or one NAV
  item number (`navItemNo`: every item carrying it, also variants synced later), an ITEM_GROUP holds item rows and/or
  NAV item numbers (`groupNavItemNos`); `getNavItemNo` (benefit on any variant) is stored but refused for now.
  Matched at ticket time: item on its row > item on its NAV number > its groups (rows and NAV numbers one level,
  priority decides), read only for an item carrying a NAV item number (EMTOP, Happyness: the same reads and answers);
  cross-product buy side too. Save: an ITEM never holds both, a NAV number only on ITEM, NAV numbers only on
  ITEM_GROUP, trimmed; a quantity promotion on a NAV target or with a NAV benefit is refused (400) until the till counts
  the lines of a NAV item together (step 2); the three locked once used. The head office copy and the screens follow
  (steps 1b and 3).
- Promotions on every variant of a NAV item, step 1b: `GET /item/search` items carry `navItemNo` (null without one);
  `GET /nav-item/names?navItemNo=..&navItemNo=..` (read only): `{number: name}` of the NAV item numbers asked (at most
  200, a number no item carries left out), the names of the promotions' NAV targets.
- Promotions on every variant of a NAV item, step 4: `GET /item/lookup?scope&q&page&size` (read only), the target lookup
  of the promotion form: scope ITEM, one list of ERP item lines (every active variant of an ERP item number, its
  lowest id and its number of variants) and item lines, by name and ERP item number, each ERP item line right above
  its own variants; search on code, name, ERP
  item number and barcode; packs and inactive items left out; FAMILY, SUBFAMILY, CATEGORY, SECTION: their active
  rows, with the parent's name. Without ERP item numbers: item lines only, the same statement.
- Promotions on every variant of a NAV item, step 3: the head office copy carries `navItemNo`, `getNavItemNo` and
  `groupNavItemNos` (sorted); the store saves them as they are, never waiting for an item carrying them (a group is
  missing only with none of its rows and no NAV number), and logs one INFO line for a number no item of the store
  carries yet. A copy without them (null / empty) compares equal to an existing promotion: nothing rewritten (EMTOP,
  Happyness). A store of an older build ignores the fields.
- Promotions on every variant of a NAV item, step 2, counted across the cart lines (rule 6a): `POST /price-calculate`
  takes `cartLines` (the cart in order) and answers `acrossLines`; the cart pass (`customerId`) adds `lineResults`, every
  line priced with the cart around it (a cart of one item: its line priced alone). Quantity promotions on NAV targets
  and a NAV free item are accepted; a benefit on both an item and an ERP item number is refused; buy NAV = get NAV is
  a same-item promotion. Cross-product:
  `getNavItemNo`, `freeByItemId`, `entitledByItemId` (cheapest first). Promo code: `navItemNo`, `groupNavItemNos`,
  `getNavItemNo`. Without `cartLines`, or a cart of one line: priced exactly as before.
- Till levels catégorie and section, step 1, API only (ZEN; docs/modules/head-office.md, "Till levels"): read-only POS
  lists `GET /item-category/pos`, `GET /item-family/pos?category={code}` and `?withoutCategory=true`,
  `GET /item-section/pos?subFamily={id}`, `GET /item/pos/by-section/{id}`, the fall-throughs
  `GET /item/pos/without-section/{subFamilyId}` and `GET /item/pos/without-family?category={code}`; a section holds the
  items of its own sous-famille that carry its code. `GET /item/pos/search` adds `categories` and `sections` and a `path`
  on every hit, only where the installation has the level: without catégories and sections (EMTOP, Happyness) every
  new list is empty and the search answer is unchanged.
- Supply invoices of ZEN's NAV, step 9a (the read; docs/modules/head-office.md, "Invoices from the ERP, ZEN"): the
  connector `zen` reads ZEN's posted sales invoices (since moved to ZEN's franchise API, see Enhancements) as Happyness's invoices (by year
  prefix `FVE26` after the head office's highest number, the General Setup "Read ERP invoices after number" as the
  starting point), only the invoices of the stores' ERP customer numbers (every store that has one, active or not:
  erp-api `ErpInvoiceCustomers`, head office `HoErpInvoiceCustomers`; none: nothing read, a warning); item code
  `No~Variant_Code`; a cancelled invoice read past and left out, a corrective one read with a warning; the runs in
  the communications log. Ability `SUPPLY_INVOICES`: `headoffice.supply.source=ERP` is accepted with `zen` (the
  configs keep `HEAD_OFFICE` until the live test, step 9b).

### Enhancements
- Till authorisation, step B: `GET /config` serves `tillActions`, the registry of the five badge actions (`{code, key}`,
  `BadgePermission.key()` = the `till:*` role permission key of step C), empty on a head office; the frontend builds its
  badge lists, labels and filters from it.
- Promotions: a free piece only when scanned (buy N get M), repeated for each group of N + M pieces; the free pieces
  carry RFID tags; a return is whole per item.
- Stock adjustments accept decimal quantities when the store allows them; the till's search runs over the whole
  catalogue.
- `GET /config` gains `cashierClosingTicket`, `navItems`, `itemCategories`, `itemSections` (each read on each call).
- Session report: an undeclared payment method reads null.
- Till authorisation, step C: the till rights belong to the role, five permissions `till:consult-customer-list`,
  `till:make-return`, `till:apply-line-discount`, `till:apply-total-discount`, `till:close-session-with-discrepancy`
  (listed by `GET /app-roles/permissions`, sent with the login's `permissions` as the page permissions); a new store
  database gives the five to every role, a head office none. A badge is a credential only (code, expiry, revoked): it
  opens an action when its owner's role holds it, else `BADGE_NO_ACCESS`. The per-user badge list is no longer read or
  written (`badgePermissions` removed from the user DTO and `PUT /user-account/{id}`).
- Till authorisation, step C: on a store of before, at its first start, once (`TILL_RIGHTS_MIGRATED`), each role gets
  the rights every active user of the role passes today without a badge (an admin: the five; with
  `ALWAYS_SHOW_BADGE_SCAN_POPUP` on: nobody else; else his card's list with a valid badge, the close with a difference
  excepted); the role ADMIN gets the five; rights only added; the role × right matrix logged once. Nothing changes at
  the till on the day of the upgrade.
- Till authorisation, step C: every pass is logged in `badge_scan_log` with `granted_by`: a scan (BADGE) or a pass on
  the role's right (ROLE, no badge code, the owner is the logged user), the latter by `POST /badge/pass {action}` (403
  `TILL_RIGHT_REQUIRED` when the role does not hold it); `GET /badge/scan-history` filters `grantedBy` and its rows give
  it; the statistics count the scans only; a scan logs the scanning user's open session (the till's `sessionId` is no
  longer trusted).
- Till authorisation, step C: closing with a cash difference: the logged user whose role holds
  `till:close-session-with-discrepancy` confirms the difference (`discrepancyConfirmed`) whatever the role's name;
  anyone else brings a badge whose owner's role holds it (2.2.2: an admin by the role enum only, a badge by its list).
- Till authorisation, step D: the server checks the till rights on the requests that do the actions: each manual line
  discount and the manual header discount of `process-sale`, `save-pending`, `update-pending/{id}`,
  `complete-pending/{id}`, the return (`process-return`) and the close with a cash difference. The role's right passes
  (logged ROLE once per request); else the request carries the grant of a badge scan (`tillGrant`, per line, header,
  return, close), used once (logged BADGE with the badge's owner); else 403 `{error: TILL_RIGHT_REQUIRED, action,
  reason, lineIndex}` and nothing of the request is kept. A pending ticket keeps the discounts it held (not larger).
  Promotions, price lists and lines without a source need nothing; the split payment and the head office unchanged.
- Till authorisation, step D: `POST /badge/scan` gives a grant for the actions checked on their request (`grant: {token,
  action, badgeOwnerId, issuedAt, expiresAt}`): one action, the scanning user's open session, single use, good for
  `TILL_GRANT_MAX_MINUTES` (240); only its SHA-256 is stored. Such a scan's success is logged when the grant is used,
  no longer at the scan. `GET /config tillActions` gives `checkedOnRequest`. The close takes `tillGrant` instead of
  `badgeCode` and `badgePermission`.
- Step E: the enum `Role` is retired; a user's role is his AppRole and an administrator the user of the system role
  named ADMIN (`SystemRoles`; the ERP admin pages and the rule giving or removing the ADMIN role use it). The login,
  `UserAccountDTO`, `CreateUserRequestDTO` and `GET /user-account/cashiers` no longer carry `role`; `PUT
  /user-account/{id}` no longer reads it; creating a user needs `appRoleId`. Users of before without an AppRole get the
  role named as their enum value at the start (as before, now by JDBC).
- Till rights, discount sources: every discount of a sale or pending request needs the right (or a grant) unless the
  server verifies its source: PROMOTION with an existing active promotion, SALES_PRICE / SALES_DISCOUNT with the item's
  price list discount for the customer (not a larger percentage). A source missing, unknown or not verified counts as
  MANUAL (403 `NO_GRANT` without the right). `save-pending` and `update-pending/{id}` now store each line's and the
  header's discount source and promotion, so a recalled ticket keeps them; a ticket parked before counts as MANUAL and
  passes on the discounts it held.
- Item variants (step 11c): the return screen's ticket lookup and the return voucher lines give the item's
  `variantDescription` (`ReturnHeaderAPI`), so the till and the tickets show "name — variant" (null for the other
  customers: the name alone, as before). The sale lines already carried it through the item.
- Item variants (step 12a): ZEN's connector reads ZEN's real barcodes page `barCodes` (`itemNo`, `variantCode`,
  `barCode`, `lastModifiedDateTime`, `id`) instead of Happyness's `ItemBarCodePOS`; the barcode cursor is
  `lastModifiedDateTime` in epoch milliseconds (NAV's empty date: 1), a full page completed with every row of its last
  millisecond so that none is skipped and the run ends caught up; a barcode changed in NAV is read again.
- Item variants (ZEN), clearance at the till: a point de stock row with clearance true and a clearance price above 0
  sells at its clearance price (before VAT, the item's VAT); the head office sends it as the item's price to the stores
  of the point (`HoCatalogueService.pointCopy`, one rule `StockPointPrice`), with `clearance` and `publicPrice` for the
  till's display; a clearance change reaches the stores as any row change. The head office pages give
  `clearanceApplies` and `sellingPrice`. EMTOP and Happyness unchanged (no clearance).
- ERP items page at a store: `GET /nav-item/variants` gives each variant's `clearance`, `publicPrice` and `ownPrice` as
  the till has them (null at a head office and for the other customers), so the page strikes the public price as the
  till does.
- Item variants (ZEN): the connector builds the variant description from the size, colour and choice descriptions
  ("Taille Standard / VERT PISTACHE / 1er Choix", the blank ones left out) and no longer reads NAV's
  `variantDescription` (always the item description on ZEN's platform); a change of one of them changes the item. The
  item name stays NAV's item description.
- Fiches items card (`GET /admin/headoffice/item-catalogue/card`): `storeStocks`, the stock each active store last sent
  for the card's item codes (`ho_store_stock`, one query, by store code then item code), and `header.withStoreStocks`;
  empty where no store sends its stock. Parameters and the other fields unchanged.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): `sort=variants` (the line's variant count) and
  `sort=price` (its smallest price with VAT, lines without a price last both ways), each then by code.
- Network stock (`GET /admin/headoffice/stock`): `stock=ALL|IN_STOCK|ZERO|BELOW_ZERO` on the columns shown (the head
  office's stock where it keeps one, and the stores shown; `belowZero=true` still means `BELOW_ZERO`), and the item
  filters `familyId`, `subFamilyId`, `divisionCode`, `categoryCode`, `sectionCode`; native SQL built with only the
  filters set (`HoNetworkStockQuery`).
- Network stock, step 2: `GET /admin/headoffice/stock` sorts on `code` (natural, the default), `name`, `headOffice`,
  `store-<id>` (items the store never sent last) and `total` (the active stores; with 2 stores shown or more), `dir`;
  rows give `itemId`, `navItemNo` and `totalStores`, the answer `withTotal`. `GET .../stock/own`: `stock`
  (IN_STOCK, ZERO, BELOW_ZERO on the row's quantity), `sort` (store, code, name, quantity, storeTime, receivedAt), `dir`;
  default order store code then item code (was store id).
- Classification column (every items page, catégorie › famille › sous-famille › section): the filter choices of
  `GET /admin/headoffice/item-catalogue/filters` give `parentCode` (a family's catégorie, a section's sub-family code)
  for the level filters' narrowing; the rows of `GET /admin/headoffice/stock-points/items` (and `/items/{rowId}`) the
  item's `categoryCode`, `categoryName`, `sectionCode`, `sectionName`; the barcode rows (`GET /item-barcode/list`,
  `/item-barcode/items-without-barcode`) the item's `categoryCode`, `sectionCode`. All nullable: null on a customer
  without catégories and sections (EMTOP, Happyness). No filter, sort or other answer changed.
- Supply invoices of ZEN, on ZEN's franchise API (its NAV team moved them): the connector `zen` reads
  `api/sat/franchise/v1.0/companies(<id>)/salesInvoices` (the zsretail API's company id) with the lines
  `salesInvoiceLines`, instead of NAV's OData V4 page `SalesInvoice`; the camelCase fields (`number`,
  `sellToCustomerNo`, `lineType`, ...); the totals from the header (`amount`, `amountIncludingVAT`, the VAT their
  difference), the lines checked against `amount`; no `$select` on the header, so `cancelled` and `corrective` are read
  once the page gives them (not yet: until then every invoice is read and sent); a `currencyCode` that is not blank
  gives a warning. Item code, line types, costs at the store and the head office flow unchanged.

### Fixes
- ERP jobs no longer stop for good on a NAV call that never answers (Emtop 1.12.1, 2026-10-22: Import Customers waited
  on NAV from 14:18 and no job ran by itself again, only "Run now"): the emtop connector's client has a connect timeout
  (30 s) and a read timeout (600 s of silence, not the length of a read), like the happyness and zen connectors.
- Promotions, group of items (Emtop 1.12.1: a group saved with one item): the back end no longer depends on what the
  screen sends. `groupItems` is read by distinct id (`{ id }`, `{ id, itemCode }` or a full item give the same group)
  and `PromotionService.save` loads those ids from the database; an unknown id or an item without an id is refused.
  `Item` equality is unchanged.
- Quantity promotions count the whole pieces of every cart line of their target (rule 6a, every customer, decided
  2026-10-23): a minimum quantity or a "buy N get M free" on a famille, sous-famille, section, catégorie, group, NAV
  item or all items read each line alone (2 shirts of one famille in two lines never reached "buy 2"); the cross-product
  buy side summed each line's floor (1 + 1 pieces never reached N = 2). Level by level (row, NAV item, group, section,
  sous-famille, famille, catégorie, all items), each level counting only the lines no earlier level took; free pieces
  cheapest first, then cart order; a free promotion giving a free piece takes every line it counts. A cart of one line
  is priced as before. Check EMTOP and Happyness quantity promotions on other scopes than ITEM before deploying.
- `Item` equality, root cause of the group saved with one item (Emtop 1.12.1): two items are equal when they have the
  same id (read through `getId()`, a Hibernate proxy equals its item), an item without an id only to itself, one hash
  for every item; Lombok's equality left the id out, so items with only an id were all equal. The other maps keyed by
  an item hold saved rows (invoice and purchase invoice lines grouped by item, head office supply prices): unchanged.
- Promotions page of a store (`POST /promotion`, `PUT /promotion/{id}`): a promotion refused by the save rules (a group
  without items, a catégorie or section promotion without its target, the NAV targets' rules) answers 400 with the
  reason, as the head office does; it answered 500.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): failed on SQL Server ("duplicated sql alias []", the page
  showed no item): every column of its native queries now has its own alias.
- Badge scan history: the statistics count every row of the period, the passes on the role's right (ROLE) with the
  badge scans (BADGE), as the list shows them (the tiles said 19 for a list of 20).
- Batch 3 (test log 2026-10-17): a scan of a known barcode whose item is inactive answers 404 with `inactive: true`
  (the till says "Inactive item"), and an inactive item is never found by the scan (I-04); points removed on a return are
  rounded down as the points earned, the money returned at the sale's rate (24.657 TND returned removes 24, was 25), a
  tiered program still removing what the money kept loses to a lower tier (I-57); the head office ticket and return
  details, network stock and delivery note lines give the item's `variantDescription` when the head office item has one
  (I-24); the sales history list gives `loyaltyCardNumber` (I-54); `GET /promotion/paginated` takes `active`
  (true / false, none: all).
- Invoices (test log I-71): the invoice header's and lines' amounts (HT, VAT, discount, TTC) are stored to the millime,
  half up, on every save, as the tickets (I-07); the header is the sum of the rounded lines. Unit prices unchanged;
  invoices stored before are not corrected.
- Sales report (test log I-67 re-test): the report answered 500 in every grouping since the points fix (HQL
  `unexpected token: /`: Hibernate 5 takes a single term after `THEN`); the points share is in parentheses.
- Stock report: a negative stock has its own status `NEGATIVE` (was `OUT`, test log I-52). Statistics: a KPI delta is
  null, no comparison, when the previous period is 0 (was 0.00, test log I-51).
- Sales report (test log I-67): the loyalty points spent are taken off as the ticket discount, in every grouping, so the
  report adds up to the tickets' paid total; the points share is in the discount column.
- Invoice made from tickets (test log I-63): a ticket with a ticket discount, a cart promotion or points spent adds one
  "Remise ticket <number>" line (HT, VAT, TTC negative), so the invoice adds up to what was paid; the ticket's lines are
  unchanged and a ticket without a reduction gives the invoice of before.
- Head office BL sent with items without a supply price (test log I-35): the validation's answer lists them
  (`missingSupplyPrices`) and logs one WARN line; the BL is sent as before (their supply invoice, and so the store's cost,
  wait for a price).
- Reports grouped by family (test log I-66): the sales and purchase reports count the items without a family, under
  "—" (they were left out).
- Statistics (test log I-49, I-50): "Top 5 products" counts the items without a family (family "—"); the payment split's
  cash is net of the change given back, as the ticket Z (it counted the cash tendered).
- A return's total and a session's expected cash to the millime (test log I-07): `total_return_amount` and `real_cash`
  stored rounded half up; the closing cash check compares the rounded expected cash.
- Return vouchers to the millime (test log I-07): `voucher_amount` and the amount used are stored rounded half up, and a
  voucher payment debits the voucher with the payment's rounded amount (remaining and "fully used" read on the millimes),
  so the voucher and its payment carry the same value.
- Ticket amounts to the millime (test log I-07): a ticket's amounts, its lines' amounts and its payments are stored
  rounded to 3 decimals, half up, on every save (`roundAmounts` of `SalesHeader`, `SalesLine`, `Payment`; unit prices and
  percentages untouched); paid vs total is checked on the rounded values with the till's tolerance of 0.010 (a sale more
  short is refused, before nothing was checked) and the change is rounded paid − rounded total (no more 0.0002). Tickets
  stored before are not corrected; vouchers and the session's real cash are not covered.
- Ticket HT and VAT after a ticket-wide reduction (test log I-48): a ticket with a ticket discount, a cart promotion or
  loyalty points spent stores the HT and VAT of what was paid, spread over the VAT rates in proportion to the lines'
  TTC as the ticket X / Z spreads them (`SalesHeaderService.applyTicketReductionToTotals`), so HT + VAT + stamp = total
  on the receipt, the ticket and the head office copy (85.252 = 71.640 + 13.612, was 79.600 + 15.124). The lines keep
  their amounts before the reduction; a ticket without one keeps the till's values. The ERP ticket export takes its
  gross from the lines, so NAV receives the same discount % as before. Tickets stored before are not corrected.
- Users (`/user-account`, test log I-21): nobody changes the role of his own account, and only an administrator gives
  or removes the Administrateur role, on an edit or a new user; a refused change answers 403 with the reason in French
  (`RoleChangeRefusedException`). A responsible could make himself Administrateur.
- The closing cash check compares cash with cash.
- The return's ticket lookup refuses a ticket that is not completed.
- The RFID Tags page list on SQL Server (Msg 402); a pending ticket paid with its tags no longer breaks
  `uk_rfid_sale_tag_header_epc`.
- A FREE_QUANTITY discount in the session report.
- ZEN's barcodes (step 12a): the run no longer ends on "rows at or below the cursor". NAV keeps the dates in SQL
  datetime (1/300 s) and rounded the filter's cursor + 1 ms back to the cursor; the filters are now
  `lastModifiedDateTime gt <cursor>` and, to complete a full page, `eq <its last time>`.
- Head office link (store): after a "Run now" the job's next run comes from the end of that run, 5 s later when it has
  more to do (e.g. the catalogue copies down by cycles of 1,000), else its frequency; it stayed at the time the
  schedule had computed before (30 min for the copies), also when the page stopped waiting after 30 s.
- Speed with a large catalogue (ZEN's test head office, 21,903 items): the page "Items by point de stock" without a
  search no longer reads every item's barcodes (its query took minutes; the barcode search is only in the query of a
  search); the store reads the tracking rows of a page of copies down in one query, not one per record (each read
  scanned `hol_down_record`); a page of barcodes is read with its items in one query; a store's pull reads its changes
  in the new index alone (the domain cast to `varchar`). Same answers and rows as before.
- Speed of the copies down, barcode phase (store): each barcode received read the old `item.barcode` field twice, a
  scan of `item` each time (3–5 s per page of 100 on 21,904 items); now one read, on an index.

- Head office link (store), copies down: a slow head office no longer stops the catalogue catch-up for 30 min. The
  pull has its own read timeout (60 s, `headoffice.copies-down.read-timeout-seconds`; heartbeat and pushes keep
  10 s); a read timeout is `TIMEOUT` "head office answered too slowly (read timeout 60 s)", not OFFLINE; a run that
  failed on a timeout or an I/O error runs again 10 s later, up to 3 times ("retry in 10 s (1/3)"), the 4th back to
  the frequency with one WARN line; a failed pull is logged at WARN (was INFO).
- ERP catalogue, items step (speed): a packet of items reads its existing items in one query by ERP id and one by
  code, the families and sub-families once, instead of up to five SELECTs per row (each scanning `item`, which has no
  index on `erp_external_id`, and flushing the packet); the item and point de stock row updates go by JDBC batches of
  100 (that transaction only). One INFO line per items step (rows read, equal, new, changed, written, ms) and per
  packet. Unchanged rows were already never written (the engine compares every field).
- ERP catalogue run: a page read that fails on an I/O error or a timeout (e.g. a VPN drop) is read again once 5 s
  later, with one WARN line, for every connector; a second failure fails the step as before; cursors and
  checkpoints move only on a read that succeeds.
- Security (step S): the `/user` controller is removed (`UseAccountAPI`: generic CRUD on the raw `UserAccount`, password
  hash included, and `PUT /user/{id}/role`, which set the role enum without the I-21 check), and so are the unlogged
  badge lookups `GET /badge/by-badge/{code}`, `POST /badge/check-permission`, `GET /user-account/by-badge/{code}`,
  `GET` and `PUT /user-account/{id}/badge`; no caller in the front.
- Security (step S): the badge scan history reads (`/badge/scan-history`, `/badge/{code}/scan-history`,
  `/badge/scan-statistics`) answer 403 unless the role holds `read:admin-badge-scan-history` (any logged user read them),
  and give flat rows (`BadgeScanLogDTO`, the fields the page reads) instead of the entity, which carried both users'
  `UserAccount` (password hash, badge code): the "Scanned by" and "Badge owner" columns are filled again; no badge code
  in the rows nor in the statistics (`topBadges` removed), which give `failedScans` (the "Failed" card showed 0).
- Security (step S): the badge of a deactivated user is refused: at a scan `BADGE_OWNER_INACTIVE` (logged like the
  other refusals), at the close with a cash difference as an unknown badge. It opened every action before.
- Security (step S): badge codes are unique in the database (filtered index `ux_user_account_badge_code`, created by
  the script, or at the start of a new database by `UserBadgeCodeIndex`); a generated code takes its 4 digits from
  `SecureRandom` (was `java.util.Random`); a new database's responsible gets a generated badge code, logged once at the
  first start, instead of the fixed `RESP-BADGE-001` written in the source.
- connector-zen: stockPoints new fields — HT prices (unitPrice, clearancePriceExclVAT), vatPercent, the three blocked
  flags. `publicPrice` and `clearancePrice` are the prices with VAT and were read as the prices before VAT (a till sold
  19.50 × 1.19); they are now a check only (a pair that does not agree with the price before VAT × VAT is noted). VAT
  from `vatPercent` (absent: `erp.zen.default-vat`); `itemBlocked`, `variantBlocked` or `stockPointBlocked` true makes
  the row inactive, each noted; the guessed `active` and `vat` fields are gone. A row of the old page (`publicPrice`, no
  `unitPrice`) fails the read of its location, nothing applied. The engine compares a clearance price at 5 decimals.
  **Deploy**: on a head office that already read ZEN's rows, reset the points' checkpoints (`items.since:*` keys of
  `erp_catalogue_state`), else the rows NAV does not change again keep the price with VAT (docs/modules/erp-sync.md).
### DB script (`db/3.1.0/update.sql`, store and head office)
- RFID tables on a store: `rfid_reader` (with `min_signal`), `pos_machine`, `rfid_tag`, `rfid_sale_tag`.
- Payment method "Retenue à la source", inactive (kept as it is where EMTOP 1.12.1 has it).
- Promotions by catégorie and section: nullable `promotion.item_category_id` and `item_section_id`, their foreign keys
  (only when the column has none) and indexes, store and head office; re-runnable, no data changed.
- Promotions on every variant of a NAV item: nullable `promotion.nav_item_no` and `get_nav_item_no` (varchar(255), as
  `item.nav_item_no`), index `idx_promotion_nav_item_no`, table `promotion_group_nav_item` (`promotion_id`,
  `nav_item_no`, primary key both, foreign key to `promotion`) with index `idx_promo_group_nav_item_nav`; store and head
  office, re-runnable, no data changed.
- Item variants: 14 nullable columns of `item`, `item_family.category_code`, tables `item_category` and `item_section`,
  `ho_stock_point_item.clearance` and `clearance_price` (head office), index `ix_item_nav_item_no` (plain, not filtered).
- Item variants (ZEN): `item.variant_description` rebuilt from `size_description`, `color_description`,
  `choice_description` (" / ", the blank ones left out, null when none) for the items with a `nav_item_no`;
  re-runnable.
- Clearance at the till (ZEN): nullable `item.clearance` (bit) and `item.public_price` (float), store and head office
  (written on a store only, by the copies down); re-runnable, no data changed.
- Speed, the ERP import: indexes `ix_item_erp_external_id` (`item.erp_external_id`) and
  `ix_item_barcode_erp_external_id` (`item_barcode.erp_external_id`), store and head office; re-runnable.
- Speed: indexes `ix_item_barcode_item` (`item_barcode.item_id`) and `ix_item_barcode` (`item.barcode`), store and
  head office; on a head office
  `ix_ho_down_change_pull` (`domain`, `change_version`, `store_id`, `record_code`) replaces `ix_ho_down_change_version`.
  Re-runnable, no data changed.
- Security (step S): filtered unique index `ux_user_account_badge_code` (`user_account.badge_code`, not null), store
  and head office; skipped with a warning when two users already share a code; re-runnable, no data changed.
- Till authorisation, step C: `badge_scan_log.granted_by` (varchar(10), nullable; the rows of before set to `BADGE`)
  and `scanned_badge_code` nullable, store and head office, re-runnable (also at each start, `TillSchemaUpgrade`). The
  till rights are given to the roles by the first start, not by the script (it must read `ALWAYS_SHOW_BADGE_SCAN_POPUP`
  first). `user_account.badge_permissions` is kept, unused (deprecated, dropped in the next version).
- Till authorisation, step D: table `till_grant` on a store (the grants of the badge scans: token SHA-256, action,
  session, user who scanned, owner, badge code, issued, expires, consumed, by what); re-runnable, no data changed.
- Step E: `user_account.role` nullable (its type kept; also at each start, `TillSchemaUpgrade`), kept unused
  (deprecated, dropped in the next version); the users without an AppRole given the role of their enum's name. Store
  and head office, re-runnable.
- Discount sources of pending tickets: `sales_line` / `sales_header` `.discount_source` and `.promotion_id` made sure
  of (they exist already); nothing filled in, the pending tickets of before keep nulls. Re-runnable.
- ZEN's clearance price before VAT keeps 5 decimals: `ho_stock_point_item.clearance_price` `numeric(18,5)` (head
  office; a column made with 3 decimals widened, its values kept); re-runnable.
- `APP_VERSION` 3.1.0 and its notes.

### Config
- `rfid.enabled` (store, default false).
- `erp.connector=zen` and `erp.zen.*` (`base-url`, `company` or `company-id`, `domain`, `username`, `password`,
  `default-vat`, `packet-size`, `dry-run`, page names); deploy files `configs/customers/zen-headoffice.properties` and
  `zen-store.properties`.
- `erp.zen.default-vat`: the VAT of a stockPoints row without `vatPercent` (the page gives it since 2026-10-24).
- `erp.zen.page.barcodes` defaults to `barCodes` (step 12a; was `ItemBarCodePOS`).
- `erp.zen.invoices.*` (step 9, all optional): `page` (`salesInvoices`), `lines-expand` (`salesInvoiceLines`),
  `api-path` (`api/sat/franchise/v1.0`), `number-prefix` (`FVE`), `page-size` (50, 1 to 1000), `seller-name` (`ZEN`).
  The company as for the other pages (`company` or `company-id`: the invoices API is under the same `companies(<id>)`);
  `configs/customers/zen-headoffice.properties` names ZEN's company `ESP`.
- `headoffice.copies-down.read-timeout-seconds` (store, default 60): the read timeout of the pull of copies down.
- `erp.emtop.connect-timeout-seconds` (default 30) and `erp.emtop.read-timeout-seconds` (default 600): the emtop
  connector's NAV timeouts.
- General setup `CASHIER_CLOSING_TICKET` (`Z`, the default, or `DECLARED`).
- General setup `ALWAYS_SHOW_BADGE_SCAN_POPUP` removed (till authorisation, step C): no longer created; on a store its
  row is read once by the till rights migration, then deleted. `TILL_RIGHTS_MIGRATED` (read only): written once by that
  migration, with its date and the role × right matrix.
- General setup `TILL_GRANT_MAX_MINUTES` (store, default 240): the minutes a badge scan's grant stays good when no
  request used it (till authorisation, step D).

## 2.3.0 — 2026-10-11 (tag `v2.3.0`)

### Enhancements
- No functional change from 2.2.2: the store and the head office are two applications, each its WAR deployed as
  `zsretailws` (code cleanup, projects erp-api, connectors, core, store, headoffice, network-tests).

### DB script
- `APP_VERSION` 2.3.0 and its notes.

### Config
- The outside file names no type: remove `spring.profiles.active` and `node.type`; choose the ERP with
  `erp.connector`; rename `erp.dynamicsnav.*` to `erp.emtop.*` and `erp.navpospages.*` to `erp.happyness.*` (an old line
  stops the start, docs/deploy-note.md).

## 2.2.2 — 2026-10-10

### Features
- Returns with decimal quantities (when allowed), the amount the share of the line sold.
- Till: hide families and sub-families without an item to sell; a "Without sub-family" tile.
- Company information, "Sale ticket" block (address, footer text, thank-you line, "ZS Retail" mention).
- Store setting "Show amounts with 3 decimals on the POS screens".

### Enhancements
- An administrator is never asked for a badge at the till; at closing with a difference, the administrator sees the
  difference and confirms it.

### Fixes
- A fixed-amount promotion larger than the line no longer writes negative amounts; a decimal quantity typed when not
  allowed is refused with a message.

### DB script
- `return_line.quantity` and `ho_return_line.quantity` become DECIMAL(18,3); five nullable columns of the company
  information (the sale ticket's identity); `APP_VERSION` 2.2.2 (needs 2.2.1).

### Config
- General setup `POS_AMOUNTS_3_DECIMALS`, `POS_HIDE_EMPTY_FAMILIES` and `POS_HIDE_EMPTY_SUB_FAMILIES`, created at the
  first start, off.

## 2.2.1 — 2026-10-10

### Features
- Decimal quantities (up to 3 decimals) at the till, in the stock and its movements, the inventory count, the receptions
  and the head office's tickets, stock and ERP invoices.

### Enhancements
- The line amount of a decimal quantity is computed by the server (unit price with VAT × quantity, to the millime);
  pages show decimals untruncated; where decimals are not possible yet, a refusal names the item.

### Fixes
- Tax stamp: one rule for the till and the server.

### DB script
- The quantity columns (stock, sales lines, stock movements, head office copies, inventory counts, ERP invoices, BLs and
  receptions, purchase invoices) become DECIMAL(18,3), one transaction per column; `APP_VERSION` only when all are done.

### Config
- General setup `ALLOW_DECIMAL_QUANTITY`, created at the first start, off (off: as in 2.2.0).

## 2.2.0 — 2026-10-09

### Features
- Head office with the ERP catalogue: several points de stock (one ERP location each), read by the items job; each
  store receives only the items of its point, with their name, family and price there.

### Enhancements
- ERP invoices: a warning names the items not in the store's point de stock.

### DB script
- `ho_store.stock_point_id` and the last read columns of `ho_stock_point`; the tables `ho_stock_point` and
  `ho_stock_point_item` are created by Hibernate at the first start.

### Config
- `erp.navpospages.location-code`, `invoices.years` and `invoices.start-number` are no longer read (points created
  at the head office; the start number in General setup).

## 2.1.0 — 2026-10-08

### Features
- Head office: a new installation type that manages stores and never sells (stores, keys, their sales, returns and
  sessions); promotions and loyalty decided by the head office (one member register for the network); items and selling
  prices decided by the head office (base prices, price lists per store); supply by the head office (purchases, stock,
  BLs, reception at the store, the stock of every store); invoicing of deliveries to franchise stores; ERP invoices read
  from Business Central for a head office with the ERP catalogue.
- Inventory count imported from an Excel file.

### Enhancements
- A manual stock adjustment records its movement; editing an item no longer replaces its stock.

### DB script
- New nullable columns of existing tables; the `ho_*` (head office) and `hol_*` (store linked to one) tables are
  created by Hibernate at the first start; nothing dropped (the franchise columns stay, unused).

### Config
- One outside file per installation (database, port, log, ERP, head office address and key) and a preset (store, store
  ERP, head office, head office ERP, network store, network store ERP); the franchise profiles removed.

## 1.12.1 — 2026-10-12 (tag `v1.12.1`, hotfix of 1.12.0)

### Features
- Payment method "Retenue à la source" (`ENCAISS-RS-CLT`) for EMTOP.

## 1.12.0 — 2026-10-02

### Features
- Promotions: a new scope "All items", for an item no narrower promotion covers.
- Loyalty: earning tiers by ticket amount.

### Fixes
- Pending tickets earn and use loyalty points like a direct sale, with the tax stamp line and the loyalty block.
- Points conversion: the same ceiling at the till and on the server.
- Returns: only the points earned by the items returned are taken back, the points used are given back in proportion.

## Before 1.12.0

1.2.5 to 1.11.0: the changes are described in the release notes of each `db/<version>/update.sql`.
