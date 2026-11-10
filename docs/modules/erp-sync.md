# ERP Synchronization

## Synchronization Layer Kickoff
- Goal: backend-only abstraction that schedules data sync with external ERPs (one ERP per deployment).
- Jobs read/write via `ERP_Communication` log table; tracking level configurable in General Setup (`Errors only` or `All`).
- Initial scope: read families, subfamilies, items, item barcodes, locations, customers; post customers and ticket headers/lines.
- Sync DTOs are isolated from POS DTOs; each ERP implementation provides its own credentials/transport.
- Execution triggered by scheduled jobs defined in database (future configuration UI).
- Dynamics NAV integration scaffold: properties-driven NTLM REST client, family import wired through `DynamicsNavConnector`; default `NoOpErpConnector` only active when NAV (or other ERP) is disabled.
- ERP sync jobs seeded using job type as unique key (no separate job name column).
- Dynamics NAV now also exposes subfamily fetch (`SubFamilyList`) mapped to neutral DTOs.
- Locations (`LocationList`) pulled with country/block status mapping to ERP DTOs.
- Items (`StockkeepingUnitList`) and barcodes (`BarCodeList`) mapped to ERP DTOs including pricing and primary barcode flag.
- Incremental checkpoints (`ErpSyncCheckpointService`) persist the last `Modified_At` per job in `GeneralSetup`; `ErpSyncFilter.updatedAfter` (offset-aware) is seeded from these values before each run and updated post-import when DTOs expose timestamps.
- ERP sync bootstrap persists item families/subfamilies into local tables using `ErpItemBootstrapService`, with new `erp_external_id` columns for idempotent upserts.
- `ErpItemBootstrapService` now also upserts items, barcodes, customers, and locations into POS repositories, wiring through `ErpSyncJobRunner` so imports immediately populate local data.
- Admin-only REST view `admin/erp/jobs` exposes current ERP sync job configuration, last run metadata, and checkpoint snapshot using `ErpSyncJobViewDTO`.
- Incremental fetch for NAV items/barcodes leverages `Modified_At` when `ErpSyncFilter.updatedAfter` is provided, while other ERPs can still fetch full datasets.


## Dynamics NAV Ticket Synchronization (EXPORT_TICKETS)
**Status**: ✅ Complete

**Overview:**
- Exports completed sales tickets (orders) from POS to Dynamics NAV
- Synchronizes both header and line items sequentially
- Tracks synchronization status for each ticket and line item
- Handles partial synchronization and automatic retries

**Implementation Details:**

**Entity Changes:**
- `SalesHeader`:
  - Added `synchronizationStatus` field (enum: `NOT_SYNCHED`, `PARTIALLY_SYNCHED`, `TOTALLY_SYNCHED`)
  - Added `erpNo` field to store Dynamics NAV document number (`Document_No`)
  - Default status: `NOT_SYNCHED`
- `SalesLine`:
  - Added `synched` boolean field (default: `false`)
  - Tracks individual line synchronization status

**New Enum:**
- `SynchronizationStatus` - Tracks ticket synchronization state

**DTOs:**
- `DynamicsNavSalesOrderHeaderDTO` - For creating sales order headers in NAV
  - `Document_No` field is read-only (excluded from POST requests via `@JsonIgnore` on getter)
  - Fields: Document_Type, Sell_to_Customer_No, Sell_to_Customer_Name, Responsibility_Center, Location_Code, Posting_Date, Fence_No, POS_Document_No, POS_Invoice, Fiscal_Registration, Discount_Percent, POS_Order
- `DynamicsNavSalesOrderLineDTO` - For creating sales order lines in NAV
  - Fields: Document_Type, Document_No, Line_No, Type, No, Quantity, Unit_Price, Line_Discount_Percent

**Services:**
- `TicketExportService` - Main service for exporting tickets
  - `exportTickets()` - Exports all unsynced completed tickets
  - `exportTicket(SalesHeader)` - Exports a single ticket
  - Handles header creation first, then lines sequentially
  - Updates `POS_Order` to `true` in NAV when all lines are synced
  - Logs all ERP communications using `ErpCommunicationService`
  - Captures error response bodies for debugging

**Client:**
- `DynamicsNavRestClient`:
  - `createSalesOrderHeader()` - Creates header in NAV (POST to SalesOrdersPos)
  - `createSalesOrderLine()` - Creates line in NAV (POST to SalesOrdersPosSalesLines)
  - `updateSalesOrderHeaderPosOrder()` - Updates POS_Order flag via PATCH
  - `extractErrorMessage()` - Extracts detailed error messages from NAV JSON responses
  - Re-throws original HTTP exceptions (not wrapped) to preserve error response bodies
  - Reads the store's General Setup `DEFAULT_LOCATION` (items) and the responsibility centre of the default location
    (prices, discounts) through `erp/api/ErpStoreLocation`, answered in core by `ErpStoreLocationService` (code cleanup
    step 5.8d; the same values, `DynamicsNavRestClientValuesTest`)

**Configuration:**
- `DynamicsNavConfig` - Configured RestTemplate with:
  - `JavaTimeModule` for proper `LocalDate` serialization (as "YYYY-MM-DD" string)
  - `NON_NULL` inclusion to exclude null fields from requests
  - Custom ObjectMapper for consistent JSON handling

**Repository:**
- `SalesHeaderRepository`:
  - Added `findByStatusAndSynchronizationStatusNot()` method

**Job Runner:**
- `ErpSyncJobRunner`:
  - Integrated `TicketExportService` for `EXPORT_TICKETS` job type

**Synchronization Workflow:**
1. Scheduled job (`EXPORT_TICKETS`) finds all completed tickets that are not `TOTALLY_SYNCHED`
2. For each ticket:
   - If `NOT_SYNCHED`: Creates header in NAV, saves `erpNo` from response, sets status to `PARTIALLY_SYNCHED`
   - Exports unsynced lines to NAV (starting at line number 10000, incrementing by 10000)
   - Marks each successfully exported line as `synched = true`
   - When all lines are synced: Updates `POS_Order` to `true` in NAV and sets status to `TOTALLY_SYNCHED`

**Field Mappings:**
- `Fence_No` = CashierSession sessionNumber
- `POS_Document_No` = SalesHeader salesNumber
- `Sell_to_Customer_No` = Customer customerCode
- `Document_No` (returned from NAV) = stored in SalesHeader erpNo
- `Responsibility_Center` = from GeneralSetup "RESPONSIBILITY_CENTER"
- `Location_Code` = from GeneralSetup "DEFAULT_LOCATION"

**Error Handling:**
- All ERP communications are logged via `ErpCommunicationService`
- Error response bodies are captured and stored in response payload (not just error message)
- Detailed error messages extracted from NAV JSON responses (handles both array and object formats)
- Failed operations don't block other tickets from being processed
- Original HTTP exceptions are preserved (not wrapped) to allow error response extraction in parent services

**Important Configuration:**
- **GeneralSetup Required:**
  - `RESPONSIBILITY_CENTER` - for Responsibility_Center field
  - `DEFAULT_LOCATION` - for Location_Code field
- **Document_No Handling:**
  - Excluded from POST requests using `@JsonIgnore` on getter
  - Extracted from NAV response and stored in `erpNo` field
- **Date Serialization:**
  - `LocalDate` serialized as ISO date string ("YYYY-MM-DD") via `JavaTimeModule`
- **Null Fields:**
  - Automatically excluded from requests via `NON_NULL` configuration
- **Error Response Payload:**
  - Error responses from NAV are captured and displayed in communication details
  - Both success and error responses are logged in `ErpCommunication` table

