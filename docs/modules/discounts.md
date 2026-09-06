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
