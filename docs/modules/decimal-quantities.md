# Decimal quantities (2.2.1)

Bulk items sold by the litre or the kilo (Happyness: perfume "V H 52 1L" sold 0.2 or 0.058 at the till). Built in
steps on `release/2.2.1` (both repos); this page says what each step converted. Step 1 is done (2026-10-09).

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
  the till, which rounds a decimal line the same way (`src/libs/quantity.js`, `decimalLineTotalInclVat`).
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

## Not converted yet, and how each reacts to a decimal today

| Place | Reaction |
|---|---|
| Ticket copy up (`SalesCopyMapper`, `TicketLineCopyDTO`, `HoTicketLine`) | the copy is not built, with the message; other copies go on |
| Stock copy up (`SupplyPushService`, `StockCopy`, `HoStoreStock`) | a decimal stock stops the stock push (loud, logged) |
| Returns (`ReturnHeaderService`, `ReturnHeaderAPI`, `ReturnLine`, `ProcessReturnRequestDTO`, `ReturnProducts.vue`) | returning a decimal sales line fails loudly; the return screen still rounds (front) |
| Invoices from tickets (`InvoiceService`, `InvoiceLine`) | fails loudly on a decimal line |
| Inventory count (reader, `InventoryCountLine`, `InventoryLineStore`, `InventoryCountService`) | the file still refuses "not a whole number"; a decimal stock makes import/validation/summary fail loudly |
| Reports and dashboard (`ReportService.toLong`, `AnalyticsService.toLong`) | a decimal quantity sum fails loudly |
| Head office: BLs (`HoDeliveryService`), network stock (`HoNetworkStockService`) | a decimal head office stock fails loudly |
| ERP invoices (`NavPosPagesMapper`, `HoErpInvoiceService.whole`) | held, as in 2.2.0 |
| Purchases, compositions, stock adjustment (`AdjustStockRequestDTO.delta` Integer), Excel import (`DataImportService.parseInteger` strips "." and ",": "0.250" gives 250, as in 2.2.0) | whole only, as in 2.2.0 |
| EMTOP ticket export (`TicketExportService`) | passes the quantity through (`Double` to NAV): 2 stays 2.0, 0.2 is sent as 0.2 |

Jackson 2.11 (`ACCEPT_FLOAT_AS_INT` on) still turns 0.2 into 0 for any `Integer` field of a request or a copy: the
places above that read JSON (`ProcessReturnRequestDTO`, `AdjustStockRequestDTO`, the head office copy DTOs) truncate
until converted. A store and its head office move to 2.2.1 together.

## Database

`db/2.2.1/update.sql` converts each column with the temporary procedure `#zs_decimal_quantity` (same nullability; indexes
and default constraints dropped and recreated; any other object on the column stops it with its name; already done or
absent: skipped). Each later step adds its columns there. A new database gets `DECIMAL(18,3)` from Hibernate.

## Tests

`QuantitiesTest`, `SaleDecimalQuantityTest` (0.2 and 0.058 at 50.000 the litre, discounts, half a millime, parked
ticket, insufficient stock, refusals with the setting off or absent or 4 decimals, whole quantities identical to 2.2.0,
JSON `2` / `0.2`).
