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
- `connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/config/DynamicsNavConfig.java` - RestTemplate config
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
| `zen` (3.1.0, item variants step 8) | yes | — (step 9) |

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
`JpaHeadOfficeCatalogueTest` (the items query on H2).

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
not change. The item does not compare them. No price logic: the stores keep the row's price; the page "Items by point
de stock" (`StockPointItemDTO`) shows both values. Tests: `CatalogueClearanceTest` (core), `HoStockPointRowsTest` (H2),
`HoStockPointItemsServiceTest.clearance`.

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
(`ZenConnectorDescriptor`, ability `HEAD_OFFICE_STOCK_POINTS`; `SUPPLY_INVOICES` at step 9), its settings
(`ZenProperties`, checked by `ZenStartupCheck`; keys in `docs/deployment-modes.md`), an NTLM client refusing anything but
GET (`ZenConfig`, `ZenRestClient`, following `@odata.nextLink`), the mapper (`ZenMapper`), the source the engine reads
(`ZenCatalogueSource`, `readsChangesOnly()` true), the engine's settings and a read-only `ErpConnector` (`ZenConnector`).
- Pages of ZEN's custom API (`<base-url>/api/sat/zsretail/v1.0/companies(<id>)/`, the id `erp.zen.company-id` or looked up once by the company's name `erp.zen.company`, step 10): `stockPoints`, read per location
  with `$filter=locationCode eq '<code>'` and, from a checkpoint, `and lastModifiedDateTime ge <time in UTC>`, no
  `$select` (so the fields `active` and `vat` are read when the API gives them); `itemCategories`; the barcodes, Happyness's
  page with `Variant_Code` (`ItemBarCodePOS` by default, to confirm; a page may be given as a whole address).
- Items: one per row, code `itemNo~variantCode` (`itemNo` alone without a variant); name and description the
  `itemDescription`; the 14 variant fields (blank as null); brand `brandCode`; family and sub-family `familyCode`,
  `subFamilyCode`; `publicPrice` before VAT, 0 or none inactive; `active` false inactive, absent active; VAT the row's
  `vat` when given, else `default-vat`; `lastModifiedAt` from `lastModifiedDateTime` (NAV's 0001-01-01: none); the
  location's `clearance` (absent: false) and `clearancePrice`. The rows of another location, a blank `itemNo`, a `~` in
  `itemNo` or `variantCode`, the same item and variant twice are left out. Not used: `barCode` (the barcodes page is
  read), `stockPointDescription`, `itemCategoryCode`, `baseUnitOfMeasure`, `brandDescription`, `clothingStyleCode`,
  `cutCode`, `noOfPieces`.
- The tree by `indentation`: 0 catégories, 1 families (`categoryExternalId` = the parent when it is a catégorie), 2
  sub-families (left out when the parent is not a family of the read), 3 sections (left out when the parent is not a
  sub-family of the read); display order `presentationOrder`.
- Barcodes: the item is `Item_No~Variant_Code`; those of new items are read by `(Item_No eq '..' and Variant_Code eq
  '..')`, the rows of other variants left out.
- Tests: `ZenMapperTest` (on the two samples of the API in `connectors/zen/src/test/resources/zen`),
  `ZenRestClientTest`, `ZenCatalogueSourceTest` (in the project); `ZenStartupCheckTest`, the pinned lists of
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
