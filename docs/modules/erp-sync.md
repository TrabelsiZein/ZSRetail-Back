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

**ErpSyncOperation Enum:**
- `IMPORT_ITEM_FAMILIES`, `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`, `IMPORT_ITEM_BARCODES`, `IMPORT_LOCATIONS`, `IMPORT_CUSTOMERS` - Import operations
- `EXPORT_CUSTOMER` - Export customer to ERP
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

| Connector | `HEAD_OFFICE_STOCK_POINTS` | `SUPPLY_INVOICES` |
|---|---|---|
| `none` | — | — |
| `emtop` | — | — |
| `happyness` | yes | yes |
| `zen` (3.1.0, item variants step 8) | yes | yes (step 9) |

- `HEAD_OFFICE_STOCK_POINTS` (was `CATALOGUE_BY_STOCK_POINTS`): the head office keeps several points de stock in its own
  tables (`ho_stock_point`, `ho_stock_point_item`, the points page), filled by the single catalogue job `SYNC_CATALOGUE`. Not
  "reads items by location": every connector may do that.
- `SUPPLY_INVOICES`: the ERP's supply invoices are read, so a head office may set `headoffice.supply.source=ERP`.

`ErpAbilities.connectorInEffect(env)` (none, emtop, happyness, zen) is the value of `erp.connector`;
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
`HoStockPointItemsServiceTest.clearance`.

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
(`clearanceChangeRecordedForTheStores`), `HoStockPointItemsServiceTest.sellingPrice`, store `ClearanceAtTheTillTest`,
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
- Supply invoices (step 9): not on the custom API but on NAV's standard OData V4 page `SalesInvoice` under
  `Company('<erp.zen.company>')` (ESP), the same client; `ZenInvoices`, a copy of Happyness's invoice read (by year
  prefix after the head office's highest number), only the invoices of the stores' ERP customer numbers
  (`ErpInvoiceCustomers`, erp-api, given by the head office's `HoErpInvoiceCustomers`). Details in `head-office.md`,
  "Invoices from the ERP, ZEN".
- Tests: `ZenMapperTest` (on the two samples of the API in `connectors/zen/src/test/resources/zen`),
  `ZenRestClientTest`, `ZenCatalogueSourceTest` (in the project); step 9: `ZenMapperInvoicesTest` (the sample
  `SalesInvoice.json`), `ZenInvoiceClientTest`, `ZenInvoicesTest`; `ZenBarcodeCursorTest` (the engine's barcode cursor on
  a fake `barCodes` page), `ZenStartupCheckTest`, the pinned lists of
  `ErpConnectorTest`, `OutsideFileUpgradeTest`, `ThirdConnectorTest`, `ErpAbilitiesTest`, `ErpAbilityBeansTest`
  (network-tests). `ConnectorNamesGuardTest` takes a connector's name as a whole word (no letter or digit around it).

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