**Key Files:**
- `core/src/main/java/com/digithink/zsretail/core/model/SalesHeader.java` - Entity with sync fields
- `core/src/main/java/com/digithink/zsretail/core/model/SalesLine.java` - Entity with synched field
- `core/src/main/java/com/digithink/zsretail/core/model/enumeration/SynchronizationStatus.java` - Sync status enum
- `connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/dto/DynamicsNavSalesOrderHeaderDTO.java` - Header DTO
- `connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/dto/DynamicsNavSalesOrderLineDTO.java` - Line DTO
- `store/src/main/java/com/digithink/zsretail/store/erp/service/TicketExportService.java` - Export service
- `connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/client/DynamicsNavRestClient.java` - NAV client
- `connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/config/DynamicsNavConfig.java` - RestTemplate config: connect timeout
  `erp.emtop.connect-timeout-seconds` (30) and read timeout `erp.emtop.read-timeout-seconds` (600, the silence allowed
  while waiting for NAV's data, not the length of a read). Without them a call NAV never answered held the one scheduler
  thread for good: no ERP job ran by itself any more, only "Run now" (Emtop 1.12.1, 2026-10-22)
- `core/src/main/java/com/digithink/zsretail/core/erp/service/ErpSyncJobRunner.java` - Job runner integration


## ERP Synchronization Operations

**Export jobs** (code cleanup step 5): `EXPORT_TICKETS`, `EXPORT_RETURNS`, `EXPORT_SESSIONS` run through `erp/service/ErpExportJob`
(`TicketExportService`, `ReturnExportService`, `SessionExportService`, the store's): `ErpSyncJobRunner` calls the export of the
job's type, in the same transaction as before (`ErpExportJobsTest`); an application without one sends nothing.

**Catalogue import job `IMPORT_CATALOGUE`** (3.1.1, every customer): one job replaced the four `IMPORT_ITEM_FAMILIES`,
`IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`, `IMPORT_ITEM_BARCODES`, which ran each on its own cron, in no set order (in one
scheduler tick by job type: barcodes before items, so a new item's barcodes were skipped while the barcodes checkpoint
moved past them). `core/erp/service/ImportCatalogueJob`, called by `ErpSyncJobRunner`, runs in a fixed order:
1. catégories: `ErpConnector.fetchItemCategories` (default: none) → `importItemCategories` (`item_category`, 3.1.0);
2. families: `fetchItemFamilies` → `importItemFamilies` (`item_family`; its `category_code` when the connector gives
   `categoryExternalId`), checkpoint `ERP_SYNC_LAST_ITEM_FAMILY`;
3. sub-families: `fetchItemSubFamilies` → `importItemSubFamilies`, checkpoint `ERP_SYNC_LAST_ITEM_SUBFAMILY`;
4. sections: `fetchItemSections` (default: none) → `importItemSections` (`item_section`, 3.1.0);
5. items: `fetchItems` → `importItems`, checkpoint `ERP_SYNC_LAST_ITEM`;
6. barcodes: `fetchItemBarcodes` → `importItemBarcodes`, checkpoint `ERP_SYNC_LAST_ITEM_BARCODE`.

Each part is today's read through `ErpSynchronizationManager` (one communications log row, its operation as before:
`IMPORT_ITEM_CATEGORIES`, `IMPORT_ITEM_FAMILIES`, ..., `IMPORT_ITEM_BARCODES`; `pullItemCategories` and `pullItemSections`
write no row when the connector answers none without reading, so EMTOP's log is as before), today's import and, for the
four parts that had one, the same General Setup checkpoint (`ErpSyncCheckpointService.createFilterForCataloguePart`,
`updateLastSyncForCataloguePart`); catégories and sections are read whole. **The first failing part stops the run**: the
parts before it are saved, the next run goes on, the job's status `ERROR: Items failed (the parts before it are saved;
barcodes wait for the next run): ...` (a warning, e.g. no `DEFAULT_LOCATION`, stays `WARNING: ...`). The jobs page shows
the items' checkpoint for the job; its statistics gather the six operations. It runs wherever the four ran: a store on the
ERP and a head office whose ERP owns everything; a head office whose catalogue only comes from the ERP refuses it
(`HeadOfficeErpJobs`, it has `SYNC_CATALOGUE`). EMTOP (2026-10-31, accepted): one schedule, the cron of `IMPORT_ITEMS`
(every 12 h), and a failing part (e.g. `FamilyList`) now stops the items until the next run.

**Conversion of the rows** (`core/erp/service/ErpSyncJobTypesUpgrade`): at startup, in JDBC, first thing in
`ZZDataInitializer.init()` (before any JPA read of `erp_sync_job`: a row whose type the code no longer has would fail
every read), when one of the four rows is there: `IMPORT_CATALOGUE` updated or inserted with the cron of `IMPORT_ITEMS`
(`0 0 * * * *` without that row) and its next run, enabled when any of the four was, then the four rows deleted, one
transaction. The same batch is in `db/3.1.1/update.sql` (the order of script and deploy does not matter); nothing to do
once the four rows are gone. A new installation is seeded with `IMPORT_CATALOGUE` disabled (`0 0 * * * *`). Tests:
`ImportCatalogueJobTest` (order, stop at the first failure, warnings), `ErpSyncJobTypesUpgradeTest` (the batch, the
script holding it, the startup run).

**Removed job type `EXPORT_CUSTOMERS`** (3.1.1): never implemented (the runner only logged "not yet implemented", every
connector's `pushCustomer` answered a failure, nothing called `ErpSynchronizationManager.pushCustomer`; with an ERP the
customers come from it, the application never creates one). Gone: the job type, its operation `EXPORT_CUSTOMER`,
`ErpConnector.pushCustomer` and its five implementations, `ErpSynchronizationManager.pushCustomer`, the disabled template
row seeded by `ZZDataInitializer`, its place in `HeadOfficeErpJobs.NOT_ON_HEAD_OFFICE`. `IMPORT_CUSTOMERS` is unchanged.
The rows: the job and operation columns are read with `EnumType.STRING`, so a value the code no longer has throws
(`No enum constant`) on every read of the table (the scheduler, the jobs page, the communications log). The same
startup step deletes them (`ErpSyncJobTypesUpgrade.DEAD_ROWS`: `erp_sync_job` rows of type `EXPORT_CUSTOMERS`,
`erp_communication` rows of operation `EXPORT_CUSTOMER`, each table guarded, only when there are some), and
`db/3.1.2/update.sql` holds the same statements (3.1.2: 3.1.1 is released without them). Seen 2026-10-31: one disabled row on `pos_db_prod` (EMTOP) and on
`pos_headoffice`, no log row anywhere.

**ErpSyncOperation Enum:**
- `IMPORT_ITEM_FAMILIES`, `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`, `IMPORT_ITEM_BARCODES`, `IMPORT_LOCATIONS`, `IMPORT_CUSTOMERS` - Import operations
- `EXPORT_TICKET` - Export ticket header to ERP (main operation)
- `EXPORT_TICKET_LINE` - Export individual ticket line to ERP (automatically triggered by EXPORT_TICKET)
- `UPDATE_TICKET` - Update ticket status (POS_Order) in ERP (automatically triggered by EXPORT_TICKET)
- `EXPORT_PAYMENT` - Placeholder for future payment export functionality

**Important Notes:**
- `EXPORT_TICKET_LINE`, `UPDATE_TICKET`, and `EXPORT_PAYMENT` are **NOT** in `ErpSyncJobType` enum
- These operations do **NOT** have scheduled jobs or job records
- They are **NOT** shown in the frontend ERP JOBS page
- They run automatically as part of the `EXPORT_TICKETS` job execution
- All operations are logged in `erp_communication` table with their specific operation type

**Operation Usage:**
- `ErpSynchronizationManager.pushTicketHeader()` uses `EXPORT_TICKET`
- `ErpSynchronizationManager.pushTicketLine()` uses `EXPORT_TICKET_LINE`
- `ErpSynchronizationManager.updateTicketStatus()` uses `UPDATE_TICKET`


## ERP Abstraction Layer

**Design Philosophy:**
- POS system is decoupled from specific ERP implementations (e.g., Dynamics NAV)
- Abstract `ErpConnector` interface (`com.digithink.zsretail.erp.api.ErpConnector`) defines ERP-agnostic methods
- Specific ERP implementations (e.g., `DynamicsNavConnector`) implement the interface
- `NoOpErpConnector` provides stub implementation when ERP is disabled
- **One connector** (code cleanup step 2): `NoOpErpConnector`, `DynamicsNavConnector` and `NavPosPagesConnector` are mutually
  exclusive by the connector in effect (`@ConditionalOnErpConnector`, used by these three classes only), so exactly one
  `ErpConnector` bean exists; no `@Primary`. Nothing injects a list of connectors or one by its class.

**Abstract DTOs:**
- `ErpTicketDTO` - Generic ticket DTO for ERP export
- `ErpTicketLineDTO` - Generic ticket line DTO for ERP export
- `ErpOperationResult` - Generic result object containing success status, external reference, messages, and payloads

**ERP-Specific DTOs:**
- `DynamicsNavSalesOrderHeaderDTO` - NAV-specific header DTO
- `DynamicsNavSalesOrderLineDTO` - NAV-specific line DTO
- Mappers (`DynamicsNavMapper`) convert abstract DTOs to ERP-specific DTOs

**Benefits:**
- Easy to switch between ERP systems
- New ERP implementations only need to implement `ErpConnector` interface
- Business logic in `TicketExportService` remains ERP-agnostic
- Partial synchronization and external reference handling logic preserved across all ERPs

### Abilities (code cleanup step 2)

The connector is chosen by `erp.connector`, the only line since code cleanup step 8 (`deployment-modes.md`). The application asks what
the connector in effect can do, never which one it is: `erp/api/ErpAbilities` gives, for each connector name, its
abilities (`erp/api/ErpAbility`), read from the environment, so a condition uses it before the beans exist. Since code
cleanup step 7 each connector declares its abilities in its descriptor (`ErpConnectorDescriptor`); the table is what
they declare today.

| Connector | `HEAD_OFFICE_STOCK_POINTS` | `SUPPLY_INVOICES` | `STORE_ORDERS` |
|---|---|---|---|
| `none` | — | — | — |
| `emtop` | — | — | — |
| `happyness` | yes | yes | — |
| `parafendri` (3.1.1: stores, and a head office whose catalogue comes from the ERP) | yes (head office catalogue) | — | — |
| `zen` (3.1.0, item variants step 8) | yes | yes (step 9) | yes (3.1.1) |

- `HEAD_OFFICE_STOCK_POINTS` (was `CATALOGUE_BY_STOCK_POINTS`): the head office keeps several points de stock in its own
  tables (`ho_stock_point`, `ho_stock_point_item`, the points page), filled by the single catalogue job `SYNC_CATALOGUE`. Not
  "reads items by location": every connector may do that. Its beans (the 7 of the engine in core, the 4 of the points
  in the head office) exist with the ability **and** on a head office whose catalogue only comes from the ERP
  (`@ConditionalOnInstallation(HEAD_OFFICE_ERP_CATALOGUE_ONLY)`, 3.1.1): ParaFendri is the first connector on stores and
  a head office, and a store keeps its job `IMPORT_CATALOGUE` (`ErpAbilityBeansTest.parafendriStoreAndHeadOffice`).
- `SUPPLY_INVOICES`: the ERP's supply invoices are read, so a head office may set `headoffice.supply.source=ERP`.
- `STORE_ORDERS` (3.1.1): the stores' orders are posted as sales orders (`ErpConnector.postSalesOrder`, looked up first by
  their external document number) and the variants' stock for a store is read (`fetchVariantStock`, with its code and ERP customer number), so a head office
  may set `headoffice.storeorders.post=ERP`; the job `POST_STORE_ORDERS` posts them (`docs/modules/store-orders.md`).
  Every other connector keeps the two defaults: not supported.

`ErpAbilities.connectorInEffect(env)` (none, emtop, happyness, parafendri, zen) is the value of `erp.connector`;
`hasConnector(env)` is true for any connector but none. A new ability is added there when the code needs it, never a test on
a connector name.

**Rule: the application never names a connector.** Outside the connector packages (`connector/<name>`, every project of
`connectors/`), no class imports a connector package or contains a connector's key prefix (`erp.emtop`,
`erp.happyness` and the old `erp.dynamicsnav`, `erp.navpospages`: what the descriptors declare), not even in a comment; since code cleanup step 7 not even the
check of `erp.connector` and (step 8) the refusal of the old lines, which read the descriptors. Since code cleanup step 6.3 Maven makes it true as well: the application takes
the connector projects at `runtime` scope, so a main class that names one does not compile, and a connector depends on
erp-api only. It uses `@ConditionalOnErpAbility(...)` or `ErpAbilities.has(env, ...)`; what a
connector's settings give the application goes through `ErpConnector` (the ids of deleted prices, the seller named on the
stores' invoices: `supplyInvoiceSellerName`, the connector's `invoices.seller-name`) or a port in `erp/api` that the
application implements (`StockPoints`, the head office's points de stock). **Guard test**: `erp/ConnectorNamesGuardTest`
scans every project's `src/main/java` and fails naming each offending line. Since code cleanup step 8 it also checks what a user can
read: no string literal outside the connectors (a message, a setting's description) names a connector, its old name or
NAV; it says "the ERP", or words it by the ability ("a connector that keeps points de stock"). No exception: the old name of the
catalogue state table is declared by its connector (`ErpConnectorDescriptor.oldCatalogueStateTable()`).

**Deletion sync** (`ErpDeletionSyncService`, job of the ERP Log): the `erp_external_id` of a deleted sales price or discount
is built by the connector in effect (`ErpConnector.deletedSalesPriceExternalId` / `deletedSalesDiscountExternalId`), the
way it built it at the import: `DynamicsNavConnector` through `DynamicsNavMapper`, the other connectors answer null (they
have no deletion log). The ids are pinned character for character by `ErpDeletionSyncServiceTest`.

### Catalogue engine (code cleanup step 3)

A connector is a thin part: which pages to call, the field names, the rows to `Erp*DTO`, its settings. The catalogue
logic is written once, in `core/erp/engine`, and exists with a connector that has the ability `HEAD_OFFICE_STOCK_POINTS`.

**The engine** (`core/erp/engine`):
- `CatalogueEngine` (was `NavPosPagesSync`): families, sub-families, items per point de stock, barcodes. It compares
  with the head office tables, waits for the families, applies in packets (each its own transaction, through
  `CatalogueImport`), keeps the barcode cursor by position and writes the summary of each run (`RunSummary` of
  `erp/api`, the format of the communications log). One items run and one barcode run at a time.
- `CatalogueJob` (was `NavPosPagesCatalogueJob`): the single job `SYNC_CATALOGUE` (`ErpCatalogueSync`).
- What the engine reads and writes at the head office: `HeadOfficeCatalogue` (`JpaHeadOfficeCatalogue`), `CatalogueState`
  (`JdbcCatalogueState`: the table `erp_catalogue_state`, `navpospages_state` up to 2.2.2 and renamed at the first
  start, see `head-office.md`, "State table"; the keys `items.run`, `items.pending`, `barcodes.cursor`, `needs-barcodes:`
  unchanged), `CatalogueImport` (`BootstrapCatalogueImport`, through `ErpItemBootstrapService`),
  `StockPoints` (`headoffice.service.HoStockPointRows`).

**What a connector gives** (`erp/api`):
- `ErpCatalogueSource`: `categories()` → `ErpCategories` (families and sub-families from one read),
  `items(locationCode)`, `barcodesAfter(position)`, `barcodesOfItems(itemCodes)`, `pageLabel(part)`, `pageUrl(part)`.
  Each read is an `ErpRead<T>`: the rows kept, how many were read, the rows left out by reason (the connector's own
  reasons, shown in the summary), and for barcodes the position of every row read.
- `ErpBarcodeRow`: an `ErpItemBarcodeDTO` (unchanged) with its position, a number the ERP gives each barcode row and
  that only grows (navpospages: `Entry_No`). The engine keeps the highest position of the same barcode and moves its
  cursor over every row read, left-out ones included.
- `ErpSyncWarningException` (`erp/api`): a run that waited or was refused.
- Since code cleanup step 5 also in `erp/api`: `CatalogueSettings` (packet size, dry run, default VAT, given by the
  connector from its own keys), `CatalogueRun` (the result of one run) and `RunSummary` (the summary format, used by the
  engine and by a connector's own runs); `ErpInvoiceStart` (the General Setup `ERP_INVOICES_READ_AFTER`, answered by
  the engine's `HeadOfficeCatalogue`), `GeneralSetupValueCheck` (a check of a General Setup value on save),
  `ErpQuantities` (the decimals allowed in a quantity, the same as `utils.Quantities`), and `ErpConnectors`, which also
  answers whether the installation is a head office whose catalogue only comes from the ERP (asked of the application
  through `ErpConnectorResolver`, as the startup resolves the owners); `ErpStoreLocation` (the store's default location
  and its responsibility centre, answered by `ErpStoreLocationService`).

**navpospages today**: `NavPosPagesCatalogueSource` (the pages through `NavPosPagesMapper`),
`NavPosPagesCatalogueSettings` (`erp.happyness.packet-size`, `dry-run`, `default-vat`, unchanged),
`NavPosPagesConnector` (read-only answers; its four catalogue fetches answer none since step 5: the catalogue is
read by the single job, the four import jobs are refused on its head office), and its own thin parts:
`NavPosPagesInvoices` (the invoices page, FVV numbers by year, the seller name).

**How to add a connector** (code cleanup step 7: a connector declares itself):
1. A project `connectors/<name>/` (copy `connectors/happyness/pom.xml`, artifact `zsretail-connector-<name>`): of ours
   it depends on erp-api only. Its classes under `com.digithink.zsretail.connector.<name>`: its settings
   (`erp.<name>.*`), its `ErpConnector` (`@ConditionalOnErpConnector("<name>")`) and, for a head office's catalogue,
   an `ErpCatalogueSource` and a `CatalogueSettings`.
2. Its descriptor, a class implementing `ErpConnectorDescriptor` (its name, its abilities; no old or dead keys for a
   new connector), named in its `src/main/resources/META-INF/spring.factories`:
   `com.digithink.zsretail.erp.api.ErpConnectorDescriptor=com.digithink.zsretail.connector.<name>.<Name>ConnectorDescriptor`.
3. Two lines in `connectors/pom.xml`: `<module><name></module>` and its `<dependency>` (one line).
4. Its HTTP client from `erp.api.ErpHttp` (since 4.0.0, Boot 4 migration step 1.7): `ErpHttp.requestFactory(login,
   connectSeconds, readSeconds)`, with `ErpHttp.ntlm(user, password, domain)` (and `.onlyFor(host, port)` to give the login
   to one host only) or `null` for none. HttpClient 5 answers no NTLM challenge by default; `ErpHttp` registers NTLM and
   names it in the preferred schemes, and puts the timeouts on the connections (`ErpHttpTest`). Do not build a client by
   hand.

Nothing else changes: `erp.connector=<name>` is accepted, its abilities answer `ErpAbilities`, its beans are
chosen, the WARs carry it (they depend on `zsretail-connectors`), and the guards find it by its folder and its
descriptor (`ThirdConnectorTest` proves it with a connector that exists only in the tests). The two connectors of
before had other keys until code cleanup step 8: their descriptors give the old prefix (`erp.dynamicsnav`,
`erp.navpospages`), every key under it is refused naming its new name, and EMTOP's gives the NAV address of the startup
summary. A new connector has no old prefix.

**Item variants (ZEN, 3.1.0, step 3; `docs/variants-zen-analysis-2026-10-14.md`)**: an ERP may hand one item per
variant (code = ERP item number + `~` + variant code). `ErpItemDTO` carries 14 variant fields (`navItemNo`,
`variantCode`, `variantDescription`, `sizeCode`, `sizeDescription`, `sizeSortOrder`, `colorCode`, `colorDescription`,
`choiceCode`, `choiceDescription`, `seasonCode`, `divisionCode`, `categoryCode`, `sectionCode`) and `brand`.
`ErpItemBootstrapService` always writes the 14 fields (trimmed, blank as null; null for EMTOP and Happyness, which give
none) and writes `brand` only when the ERP gives one, so a brand typed by hand is never erased. The items run compares
the 14 fields (`erp/service/ItemVariant`) and the brand when given, so a size or colour change at the ERP is a change of
the item; the rows of the points de stock do not compare them. The engine's own copies of a head office item (an item
gone from every point, a point that answered no row) keep its variant fields and brand. The item name stays the ERP's
item description. Tests: `core/erp/engine/CatalogueEngineVariantsTest` (the real import over in-memory tables),
`JpaHeadOfficeCatalogueTest` (the items query on H2). The read-only page "NAV items" (step 11a, one line per NAV item
number): `docs/modules/head-office.md`, "NAV items".

**Catégories and sections (step 4)**: `ErpCategories` gives, from the same categories page, the catégories (above the
families, table `item_category`) and the sections (below the sub-families, table `item_section`); an ERP that has none
uses the 2-argument constructor (empty reads). The job `SYNC_CATALOGUE` runs, in order: categories, families,
sub-families, sections, items, barcodes. The two new parts are handed like the families (one packet per run, compared
by code on name, description, ERP id, parent code, and display order and active when the ERP gives them); they never
wait and nothing waits for them. An empty read is 0 read, 0 changed, nothing imported, no warning. A family keeps its
catégorie by code (`item_family.category_code`, written and compared only when the ERP gives one); a section keeps its
sub-family by code; an item keeps its catégorie and section by code and is never held back for them. Not sent to the
stores yet. Tests: `core/erp/engine/CatalogueCategoriesTest`; the families and sub-families of an ERP without
catégories (Happyness) are summarised as before (`CatalogueEngineTest.catalogueJobOneRun`, network-tests). Each part
reads the categories page: two more reads of that small page per run.

**Clearance of a point de stock row (step 5)**: `ErpItemDTO.clearance` / `clearancePrice` are the values of the point
read; `StockPoints.Row` carries them (`ho_stock_point_item.clearance`, `clearance_price`, written and read by
`HoStockPointRows`; a null column, a row written before 3.1.0, is false). The row compare adds the flag (null = false on
both sides) and the clearance price at 3 decimals (null = no price): the rows of an ERP without clearance (Happyness) do
not change. The item does not compare them. Tests: `CatalogueClearanceTest` (core), `HoStockPointRowsTest` (H2),
`StockPointFilterQueryTest` (3.1.1, the Item cards point price; `HoStockPointItemsServiceTest` retired with its page).

**Page read retry (2026-10-17)**: every page the catalogue engine reads (categories, the items of a point, whole or
since its checkpoint, the barcodes after the cursor or of some items) goes through `RetryingCatalogueSource`, for every
connector: a read that fails on an I/O error or a timeout (an `IOException` among its causes: VPN drop, connection
refused, connect or read timeout) is read again once 5 s later with one WARN line (`ERP catalogue: reading the page
barCodes after 1792170044207 failed (ConnectTimeoutException: ...), retry in 5 s (1/1)`); a second failure fails the
step as before. The same page is asked again: no cursor or checkpoint moves before a read succeeds, and a read that
succeeds on the retry records no failed read. Another failure (an answer refused) is not retried. The connectors
keep the I/O error as the cause of their read exception: ZEN (`ZenReadException`) and Happyness
(`NavPosPagesReadException`, `NavPosPagesReadRetryTest` in network-tests: the real client, a connect timeout read
again). EMTOP has no catalogue source (no ability): its catalogue does not go through the engine, so no retry there.
Tests: `CatalogueReadRetryTest`, `NavPosPagesReadRetryTest`.

**Item images (3.1.1)**: the job's last part, `images`, after the barcodes, whenever the items part ran
(`core/erp/engine/ItemImagesEngine`). A connector that reads images says so (`ErpCatalogueSource.readsItemImages()`,
false by default: the part is skipped, `"skipped": "not configured"`, no warning; EMTOP and Happyness keep the default)
and answers `itemImages(navItemNos)` (at most `ITEM_IMAGES_PER_CALL` = 100 per call), each an `ErpItemImagesDTO
{navItemNo, urls}` (the addresses in order, the first the main one; empty: the item has none).
- Which items: every run asks the NAV item numbers of the items the items part handed in that run
  (`CatalogueEngine.changedNavItemNos()`; none changed: no call). A **full run** asks every NAV item number of the head
  office instead (`HeadOfficeImages.navItemNos()`), at most once per 24 h (`FULL_EVERY`, no setting): the first run (no
  `images.since` in `erp_catalogue_state`), then the first run 24 h or more after the last full one that succeeded; it
  catches a picture replaced on ZEN's side for an item NAV did not change. `images.since` = the start of the last full
  run, written once every call of it succeeded, never in a dry run; a failed full run is full again at the next run. No
  "changed since" read: ZEN's `updatedAt` filter answers the whole catalogue whatever the window (2026-10-28). An I/O
  failure is read again once (`RetryingCatalogueSource`). To make the next run full, delete the `images.since` row.
- Applied per call (`JpaHeadOfficeImages`, one transaction): every variant of the NAV item (`item.nav_item_no`) gets the
  addresses as `item_image` rows, source URL, the first `is_main`, `sort_order` 0, 1...; a variant already right is left
  as it is; an empty list removes its URL rows; its FILE rows are never touched; an item not answered keeps its rows; an
  address longer than `item_image.path` (1,000) is left out (`urlsTooLong`); a NAV item number the head office has not
  is counted (`unknown`). The variants written are recorded for the stores (`CatalogueHeadOfficeHooks.afterImport`,
  ITEM): their copies go down again with their images (`head-office.md`, "Item images").
- Summary of the part (in the job's one communications log row): `source`, `mode` (full / changes), `lastFull`,
  `asked`, `calls`, `answered`, `itemsChanged`, `itemsEqual`, `itemsCleared`, `notAnswered`, `unknown`, `durationMs`. **A failure never fails the job**: the part's `error`, the run a WARNING "Images failed (the catalogue
  is saved, the next run asks again): ...", the parts before it saved, `images.since` unmoved.
- Tests: `ItemImagesEngineTest`, `JpaHeadOfficeImagesTest` (H2), `CatalogueJobImagesTest` (core).

**Items step speed (2026-10-17)**: the engine hands the import only the rows it found new, changed or gone (every
field compared: the item fields, `ItemVariant`, the brand when given); an equal row is read, never written. The import
(`ErpItemBootstrapService.importItems`) reads a packet's items in one query by ERP id (`findByErpExternalIdIn`) and one
by code, and the families and sub-families once per packet, instead of up to five SELECTs per row (each one scanned
`item`, which has no index on `erp_external_id`, and auto-flushed the packet); its writes and those of
`HoStockPointRows.write` go by JDBC batches of 100 (`Session.setJdbcBatchSize`, that transaction only; new rows still
one by one, IDENTITY). Indexes (2026-10-17, db/3.1.0/update.sql, re-runnable; also `` on the entities):
`ix_item_erp_external_id` (`item.erp_external_id`) and `ix_item_barcode_erp_external_id`
(`item_barcode.erp_external_id`), the ERP id lookups of the item and barcode imports; **to run by hand on existing
databases** (the two `CREATE INDEX` statements after `ix_item_barcode` in the script). Log: `ERP catalogue items: N rows read, E equal, ... written in T ms` per step, `ERP items
import: ...` per packet. The run of 17/10 (846 s, 21,086 changed) was a real change: the first run after the
variant description was built by the connector, before the 3.1.0 UPDATE. Test: `ErpItemImportLookupsTest`.

**Barcodes step speed (3.1.1, 2026-11-01)**: `importItemBarcodes` ran three lookups per row (item by ERP id then by
code, barcode by ERP id then by value), each auto-flushing a session that grew with the packet: quadratic, 13,165
ParaFendri barcodes ran more than 27 minutes in one transaction. It now takes the packet by windows of 1,000 rows
(`IN_CHUNK`): per window, four reads (`ItemRepository.findByErpExternalIdIn` / `findByItemCodeIn`,
`ItemBarcodeRepository.findByErpExternalIdIn` / `findByBarcodeIn`, both with their item joined), the rows in memory
with the same rules and messages (one WARNING row per barcode left out; ERP id before value; the primary flag only when
given; a later row of the same barcode finds the one just saved, and a barcode whose value changed is no longer found
by its old one), the writes by JDBC batches of 100, then a flush and a clear, so each window's session holds only its
own rows (a clear every 100 rows would make each later update of an already-read barcode a SELECT by `merge`). The
lookup keys compare as SQL Server's collation does (case and trailing spaces ignored), so a barcode the per-row query
found is found here too. Log: `ERP item barcodes import: N rows handed, W written (C new, U updated) in T ms`. Test:
`ErpBarcodeImportLookupsTest` (2,000 rows: 8 reads).

**Clearance price at the till (2026-10-17, reverses step 11b's "no price logic")**: one rule,
`headoffice/service/StockPointPrice.sellingPrice` / `clearanceApplies`: a row with clearance true and a clearance price
above 0 sells at its clearance price, any other row (clearance false or null, a price null or 0) at its own price. Both
are before VAT, the item's VAT applies. The head office applies it where it decides a store's price,
`HoCatalogueService.pointCopy`: the item copy's `unitPrice` is the clearance price, with `clearance` true and
`publicPrice` (the row's price) for the till's display; null both otherwise. The store sells what it receives (scan,
tiles, cart, ticket, price lookup: all from `item.unitPrice`, no store rule); returns and reprints use the prices of
the sale line. The store keeps the two values in `item.clearance` and `item.public_price` (written by the copies down
only; a store's own price still wins, and keeps the till's struck price and badge, test log I-33). A change of clearance or clearance price is a row change (`differs`), and
`HoStockPointRows.write` hands every written row to `stockPointRowsChanged`: the stores of the point receive the item
again. The head office pages read the same rule: `StockPointItemDTO` and `NavItemVariantsDTO.PointPrice` give
`clearanceApplies` and `sellingPrice`. EMTOP and Happyness rows have no clearance: unchanged. Items already at a store
are sent again only on a row change or a change of the store's point (none at the upgrade). Tests:
`StockPointPriceTest`, `HoStockPointCatalogueTest` (clearance), `HoStockPointRowsTest`
(`clearanceChangeRecordedForTheStores`), `StockPointFilterQueryTest.pointPrice` (3.1.1), store `ClearanceAtTheTillTest`,
network-tests `ClearanceRoundTripTest`.

**Items run of changes only (step 6)**: a connector whose `ErpCatalogueSource.readsChangesOnly()` is true (ZEN) is read
per point de stock from its checkpoint: `items(location, since)`, the connector filtering `lastModifiedAt ge since`;
since is null on the first run (a whole read). Happyness and EMTOP (false) run the whole read exactly as before
(`CatalogueEngine.readAndApplyItems` branches to `readAndApplyChanges` only for such a connector).
- Checkpoint: `erp_catalogue_state`, key `items.since:<point id>`, ISO time with offset = the highest `lastModifiedAt`
  of the rows the point answered (never earlier than the one it had), written once all the point's rows are applied;
  kept when the point failed, answered no row or no dated row, and in a dry run. `CatalogueEngine.checkpoint(pointId)`,
  `resetCheckpoint(pointId)` (the next run of that point is a whole read; no screen yet).
- Nothing is deactivated by absence. A row answered inactive makes its row inactive; an item is active when one of its
  rows in the active points is (answered, else stored): its last active row answered inactive makes it inactive, an
  answered active row active again. The rows at the checkpoint itself come back (ge) and compare unchanged.
- The codes answered are handled in packets of packet-size: only the stored rows of a packet's codes are loaded
  (`StockPoints.rows(pointId, codes)`, an `IN` query in `HoStockPointRows`), then the packet's items, then its rows of
  each point. A failed packet of items ends the run; a point whose rows fail stops (checkpoint kept), the others go on,
  the run ends in error. A point answering no row is a success.
- Summary: `changesOnly`, and per point `since`, `read`, `maxSeen`, `new`, `changed`, `deactivated`, `written`,
  `checkpoint`, `checkpointWritten` (`failed` when it failed). Tests: `CatalogueChangesOnlyTest` (core),
  `HoStockPointRowsTest.readsTheRowsOfSomeCodes` (H2).

Tests: `core/erp/engine/CatalogueEngineTest` (the engine and the job, fed page rows of navpospages through its adapter,
`NavPosPagesFixture`), `NavPosPagesInvoicesTest`, `NavPosPagesCatalogueSourceTest`.

**Guard of the connector packages** (`erp/ConnectorDependenciesGuardTest`): a class of `connector/happyness` or
`connector/emtop` may use erp-api (as `ProjectSortingGuardTest` sorts it: `erp/api` with `erp/api/dto`, the project
erp-api since code cleanup step 6.2; not the two admin views of `erp/dto`, in core since step 6.4 as `core/erp/dto`), its own package, the JDK and
libraries. What would break the rule goes on an allow-list
inside the test, each line with its reason (an entry nothing uses any more fails too); empty since code cleanup step
5.8e (navpospages untied at 5.8c, dynamicsnav at 5.8d).

## ZEN's connector (`erp.zen`, item variants, 3.1.0, step 8)

Project `connectors/zen` (`com.digithink.zsretail.connector.zen`), built like `connectors/happyness`: its descriptor
(`ZenConnectorDescriptor`, abilities `HEAD_OFFICE_STOCK_POINTS` and, step 9, `SUPPLY_INVOICES`), its settings
(`ZenProperties`, checked by `ZenStartupCheck`; keys in `docs/deployment-modes.md`), an NTLM client refusing anything but
GET (`ZenConfig`, `ZenRestClient`, following `@odata.nextLink`), the mapper (`ZenMapper`), the source the engine reads
(`ZenCatalogueSource`, `readsChangesOnly()` true), the engine's settings and a read-only `ErpConnector` (`ZenConnector`).
- Pages of ZEN's custom API (`<base-url>/api/sat/zsretail/v1.0/companies(<id>)/`, the id `erp.zen.company-id` or looked up once by the company's name `erp.zen.company`, step 10): `stockPoints`, read per location
  with `$filter=locationCode eq '<code>'` and, from a checkpoint, `and lastModifiedDateTime ge <time in UTC>`, no
  `$select` (the fields not used are ignored);
  `itemCategories`; the barcodes, ZEN's page `barCodes` (step 12a, by default; a page may be given as a whole address).
- Items: one per row, code `itemNo~variantCode` (`itemNo` alone without a variant); name and description the
  `itemDescription`; the 14 variant fields (blank as null), except the variant description, which the mapper builds:
  `sizeDescription`, `colorDescription`, `choiceDescription`, trimmed, the blank ones left out, joined with " / "
  ("Taille Standard / VERT PISTACHE / 1er Choix"; "" when none, stored null as every blank variant field). NAV's
  `variantDescription` is not read: on ZEN's platform it is always the item description. A change of one of the three
  descriptions is a change of the item (the engine compares `variantDescription` with the other variant fields).
  `db/3.1.0/update.sql` rebuilds `item.variant_description` the same way for the items with a `nav_item_no`; brand `brandCode`; family and sub-family `familyCode`,
  `subFamilyCode`; the price `unitPrice` (before VAT), 0 or none inactive (noted "null or zero unitPrice");
  `itemBlocked`, `variantBlocked` or `stockPointBlocked` true inactive, each noted under its name (absent: not
  blocked; the engine keeps an item active while one of its rows is); VAT `vatPercent` (rounded to a whole number,
  noted when it was not one), absent `default-vat`; `lastModifiedAt` from `lastModifiedDateTime` (NAV's 0001-01-01:
  none); the location's `clearance` (absent: false) and clearance price `clearancePriceExclVAT` (before VAT). The rows of
  another location, a blank `itemNo`, a `~` in `itemNo` or `variantCode`, the same item and variant twice are left out.
  Not used: `barCode` (the barcodes page is read), `stockPointDescription`, `itemCategoryCode`, `baseUnitOfMeasure`,
  `brandDescription`, `clothingStyleCode`, `cutCode`, `noOfPieces`.
- Prices with VAT (the page of 2026-10-24): `publicPrice` and `clearancePrice` are `unitPrice` and
  `clearancePriceExclVAT` × (1 + `vatPercent` / 100) at 3 decimals (all 100 rows of ZEN's sample). They are never read
  as a price (the till adds the VAT to the price it receives); a check only: a pair that does not agree is noted
  ("publicPrice inconsistent with unitPrice × VAT", "clearancePrice inconsistent with clearancePriceExclVAT × VAT"), the
  data untouched, both above 0 only. The page before had only the prices with VAT, read as before VAT (a till sold
  19.50 × 1.19 = 23.205): a row with `publicPrice` but no `unitPrice` fails the read of its location
  (`ZenCatalogueSource.NO_UNIT_PRICE`, "zen: stockPoints has no unitPrice: update the page"; the point's read failed,
  nothing applied, its checkpoint kept). ZEN's prices before VAT have 5 decimals (16.38655): `item.unit_price` and
  `ho_stock_point_item.unit_price` are float, `ho_stock_point_item.clearance_price` is `numeric(18,5)` since then (at 3
  decimals a price like 16.38655 would sell at 19.501; `db/3.1.0/update.sql` widens a column made with 3), the engine compares
  a clearance price at 5 decimals (`CatalogueEngine.samePrice`). **Deploying it on a head office that already read
  ZEN's rows**: the read is incremental, so the rows NAV does not change again keep the price with VAT as their price.
  Reset every point's checkpoint (the `items.since:<point id>` keys of `erp_catalogue_state`): the next run is a whole
  read, the prices change, the stores receive their items again.
- The tree by `indentation`: 0 catégories, 1 families (`categoryExternalId` = the parent when it is a catégorie), 2
  sub-families (left out when the parent is not a family of the read), 3 sections (left out when the parent is not a
  sub-family of the read); display order `presentationOrder`.
- Barcodes (step 12a, page `barCodes`: `id`, `itemNo`, `variantCode`, `barCode`, `lastModifiedDateTime`, with
  `$select`): the item is `itemNo~variantCode`; a blank `barCode` or `itemNo`, a date not readable, left out. Every row
  is kept whatever its `unitOfMeasureCode` (a question for ZEN). The engine's position is `lastModifiedDateTime` in
  epoch milliseconds (rounded down; NAV's empty date, a date before 1970 or not readable: 1): cursor 0 reads with no
  filter, after it `$filter=lastModifiedDateTime gt <cursor in UTC>`, `$orderby=lastModifiedDateTime`, `$top`
  `barcode-page-size`. A full page is completed with every row of its last time (`eq P`; position 1: `lt 1970`; next
  links followed, the same `id` once), so a page never ends in the middle of a time: the engine's guard "rows at or
  below the cursor" never fires, an empty answer is caught up, a time bigger than a page is read whole. Never a time
  computed from the cursor (+ 1 ms, a half-open range): NAV keeps these dates in SQL `datetime` (ticks of 1/300 s,
  `.000 .003 .007`) and SQL rounds a time it is given to a tick (`ge .208` is `ge .207`); `gt` and `eq` on a time NAV
  gave land on its own tick (step 12a follow-up). A barcode
  changed in NAV is read again (its new date). Those of new items are read by `(itemNo eq '..' and variantCode eq '..')`,
  the rows of other variants left out.
- Supply invoices (step 9): on ZEN's second custom API, `api/sat/franchise/v1.0`, page `salesInvoices` under the same
  `companies(<id>)` (NAV's OData V4 page `SalesInvoice` until 2026-10-25), the same client; `ZenInvoices`, a copy of Happyness's invoice read (by year
  prefix after the head office's highest number), only the invoices of the stores' ERP customer numbers
  (`ErpInvoiceCustomers`, erp-api, given by the head office's `HoErpInvoiceCustomers`). Details in `head-office.md`,
  "Invoices from the ERP, ZEN".
- Item images (3.1.1): ZEN's **products API**, outside NAV (`ZenProductsClient`): `erp.zen.products.url` (e.g.
  `https://api-zentral.zen.com.tn/api/products/advanced-search-zs`) and `erp.zen.products.api-key` (header
  `X-Api-Key`, a secret: the machine file only), both or neither (`ZenStartupCheck`; neither: no images). A POST that
  reads: `{"skus": [<NAV item numbers>]}` alone, which filters exactly; **never `updatedAt`**: on ZEN's side any window
  answers the whole catalogue (10,893 products, 3.3 MB, checked 2026-10-28) and overrides `skus`. The answer
  `{"data": [{sku, title, onSell, onSellB2b, dateActiveB2C, dateActiveB2B, frontImg, detailsImg}]}`, flat, one row
  per item (no image per variant or colour). Each product: `sku` (= `nav_item_no`), `frontImg` (the main image) then
  `detailsImg`, blank ones left out (none: the item's URL images removed); the other fields are not used. The reader
  also takes a plain list or a list under `products`, `items`, `results` or `value`. Its own RestTemplate:
  **no NAV login** (the NTLM one answers any host that asks), the connector's two timeouts, a guard letting only a POST
  to that exact address leave (`ZenProductsClient.PostOnlyTo`); no proxy (the head office needs outbound HTTPS to the
  API). A refusal: "zen: reading the products API failed: HTTP <status> (<url>): <body>", never the key.
  Tests: `ZenProductsClientTest` (the sample `products.json` has the live answer's shape; the values of its first
  product are those of ZEN's email of 2026-10-27).
- Tests: `ZenMapperTest` (on the two samples of the API in `connectors/zen/src/test/resources/zen`),
  `ZenRestClientTest`, `ZenCatalogueSourceTest` (in the project); step 9: `ZenMapperInvoicesTest` (the sample
  `salesInvoices.json`), `ZenInvoiceClientTest`, `ZenInvoicesTest`; `ZenBarcodeCursorTest` (the engine's barcode cursor on
  a fake `barCodes` page), `ZenStartupCheckTest`, the pinned lists of
  `ErpConnectorTest`, `OutsideFileUpgradeTest`, `ThirdConnectorTest`, `ErpAbilitiesTest`, `ErpAbilityBeansTest`
  (network-tests). `ConnectorNamesGuardTest` takes a connector's name as a whole word (no letter or digit around it).

## ParaFendri's connector (`erp.parafendri`, 3.1.1, step 0)

ParaFendri: six points de vente of its own on Business Central 28 on-prem. Each runs a ZS Retail **store on the ERP as
EMTOP's** (`ownership.*=ERP`, `sales.upstream=ERP`, `erp.connector=parafendri`, `configs/customers/parafendri-store.properties`);
a head office may be added later by configuration only. Their API is the family of ZEN's: custom API pages under
`<base-url>/api/sat/zsretail/v1.0/companies(<company-id>)/` (`locations`, `itemCategories`, `stockPoints`, `barCodes`,
`stockLevels`, `customers`, `salesOrders`, `salesOrderLines`, `shipAndInvoice`), OData V4, NTLM, prices before VAT and
`vatPercent` on the `stockPoints` row. Keys: `docs/deployment-modes.md`, "The connector of ParaFendri's stores".

Project `connectors/parafendri` (`com.digithink.zsretail.connector.parafendri`), a copy of the existing code with no
dependency on another connector:
- `ParafendriConnectorDescriptor`: name `parafendri`, **no ability** (none needed by a store connector so far), the
  summary `BC <base-url>`.
- `config/ParafendriProperties` (`base-url`, `company-id`, `domain`, `username`, `password`, the two timeouts, EMTOP's
  30 s / 600 s); the API path `api/sat/zsretail/v1.0` and the page names are constants of the connector, not keys.
- `config/ParafendriStartupCheck`: the required keys, `base-url` well formed, `company-id` a GUID; on a store or a
  head office alike (unlike ZEN's, which refuses anything but a head office whose catalogue comes from the ERP). No call
  to Business Central at startup.
- `config/ParafendriConfig`: NTLM `RestTemplate`, GET only (`GetOnly`), the login given to the host (and port) of
  `base-url` only, not `AuthScope.ANY` as the other connectors: a next link or any other host gets no login.
- `client/ParafendriRestClient`: `readCompanies()` (`GET <base-url>/api/sat/zsretail/v1.0/companies`, next links
  followed) and `checkCompany()`, **the connection test**: the row of `company-id` (case ignored), else a
  `ParafendriReadException` naming it and the companies found. A failed read names the page and the HTTP status
  (`ParafendriReadException.getStatus()`). Step 1: `readLocations()` (`GET companies(<company-id>)/locations?$select=
  code,name,lastModifiedDateTime`, next links followed; `dto/ParafendriLocationRow`) and `checkLocation(code)` (true
  when the code, trimmed, case ignored, is a location of the company).
- `config/ParafendriConnectionCheck` (step 1): **the connection check after the start**, on `ApplicationReadyEvent`
  (the database ready, the installation summary logged), on its own daemon thread `parafendri-connection-check`, so it
  never delays the start nor a sale. `checkCompany()`, then `checkLocation(<General Setup DEFAULT_LOCATION>)`, read
  through `ErpStoreLocation` (erp-api, answered by core). One INFO line `ERP connector parafendri: Business Central
  reachable, company <name>, location <code> found.`, else one WARNING naming the problem: Business Central unreachable
  or an HTTP status (the page named), the company missing, `DEFAULT_LOCATION` empty (always on a store's first run,
  until it is set), the location not a location of the company, any other failure. It never throws: the store starts
  and sells without the ERP. Not at `ParafendriStartupCheck` (the settings only, before the start): the database and
  `DEFAULT_LOCATION` are not there yet, and a call there would hold the deploy up to the timeouts (30 s, 600 s) with
  Business Central down. Neither emtop nor ZEN calls their ERP at startup. On a head office (later) `DEFAULT_LOCATION`
  is empty: the check warns until a head office has its own check.
- `connector/ParafendriConnector`: the `ErpConnector` of EMTOP's shape (a store connector, not a catalogue source).
  Step 2, the tree for the job `IMPORT_CATALOGUE`: `fetchItemCategories`, `fetchItemFamilies`, `fetchItemSubFamilies`,
  `fetchItemSections` each read the page `itemCategories` (`ParafendriRestClient.readItemCategories()`,
  `companies(<id>)/itemCategories`, next links followed, no `$select`; 121 rows at ParaFendri: no cache, one GET per
  fetch) and keep their level through `mapper/ParafendriMapper` (`ErpRead` per level, as ZEN's mapper of the same API):
  `indentation` 0 catégorie, 1 famille (`categoryExternalId` = its parent when the parent is a catégorie of the read;
  else kept without one and noted "famille whose parent is not a catégorie"), 2 sous-famille (`familyExternalId`; left
  out "parent not a famille of the read"), 3 section (`subFamilyExternalId`; left out "parent not a sous-famille of the
  read"); code and external id `code`, name and description `description`, display order `presentationOrder`, active. A
  blank code is left out at its level, a row without a level 0 to 3 with the sections ("indentation 4 (deeper than a
  section)", "no indentation"); a level's read counts its own rows only. Each read goes to the communications log as
  EMTOP's (`PullOperationResult`: the address, then `{read, kept, leftOut, notes, rows}`; the address alone when the read
  fails), with one WARN line when a row was left out. No ability on the descriptor: `IMPORT_CATALOGUE` asks none (an
  ability like `HEAD_OFFICE_STOCK_POINTS` would bring the head office's engine). Live, 2026-10-31: 121 rows, 2
  catégories, 16 familles, 103 sous-familles, no section, nothing left out. The other fetches (but the locations and
  the customer, below) answer an empty list, the
  pushes and updates a failure: `erp.sync.enabled=false` in the store's file until the items and barcodes are written.
- Step 3, the items (`fetchItems`, the job's items part): the page `stockPoints` of the store's point de vente (General
  Setup `DEFAULT_LOCATION` through `ErpStoreLocation`; none: the warning "DEFAULT_LOCATION is not configured", as EMTOP's),
  `$filter=locationCode eq '<code>'` and, from the job's checkpoint `ERP_SYNC_LAST_ITEM` (`filter.updatedAfter`: the
  newest `lastModifiedDateTime` of the items it got, as EMTOP's `Modified_At`), `and lastModifiedDateTime ge <time in
  UTC>`: `ge` as ZEN's, so that a row written in the same tick of the SQL `datetime` (1/300 s) as the newest row read is
  never missed (the rows of that time are read again, the import is the same). Next links followed, no `$select`.
  `ParafendriMapper.items`: code and external id `itemNo`; name and description `stockPointDescription`, else
  `itemDescription`; unit `baseUnitOfMeasure` (blank: none; `ErpItemDTO.unitOfMeasure`, 3.1.1, written by the import only
  when the ERP gives one); **the price `unitPrice` as received, before VAT, never rounded** (18.90756 × 1.19 = 22.4999964,
  shown 22.500 by the till, which adds the VAT; 18.908 would give 22.501; ZEN's lesson); the VAT `vatPercent` (not a
  whole number: rounded, noted); `lastModifiedAt` `lastModifiedDateTime` (0001-01-01: none, noted). Inactive, kept and
  noted: `itemBlocked`, `itemSalesBlocked`, `stockPointBlocked`, a null or zero `unitPrice`. The four links
  (`categoryCode`, `familyCode`, `subFamilyCode`, `sectionCode`): blank none; a code that is not a row the tree keeps at
  its level (the tree is read again first, 121 rows) none and noted, the item kept. Left out with their reason: a blank
  `itemNo`, a row of another location, no `vatPercent`, the same `itemNo` twice. Not read: `itemCategoryCode` (the
  sous-famille again), `customerNo` (step 6). The communications log gets the address with its filter, the location,
  the checkpoint, the counts and the reasons, not the rows (13,165 on a first read). Known limit, as EMTOP's: with the
  delta read, an item whose row is deleted in Business Central is not made inactive (a blocked one is). Live,
  2026-10-31, PV-GREMDA: 13,165 rows in one page, 4.4 s, all kept, nothing noted.
- Step 4, the barcodes (`fetchItemBarcodes`, the job's barcodes part): the page `barCodes`, **company-wide** (no
  location: one barcode per item of the company), every row on a first run (no checkpoint), else from the job's
  checkpoint `ERP_SYNC_LAST_ITEM_BARCODE` (the newest `lastModifiedDateTime` of the barcodes it got) with
  `lastModifiedDateTime ge <time in UTC>`, **plus every barcode of the items the items part of the same run got from its
  own checkpoint** (`itemNo eq '..' or ..`, 20 items per GET): the importer leaves out a barcode whose item is not in the
  store, and the checkpoint still moves past it, so an item added to the point de vente later (or fixed later) would
  never get its barcode, its barcode row not changing. The items part notes its items in the connector (a `ThreadLocal`
  of the job's thread, set by `fetchItems` on a run from a checkpoint, taken and cleared by `fetchItemBarcodes`; not by
  `clearLastPullOperationResult`, which the manager calls after every read); a row read twice is kept once.
  `ParafendriMapper.barcodes`: value `barCode`, item `itemNo` (the item's ERP id since step 3), external id the row's
  `id` (a GUID: a barcode changed in Business Central replaces the old one, the import finding it by its ERP id first;
  no id: the value), `lastModifiedAt` (0001-01-01: none, noted); the primary flag left to the import, as EMTOP's. Left
  out with their reason: a blank `barCode`, a blank `itemNo`. Not read: `unitOfMeasureCode` (`item_barcode` has no
  unit), `description` (empty). The communications log gets the address, the counts (`readFromCheckpoint`,
  `itemsAsked`, `readForItems`, `sameRowTwice`) and the reasons, not the rows. **Barcodes of an item this store does not
  have**: the core import writes one WARNING row each in the communications log ("parent item ... not found"), as for
  EMTOP; measured live 2026-10-31 for PV-GREMDA: 0 (its 13,165 items are the company's 13,165 barcodes), so no change in
  core; to measure at each other point de vente when it starts (the live test counts them). Live: 13,165 rows in one
  page, 5.0 s, all kept.
- Step 6, the tickets (`pushTicket`, the job `EXPORT_TICKETS`): **one POST per ticket**, `companies(<id>)/salesOrders`
  with its lines inside (`salesOrderLines`, a contained navigation property: a deep insert). The ability
  **`ErpAbility.WHOLE_TICKET_EXPORT`** (erp-api; only this connector declares it) makes `TicketExportService` take one
  branch at the top of `exportTicket` (`ErpAbilities.has(environment, ...)`, no configuration line): `pushTicket` with
  every line; success = Business Central's number as the ticket's `erpNo`, every line synchronized and the ticket
  `TOTALLY_SYNCHED` at once; failure = nothing changed (no partial state, no status update), counted as an error,
  retried at the next run. Any other connector keeps the header, the lines one by one, then the status, unchanged (the
  shared mapping only fills a few new optional fields of `ErpTicketDTO` / `ErpTicketLineDTO`: the lines' position, TTC
  and promotion code, the ticket's HT and its reduction amount; emtop's mapper reads none of them).
  - **Never twice**: before the POST, `GET salesOrders?$filter=ticketNo eq '<ticket number>'`; a ticket found is
    answered with its order (its number, no POST). **Today Business Central answers 400 "Field 'ticketNo' is not
    filterable"** (2026-10-31: of the header's fields only `number` and `orderStatus` filter); the export then posts
    anyway and the run writes **one** WARN, in the log and as a WARNING row of the communications log (operation
    `EXPORT_TICKET`): "no-double guard unavailable: ticketNo is not filterable in BC — ask the BC contact to make
    ticketNo a stored, filterable field" (the connector's `ErpOperationResult.addWarning`, gathered by
    `TicketExportService` and written once per run; a ticket exported alone writes its own). Any other failure of the
    lookup: the ticket is not posted. Ticket numbers are `DEFAULT_LOCATION` + date + sequence, unique across the
    points de vente.
  - `customerNo`: the point de vente's customer, `customerNo` of one `stockPoints` row of `DEFAULT_LOCATION` (`$top=1`),
    read at the first ticket and kept for the location (none: the ticket is not sent, read again next time).
  - The mapping (`ParafendriMapper.salesOrder`), **each point to confirm with Sayf** (ParaFendri's BC contact):

    | Business Central | Value |
    |---|---|
    | `ticketNo` | the ticket number |
    | `customerNo`, `locationCode` | the point de vente's customer, `DEFAULT_LOCATION` |
    | `orderDate`, `postingDate` | the ticket's date |
    | `ticketAmount` | the total HT after the ticket-wide reduction (`SalesHeader.subtotal`) |
    | `amountIncludingVAT` | **computed by BC**, never sent (read-only: sent, BC answers HTTP 400 "Control 'amountIncludingVAT' is read-only"); read back from the answer and put in the log message next to our total TTC (`totalAmount`, stamp included) |
    | `discountPercent` | the ticket-wide reduction (discount + loyalty points) as a % of the lines' TTC, 5 decimals: EMTOP's `Discount_Percent` |
    | `invoiceDiscountAmount`, `invoiceDiscountPercent` | 0 |
    | `fenceNo` | the cashier session number, as EMTOP's `Fence_No` (the ticket carries no till id) |
    | `orderStatus` | never sent (Entered, Posted, Error, Double: set by BC), shown in the log message |
    | line `lineNo` | 10000 × its position among the lines sent |
    | line `itemNo` | the item's ERP code; the stamp's `TAX_STAMP_ERP_ITEM_CODE`, and without it no stamp line and one WARN per run |
    | line `quantity`, `unitPrice` | decimals kept; the price before VAT as stored, never rounded |
    | line `lineDiscountPercent` | as EMTOP's (stored, else derived; a free line 100, its price kept) |
    | line `promotionNo` | the promotion's code, else "" |
    | line `amountIncludingVAT` | **computed by BC**, never sent (read-only, as the header's) |

  - The communications log: one row per ticket (`EXPORT_TICKET`): the body sent, Business Central's answer, the
    message with its number, `orderStatus` and the two totals ("ticket X posted as sales order N (orderStatus Entered,
    BC amountIncludingVAT 22.5, our total TTC 22.500)": BC's rounding of 18.90756 × 1.19 next to ours), "already in
    Business Central as ..."), Business Central's own error text on a refusal (`{"error":{"code","message"}}`). The
    connector's writes: `GetOnly` lets a POST to `.../salesOrders` (no query) through, and since step 7 one to
    `.../shipAndInvoice`, nothing else.
  - Returns and sessions are not exported yet (later, with Sayf's pages): with a connector posting tickets whole
    (`ApplicationModeService.isWholeTicketExport`) `ZZDataInitializer` seeds `EXPORT_RETURNS` and `EXPORT_SESSIONS`
    disabled; `pushReturnHeader` / `Line` and `pushSession` answer a failure.
  - Tests: `ParafendriSalesOrderMapperTest` (the sample `salesOrder-request.json`, the stamp without its ERP code),
    `ParafendriTicketExportTest` (posted, already there, not filterable then posted with the warning, another lookup
    failure, refused with BC's text, `customerNo` once, none, no location), the guard and BC's error text in
    `ParafendriRestClientTest`, `TicketExportWholeTicketTest` (network-tests: the branch, all or nothing, emtop and none
    unchanged, one warning per run, the seeding question). Live (GET only, 2026-10-31): the lookup answers 400 not
    filterable, the test passes on that path; the live POST test runs only with `-Dparafendri.live.post=true` (one test
    ticket, then the same again: no double), not run yet.
- Step 7, the invoice of a ticket (`requestInvoice`, the same job `EXPORT_TICKETS`): Business Central's page
  `companies(<id>)/shipAndInvoice` (key `orderNo`; fields `orderNo`, `invoiceNo`, `invoicePdfBase64`,
  `lastModifiedDateTime`; no OData action): **POST `{ "orderNo": "<the ticket's erpNo>" }`**, Business Central ships and
  invoices the sales order and answers the row with the invoice number and the PDF in base64.
  - erp-api: `ErpConnector.requestInvoice(externalReference, fiscalRegistration, invoiceCustomerName)` →
    `ErpInvoiceResult` (an `ErpOperationResult`: success, message, warnings, plus `invoiceNo` and `pdf`, bytes or null);
    the default throws `ErpNotSupportedException`. No new ability: `WHOLE_TICKET_EXPORT` covers it (a connector posting
    tickets whole answers it). Core: `ErpSynchronizationManager.requestInvoice`, logged as the other pushes under the
    new operation **`REQUEST_INVOICE`** (counted with the job `EXPORT_TICKETS` in its statistics). The front's filter
    list of the communications page (`ErpCommunications.vue`) does not list it yet (no front work in this step).
  - **The service rule** (`TicketExportService`, the whole-ticket branch only): a ticket with its `erpNo` is **never
    posted again**. An invoice is due when the ticket was prepared for one (`prepareInvoice`: `invoiced`, its fiscal
    registration), has no `invoiceNumber` and no local invoice (`SalesHeader.invoice` null). Then:
    - in the ERP already (prepared after the post: `prepareInvoice` set it back to `PARTIALLY_SYNCHED`):
      `requestInvoice`; a failure throws (counted as an error of the run), the ticket stays `PARTIALLY_SYNCHED`;
    - prepared before it went: posted whole, saved `PARTIALLY_SYNCHED` with its `erpNo`, then the invoice asked at once;
      a failure keeps the post (not counted as an error), the invoice asked again next run;
    - success: the PDF written, then `invoiceNumber` = Business Central's number and `TOTALLY_SYNCHED`. A PDF that cannot
      be written fails the step (asked again next run: the lookup answers the same invoice); an answer without a PDF is
      kept with a WARN line;
    - in the ERP with nothing due (e.g. `PARTIALLY_SYNCHED` and not invoiced): `TOTALLY_SYNCHED` again, nothing sent.
      Before step 7 such a ticket would have been posted a second time (the `ticketNo` lookup does not filter).
  - `prepareInvoice` is unchanged; EMTOP's path is unchanged (POS_Invoice through `updateTicketStatus`, never
    `requestInvoice`). The no-ERP invoices are untouched: a ticket with a local `InvoiceHeader` (`invoice` set, its
    number in `invoiceNumber`) is never asked; with sales going to the ERP the till offers only "Prepare invoice".
    `SalesHeader.invoiceNumber` now also holds an ERP invoice number with `invoice` null; it goes up to the head office
    in the ticket's copy as before (`HoTicket.invoiceNumber`, shown in the head office's tickets).
  - **Where the PDF lands**: `<app.upload.dir>/erp-invoices/<invoiceNo>.pdf` (`ErpInvoicePdfFiles`; a character other
    than a letter, a digit, `.`, `_` or `-` becomes `_`), written through a `.part` file beside it. The ticket has no
    path field: the place is derived from `invoiceNumber` (`ErpInvoicePdfFiles.pathOf`). Nothing serves the folder (the
    images are served by `ItemImageController` from their own folders); a screen to open the PDF is a later step.
    ParaFendri's file: `app.upload.dir=C:/zsretail-parafendri/store/uploads/pos-images`, so
    `.../pos-images/erp-invoices/`.
  - **Never twice** (`ParafendriConnector.requestInvoice`): first `GET shipAndInvoice?$filter=orderNo eq '<erpNo>'`; a
    row with its `invoiceNo` answers (its PDF decoded), nothing posted. A failed lookup posts nothing.
  - **Fiscal registration and name: not sendable** (`shipAndInvoice` has no field for them, **open with Sayf**): they
    are written in the message of the `REQUEST_INVOICE` row ("...; not sent (shipAndInvoice has no field for them):
    fiscal registration X, invoice name Y"), and the run writes **one** WARN, in the log and as a WARNING row
    (operation `REQUEST_INVOICE`): "shipAndInvoice has no fiscal fields — ask the BC contact". Until Sayf adds them, the
    invoice Business Central makes is in the name of the point de vente's customer (`customerNo` of the sales order).
  - The communications log: the request `{ orderNo }`, the answer without the PDF (`invoicePdfBase64`: its length), the
    message with the invoice number or Business Central's own error text.
  - Tests: `ParafendriInvoiceTest` (the samples `shipAndInvoice-request.json` / `shipAndInvoice-answer.json`, a 49-byte
    PDF: posted with the fiscal warning, already invoiced, refused with BC's text, the lookup failing, no PDF, no
    invoice number, no order number), the guard in `ParafendriRestClientTest`, `TicketExportWholeTicketTest`
    (network-tests: asked once and the PDF written, prepared before the post, refused, nothing due, one fiscal warning
    per run, EMTOP unchanged). Live: the GET lookup always (a number that is no order: no row), not run yet (2026-11-01:
    Business Central did not answer, a connection timeout on the first page; to run again with the VPN open, which also
    checks that `orderNo` filters); the live POST `liveInvoicePost` runs only with `-Dparafendri.live.post=true
    -Dparafendri.order=<a test ticket's order>`, not run yet.
  - **Open with Sayf**: the fiscal fields on `shipAndInvoice` (or the customer's own `customerNo`); whether Business
    Central posts the sales orders itself (e.g. a batch at night: an order posted that way is deleted, and an invoice
    asked later would fail at every run); what `shipAndInvoice` answers for an order already shipped.
- **The locations and the point de vente's customer (2026-11-01)**, the two reads the till relies on (without them a
  sale fails with "Default customer not found" and the Emplacements page is empty):
  - `fetchLocations` (job `IMPORT_LOCATIONS`): every location of the company, the page the connection check reads
    (`locations?$select=code,name,lastModifiedDateTime`), code (also the ERP id) and name; a blank code or a code read
    twice (case ignored) left out. **Always whole**: the core keeps no checkpoint for the locations (`ErpLocationDTO`
    carries no time) and a location never changed is dated 0001-01-01 (PV-TANIOUR), which a delta would miss. None is
    marked as the default: the admin picks it on the Emplacements page (`DEFAULT_LOCATION`), as at EMTOP.
  - `fetchCustomers` (job `IMPORT_CUSTOMERS`): **one row only**, the account of `DEFAULT_LOCATION`: its `customerNo` from
    `stockPoints?$filter=locationCode eq '<code>'&$top=1` (the read of `pushTicket`, fresh here, and kept for it), then
    `customers?$filter=number eq '<customerNo>'` (`dto/ParafendriCustomerRow`: `number`, `name`, `blocked`,
    `lastModifiedDateTime`). Business Central refusing the filter (HTTP 400 "is not filterable"): the page read whole,
    that number kept, one WARN. Never another point de vente's account (`ParafendriMapper.customer`: left out "another
    customer than the point de vente's"). Code and ERP id the number, the name, **active unless blocked** (`_x0020_`,
    Business Central's blank option, or blank: active; `Ship`, `Invoice`, `All`: inactive), `lastModifiedAt` its
    `lastModifiedDateTime`. **Always read, not from the checkpoint** `ERP_SYNC_LAST_CUSTOMER` (one row: nothing to
    save, and a point de vente changed for one whose account changed earlier would never be read). No
    `DEFAULT_LOCATION`, or no `customerNo` on its rows: no row and one WARN; another failed read fails the job.
  - Tests: `ParafendriLocationsCustomersTest` (the samples `locations.json` and `customers.json`: three accounts,
    4110001 to 4110003, one blocked `All`), the live test (GET only) through the connector.
  - **ParaFendri install**: run `IMPORT_LOCATIONS` and `IMPORT_CUSTOMERS` once, pick the default location on the
    Emplacements page, set `PASSENGER_CUSTOMER` to the point de vente's customer number (e.g. 4110001). Picking the
    location first is needed for the customer: `IMPORT_CUSTOMERS` reads the account of `DEFAULT_LOCATION` (none set:
    no row), so after a first `IMPORT_CUSTOMERS` without it, run it again once the location is picked.
- **The head office catalogue (2026-11-01)**: a ParaFendri head office whose catalogue comes from the ERP, its points de
  stock the six points de vente, runs the catalogue engine as ZEN's and Happyness's: the job **`SYNC_CATALOGUE`**
  (`CatalogueJob` → `CatalogueEngine`), not `IMPORT_CATALOGUE` (on such a head office the conversion of the four old jobs
  still makes an `IMPORT_CATALOGUE` row, which `HeadOfficeErpJobs` switches off at each start and never offers).
  - The descriptor declares `HEAD_OFFICE_STOCK_POINTS` beside `WHOLE_TICKET_EXPORT`. ParaFendri is the first connector
    on stores and a head office: the engine's beans are gated by the ability **and** the installation
    `HEAD_OFFICE_ERP_CATALOGUE_ONLY` (without it a store would have had the engine, which needs the head office's
    `StockPoints` and creates `erp_catalogue_state`, and would not start). The connector's `ParafendriCatalogueSource` and
    `ParafendriCatalogueSettings` exist only there too (`config/ParafendriHeadOfficeCondition`,
    `ErpConnectors.isErpCatalogueOnlyHeadOffice`). A store (Gremda, Taniour...) is unchanged: `IMPORT_CATALOGUE`
    through `ImportCatalogueJob` and the connector's fetches.
  - `client/ParafendriCatalogueSource` (`ErpCatalogueSource`), the client and the mapper of steps 2 to 4, no HTTP code of
    its own: `categories()` the tree `itemCategories` (catégories, familles, sous-familles, sections: the engine uses the
    four levels); `items(point)` and `items(point, since)` the page `stockPoints` of the point (`locationCode eq`, from
    its checkpoint `lastModifiedDateTime ge`, `readsChangesOnly()` true: a row not answered is unchanged, never gone),
    the tree read again for the links, the prices before VAT as received, a blocked row inactive (step 3's rules);
    `barcodesAfter(position)` and `barcodesOfItems(codes)` the page `barCodes`; `readsItemImages()` false.
  - **The barcode cursor, ZEN's contract (step 12a)**: the position of a row is its `lastModifiedDateTime` in epoch
    milliseconds (`ParafendriMapper.position`; the empty date 0001-01-01, a blank or an unreadable date: 1).
    `ParafendriRestClient.readBarcodesAfter(position)`: `barCodes?$filter=lastModifiedDateTime gt <instant>` (no filter
    from 0) `&$orderby=lastModifiedDateTime&$top=1000` (`BARCODE_PAGE_SIZE`, a constant); a full page may end inside a
    millisecond whose other rows are on the next page, so every row of its last millisecond is read too
    (`lastModifiedDateTime eq <instant>`, next links followed; for position 1, `lt 1970-01-01`), a row already on the
    page once (its GUID `id`). `gt` and the strict order: no loop (the engine also stops when a page is at or below its
    cursor), no millisecond half read; a barcode changed later has a newer time and comes back. As ZEN's, a row BC
    commits after a later one with an earlier time could be passed; new items have their barcodes read by item
    (`barcodesOfItems`, 20 per GET, the engine's "needs its barcodes"). Checked live on 2026-11-01 (GET): `$orderby`
    asc and desc, `$top`, `gt`, `lt`, `eq` on a millisecond (one row). Every row's position, left out ones included,
    goes to the engine; the external id is the GUID `id` (the value without one), as on a store.
  - `config/ParafendriCatalogueSettings`: constants, no key: packets of 500, never a dry run, VAT 19 for the rows the
    engine keeps without one (the rows read carry their own `vatPercent`).
  - **The head office's file** (`<Tomcat>/conf/zsretail/zsretailws.properties`, the head office WAR): `ownership.catalogue=ERP`
    (customers and supply kept here: `headoffice.supply.source=HEAD_OFFICE`; ParaFendri reads no supply invoice and
    posts no store order), `headoffice.stock.enabled` as the customer wants, `erp.connector=parafendri`,
    `erp.sync.enabled=true`, and the five `erp.parafendri.*` keys of the store (`base-url`, `company-id`, `domain`,
    `username`, `password`). No `DEFAULT_LOCATION` on a head office: the connection check after the start warns that it
    is empty (the company is still checked).
  - **The first run**: start the head office (the seeding makes `SYNC_CATALOGUE`, disabled, hourly); on the points page
    add the six points de stock, their codes the BC location codes `PV-GREMDA`, `PV-LAFRANE`, `PV-MALL-SF`, `PV-NABEUL`,
    `PV-SOUSSE`, `PV-TANIOUR`; run `SYNC_CATALOGUE` once by hand (the tree, then the items of each point in list order,
    then the barcodes until caught up); then enable it. Expected first run, from the live times of 2026-11-01 (13,165
    rows per point): reads about 6 × 3.6 s for `stockPoints` and about 4 s for `barCodes` (14 pages); writes 13,165
    items, about 79,000 rows of points and 13,165 barcodes: about 2 to 4 minutes. Later runs read the changes only.
  - Tests: `ParafendriCatalogueSourceTest` (the samples of steps 2 to 4: the four levels, the items of one point whole
    and from its checkpoint, the cursor from 0 and after a position, a full page completed with its last millisecond, the
    barcodes by item, the filters, the ability declared), `ErpAbilityBeansTest` in network-tests (the engine's 7 beans
    with a connector that keeps points de stock on such a head office only; the ParaFendri store of configs/customers,
    a store and a head office without the catalogue from the ERP with `erp.connector=parafendri`: the connector, no
    engine, no catalogue source, no stock point bean), `ErpAbilitiesTest` (the abilities), the live test (GET only):
    the source's tree, the items of `-Dparafendri.location` and two pages of the cursor.
- Tests (in the project): `ParafendriRestClientTest` (mock server, the sample `companies.json`: two companies, one the
  configured id; next links, an unknown id, an HTTP 401, GET only, the login's host; step 1, the sample
  `locations.json` with the six points de vente PV-GREMDA, PV-LAFRANE, PV-MALL-SF, PV-NABEUL, PV-SOUSSE, PV-TANIOUR:
  the address, a code found or not, next links, an HTTP 404), step 2: `ParafendriMapperTest` (the sample
  `itemCategories.json`: COS, COS-COF, COS-COF-PAR, COS-PAR and a section, an orphan famille kept and noted, an orphan
  sous-famille, a section under a famille, a level 4 row and a blank code left out) and `ParafendriConnectorTest` (the
  four fetches, one GET each, what the log receives, a failed read), step 3: `ParafendriItemsMapperTest` (the sample
  `stockPoints.json` against the tree of `itemCategories.json`: the fields, the price as received, the VAT rounded, the
  unit, the three blocks and the zero price, codes outside the tree, the empty date, the four left-out reasons), the
  items in `ParafendriConnectorTest` (the tree then `stockPoints`, the checkpoint's `ge`, the log without the rows, no
  `DEFAULT_LOCATION`), the filter and the paging in `ParafendriRestClientTest`, the unit in core's
  `ErpItemImportLookupsTest`; step 4: `ParafendriBarcodesMapperTest` (the sample `barCodes.json`),
  `ParafendriBarcodesConnectorTest` (a first run reads all; an item added later gets its barcode through the read of
  the run's items; a row read twice kept once; the run's items used once; items read whole: no read per item), the
  address and the batches of 20 in `ParafendriRestClientTest`, `ParafendriStartupCheckTest`,
  `ParafendriConnectionCheckTest` (step 1: all found, Business Central down, an HTTP 503, the company missing,
  `DEFAULT_LOCATION` empty, the location missing, the locations page or the location failing; no exception, its own
  daemon thread); `ParafendriLiveConnectionTest`, skipped unless `-Dparafendri.live=true`, reads the companies and (step
  1) the locations of a real Business Central and checks `-Dparafendri.location` (PV-GREMDA by default), with the
  outside file named by `-Dparafendri.file` (no default; never the customer's production without Zein's word).
- **Later step: the status on screen.** The connection check is a log line only. Showing it to the user means a small
  change in core: record it in the communications log (`erp_communication`, the admin page) or add a "test connection"
  button (an endpoint asking the connector through erp-api). In network-tests the pinned lists name it (`ErpConnectorTest`, `ThirdConnectorTest`, `OutsideFileUpgradeTest`,
  `ErpAbilitiesTest`, `ErpAbilityBeansTest`) and both proof records have the store's file (`configuration-keys.txt`:
  its section, and `erp.parafendri.*=(none)` in every other configuration; `installation-beans.txt`: its line).

## Head office catalogue from the ERP pages (`erp.happyness`)

The head office reads its catalogue from the ERP pages of one location (details in `head-office.md`, "the connector
navpospages"). Items of the ERP (`item.erp_external_id` set) no longer in the location are handed inactive, never
deleted, **whatever their number** (since 2026-10-08; the old `deactivate-guard-percent` key is gone; since code cleanup
step 8 a line left in a file stops the startup). The only refusal: when the ERP answers 0 rows for the location, nothing is deactivated and the summary
warns "the ERP answered no item for the location: N items not deactivated" (an empty answer is a broken connection or
a wrong location code, never a cleanup). Hand-made items, packs made at the head office and `TAX_STAMP` have no ERP id
and are never touched.

One items run applies **all** its changes (new, changed, deactivated) in packets of `erp.happyness.packet-size` (default
500, formerly `max-changes-per-run`), each packet in its own transaction with the store change rows; a failed packet ends the
run in error and the next run goes on with what is left; one items run at a time. Its summary says what was applied (`new`,
`changed`, `deactivated`, `applied`, `packets`). A blank `Description` never replaces the name of an item already at the head
office.

One barcode run goes on until it has caught up, the same way: every item waiting for its barcodes, then the cursor on
`Entry_No` page after page until the ERP answers no row, packet after packet; the cursor is saved after each packet and never
passes a row not saved. Summary: `read`, `leftOut`, `itemsNeedingBarcodes`, `cursorFrom`, `cursorTo`, `pagesRead`, `applied`,
`packets`, `caughtUp`.
