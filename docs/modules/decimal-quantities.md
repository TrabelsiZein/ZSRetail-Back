# Decimal quantities (2.2.1, returns 2.2.2)

Bulk items sold by the litre or the kilo (Happyness: perfume "V H 52 1L" sold 0.2 or 0.058 at the till). Built in
steps on `release/2.2.1` (both repos); this page says what each step converted. Steps 1 to 6 are done (2026-10-09 and 10).
2.2.2 (`release/2.2.2`), step 1: returns with decimal quantities (2026-10-10), see "2.2.2, step 1" below.

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
- **SQL arithmetic casts the quantity parameter** (step 6): SQL Server types a BigDecimal parameter as
  DECIMAL(38, scale of the value), and DECIMAL(18,3) - DECIMAL(38,1) needs more than 38 digits, so the result is cut to
  the parameter's scale without an error (1.742 - 0.2 gave 1.500, 1.442 - 1 gave 0.000 with mssql-jdbc 7.4.1). The stock
  updates (`ItemRepository.addToStockQuantity`, `decrementStockQuantityIfSufficient`, `decrementStockQuantityUnconditional`,
  `StockBatchRepository.addToStockQuantities`) write `CAST(:quantity AS DECIMAL(18,3))`; any new SQL doing arithmetic
  with a quantity parameter must do the same (`StockArithmeticSqlTest`). Plain assignments are not affected.
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
  `CatalogueItemCopyDTO.Component.quantity`, `ReturnLineCopyDTO.quantity` (until 2.2.2, now `BigDecimal`), `SessionCountCopyDTO.quantity`,
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

## 2.2.2, step 1: returns

- **Columns**: `ReturnLine.quantity` (`return_line`, NOT NULL) and `HoReturnLine.quantity` (`ho_return_line`) are
  `BigDecimal` DECIMAL(18,3), read normalized (1.000 is 1). `ReturnLineCopyDTO.quantity` is `BigDecimal` (the whole-only
  guard is gone); `SalesCopyMapper`, `HoSalesCopyMapper`, `ConsolidatedSalesService.returnDetail` pass it as it is.
  `ReturnExportService` gives the normalized quantity to the ERP DTO: a whole return reaches NAV as `1.0` as in 2.2.1,
  0.2 as `0.2` (EMTOP keeps the setting off).
- **Who may return decimals**: a quantity asked goes through `QuantityPolicy.check` (setting off: "Quantity 1.5 of item
  CODE (Name): decimal quantities are not allowed in this store (General Setup, Allow decimal quantities)", 400, nothing
  written; more than 3 decimals refused even when on); a whole quantity never reads the setting. A line sold with
  decimals while the setting is off is not returnable at all, neither all of it nor a whole part
  (`Quantities.notAllowedInStore`: "Item CODE (Name): the quantity 0.5 has decimals, and decimal quantities are not
  allowed in this store (General Setup, Allow decimal quantities)."; Zein, 2026-10-10: no rest that could never be
  returned). The quantity asked can be anything up to what remains on the line (sold minus every earlier return);
  above it: "Return quantity (0.1) cannot exceed remaining returnable quantity (0)" (2.2.1 text, quantities written by
  `Quantities.plain`).
- **Amounts** (`ReturnHeaderService.processReturn`, the earlier returns read once by `ReturnHeaderService.Returned`):
  - a whole quantity of a whole line, no earlier return of the line with decimals: the 2.2.1 formula, unchanged
    (`lineTotal / sold x returned`, not rounded);
  - otherwise (line sold with decimals, quantity with decimals, or an earlier decimal return of the line):
    `Quantities.shareOf` = line amount / sold x returned, rounded once to 3 decimals (`roundAmount`), for the amounts
    excluding and including VAT; the return that takes the last remaining quantity gets what is left instead (line
    amount minus what the earlier returns refunded, rounded to 3 decimals), so the refunds add up to the line paid
    (Zein, 2026-10-10). A decimal sold line has a TTC in millimes, so its TTC parts add up exactly; its HT has 15
    digits (TTC / 1.19), so the HT parts are within half a millime of it.
  - The header discount, the ratio fallback and the loyalty refund factor apply to the sum as in 2.2.1 (the voucher is
    not rounded further); `LoyaltyService.applyReturn` works on the goods amounts, so several returns of one ticket add
    up as before. The stock goes back by the quantity returned (`incrementForReturn`, the movements with it).
