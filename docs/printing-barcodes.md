# Printing & Barcodes

- 3.5.0: every ticket (the sale ticket and its split bill, its duplicate, the return voucher and its reprint, the cash
  refund slip, the warranty ticket and its duplicate, the tombola slip, the tickets X / Z, the closing ticket, the cash
  slips) prints through the ticket templates' renderer (`src/libs/ticket`), each with its template ("Modèles de
  tickets": blocks, width 58 / 80, size, spacing, the POS machine's width first); `modules/ticket-templates.md`. The
  barcodes are drawn by `JsBarcode`, with the values and options of before. `ReceiptTemplate.vue`,
  `receiptPrintStyles.js` and `receiptBranding.js`, named in the lines below, were deleted at step 6 (the lines below
  say what the ticket prints; the new renderer prints the same).
- Cash refund slip (3.1.0, test log I-56): a return refunded in cash (`SIMPLE_RETURN`) prints
  `ReturnProducts.printCashRefundReceipt`, `ReceiptTemplate` with the prop `isRefund`: the voucher's layout without its
  voucher parts and without a barcode ("REMBOURSEMENT", return number, original ticket, date, cashier, returned lines
  TTC, "Remise totale" = lines − amount paid back, its VAT, "REMBOURSÉ EN ESPÈCES"). Every `isRefund` branch comes after
  the voucher's, so the voucher print (`printVoucherReceipt`, `isVoucher`) is unchanged.
- `ReceiptTemplate.vue` now reads company name, logo, address, phone, email, matricule fiscal, and footer note from the Vuex `appConfig/companyInfo` store (loaded from `GET /company-info` at startup). The static `receiptBranding.js` file is no longer used for these values.
