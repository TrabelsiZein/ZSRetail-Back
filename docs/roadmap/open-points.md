# Open points

Gaps seen in the code and decided "not now". Each one names where it lives and what a fix would do; none is built.
When one is taken, it moves to its module doc and the CHANGELOG, and leaves this file.

## Split bill of a table ticket (`SalesHeaderService.splitAndPay`)

Seen 2026-11-02 (cost topic, step 2). Since 3.1.2 the lines paid apart leave the stock with their SALE movements, keep
their discount source and promotion, and take their unit cost (`docs/modules/table-management.md`). What the other two
ways of completing a ticket (`processCompleteSale`, `completePendingSale`) do and the split ticket still does not:

| Gap | Today | What a fix would do |
|---|---|---|
| ERP payment export | No `triggerSessionExportForPayments`: the split ticket's payments get no payment headers / lines for the session export | Call it with the split ticket's payments, as the two others do |
| Loyalty | No `attachLoyaltyMember` / `processLoyaltyPoints`: a split ticket earns and redeems no points (`SplitBillRequestDTO` carries no card) | The card and points in the request, then the same calls |
| RFID tag hook | No `SaleTagHooks.ticketCompleted`: the tags of the lines paid apart are not marked sold | Hand the hook the split lines with their tags |
| Tax stamp | No `addTaxStampLineIfEnabled`: a split ticket has no fiscal stamp line | Add it on the split ticket (and decide whether the rest, paid later, still gets one: it does today) |
| Payment details (seen 2026-11-12, payment methods topic) | The split ticket's payments (`SalesHeaderService.java:797`, "5. Create payments for the split header") skip `validateAdditionalPaymentFields`; `SplitBillModal.vue:256` offers every active method and `:277` sends only the method and the amount: a cheque or a traite is paid without its N° titre, due date, drawer or bank | Ask the method's fields in the modal (or offer only the methods without any), then validate them as the sale does (`SalesHeaderService.java:1042`) |
| Return voucher (seen 2026-11-12) | The same loop takes a return voucher as any method: no voucher number, the voucher is neither checked nor debited (the sale does it, `SalesHeaderService.java:1022-1039`), so its value can be spent again | Leave the return voucher out of the modal, or ask its number and debit it as the sale does; the scanned tickets (3.2.0, step 4) are refused the same way |
| Sale checks (seen 2026-11-13, 3.2.0 step 7) | Nor the cash ceiling (`validateCashPlafond`), the paid total (`checkPaidCoversTotal`) or the change rule (the change is the request's, kept only with cash; a card above the amount is accepted as paid) | The split's payments through the sale's own payment path (`createAndSavePaymentsForSale`), which would close the four rows above at once |

Checked again 2026-11-13 (3.2.0, step 7): the two payment rows above are still open, the scanned tickets are refused
(`refuseScannedTickets`, and the modal leaves them out). Not fixed in step 7: a server check alone would make a split by
cheque or voucher fail with no field to fill; the fix is the modal's fields (the payment page's method card) and the
sale's payment path on the server, one step of its own.

## Item cost in the till's JSON

Logged 2026-11-02. `Item.costPrice` (the CMP) has no `@JsonIgnore`: every item the till reads (search, the item of
each ticket line) carries it. The line cost (`SalesLine.unitCost`) is hidden and sent apart behind
`read:admin-margins`; the item's own cost is not. Hiding it touches the admin item pages, the imports and the head
office copies, which read it.