- **Ticket details** (`/return-header/ticket-details`): `quantity`, `returnedQuantity`, `remainingQuantity` as
  decimals (whole: `3`, never `3.000`), plus `remainingLineTotalIncludingVat` (what is left of the line TTC) and
  `decimalReturned` (an earlier return of the line had decimals) so the screen computes the amounts as the server. A
  line sold with decimals while the setting is off: listed with `returnable: false` and the reason above.
- **Head office**: the copy of a return arrives with its decimals (a 2.2.1 head office refuses a 0.2 with a 400, the
  store sends it again once the head office is 2.2.2); the network stock follows the store's stock copy (step 2).
- **Front** (`ReturnProducts.vue`): setting on, the quantity box is a text box (`inputmode=decimal`) read by
  `parseQuantity` ("0,2" or "0.2", 0 or empty = nothing); off, the 2.2.1 number box. No `Math.round` any more: a value
  refused (decimals while off, more than 3 decimals, above what remains, not a number) is said under its box and
  blocks "Process return". Amounts with the same rules (`returnShareOf` in `src/libs/quantity.js`, the remaining TTC for
  the return that closes a line). `ReturnsManagement.vue` and `HeadOfficeReturns.vue` show `formatQuantity`; the
  voucher print uses the receipt template (step 1). Screen check follow-up: the return lines of `POST /return-header/process-return`
  and `GET /return-header/{id}/details` carry `vatPercent`, the rate of the line sold (else the item's default rate;
  `ReturnHeaderAPI.returnLineData`, `ReturnLineDataTest`), and both voucher prints (after the return, and again from the
  Returns page) use it: 0.2 of an item at 19% prints "TVA : 19%", no longer 19.02% worked back from the rounded amounts.
  A ticket with nothing left to return says so ("Nothing left to return on this ticket", en/fr/ar) instead of an empty
  table.
- **Reports and dashboards**: nothing to convert: they add up return amounts (`total_return_amount`), never returned
  quantities; the movement report reads `stock_movement` (step 1).
- **Whole returns are as in 2.2.1** (pinned in `ReturnRefundLoyaltyTest.wholeReturnAsIn221` with the values the 2.2.1
  code produced: stored line, copy JSON and hash, NAV return header and line JSON, for sold 3, return 1 then 2).

## Still whole, and how each reacts to a decimal (after step 5)

| Place | Reaction |
|---|---|
| Returns (`ReturnHeaderService`, `ReturnLine`, `ReturnProducts.vue`) | 2.2.2: converted (above). Until 2.2.1: a line sold with decimals was listed as not returnable; returning it, or asking 0.5 of a whole line: 400 naming the item, nothing written |
| Invoices from tickets (`InvoiceService`, `InvoiceLine`) | 400 naming the ticket and the item, before the invoice number is taken |
| Purchases (`PurchaseHeaderService`, `PurchaseLine`) | 400 naming the item (screen and server) |
| Compositions of a pack (`ItemComposition`) | 400 naming the component (screen and server) |
| Stock adjustment (`ItemAPI.adjustStock`) | 400 naming the item (screen and server) |
| Head office BL made at the head office (`HoDeliveryService`) | 400 "Line n: Item ..." (text under the box on the page) |
| Head office supply invoice (HEAD_OFFICE source, `HoSupplyInvoiceLine`) | a BL received with a decimal: 409 naming the BL, the line and the item; PER_BL: the text in the BL's invoice note |
| Excel item import, stock column | the row refused with its number |
| Promotions with a minimum or a free quantity, buy X get Y | a decimal line is not eligible (never refused, never a free item on a fraction) |
| Cash counts, promotion settings, warranties, free quantity of a sale line, session count / supply invoice / catalogue component copies (the return copy until 2.2.1) | whole for good: 1.5 refused with a 400 naming the field; a warranty on a decimal line refused |
| EMTOP ticket export (`TicketExportService`) | passes the quantity through (`Double` to NAV): 2 stays 2.0, 0.2 is sent as 0.2 |

