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
  The free-item line the server adds for a FREE_QUANTITY promotion (`lineTotalIncludingVat` 0, 100 %) is counted at
  `unitPriceIncludingVat x quantity`: the value stored since 3.1.0, and the right TTC value of the lines stored before
  (they hold `unitPrice x freeQuantity`, HT; see "Closed points");
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
- Counted (Z): the `POS_USER` rows per method, the rows without method (the float) as cash. A method without a row
  was not declared: counted and difference null ("—", as the session history's detail); a row of 0 stays 0. Since
  3.1.0 the till sends no row of 0 (an empty line is not sent), so a method declared 0 at the till reads as not
  declared; only an older till or closing leaves rows of 0. A
  session closed with a total only (no row) has its `posUserClosureCash` as the cash counted, the other methods null.
- The methods in the till's order (`displayOrder`, then code); the name of today's method.

**Verification** (`TERMINATED`): with `RESPONSIBLE` rows (`verifySession` saves the cash without a method), per method
against the same expected amounts; without, `responsibleClosureCash` against **every method's expected amount added up**:
the verification form sends the total of every method, and the session history page compares it with all the methods.

**Modified after closing.** `changePaymentMethod` is allowed on a `CLOSED` session not yet synchronised: `realCash`
moves, the cashier's count does not, so a Z printed again differs from the first one. The ticket then says so.

## Store ticket X / Z

The whole store for one day: the session tickets of the day's sessions added up. Back end only for now; nothing is
stored, and the selling and session code is untouched.

- `GET /cashier-session/store-report-ticket/x`: the store ticket X of today (the server's date).
- `GET /cashier-session/store-report-ticket/z?date=YYYY-MM-DD`: the store ticket Z of that date; 400 for a date that
  does not parse.
- 404 with the reason when no session matches. No role check, as the session ticket.
- Test log I-19 (2026-10-17, front only): the Journal's day page does not print the store Z while a session opened on
  or before that day is still open (the X of today lists the sessions open now); it names them ("Sessions still open:
  ..."). The Z itself is unchanged and still shown.

**The date rule.**
- **Z of a date D**: every session `CLOSED` or `TERMINATED` whose `closedAt` is on D (from D 00:00 included to D+1 00:00
  excluded), **whatever its opening**: a session opened the day before and closed after midnight is in the Z of its
  closing day. The open sessions are left out.
- **X**: today, every session **closed today** (`CLOSED` or `TERMINATED`, the Z's rule for today) and every session
  **open now** (`OPENED`, opened any day). After the last closing of the day, the X and the Z of today hold the same
  sessions; a session opened yesterday and closed today is in both. A session open since two days is in today's X with
  all its sales.

`store.service.StoreReportService` (`buildX()`, `buildZ(LocalDate)`), into `store.dto.StoreReportTicketDTO`:
`type`, `date`, `storeName` (`CompanyInformation.companyName`), `sessions[]` (`id`, `sessionNumber`, `cashierName`,
`openedAt`, `closedAt`, `status`, oldest opening first; the admin page opens a session's details with `id`), `sales`, `returns`, `netSales`, `payments[]`, `openingCash`,
`control[]`, `modifiedAfterClosingCount` (Z only, null on the X: how many of the sessions had a payment method
changed after their closing, the session ticket's flag), `printedAt`, `printedBy`. The blocks have the shapes of the
session ticket (`SessionReportTicketDTO`).

How it is computed: one session ticket per session (`SessionReportService.build`, the rules above, the stored realCash
of a closed session), then added up in BigDecimal from their millimes:
- VENTES: counts and amounts added up; the VAT lines of the same rate merged into one (HT, VAT and TTC each added up,
  so HT + VAT = TTC per rate and HT + VAT + stamp = TTC); the pending tickets on the X only (null on the Z).
- RETOURS, the net line and PAIEMENTS: added up, the methods merged by code in the till's order (`displayOrder`).
- CONTRÔLE: expected = the sessions' expected added up; counted = their counts added up, **null on the X**, and null for
  a method one session declared nothing for (a session closed with a total only has the cash alone); difference =
  counted - expected. `openingCash`, the "Fond de caisse" line: the sessions' opening cash added up, for information:
  the cash line's expected amount and count already hold it, as on the session ticket.
- Not carried over: the responsible's verification of each session.

Queries: `CashierSessionRepository.findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc`
(Z) and `findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrStatusOrderByOpenedAtAsc` (X: "And" binds
before "Or", so (closed today) or (open)); then the session ticket's queries per session.

## Journal

Read-only reporting of the store's days (back end; no page yet). Nothing is stored, the selling code is untouched.

**The two date rules.**
- **Tickets and returns by their own date**, whatever their session: every ticket, finished (status neither
  `PENDING` nor `CANCELLED`, so `REFUNDED` counts) or cancelled, by its `salesDate`, as the sales reports
  (`SalesReportService`); a return by its `returnDate`. A ticket parked at 23:50 and finished at 00:10 is on the day
  it was parked (its `completedDate` is not read).
- **The store ticket Z by the sessions closed** (closedAt, "Store ticket X / Z"). So the journal of a day and the Z
  of that day differ when a session crosses midnight: the journal splits its tickets by their date, the Z takes the
  whole session on its closing day.

**`GET /cashier-session/store-days?month=YYYY-MM`** (`StoreDaysService`, `DayRowDTO`), newest first, one row per date
with at least one finished ticket, cancelled ticket, return, session closed or session opened and still open:
`date`, `sessionsClosed` (closed that date, the Z's rule), `sessionsOpen` (opened that date and still open),
`ticketsCount`, `returnsCount`, `cancelledCount`, `totalTTC` (finished tickets), `returnsAmount`, `net`, `inProgress`
(today's row only, while a session is open, whatever its opening day: a session opened yesterday and still open sells into today's journal; `CashierSessionRepository.countByStatus`). A month without anything: `[]`; 400 for a month that does not parse.
Light queries (dates and amounts only): `SalesHeaderRepository.findFinishedTimesAndTotals`,
`findSalesDatesByStatusBetween`, `ReturnHeaderRepository.findReturnTimesAndAmounts`, the Z's session query over the
month, `CashierSessionRepository.findByStatusAndOpenedAtGreaterThanEqualAndOpenedAtLessThan`.

**`GET /cashier-session/day-journal?date=YYYY-MM-DD`** (`DayJournalService`, `DayJournalDTO`): `date`, `storeName`,
`lines[]` in time order, `totals`. 404 when the date has no line, 400 for a date that does not parse.
- A line: `time`, `number`, `type` (`TICKET`, `RETURN`, `CANCELLED`), `sessionNumber`, `cashierName` (the session's
  cashier), `customerName` (null for the passenger customer `PASSENGER_CUSTOMER`, and for a return), `totalHT`,
  `totalVat`, `taxStamp`, `totalTTC`, `payments[]` (`methodName`, `amount`).
- A ticket: HT and VAT as on the session ticket (the ticket discount and the points spread over the rates,
  `SessionReportService.addTicketTtcByRate` and `vatLines`, shared), so HT + VAT + stamp = TTC; `taxStamp` was added
  to the columns asked so that the line adds up. Payments per method, the cash net of the change.
- A return: its amount given back, negative; HT in the proportion of its lines (their HT over their TTC), VAT = the
  rest. Its money out is a payment line, negative: a simple return under the cash method, a return voucher under the
  return voucher method.
- A cancelled ticket: amounts 0, no payment.
- `totals`: `ticketsCount`, `returnsCount`, `cancelledCount`, `totalHT`, `totalVat`, `taxStamp`, `totalTTC` (the
  finished tickets), `returnsAmount`, `net` (= totalTTC - returnsAmount), `payments[]` per method in the till's order:
  the lines' payments added up, returns included, so they add up to `net` (the cash: in, minus change, minus cash
  refunds; the voucher method: vouchers taken, minus vouchers given).
- Queries: `SalesHeaderRepository.findFinishedBetween`,
  `findByStatusAndSalesDateGreaterThanEqualAndSalesDateLessThanOrderBySalesDateAsc`,
  `ReturnHeaderRepository.findByReturnDateGreaterThanEqualAndReturnDateLessThanOrderByReturnDateAsc`, then one query
  each for the lines, payments and return lines (`findBySalesHeaderIn`, `findByReturnHeaderIn`, never with an empty
  list).

**Excel**: `GET /cashier-session/day-journal/excel?date=` (`journal-2026-10-13.xlsx`, 404 as the journal) and
`GET /cashier-session/store-days/excel?month=` (`jours-2026-10.xlsx`), `JournalExcel` (Apache POI, already in core):
one sheet, a header row (French, as the store's printed tickets), one row per line or day, a totals row, amounts
`#,##0.000`. The journal has two totals rows: "Total ventes TTC" (the finished tickets' HT, VAT, stamp, TTC) then
"Net" (the amount columns added up, returns included, so its TTC is the day's net), with the totals per method.

No role check, as the session ticket.

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

## Closed points

- **The FREE_QUANTITY line stored an HT discount (fixed in 3.1.0).** For a "X bought, Y free" promotion the server adds
  the free-item line itself (`SalesHeaderService`, at a sale and when a parked ticket is completed): totals 0, 100 %.
  Its `discountAmount` was `unitPrice x freeQuantity`, the price **without VAT**, where every other line stores the
  discount of the whole line **with VAT**. Example: an item at 10.000 HT, 19 % (11.900 TTC), "2 bought, 1 free": the
  free line stored 10.000 instead of 11.900, 1.900 too low, the VAT of the free piece. No total and no VAT amount was
  wrong; only the discount shown for that line: the receipt and its reprints ("Remise : -10.000 (100%)"), the head
  office's ticket copy and detail. The ERP export sends the 100 % rate, the connectors do not read the amount.
  Fix: `SalesHeaderService.freeLineDiscountAmount` stores `unitPriceIncludingVat x freeQuantity` in both places
  (`FreeLineDiscountTest`). The lines stored before keep their HT value (none in `pos_db_prod` on 2026-10-13); the
  ticket X / Z keeps reading every free line at its TTC value (`SessionReportService.lineDiscount`), right for both.

## Tests

`store/src/test/.../service/SessionReportServiceTest`: X (no closing date, pending, expected only), Z with a ticket
discount over two rates, a stamp and points (exact VAT lines and HT + VAT + stamp = TTC), payments (cash net of change,
two methods, voucher, sum = TTC), returns and net, the cashier's count (float), a method not declared (null) beside one declared 0, the stored realCash,
a closing with a total only, the verification per method and in total, modified after closing, the free-item line,
rounding over 60 uneven tickets, an empty session, an unknown session, and the JSON of a Z.

`DayJournalServiceTest`: a day with two tickets, a cancelled ticket and a return (time order, amounts, cash net of the
change, a ticket of the day before and a pending one left out); totals and per method adding up to net; a return
voucher; the passenger customer gives null; a ticket parked before midnight on the day of its salesDate; an empty day
refused; the Excel file. `StoreDaysServiceTest`: two days newest first with their counts and net; inProgress on today's row only, with any
session open (one opened the day before too); a session closed the next day; an empty month; the Excel file. `FreeLineDiscountTest`: the free line's
discount TTC.

`store/src/test/.../service/StoreReportServiceTest` (the real SessionReportService over several sessions in memory):
Z sums two sessions closed the same day (sales, returns, net, payments, control, float); VAT lines of the same rate
merged and HT + VAT + stamp = TTC; Z ignores a session closed another day and an open one; a session opened yesterday
and closed today in today's Z only; a terminated session and the day's bounds; Z without a session refused with the
date; X with the sessions closed today and the open ones (counted null, pending tickets, a session open since two
days), not yesterday's closed one; a session opened yesterday and closed today in today's X, the X and the Z of today
holding the same sessions; X without a session refused; a method a session did not declare keeps the counted null;
`modifiedAfterClosingCount` on the Z.
