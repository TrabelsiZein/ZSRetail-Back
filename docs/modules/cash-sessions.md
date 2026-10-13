# Cash sessions: tickets X and Z

**Status**: 3.1.0. Step A (backend, `478ed95`) and step B (the print code and the buttons of the two session pages,
front `45cfd32`) done; step 2: the cashier's closing ticket is the ticket Z (setting `CASHIER_CLOSING_TICKET`).

A cashier session (`CashierSession`) is `OPENED` by the cashier, `CLOSED` by the cashier with the count of the till
(`SessionCashCount` rows `POS_USER`), then `TERMINATED` when a responsible verifies it (rows `RESPONSIBLE`,
`responsibleClosureCash`). Customers (ParaFendri, ZEN) asked for the "ticket X" (the open session so far) and the
"ticket Z" (the closed session): one 80 mm ticket built from one DTO.

Store application only; nothing on the head office. No table or column was added; the sale, payment, return and
closing code is unchanged. One General setup row (step 2) chooses the ticket the till prints at the cashier's closing.

## Endpoint

`GET /cashier-session/{id}/report-ticket` (`CashierSessionAPI`), read-only, 404 when the session does not exist.
Like the rest of the API it checks no role: the two admin pages that print it (`SessionDashboard.vue`,
`SessionHistory.vue`, admin and responsible) are guarded by the frontend.

Built by `store.service.SessionReportService` into `store.dto.SessionReportTicketDTO`:

| Field | Content |
|---|---|
| `type` | `X` for `OPENED`, `Z` for `CLOSED` and `TERMINATED` |
| header | `sessionNumber`, `status`, `cashierName` (full name, else user name), `openedAt`, `closedAt` (null on X), `openingCash` |
| `sales` | `ticketsCount`, `vatLines[]` (`rate`, `baseHT`, `vatAmount`, `totalTTC`), `totalHT`, `totalVat`, `taxStamp`, `totalTTC`, `lineDiscounts`, `ticketDiscounts`, `loyaltyDiscounts`, `cancelledCount`, `pendingCount` (X only, null on Z) |
| `returns` | `count`, `simpleCount` / `simpleAmount` (SIMPLE_RETURN, cash), `voucherCount` / `voucherAmount` (RETURN_VOUCHER), `totalAmount` |
| `netSales` | `sales.totalTTC - returns.totalAmount` |
| `payments[]` | per method: `code`, `name`, `cash`, `ticketsCount`, `amount` |
| `control[]` | per method: `expected`, `counted`, `difference` (= counted - expected); on X `counted` and `difference` are null |
| `verification` | `TERMINATED` only: `verifiedBy`, `verifiedAt`, `perMethod`, `lines[]` (as `control`) or `totalExpected` / `totalCounted` / `totalDifference` |
| `modifiedAfterClosing`, `paymentChangesAfterClosing` | `PaymentChangeLog` rows on the session's tickets created after `closedAt` |
| `printedAt`, `printedBy` | server time, the logged user |

Dates as `yyyy-MM-dd HH:mm:ss`. Every amount in TND rounded to the millime (HALF_UP). The store name and logo are not
in the DTO: the page takes them from `appConfig/companyInfo`, as the other tickets.

## Rules

**Sales.** The session's tickets, `PENDING` and `CANCELLED` left out (as `getSessionDetails`); `REFUNDED` counts.
`totalTTC` is the sum of `SalesHeader.totalAmount`, the same total as the session pages.

**VAT per rate.** The VAT is only on the lines (`SalesLine.vatPercent`, `lineTotalIncludingVat`), sent by the till.
The ticket-level discount (`SalesHeader.discountAmount`) is **not spread on the lines**, and the header's `subtotal`
and `taxAmount` are the amounts before it (`pos_db_prod`, 2026-10-13: 399 of 980 completed tickets); the loyalty points
are not on the lines either, and `totalAmount` is net of them. So for each ticket:

- `goodsAndStamp(lines)` (`ReturnHeaderService`, package-private, shared): goods TTC and fiscal stamp;
- factor = (`totalAmount` - stamp) / goods TTC; each goods line adds its TTC x factor to its rate;
- a ticket with an amount and no goods line (old data) adds its amount at 0 %.

Then each rate's TTC is rounded to the millime and the rounding remainder goes to the largest rate, so that the rates
add up to `totalTTC - taxStamp` exactly; HT = TTC x 100 / (100 + rate) to the millime, VAT = TTC - HT. Hence
`totalHT + totalVat + taxStamp = totalTTC` to the millime. The fiscal stamp (item `TAX_STAMP`) is its own line, never a
VAT 0 % line.

