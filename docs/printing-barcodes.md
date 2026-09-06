# Printing & Barcodes

- Barcode shown on printed sales receipts, returns vouchers, and duplicate ticket prints by rendering `ReceiptTemplate` + `JsBarcode`.
- `ReceiptTemplate.vue` now reads company name, logo, address, phone, email, matricule fiscal, and footer note from the Vuex `appConfig/companyInfo` store (loaded from `GET /company-info` at startup). The static `receiptBranding.js` file is no longer used for these values.
