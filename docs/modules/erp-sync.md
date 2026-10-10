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
- `src/main/java/com/digithink/zsretail/model/SalesHeader.java` - Entity with sync fields
- `src/main/java/com/digithink/zsretail/model/SalesLine.java` - Entity with synched field
- `src/main/java/com/digithink/zsretail/model/enumeration/SynchronizationStatus.java` - Sync status enum
- `src/main/java/com/digithink/zsretail/erp/dynamicsnav/dto/DynamicsNavSalesOrderHeaderDTO.java` - Header DTO
- `src/main/java/com/digithink/zsretail/erp/dynamicsnav/dto/DynamicsNavSalesOrderLineDTO.java` - Line DTO
- `src/main/java/com/digithink/zsretail/erp/service/TicketExportService.java` - Export service
- `src/main/java/com/digithink/zsretail/erp/dynamicsnav/client/DynamicsNavRestClient.java` - NAV client
- `src/main/java/com/digithink/zsretail/erp/dynamicsnav/config/DynamicsNavConfig.java` - RestTemplate config
- `src/main/java/com/digithink/zsretail/erp/service/ErpSyncJobRunner.java` - Job runner integration


## ERP Synchronization Operations

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
- Abstract `ErpConnector` interface (`com.digithink.zsretail.erp.spi.ErpConnector`) defines ERP-agnostic methods
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

The connector is chosen by `erp.connector` (or the old enabled lines, `deployment-modes.md`). The application asks what
the connector in effect can do, never which one it is: `erp/spi/ErpAbilities` gives, for each connector name, its
abilities (`erp/spi/ErpAbility`), read from the environment, so a condition uses it before the beans exist.

| Connector | `CATALOGUE_BY_STOCK_POINTS` | `SUPPLY_INVOICES` |
|---|---|---|
| `none` | — | — |
| `dynamicsnav` | — | — |
| `navpospages` | yes | yes |

- `CATALOGUE_BY_STOCK_POINTS`: the catalogue read by points de stock with its single job `SYNC_CATALOGUE`; the head office
  keeps the points (`ho_stock_point`, `ho_stock_point_item`) and their page.
- `SUPPLY_INVOICES`: the ERP's supply invoices are read, so a head office may set `headoffice.supply.source=ERP`.

`ErpAbilities.connectorInEffect(env)` (none, dynamicsnav, navpospages) is worked out from the values in effect;
`hasConnector(env)` is true for any connector but none. A new ability is added there when the code needs it, never a test on
a connector name.

**Rule: the application never names a connector.** Outside the connector packages (`erp/dynamicsnav`, `erp/navpospages`)
and the translation class of step 1 (`config/ErpConnectorEnvironmentPostProcessor`, the only one that knows the old keys
and the NAV address of the startup summary), no class imports a connector package or contains the text `erp.dynamicsnav` or
`erp.navpospages`, not even in a comment. It uses `@ConditionalOnErpAbility(...)` or `ErpAbilities.has(env, ...)`; what a
connector's settings give the application goes through `ErpConnector` (the ids of deleted prices, the seller named on the
stores' invoices: `supplyInvoiceSellerName`, the connector's `invoices.seller-name`) or a port in `erp/spi` that the
application implements (`StockPoints`, the head office's points de stock). **Guard test**: `erp/ConnectorNamesGuardTest`
scans `src/main/java` and fails naming each offending line.

**Deletion sync** (`ErpDeletionSyncService`, job of the ERP Log): the `erp_external_id` of a deleted sales price or discount
is built by the connector in effect (`ErpConnector.deletedSalesPriceExternalId` / `deletedSalesDiscountExternalId`), the
way it built it at the import: `DynamicsNavConnector` through `DynamicsNavMapper`, the other connectors answer null (they
have no deletion log). The ids are pinned character for character by `ErpDeletionSyncServiceTest`.

## Head office catalogue from the ERP pages (`erp.navpospages`)

The head office reads its catalogue from the ERP pages of one location (details in `head-office.md`, "the connector
navpospages"). Items of the ERP (`item.erp_external_id` set) no longer in the location are handed inactive, never
deleted, **whatever their number** (since 2026-10-08; the old `deactivate-guard-percent` key is gone and ignored when
left in a file). The only refusal: when the ERP answers 0 rows for the location, nothing is deactivated and the summary
warns "the ERP answered no item for the location: N items not deactivated" (an empty answer is a broken connection or
a wrong location code, never a cleanup). Hand-made items, packs made at the head office and `TAX_STAMP` have no ERP id
and are never touched.

One items run applies **all** its changes (new, changed, deactivated) in packets of `erp.navpospages.packet-size` (default
500, formerly `max-changes-per-run`), each packet in its own transaction with the store change rows; a failed packet ends the
run in error and the next run goes on with what is left; one items run at a time. Its summary says what was applied (`new`,
`changed`, `deactivated`, `applied`, `packets`). A blank `Description` never replaces the name of an item already at the head
office.

One barcode run goes on until it has caught up, the same way: every item waiting for its barcodes, then the cursor on
`Entry_No` page after page until the ERP answers no row, packet after packet; the cursor is saved after each packet and never
passes a row not saved. Summary: `read`, `leftOut`, `itemsNeedingBarcodes`, `cursorFrom`, `cursorTo`, `pagesRead`, `applied`,
`packets`, `caughtUp`.
