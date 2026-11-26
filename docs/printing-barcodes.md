# Printing & Barcodes

- Barcode shown on printed sales receipts, returns vouchers, and duplicate ticket prints by rendering `ReceiptTemplate` + `JsBarcode`.
- 3.5.0: with the General Setup setting `TICKET_RENDERER` on `NEW`, the sale ticket (and its split bill), its duplicate
  and the tombola slip print through the ticket templates' renderer (`src/libs/ticket`), the same ticket; `OLD`, the
  default, keeps `ReceiptTemplate`. `roadmap/ticket-templates.md`, "Step 1 — done".
- Cash refund slip (3.1.0, test log I-56): a return refunded in cash (`SIMPLE_RETURN`) prints
  `ReturnProducts.printCashRefundReceipt`, `ReceiptTemplate` with the prop `isRefund`: the voucher's layout without its
  voucher parts and without a barcode ("REMBOURSEMENT", return number, original ticket, date, cashier, returned lines
  TTC, "Remise totale" = lines − amount paid back, its VAT, "REMBOURSÉ EN ESPÈCES"). Every `isRefund` branch comes after
  the voucher's, so the voucher print (`printVoucherReceipt`, `isVoucher`) is unchanged.
- `ReceiptTemplate.vue` now reads company name, logo, address, phone, email, matricule fiscal, and footer note from the Vuex `appConfig/companyInfo` store (loaded from `GET /company-info` at startup). The static `receiptBranding.js` file is no longer used for these values.
