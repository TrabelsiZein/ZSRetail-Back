# Discounts

### Discount Calculation Logic
- **Discounts are applied to totals INCLUDING VAT** (not excluding VAT)
- When discount is percentage: `discountAmount = originalTotalIncludingVat * (percentage / 100)`
- When discount is amount: Direct subtraction from `originalTotalIncludingVat`
- New total including VAT: `originalTotalIncludingVat - discountAmount`
- New total excluding VAT: `lineTotalIncludingVat / (1 + vatPercent/100)`
- VAT amount: `lineTotalIncludingVat - discountedLineTotal`

### VAT Calculation
- VAT is calculated on the discounted amount (excluding VAT)
- Formula: `vatAmount = discountedLineTotal * (vatPercent / 100)`
- Total including VAT: `discountedLineTotal + vatAmount`

### Frontend Discount Flow
1. User clicks discount button on cart item in `ItemSelection.vue`
2. Modal opens with options: Percentage (%) or Amount (TND)
3. User enters discount value with real-time preview
4. Discount is calculated from total including VAT
5. Cart item updates with discount badge and chip
6. Order totals recalculate automatically
7. Discount fields stored in cart item (`discountPercentage`, `discountAmount`)
8. When proceeding to payment, complete line data (including discounts and VAT) sent to backend

### Ticket-wide reduction: the ticket's HT and VAT (3.1.0, test log I-48)
- A ticket-wide reduction is a ticket discount, a cart promotion (header `discountAmount` / `discountPercentage`) or
  loyalty points spent (`loyaltyDeductionAmount`, a discount, not a means of payment).
- The **lines keep their amounts before it** (`lineTotal`, `vatAmount`, `lineTotalIncludingVat`): every reader applies
  the reduction itself — the return refund (`ReturnHeaderService`), the ERP ticket export (one `Discount_Percent`), the
  sales report (lines × (1 − header %)), the ticket X / Z and the journal (`SessionReportService.addTicketTtcByRate`).
- The **header** `subtotal` / `taxAmount` carry it: once the sale is saved and the points are known,
  `SalesHeaderService.applyTicketReductionToTotals` spreads the goods total paid (total − fiscal stamp) over the VAT rates
  in proportion to the lines' TTC, with the X / Z's own code (`addTicketTtcByRate`, `vatLines`: TTC per rate to the
  millime, remainder to the largest rate, HT = TTC × 100 / (100 + rate), VAT = TTC − HT). `taxAmount` = the VAT,
  `subtotal` = total − VAT (goods HT + stamp, as the till sends it). So HT + VAT = total on the receipt (it prints the
  server's answer), the ticket, the head office copy, and the same VAT as the X / Z. Example: 4 × 23.681 TTC at 19 %,
  −10 %: 85.252 = 71.640 HT + 13.612 VAT (the till's 79.600 + 15.124 before).
- A ticket without a ticket-wide reduction keeps the till's values, untouched.
- The ERP ticket export's gross (for `Discount_Percent` = (discount + points) / gross) is the lines' TTC, stamp
  included: the till's subtotal + VAT before 3.1.0, so NAV receives the same % as before.
- Tickets stored before 3.1.0 keep the till's header values (no correction script); the X / Z were already right.

### Amounts stored to the millime (3.1.0, test log I-07)
- Every save of a ticket, a line or a payment rounds its money amounts to 3 decimals, half up
  (`Quantities.roundAmount`), in the entities' `roundAmounts` (`@PrePersist` / `@PreUpdate`), so every path (sale,
  parked ticket, split, payment method change) stores the same: `SalesHeader` subtotal, taxAmount, discountAmount,
  totalAmount, paidAmount, changeAmount, loyaltyDeductionAmount; `SalesLine` discountAmount, lineTotal, vatAmount,
  lineTotalIncludingVat; `Payment` totalAmount. **Not rounded**: the unit prices (prices, not amounts; a unit price
  including VAT is often HT × 1.19 with 4 decimals) and the percentages. The calculation itself is unchanged.
- Paid vs total, on the rounded values (`SalesHeaderService.checkPaidCoversTotal`): a sale is refused when the payments
  fall short of the total by more than **0.010**, the till's own tolerance (`Payment.vue`, `isFullyPaid`); before, the
  server checked nothing. A payment of the 2-decimal amount (314.14 for 314.141) still passes, as at the till. None of
  the 995 completed tickets of Store A and `zen_store` (2026-10-17) was more than 0.010 short.
- The change (`changeAmount`): rounded paid − rounded total, with a cash payment only, never below 0 (no more 0.0002 or
  3.5E-15).
- Return vouchers too: `ReturnVoucher.roundAmounts` (voucherAmount, usedAmount); `ReturnVoucherService` reads the
  remaining amount and "fully used" on the millimes and debits the payment's amount rounded, so a voucher and its
  payment carry the same value (a voucher stored before with 21.3129 offers 21.313 and is used up by it).
- A return's total (`ReturnHeader.roundAmounts`, `total_return_amount`) and a session's expected cash
  (`CashierSession.roundAmounts`, `real_cash`) too; `CashierSessionService.calculateRealCash` returns its sum rounded, so
  the closing check compares the value that is stored (191.870, no longer 191.8698 against a count of 191.870).
- Not covered: the return lines' amounts and the counted amounts keep the precision they are sent with.
- Tickets stored before keep their values (no script).

### Backend Processing
- `ProcessSaleRequestDTO.SaleLineDTO` includes discount and VAT fields
- `SalesHeaderService` receives DTO values and stores them in `SalesLine` entity
- If DTO provides values, they are used directly; otherwise calculated from item defaults
- All sales processing methods (`processCompleteSale`, `savePendingSale`, `completePendingSale`) handle discounts consistently

### Database Fields (SalesLine)
- `discount_percentage` (Double, nullable)
- `discount_amount` (Double, nullable)
- `vat_amount` (Double, nullable)
- `vat_percent` (Integer, nullable)
- `unit_price_including_vat` (Double, not null)
- `line_total_including_vat` (Double, not null)
- `notes` field removed