2.2.2, step 4: (b) done: a fixed discount larger than its line is capped at the line, whole or decimal (the till's subtotal and VAT per line, the line's stored `discountAmount` in `ItemSelection.vue` and `Payment.vue`, and `SalesHeaderService.applyDecimalLineAmounts`), so nothing stored is below 0; a discount within the line is unchanged. The cart line and its discount badge show the amount really applied (15.000 on a 10.71 line shows 10.71, `ItemSelection.appliedLineDiscount`); the cart summary's units are the sum of the quantities (0.5 and 1: "2 (1.5 units)"; whole quantities as before). Was: (b) the till's subtotal and VAT stopped at 0 per line for a fixed promotion larger than its line
(after the Happyness go-live). A store and its head office move to 2.2.1 together.

## Database

`db/2.2.1/update.sql` converts each column with the temporary procedure `#zs_decimal_quantity` (same nullability; indexes
and default constraints dropped and recreated; any other object on the column stops it with its name; already done or
absent: skipped). The columns are listed once in `#zs_221_columns`; each later step adds its own there. APP_VERSION and
the release notes are written only when every listed column present is DECIMAL(18,3) (otherwise an error names the
columns left and the application keeps refusing to start). The script sets its session options (`QUOTED_IDENTIFIER ON`:
sqlcmd starts with it off, which breaks the reading of a filtered index). It may run again: done columns are skipped,
the 2.2.1 notes rewritten. A new database gets `DECIMAL(18,3)` from Hibernate.

2.2.2: `db/2.2.2/update.sql` converts `return_line.quantity` and `ho_return_line.quantity` the same way (its own copy of
the procedure, the columns in `#zs_222_columns`). It runs only on APP_VERSION 2.2.1 or 2.2.2 (otherwise an error and
`SET NOEXEC ON`: nothing changed), then writes APP_VERSION 2.2.2 and its notes when both columns present are
DECIMAL(18,3). `DecimalColumnsCheck.COLUMNS` has the 19 columns of both scripts; its message names both.

A 2.2.1 application on a 2.2.0 database does not start: `AppVersionGuard` finds APP_VERSION 2.2.0 (the script
writes 2.2.1 only when every column is converted). For what that guard lets through (APP_VERSION set by hand, an
existing database with an empty APP_VERSION), `DecimalColumnsCheck` (step 6) reads `INFORMATION_SCHEMA.COLUMNS` once at
start-up for the 17 columns of the script (`COLUMNS`, keep both lists the same; an absent table is skipped): a column
not DECIMAL(18,3) (NUMERIC(18,3), which Hibernate gives a table it creates, is the same type and accepted, as by the
script) is named in an error of the log, `QuantityPolicy` and `/config.allowDecimalQuantity` treat the
setting as off, and a decimal is refused with "decimal quantities need the 2.2.1 database script (db/2.2.1/update.sql),
not run on this database"; whole quantities work as in 2.2.0. Hibernate (`ddl-auto=update`) never changes a column type.

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
`HoDeliveryServiceTest` (1.5 named), `HoSupplyInvoiceServiceTest.decimalReceivedNotInvoiced`, `CashCountQuantityTest`. Step 6: `DecimalColumnsCheckTest`, `StockArithmeticSqlTest`.
2.2.2: `ReturnRefundLoyaltyTest` (`decimalSoldLineReturnedInParts`: 0.5 returned 0.2 then 0.3, 0.1 refused, stock,
vouchers, points; `wholeLineReturnedWithDecimals`: 1.5 of 2 refused off, accepted on; `decimalLineReturnedWhole`;
`decimalSoldLineSettingOff`; `decimalsBeyondThreeRefused`; `ticketDetails`; `wholeReturnAsIn221`),
`SalesCopyReceiverTest.decimalReturnArrivesAsSent`, `DecimalSafetyNetTest` (the return screen line with the setting off,
the return copy no longer guarded).
