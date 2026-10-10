# Decimal quantities (2.2.1)

Bulk items sold by the litre or the kilo (Happyness: perfume "V H 52 1L" sold 0.2 or 0.058 at the till). Built in
steps on `release/2.2.1` (both repos); this page says what each step converted. Steps 1 to 5 are done (2026-10-09 and 10).

## Rules

- **Type**: `BigDecimal`, `DECIMAL(18,3)`, `@Column(precision = Quantities.PRECISION, scale = Quantities.SCALE)`.
  Money stays `Double`.
- **Whole stays whole**: entity getters return `Quantities.normalize(...)` (2.000 is 2, 0.200 is 0.2, never `1E+2`), so
  JSON carries `2`, never `2.000` (ticket copy hashes, EMTOP's NAV export, tickets). The NAV payloads of
  `erp/dynamicsnav` stay `Double`: a whole quantity reaches NAV as before (`2.0`).
- **Who may use decimals**: the store setting `ALLOW_DECIMAL_QUANTITY` (General Setup, "Allow decimal quantities",
  BOOLEAN, `false`, created at startup on a store only; read by `QuantityPolicy`, sent to the front as
  `/config.allowDecimalQuantity`). Off: front and back behave as 2.2.0 and the back refuses a non-whole quantity with a
  message naming the item. More than 3 decimals is refused even when on. No per-item flag in this version.
- **Stay whole for good**: cash counts (`SessionCashCount`, `HoSessionCount`), promotion minimum/free quantities,
  warranties (a warranty on a decimal sales line is refused: "A warranty covers whole units").
- **Rounding, the one place**: `Quantities.lineAmount(unitAmount, quantity)`. A whole quantity gives the plain product of
  2.2.0 (no rounding, identical results); a decimal quantity gives the product rounded to 3 decimals, half up
  (`Quantities.roundAmount`: the product x 1000 read to 15 significant digits first, so 1.0005 gives 1.001).
- **A decimal sale line is computed by the back end** (`SalesHeaderService.applyDecimalLineAmounts`), never taken from
  the till: unit price including VAT x quantity, rounded (the VAT-inclusive amount, so 0.2 L at 50.000 TTC is exactly
  10.000); minus the discount (percentage of that amount, else the fixed amount); then back to HT and VAT, the till's
  steps for every line (`discounts.md`). A parked ticket uses the same unit price and discount as a direct sale.
  Whole lines keep the till's amounts as in 2.2.0. The header totals (subtotal, VAT, total, payments) still come from
  the till, which rounds a decimal line the same way (`src/libs/quantity.js`, `decimalLineTotalInclVat`); no recompute,
  no refusal (Zein, 2026-10-09). For a ticket with a decimal line, `warnIfDecimalLinesDifferFromTotals` only logs a
  warning when subtotal + VAT differs from the sum of the lines (stamp line included) by more than 0.001. The real till
  can differ in two cases, both 2.2.0 behaviour (Zein, 2026-10-10: keep the till, fix after the Happyness go-live, see
  "Left" below): a fixed-amount promotion larger than its line (the till's subtotal and VAT are not stopped at 0, the
  line and the total paid are), and, until step 5, a tax stamp setting read differently (`ENABLE_TAX_STAMP` `1` or `TRUE`,
  or no `TAX_STAMP` item; one rule since step 5: `TaxStampRule`). A ticket discount, a cart promotion or the loyalty deduction never change subtotal or VAT.
- **Fixed-amount discounts**: the 2.2.0 formula, no special case: a fixed promotion or a manual amount counts once per
  line (2.000 off 0.2 as off 2); a cross-product amount counts per unit, min(entitled units, quantity) x amount.
- **A place not converted yet never truncates silently**: it calls `Quantities.wholeOrFail` / `wholeIntOrFail` /
  `wholeLongOrFail`, which throw `IllegalStateException` naming the place ("Ticket ... line 1, copy to the head office:
  quantity 0.2 is not a whole number, and decimal quantities are not supported here yet"). Since step 5 the writers not
  converted refuse first, naming the item (`Quantities.notSupportedYet`), and the remaining whole-number fields of the
  requests and copies refuse 1.5 with a 400 (`WholeQuantityDeserializer`); see step 5.

## Front (`ZSRetail-Front`)

- `src/libs/quantity.js`: `isWholeQuantity`, `roundQuantity` (after + / -), `parseQuantity` ("0,2" or "0.2", above 0,
  at most 3 decimals), `formatQuantity` (2, 0.2, 0.058), `countUnits` (a decimal line counts as 1 article),
  `decimalLineTotalInclVat` (same rounding as the back end), `itemLabel` ("CODE (Name)", as the refusals of the back end).
- Till (`ItemSelection.vue`): with the setting on, the quantity box is a text box (`inputmode=decimal`, 60 px) read by
  `parseQuantity`; a refused value shows a toast and the box shows the line again. Off: the number box and `parseInt` of
  2.2.0. + and - move by 1 (1.5 to 0.5; at 1 or below the line goes, as 1 did); a second scan adds 1 (0.2 to 1.2).
- `Payment.vue` (sale, pending, cart total) and the till's pending-ticket request: a decimal line's TTC from
  `decimalLineTotalInclVat`; whole lines unchanged.
- Receipt (`ReceiptTemplate.vue`): `formatQuantity`, quantity column `white-space: nowrap`.
- General Setup page: the switch, then `/config` is fetched again so the till sees it without a new login.

## Step 1: converted (sale and stock on the store)

`Item.stockQuantity`, `SalesLine.quantity`, `StockMovement.quantity`; `ProcessSaleRequestDTO.SaleLineDTO.quantity`,
`CartCalculateRequestDTO.CartItemDTO.quantity`, `PriceCalculateRequestDTO.quantity`; `SalesHeaderService` (four paths:
sale, park, update parked, complete parked); `StockService`, `StockMovementService`, `ItemRepository` stock updates,
`StockBatchRepository`; `InsufficientStockException` (BigDecimal, null available = unknown); `PricingService` and
`PromotionCalculationService` (thresholds compared as decimals; "buy X get Y" counts floor(quantity / minimum));
`TicketPrintingService` (`%6s` with `Quantities.plain`); the reception page's `storeStock`.

## Step 2: converted (copies of tickets and stock to the head office)

Store: `TicketLineCopyDTO.quantity` (BigDecimal, set from the normalized `SalesLine` quantity), `StockReportDTO.Item.quantity`,
`SupplyPushService.quantityOf`, `StockCopy.quantitySent` (`hol_stock_copy`). Head office: `HoTicketLine.quantity`
(stored as sent, amounts never recomputed), `HoStoreStock.quantity`, `HoNetworkStockService` (store stock as sent, the head
office stock with its decimals). The head office accepts a decimal from a store: `ALLOW_DECIMAL_QUANTITY` is checked at
the sale, in the store. A ticket with whole quantities gives the JSON and the copy hash of 2.2.0 (pinned in
`SalesCopyMapperTest.wholeQuantitiesAsIn220`), so 2.2.0 copies already sent are not sent again. Screens: head office
tickets history (lines) and network stock (head office, stores, own items) show `formatQuantity`. No sum of quantities
exists on those screens or their API.

## Step 3: converted (inventory count)

The file reader (`InventoryFileReader.read(file, allowDecimal)`), `InventoryCountLine` (four DECIMAL(18,3) columns),
`InventoryLineStore`, `InventoryCountService` (merge, validation, summary, lines), `StockService.applyInventoryDifferences`
and `StockMovementService.recordInventory` (BigDecimal differences), the screen `InventoryCountDetail.vue` (counted,
expected, difference, the up / down cards and the confirmation with `formatQuantity`). Rules in `inventory-count.md`,
"The file": a number cell read to 15 significant digits (Excel float noise cleaned), a text "1,34" or "1.34", at most 3
decimals (a 4th: `more than 3 decimals: 1.3405 (code ...)` on its row); setting off: every decimal refused with the
2.2.0 message. Other rules of the file unchanged. The screen has no box to type a counted quantity (the file is the
only input), so nothing to convert there. Counts are not copied to the head office.

The real file "inventaire franchise - import.xlsx" (Happyness, 484 rows, 56 with decimals), imported and validated on a
throwaway copy of the local `happyness_store1` (2026-10-10): setting off, 467 lines: 327 OK, 100 not found, 40 bad
quantity (the decimal rows of known items); setting on: 367 OK, 100 not found (95 barcodes not in the catalogue, 4 short
codes `401`, `171`, `5`, `21`, one line "No code" merging 4 rows), 0 refused; 14 lines merge 2 rows; no 4th decimal in
the file. Validated: every OK item at its counted quantity (1.34, 1.82, 0.08...), 120 movements, +312.838 / -43.16 as
the summary said.

## Step 4: converted (invoices of the ERP to the store's reception)

- Head office: `NavPosPagesMapper` (a warning only above 3 decimals), `HoErpInvoiceLine.quantity` / `quantityReceived`
  (DECIMAL(18,3)), `HoErpInvoiceService` (a quantity kept as the ERP sends it, up to 3 decimals; an item line with more
  holds the invoice, reason `invoice <number>, line <n>: quantity 1.2345 of item <code> has more than 3 decimals`; no
  setting at the head office; unit cost = `Line_Amount / Quantity` invoiced, 5 decimals, as in 2.2.0: 30.000 for 1.5 is
  20; the quantity received never enters the cost), the confirmations (decimals, more than 3 refused), the page
  (`ErpInvoiceDTO`, difference with decimals), `ErpInvoiceCopyDTO`, `DeliveryConfirmationDTO`.
- Invoices held by 2.2.0 for a quantity not whole (hold reason "is not a whole number", quantity not kept): read again
  from the ERP **by number** at the start of each run (`rereadHeldForDecimals`; `findHeldNumbersWithReason`; the chain
  `ErpSynchronizationManager.pullSupplyInvoicesByNumbers` -> `ErpConnector.fetchSupplyInvoicesByNumbers` (default: none)
  -> `NavPosPagesSync.invoicesByNumbers` -> `NavPosPagesRestClient.readInvoicesByNumbers`, `$filter=No eq '..' or ...`,
  20 numbers per GET, the same fields and lines). Each is replaced, in its own transaction, by the invoice as read now and
  then assigned like any other. The new reasons never say "not a whole number": read again once. No such invoice: no
  call. Needed because the read after the highest number never returns an invoice below it.
- Store: `ReceivedDeliveryLine` (sent, received), `ReceptionInputDTO`, `ReceivedDeliveryDTO`, `DeliveryReceptionService`
  (a copy with more than 3 decimals refused; Receive of a document with a decimal sent or typed while
  `ALLOW_DECIMAL_QUANTITY` is off: 409 `<number> cannot be received: line 10000 was sent with the quantity 1.5, and decimal
  quantities are not allowed in this store (General Setup, Allow decimal quantities).`; a whole document never reads the
  setting; the stock goes up by what was received; the 2.2.0 rules unchanged: received less, the late item), the
  purchase invoice (`PurchaseInvoiceLine.quantity` DECIMAL(18,3), as invoiced; `PurchaseInvoiceDetailsDTO`).
- Head office BLs (`HoDeliveryLine`, `DeliveryDTO`, `DeliveryInputDTO`, `DeliveryCopyDTO`, `HoDeliveryService`): carry
  decimals (a decimal received by a store, the head office stock); a BL made at the head office stays whole (1.5 refused
  with the 2.2.0 message, no longer read as 1) because its supply invoice is not converted.
- Whole quantities: the ERP invoice copy, the BL copy and the confirmation give the JSON of 2.2.0, pinned with their
  SHA-256 in `SupplyCopyCompatibilityTest` (the copies down carry no content hash in the code).
- Screens: `DeliveryReception.vue` (the received box: with the setting a text box read by the till's parser, 0 allowed;
  without it the 2.2.0 number box; a document sent with decimals shows why Receive is off), `ErpInvoices.vue` and
  `Deliveries.vue` (quantities, received, difference and stock with `formatQuantity`).
- The real Happyness page FactureFranchise (BC, 2026-10-10, GET only, two reads): HTTP 200 with no invoice for this user,
  so no decimal Quantity could be seen. The connector reads `Quantity` into a BigDecimal: a JSON number 1.5 (or a text
  "1.5") keeps its exact value.

## Step 5: the safety net (readers, writers not converted, guard, tax stamp)

- **Readers** (with decimal sales in the database no screen fails, nothing truncates): `ReportService` (sales report
  `totalQuantity`, stock report `currentQty` and its OUT / LOW status compared as decimals, movement report `qtyIn`,
  `qtyOut`, `netQty`: `BigDecimal`, normalized; `toLong` stays loud for counts), `AnalyticsService.getTopProducts`
  (`quantitySold`), the four report DTOs. Front: `SalesReport.vue`, `StockReport.vue`, `StockMovementsReport.vue`
  (cells and totals with `formatQuantity`, sums with `roundQuantity`), `TicketsHistory.vue` (line quantity),
  `ItemManagement.vue` (stock column), the till's item card (stock), `SplitBillModal.vue` and `TableSelection.vue` (article
  counts with `countUnits`: 0.2 L is 1 article). Checked, nothing to change: the session closing and its report (cash
  counts only), the ticket reprint (`ReceiptTemplate`, step 1), the low-stock signs (from the stock report and the item
  list), the Excel exports of the reports (numbers as received), the head office screens (steps 2 and 4), invoices,
  purchase invoices and warranties (values shown as the server writes them). There is no low-stock job on the server.
- **Writers not converted** refuse a decimal before writing anything, naming the item, with one text
  (`Quantities.notSupportedYet`): "Item VH52-1L (V H 52 1L): the quantity 0.2 has decimals, and decimal quantities are
  not supported in returns yet." Returns (a line sold with decimals, or a decimal quantity asked; `ProcessReturnRequestDTO`
  reads the quantity as `BigDecimal`), invoices from tickets ("Ticket T-...: Item ..."), purchases
  (`ProcessPurchaseRequestDTO` `BigDecimal`), compositions (`ItemComposition` keeps an `Integer` column; a JSON 1.5 is
  kept apart in the transient `decimalQuantity` and refused by `ItemCompositionService.save`), stock adjustments
  (`AdjustStockRequestDTO.delta` `BigDecimal`, 400), the head office's own BL ("Line 1: Item ..."), its supply invoice
  ("<BL number>, line 1: Item ...", 409; with the PER_BL rhythm the text goes to the BL's invoice note). Until 2.2.0 a
  JSON 0.5 in these requests was read as 0 and 1.5 as 1. Front: the same text from `common.decimalQuantity.notSupported`
  (en, fr, ar) on `PurchaseNew.vue`, `KitComponentsManager.vue`, `ItemManagement.vue` (adjust stock), `Deliveries.vue`
  (under the quantity box). The return screen (`ReturnProducts.vue`) lists a decimal line with its quantity, a "Not
  returnable" badge and "0.2 sold with decimals: this line cannot be returned yet. The other lines of the ticket can be
  returned." (no box, no rounding; the server sends it with `returnable: false` and `remainingQuantity: 0`); the whole
  lines work as in 2.2.0 (including its rounding of a typed 1.5 to 2 on a whole line).
- **Excel item import** (`DataImportService`): "0.250 becomes 250" was the `stockQuantity` column: `parseInteger` removes
  every "." and "," (meant for thousands separators, "1,000") before reading, so a text cell 0.250 gave 250 and a number
  cell 0.25 (written "0.25" by `getCellValue`) gave 25, 1.5 gave 15. Now `refuseDecimalStock`: a number cell with
  decimals, or a text cell with a separator that is not a whole number with thousands separators (1.000, 12,500 still
  read 1000 and 12500), refuses the row with its number ("Stock quantity '0.25' is not a whole number: decimal stock
  quantities are not supported in the item import yet, the row is not imported."). Whole numbers are read as before.
  Left as in 2.2.0: a text cell "1.250" is read 1250 (a thousands separator); `minStockLevel`, `defaultVAT` and
  `displayOrder` keep the same reading.
- **Promotions** (`PromotionCalculationService`): a line with a quantity not whole is not eligible for a promotion with a
  minimum quantity or a free quantity (`passesQuantityFilter`), and never counts as a buy line of buy X get Y; the till
  never puts a cross-product discount on a decimal get line (`reconcileCrossAdjustments`, `computeCrossDiscountAmount`).
  Percentage and fixed amount without a minimum: as in 2.2.0. A sale with a free quantity on a decimal line is refused
  by name (`SalesHeaderService.checkQuantities`).
- **Guard, scoped** (Jackson's global setting unchanged): `WholeQuantityDeserializer` on each remaining whole-number
  quantity field of a request or a copy: `ProcessSaleRequestDTO.SaleLineDTO.freeQuantity`, `CloseSessionRequestDTO`
  cash count quantity, `Promotion.minimumQuantity` / `freeQuantity`, `PromotionCopyDTO` (same two),
  `CatalogueItemCopyDTO.Component.quantity`, `ReturnLineCopyDTO.quantity`, `SessionCountCopyDTO.quantity`,
  `SupplyInvoiceCopyDTO.Line.quantity`. 2 and 2.0 (and "2", null) read as in 2.2.0; 1.5 throws `NotWholeQuantity`, and
  `WholeQuantityExceptionResolver` (first resolver, only for that exception) answers 400 with "lines[1].freeQuantity: 1.5
  is not a whole number, and decimal quantities are not supported here yet." The session closing reads its cash counts
  from a map: `CashierSessionAPI.cashCountQuantity` refuses 1.5 the same way. Loyalty points (`delta`) are not
  quantities: unchanged.
- **Tax stamp, one rule** (`TaxStampRule`): active when `ENABLE_TAX_STAMP` reads true (any case) or 1 AND the `TAX_STAMP`
  item exists. The sale adds its line by it; `/config.taxStampActive` sends it; the till (`loadTaxStampConfig`) adds the
  amount only on that boolean (the amount still from `TAX_STAMP_VALUE_MILLIMES`). A store with `true` and the item: as in
  2.2.0. The till's subtotal and VAT of a fixed promotion larger than its line: unchanged (decision (b), after the
  go-live).

## Still whole, and how each reacts to a decimal (after step 5)

| Place | Reaction |
|---|---|
| Returns (`ReturnHeaderService`, `ReturnLine`, `ReturnProducts.vue`) | a line sold with decimals is listed as not returnable; returning it, or asking 0.5 of a whole line: 400 naming the item, nothing written |
| Invoices from tickets (`InvoiceService`, `InvoiceLine`) | 400 naming the ticket and the item, before the invoice number is taken |
| Purchases (`PurchaseHeaderService`, `PurchaseLine`) | 400 naming the item (screen and server) |
| Compositions of a pack (`ItemComposition`) | 400 naming the component (screen and server) |
| Stock adjustment (`ItemAPI.adjustStock`) | 400 naming the item (screen and server) |
| Head office BL made at the head office (`HoDeliveryService`) | 400 "Line n: Item ..." (text under the box on the page) |
| Head office supply invoice (HEAD_OFFICE source, `HoSupplyInvoiceLine`) | a BL received with a decimal: 409 naming the BL, the line and the item; PER_BL: the text in the BL's invoice note |
| Excel item import, stock column | the row refused with its number |
| Promotions with a minimum or a free quantity, buy X get Y | a decimal line is not eligible (never refused, never a free item on a fraction) |
| Cash counts, promotion settings, warranties, free quantity of a sale line, return / session count / supply invoice / catalogue component copies | whole for good: 1.5 refused with a 400 naming the field; a warranty on a decimal line refused |
| EMTOP ticket export (`TicketExportService`) | passes the quantity through (`Double` to NAV): 2 stays 2.0, 0.2 is sent as 0.2 |

Left, decided apart: (b) the till's subtotal and VAT stopped at 0 per line for a fixed promotion larger than its line
(after the Happyness go-live). A store and its head office move to 2.2.1 together.

## Database

`db/2.2.1/update.sql` converts each column with the temporary procedure `#zs_decimal_quantity` (same nullability; indexes
and default constraints dropped and recreated; any other object on the column stops it with its name; already done or
absent: skipped). The columns are listed once in `#zs_221_columns`; each later step adds its own there. APP_VERSION and
the release notes are written only when every listed column present is DECIMAL(18,3) (otherwise an error names the
columns left and the application keeps refusing to start). The script sets its session options (`QUOTED_IDENTIFIER ON`:
sqlcmd starts with it off, which breaks the reading of a filtered index). It may run again: done columns are skipped,
the 2.2.1 notes rewritten. A new database gets `DECIMAL(18,3)` from Hibernate.

Tried on throwaway copies on the local server only (`pos_store_b_221_test`, `pos_headoffice_221_test`,
`happyness_store1_221_test`, `happyness_ho_rehearsal_221_test`, restored from COPY_ONLY backups of `pos_store_b`,
`pos_headoffice`, `happyness_store1` and `happyness_ho_rehearsal`,
after `db/2.2.0`; recreated and run again at the end of each step): a filtered index with a descending key and an
included column and a default constraint come back identical; a check constraint stops its column by name and
APP_VERSION stays; a second run changes nothing; counts and sums of every converted column equal the originals.

## Tests

`QuantitiesTest`, `SaleDecimalQuantityTest` (0.2 and 0.058 at 50.000 the litre, discounts, half a millime, parked
ticket, insufficient stock, refusals with the setting off or absent or 4 decimals, whole quantities identical to 2.2.0,
JSON `2` / `0.2`); `SalesCopyMapperTest.wholeQuantitiesAsIn220` (JSON and hash of 2.2.0);
`SalesCopyReceiverTest.decimalTicketArrivesAsSent` (0.2 and 0.058 over the wire, same quantities and amounts at the head
office); `SupplyRoundTripTest.decimalStockCopiedUp` (9.8 arrives as 9.8, not sent again, 9.6 after a sale of 0.2, head
office page); `InventoryFileReaderTest.decimalsAllowed` / `decimalsNotAllowed`;
`InventoryCountServiceTest.decimalCountImportedAndValidated` (1.34 imported and validated, stock 1.34, the movement holds
the difference, a 4th decimal refused by row and code), `decimalRefusedWhenSettingOff`, `wholeCountSameEitherWay`;
`SupplyErpInvoiceRoundTripTest` (a NAV invoice with 1.5 and 0.25 to B: stock, movement, cost per unit, purchase invoice;
1.2 received of 1.5 shows -0.3 at the head office; setting off refuses; an invoice held by 2.2.0 read again and released),
`HoErpInvoiceServiceTest.held` (1.2345 held with its reason), `NavPosPagesSyncTest.invoicesByNumbers`,
`SupplyCopyCompatibilityTest` (whole quantities: the JSON of 2.2.0). Step 5: `DecimalSafetyNetTest` (reports, dashboard,
return screen line, invoice and composition refusals, the Excel import by row, item and buy X get Y promotions, the guard
and its 400, the global setting unchanged), `TaxStampRuleTest` (true, TRUE, 1; no item), `AppConfigAPITest`
(`taxStampActive`), `SaleCompletionLoyaltyStampTest.stampSettingReadings`, `ReturnRefundLoyaltyTest.decimalSoldLineRefused`
/ `decimalAskedRefused`, `ItemStockAdjustmentTest.decimalDeltaRefused`, `HeadOfficeWarehouseTest.decimalPurchaseRefused`,
`HoDeliveryServiceTest` (1.5 named), `HoSupplyInvoiceServiceTest.decimalReceivedNotInvoiced`, `CashCountQuantityTest`.
