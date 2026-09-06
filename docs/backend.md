# Backend Notes

- `TransactionStatus` enum removed `DRAFT`; pending/cancelled sales excluded from session totals.
- Customer API prevents deleting the special PASSENGER customer.
- General setup API blocks updates to read-only entries.
- **PaymentMethod Entity**: Added `displayOrder` field (Integer) to control display order in POS. Initializer service sets order: 1=CLIENT_ESPECES, 2=CLIENT_TPE, 3=TICKET_RESTAURANT, 4=CHEQUE_CADEAU, 5=CLIENT_CHEQUE, 6=CLIENT_TRAITE, 7=DEPOT_BANQUE, 8=RETURN_VOUCHER.
- Item family/subfamily domain: entities, repositories, services, controllers; `Item` links to both family and subfamily, with legacy `taxable`/`taxRate` flags removed in favor of `defaultVAT`.
- `ItemBarcodeAPI /items-with-barcodes` now supports pagination + filtering; `ItemService` exposes a spec-based search and `ItemBarcodeService` fetches active barcodes per page.
- Admin ERP communications endpoint exposes filtered communications (date range, limit) for the new frontend view.
- Dynamics NAV item fetch now enforces presence of `DEFAULT_LOCATION`; if missing, an `ErpSyncWarningException` is raised, logged as a WARNING communication, job execution is marked accordingly, and the admin "Run Now" endpoint responds with HTTP 400 and the warning message.
- **SalesLine Entity**: Removed `notes` field. Added `vatAmount` (Double), `vatPercent` (Integer), `unitPriceIncludingVat` (Double, not null), and `lineTotalIncludingVat` (Double, not null). Existing `discountPercentage` and `discountAmount` fields are now fully utilized and sent from frontend in `ProcessSaleRequestDTO.SaleLineDTO`.
- **SalesHeaderService**: Updated `processCompleteSale()`, `savePendingSale()`, and `completePendingSale()` methods to set discount fields from DTO when creating sales lines. Discounts are applied to totals including VAT before calculating backwards to excluding VAT amounts. All three methods persist `discountSource` and `promotion` FK on header and lines; `processCompleteSale` and `completePendingSale` also create FREE_QUANTITY free lines when `freeQuantity > 0`.
- **Payment Processing**: Updated to allow completion when `remainingBalance <= 0.01` (enables exact payment and overpayment, with 0.01 TND tolerance for floating point precision).
