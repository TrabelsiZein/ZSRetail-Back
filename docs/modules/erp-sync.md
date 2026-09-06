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
- `src/main/java/com/digithink/pos/model/SalesHeader.java` - Entity with sync fields
- `src/main/java/com/digithink/pos/model/SalesLine.java` - Entity with synched field
- `src/main/java/com/digithink/pos/model/enumeration/SynchronizationStatus.java` - Sync status enum
- `src/main/java/com/digithink/pos/erp/dynamicsnav/dto/DynamicsNavSalesOrderHeaderDTO.java` - Header DTO
- `src/main/java/com/digithink/pos/erp/dynamicsnav/dto/DynamicsNavSalesOrderLineDTO.java` - Line DTO
- `src/main/java/com/digithink/pos/erp/service/TicketExportService.java` - Export service
- `src/main/java/com/digithink/pos/erp/dynamicsnav/client/DynamicsNavRestClient.java` - NAV client
- `src/main/java/com/digithink/pos/erp/dynamicsnav/config/DynamicsNavConfig.java` - RestTemplate config
- `src/main/java/com/digithink/pos/erp/service/ErpSyncJobRunner.java` - Job runner integration


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
- Abstract `ErpConnector` interface (`com.digithink.pos.erp.spi.ErpConnector`) defines ERP-agnostic methods
- Specific ERP implementations (e.g., `DynamicsNavConnector`) implement the interface
- `NoOpErpConnector` provides stub implementation when ERP is disabled

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
