# Printing & Barcodes

- Barcode shown on printed sales receipts, returns vouchers, and duplicate ticket prints by rendering `ReceiptTemplate` + `JsBarcode`.
- Cash refund slip (3.1.0, test log I-56): a return refunded in cash (`SIMPLE_RETURN`) prints
  `ReturnProducts.printCashRefundReceipt`, `ReceiptTemplate` with the prop `isRefund`: the voucher's layout without its
  voucher parts and without a barcode ("REMBOURSEMENT", return number, original ticket, date, cashier, returned lines
  TTC, "Remise totale" = lines − amount paid back, its VAT, "REMBOURSÉ EN ESPÈCES"). Every `isRefund` branch comes after
  the voucher's, so the voucher print (`printVoucherReceipt`, `isVoucher`) is unchanged.
- `ReceiptTemplate.vue` now reads company name, logo, address, phone, email, matricule fiscal, and footer note from the Vuex `appConfig/companyInfo` store (loaded from `GET /company-info` at startup). The static `receiptBranding.js` file is no longer used for these values.