**Discounts.** Three lines:
- `lineDiscounts`: `SalesLine.discountAmount`, which is the discount **of the whole line, TTC** (the till's
  `lineAmountsInclVat`, the server's `applyDecimalLineAmounts`; `pos_db_prod`: 1 162 discounted lines out of 1 162).
  Exception: the free-item line the server adds for a FREE_QUANTITY promotion (`lineTotalIncludingVat` 0, 100 %) stores
  `unitPrice x freeQuantity`, an HT amount; its TTC value `unitPriceIncludingVat x quantity` is counted instead;
- `ticketDiscounts`: `SalesHeader.discountAmount`;
- `loyaltyDiscounts`: `SalesHeader.loyaltyDeductionAmount` (points are not a payment).

**Returns.** The returns processed in the session (`ReturnHeader.cashierSession`), whatever the session of the ticket
returned.

**Payments.** The tickets' payments (one query, `PaymentRepository.findByCashierSession`). A cash payment holds what the
customer handed over: the cash line is cash payments - the change of the tickets that had a cash payment (as
`calculateRealCash`). A ticket paid with two methods counts under both (`ticketsCount` does not add up). A return
voucher spent is the `RETURN_VOUCHER` method. The sum of the payment lines is `totalTTC`.

**Control.**
- Expected: the cash is the stored `realCash` of a `CLOSED` or `TERMINATED` session (recomputed by
  `recalculateRealCashForClosedSession` when an admin changes a payment method), else
  `CashierSessionService.getExpectedCash` (`calculateRealCash`: opening cash + cash payments - change - simple returns).
  Another method: its payments. The cash line is always there (the opening cash).
- Counted (Z): the `POS_USER` rows per method, the rows without method (the float) as cash, a method with no row = 0. A
  session closed with a total only (no row) has its `posUserClosureCash` as the cash counted, the other methods null.
- The methods in the till's order (`displayOrder`, then code); the name of today's method.

**Verification** (`TERMINATED`): with `RESPONSIBLE` rows (`verifySession` saves the cash without a method), per method
against the same expected amounts; without, `responsibleClosureCash` against **every method's expected amount added up**:
the verification form sends the total of every method, and the session history page compares it with all the methods.

**Modified after closing.** `changePaymentMethod` is allowed on a `CLOSED` session not yet synchronised: `realCash`
moves, the cashier's count does not, so a Z printed again differs from the first one. The ticket then says so.

## Printing (front)

`src/views/admin/session/printSessionReport.js`, the one print code: `printSessionReport(vm, sessionId)` opens the print
window first (a popup is allowed only then), calls the endpoint, writes the 80 mm ticket (`sessionReportHtml`, the
style of the cashier's closing ticket, store name and logo from `appConfig/companyInfo`, amounts with 3 decimals) and
prints. It never throws: a blocked popup or a failed request shows a toast and the window closes. It takes `$i18n`,
`$http`, `$toast` and the company info from the page before the request, so the ticket still prints when the page is
gone by then. Labels: `admin.sessionReport.*`, five locales.

- Order of the blocks: header (title from `type`), VENTES, RETOURS and the net line, PAIEMENTS, CONTRÔLE, the
  responsible's verification, "Modifié après clôture", "Imprimé le … par …".
- CONTRÔLE and the verification per method: two lines per method (the name, then the amounts in three columns of a
  third of the width each under a header line "Attendu / Compté / Écart"; "Attendu" only on a ticket X), 12 px; the
  name beside three amounts did not fit 80 mm.
- The admin pages: a button in the session details of `SessionDashboard.vue` and `SessionHistory.vue`, "Imprimer
  ticket X" for an open session, "Imprimer ticket Z" otherwise (`admin.sessionReport.printX` / `printZ`).

## The cashier's closing ticket (step 2)

`CASHIER_CLOSING_TICKET` (`general-setup.md`): `Z` (the default, also when the row is absent) or `DECLARED`.

- `ItemSelection.vue`, `closeSession`: once `POST /cashier-session/close` answers, with `Z` the till prints the ticket Z
  of the closed session (`printSessionReport(this, data.session.id)`), with `DECLARED` the declared amounts as before
  (`printSessionCloseTicket(sessionCloseTicket)`). The close endpoint still returns `sessionCloseTicket`; the till does
  not read it for a Z. The back end's closing code did not change.
- The session is closed before anything prints: the print is best effort and is not awaited. A blocked popup or a
  printer failure leaves the session closed and the logout goes on.
- The print window is opened when the close answers, the moment the declared ticket opened its window; the ticket's
  request follows at once. Opening it before the close request would leave an empty window open during the cash
  difference warning and the badge scan, and a badge retry calls `closeSession` without a click anyway.
- The logout 1500 ms after the closing is unchanged: the ticket's request is sent with the token before it, the window
  is the browser's, and the print code needs nothing of the page once it started.

## Open points

- **The FREE_QUANTITY line stores an HT discount.** `SalesHeaderService` (sale and parked ticket completed) adds the
  free-item line with `discountAmount = unitPrice x freeQuantity`, the HT amount, where every other line stores the TTC
  discount of the whole line. The ticket X / Z reads its TTC value instead (`SessionReportService.lineDiscount`); the
  promotion code is unchanged for now. No such line in `pos_db_prod` on 2026-10-13. Anything else that sums
  `SalesLine.discountAmount` would mix HT and TTC on these lines.

## Tests

`store/src/test/.../service/SessionReportServiceTest`: X (no closing date, pending, expected only), Z with a ticket
discount over two rates, a stamp and points (exact VAT lines and HT + VAT + stamp = TTC), payments (cash net of change,
two methods, voucher, sum = TTC), returns and net, the cashier's count (float, method not counted), the stored realCash,
a closing with a total only, the verification per method and in total, modified after closing, the free-item line,
rounding over 60 uneven tickets, an empty session, an unknown session, and the JSON of a Z.
