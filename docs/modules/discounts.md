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
9. Maximum authorized discount: the till refuses a manual line discount above the item's `maximumAuthorizedDiscount`
   (imported from the ERP, NAV's "Maximum Authorized Discount"), only in the till (the backend does not check it).
   General setup `ENFORCE_MAX_AUTHORIZED_DISCOUNT` (BOOLEAN, default `true`, created at startup) turns it off when
   `false` (or `0`); a missing row or a failed read keeps the check. Read when the till opens: reload the till after
   changing it.

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
