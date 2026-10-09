# Decimal quantities (2.2.1)

Bulk items sold by the litre or the kilo (Happyness: perfume "V H 52 1L" sold 0.2 or 0.058 at the till). Built in
steps on `release/2.2.1` (both repos); this page says what each step converted. Steps 1 to 4 are done (2026-10-09 and 10).

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
  line and the total paid are), and a tax stamp setting read differently (`ENABLE_TAX_STAMP` `1` or `TRUE`, or no
  `TAX_STAMP` item). A ticket discount, a cart promotion or the loyalty deduction never change subtotal or VAT.
- **Fixed-amount discounts**: the 2.2.0 formula, no special case: a fixed promotion or a manual amount counts once per
  line (2.000 off 0.2 as off 2); a cross-product amount counts per unit, min(entitled units, quantity) x amount.
- **A place not converted yet never truncates silently**: it calls `Quantities.wholeOrFail` / `wholeIntOrFail` /
  `wholeLongOrFail`, which throw `IllegalStateException` naming the place ("Ticket ... line 1, copy to the head office:
  quantity 0.2 is not a whole number, and decimal quantities are not supported here yet").

## Front (`ZSRetail-Front`)

- `src/libs/quantity.js`: `isWholeQuantity`, `roundQuantity` (after + / -), `parseQuantity` ("0,2" or "0.2", above 0,
  at most 3 decimals), `formatQuantity` (2, 0.2, 0.058), `countUnits` (a decimal line counts as 1 article),
  `decimalLineTotalInclVat` (same rounding as the back end).
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

## Not converted yet, and how each reacts to a decimal today

| Place | Reaction |
|---|---|
| Returns (`ReturnHeaderService`, `ReturnHeaderAPI`, `ReturnLine`, `ProcessReturnRequestDTO`, `ReturnProducts.vue`) | returning a decimal sales line fails loudly; the return screen still rounds (front) |
| Invoices from tickets (`InvoiceService`, `InvoiceLine`) | fails loudly on a decimal line |
| Reports and dashboard (`ReportService.toLong`, `AnalyticsService.toLong`) | a decimal quantity sum fails loudly |
| Supply invoices of a head office (HEAD_OFFICE source, step 7B: `HoSupplyInvoiceLine`, `SupplyInvoiceCopyDTO`) | a BL line received with a decimal fails loudly when invoiced; a BL made at the head office stays whole |
| Return copies (`ReturnLineCopyDTO`, `HoReturnLine`), session counts (whole for good) | returns of decimal lines already fail at the store |
| Purchases, compositions, stock adjustment (`AdjustStockRequestDTO.delta` Integer), Excel import (`DataImportService.parseInteger` strips "." and ",": "0.250" gives 250, as in 2.2.0) | whole only, as in 2.2.0 |
| EMTOP ticket export (`TicketExportService`) | passes the quantity through (`Double` to NAV): 2 stays 2.0, 0.2 is sent as 0.2 |

Left, decided apart: (b) the till's subtotal and VAT stopped at 0 per line and the tax stamp setting read as the server
reads it (after the Happyness go-live); the global Jackson guard (step 5).

Jackson 2.11 (`ACCEPT_FLOAT_AS_INT` on) still turns 0.2 into 0 for any `Integer` field of a request or a copy: the
places above that read JSON (`ProcessReturnRequestDTO`, `AdjustStockRequestDTO`, the return, BL and invoice copy DTOs) truncate
until converted. A store and its head office move to 2.2.1 together.

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
`SupplyCopyCompatibilityTest` (whole quantities: the JSON of 2.2.0).
