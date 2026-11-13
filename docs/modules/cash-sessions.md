# Cash sessions: tickets X and Z

**Status**: 3.1.0. Step A (backend, `478ed95`) and step B (the print code and the buttons of the two session pages,
front `45cfd32`) done; step 2: the cashier's closing ticket is the ticket Z (setting `CASHIER_CLOSING_TICKET`).
3.2.0, step 6: scanned-tickets methods at closing and on the tickets X / Z ("Scanned tickets" below).

A cashier session (`CashierSession`) is `OPENED` by the cashier, `CLOSED` by the cashier with the count of the till
(`SessionCashCount` rows `POS_USER`), then `TERMINATED` when a responsible verifies it (rows `RESPONSIBLE`,
`responsibleClosureCash`). Customers (ParaFendri, ZEN) asked for the "ticket X" (the open session so far) and the
"ticket Z" (the closed session): one 80 mm ticket built from one DTO.

Store application only; nothing on the head office. No table or column was added; the sale, payment, return and
closing code is unchanged. One General setup row (step 2) chooses the ticket the till prints at the cashier's closing.

## Endpoint

`GET /cashier-session/{id}/report-ticket` (`CashierSessionAPI`), read-only, 404 when the session does not exist.
Like the rest of the API it checks no role: the admin page that prints it (`SessionHistory.vue`, admin and
responsible; the sessions dashboard `SessionDashboard.vue` was removed in 3.2.0) is guarded by the frontend.

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
- 3.2.0, POS machines step 3: `?posMachineId=` on both, the same ticket over that POS machine's sessions (the machine in
  its header; 404 for an unknown machine or one without a session that day); with the drawer of the POS machine the
  store's ticket also carries `posMachines`, its sessions grouped per machine with their blocks
  (`pos-machines.md`, "The day per POS machine").
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

**Gains (3.1.2)**: the cost of the goods sold and the gross margin, read from the unit costs frozen on the lines
(`SalesLine.unitCost`, `ReturnLine.unitCost`); no table, column or setting. Only where the store keeps its own stock
and for a role with `read:admin-margins` (`CashierSessionAPI.withMargins`, on the four endpoints above): otherwise
nothing is computed and the fields are absent from the JSON (`@JsonInclude(NON_NULL)`) and from the Excel files.
- `JournalCosts`, one rule for both pages: a ticket's HT is the Journal's (what was paid: each costed line's TTC before VAT
  x (total paid - stamp) / goods TTC, so the ticket discount, the cart promotions and the loyalty points are in it); a
  return's is the Journal's too (the lines' HT x refund / lines' TTC), negative. Cost = sum of quantity x unit cost;
  margin = HT - cost; both over the costed lines only. The tax stamp is left out of everything; a service or a
  discount item (`ItemType.carriesNoCost`) counts at 0; any other line with a null cost is a line without cost (never
  0). A ticket, a return or a day without a costed line: the four fields null. A day (and the .xlsx month total, `StoreDaysService.monthGains`) with a costed line counts the lines without cost of ALL its tickets and returns, those without any costed line included; a ticket or a return keeps its own count. Margin % = margin / HT, one decimal,
  null with a line without cost or an HT of 0. Amounts to the millime, half up. A day's (and a month's) cost and margin
  are the sums of its tickets' and returns' cost and margin each rounded to the millime as shown, so the day equals the
  sum of its rows (before, the rounding of the unrounded sums could differ by a millime: 28.782 vs 28.781); the day
  list and the day journal share the rule (`JournalCosts.day`), so the list still equals the detail. Statistics' margin
  of a period runs the same rule over its days (`StoreDaysService.periodGains`, `docs/modules/reporting.md`).
- Fields `cost`, `margin`, `marginPercent`, `linesWithoutCost` on each ticket and return (`JournalLine`), on the day's
  `Totals` (tickets and returns) and on each `DayRowDTO`. The day journal computes them from the lines it reads (no
  query more); the day list reads the month's lines through one projection per kind
  (`SalesLineRepository.findCostLinesOfFinishedBetween`, `ReturnLineRepository.findCostLinesOfReturnsBetween`, no
  entity) and runs the same rule, so a day's row equals its journal's totals. Nothing existing changes.
- Excel: the journal adds Coût, Marge, Lignes sans coût (the day's on the Net row); the days add Coût, Marge, Marge %,
  Lignes sans coût and the month's total (its % only when no day has a line without cost: HT = margin + cost).
- Front (`StoreDays.vue`, `DayJournalCard.vue`, `printDayJournal.js`, rules in `libs/journal-margins.js`,
  `tools/journal-margins-check.mjs`): Coût / Marge / Marge % on the days, Coût / Marge per ticket and return and the
  day's on the Net row, on `$can('read', 'admin-margins')` and own stock; "—" for an absent value, never 0; the marker
  "n ligne(s) sans coût" when `linesWithoutCost` > 0; tooltips on Coût (the CMP frozen at the sale) and Marge ("Marge
  brute HT = ventes HT - coût des articles vendus (CMP)"). The print shows them on the same condition.
- Tests: `JournalMarginsTest` (a day with sales, a service, a ticket discount and a return; a line without cost: the
  marker, no %; a day before 3.1.2: all null; without margins: absent and the lines never read; the API's rule; the
  list equal to the journal), `JournalCostLinesQueryTest` (the two projections on H2).

## Printing (front)

`src/views/admin/session/printSessionReport.js`, the one print code: `printSessionReport(vm, sessionId)` opens the print
window first (a popup is allowed only then), calls the endpoint, writes the 80 mm ticket (`sessionReportHtml`, the
style of the cashier's closing ticket, store name and logo from `appConfig/companyInfo`, amounts with 3 decimals) and
prints. It never throws: a blocked popup or a failed request shows a toast and the window closes. It takes `$i18n`,
`$http`, `$toast` and the company info from the page before the request, so the ticket still prints when the page is
gone by then. Labels: `admin.sessionReport.*`, fr, en, ar (de and pt removed in 3.2.0).

- Order of the blocks: header (title from `type`), VENTES, RETOURS and the net line, PAIEMENTS, CONTRÔLE, the
  responsible's verification, "Modifié après clôture", "Imprimé le … par …".
- CONTRÔLE and the verification per method: two lines per method (the name, then the amounts in three columns of a
  third of the width each under a header line "Attendu / Compté / Écart"; "Attendu" only on a ticket X), 12 px; the
  name beside three amounts did not fit 80 mm.
- The admin page: a button in the session details of `SessionHistory.vue`, "Imprimer ticket X" for an open session,
  "Imprimer ticket Z" otherwise (`admin.sessionReport.printX` / `printZ`).

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

## The session's ticket count at the till (3.1.1)

`GET /cashier-session/current/ticket-count` answers `{"ticketCount": n}`: the tickets of the current user's open
session, the sales `COMPLETED` (and `REFUNDED` since), 0 without a session (`CashierSessionService.countSessionTickets`,
one COUNT on the index `ix_sales_header_session_status` of `db/3.1.1/update.sql`, which also serves the pending tickets
listed when the till opens). The till shows it in its right rail, catalogue hidden (`../frontend.md`).

**A count, never an amount.** The closing is a blind count: the cashier declares what the drawer holds, the server
compares it with the cash expected and the till only says there is a difference. A session total, its sales or its
returns in money shown at the till would let the cashier work the expected cash out; they stay on the session's tickets
X / Z and the reports.

## Scanned tickets (3.2.0, step 6)

A scanned-tickets method (meal tickets, gift cheques, `PaymentMethod.behaviour` `SCANNED_TICKETS`) is declared and
checked on its papers, never on its counted value: the **number of tickets** and their **face total** (what the cashier
reads on them). Its payments stay the net (the parts applied), in PAIEMENTS, the sales and the ERP as before.

- **Storage, no new column.** The closing row of such a method (`SessionCashCount`): `quantity` = the number of tickets,
  `denominationValue` = `lineTotal` = the face total (`CashierSessionService.countLineTotal`: denomination x quantity for
  every other row). The same at the responsible's verification: the form's line sends `count` beside `amount`
  (`verifySession`). `posUserClosureCash` (and the verification total) add every row's total, so they hold the face
  totals; the per-method comparisons use the face total as expected too, so a total compares like with like.
- **Expected.** `ScannedTicketsSummary.byMethodCode` over the session's `ScannedTicketPayment` rows
  (`ScannedTicketPaymentRepository.findByCashierSession`, sales `PENDING` and `CANCELLED` left out): per method the
  number, the face total, the deduction (face - counted), the excess kept (counted - applied), the net (applied); per
  issuer the number and the face total. face = deduction + excess + net.
- **The till's closing window** (`ItemSelection.vue`): one row per active scanned-tickets method, two fields: the number
  of tickets and the face total. Sent as one row (amount = face total, quantity = tickets). The cash check
  (`ENABLE_CASH_DISCREPANCY_CHECK`) compares the cash only, as for every other method: a difference on tickets never
  stops the closing; it shows on the session's pages and tickets. `CASHIER_CLOSING_TICKET`: `Z` prints the ticket Z
  below; `DECLARED` prints the declared face total with "(n tickets)" (`SessionCloseTicketDTO.PaymentMethodAmount.ticketsCount`).
- **The session's details** (`getSessionDetails`, history and verification pages): a scanned row has `scanned`,
  `systemAmount` = the face total, `netAmount` = its payments, `systemCount`, `posClosureCount` / `respClosureCount`
  (null when the amount was not declared), `deltaCountPOS` / `deltaCountResp`.
- **Tickets X / Z** (session and store, the Journal's day page, the print): CONTRÔLE: the method's line is on the face
  total (expected, counted, difference) with `scanned`, `expectedCount`, `countedCount`, `countDifference` (the second
  line of the method on the print, "n ticket(s)"). A new block per method with tickets, `scannedTickets[]`: `ticketsCount`,
  `faceTotal`, `deduction`, `excess`, `net`, `issuers[]` (`issuerName`, `ticketsCount`, `faceTotal`); printed after
  PAIEMENTS. The store X / Z add the sessions' blocks per method, the issuers merged by name, and the counts of
  CONTRÔLE (counted null when a session did not declare the method).
- **Sessions closed before step 6**: the method has no row: counted and its number null ("—"), the expected shown.
- **Signs** (polish): the block prints the deduction and the excess kept negative: face - deduction - excess = net.
- **The closing window's rows** (polish): the active methods and every method of the session's sales, a switched-off
  one included (`GET /cashier-session/current/payment-methods`, `CashierSessionService.sessionPaymentMethods`: methods
  only, never an amount, so the count stays blind).
- **Head office** (3.2.0, last step): the session copy carries one optional field, `scannedTickets`
  (`SessionScannedCopyDTO`, built by `SalesCopyMapper.session` from `ScannedTicketsSummary`, sales PENDING and
  CANCELLED left out as in the details): per scanned-tickets method, `paymentMethodCode`, `ticketCount`, `faceTotal`,
  `netTotal`; a method declared at the closing without a scanned ticket comes with zeros. The head office keeps them in
  `ho_session_scanned` (`HoSessionScanned`, replaced with the session, as its count lines) and applies the store's rule
  (`ConsolidatedSalesService`): a scanned-tickets row (a method of the same code with behaviour `SCANNED_TICKETS`, or a
  code sent by the store) has the face total as `systemAmount`, `netAmount` and `systemCount` beside, `deltaPOS` /
  `deltaResp` face against face and `deltaCountPOS` / `deltaCountResp` tickets against tickets; the totals row's
  differences are shown. The list's "Diff." adds face - net to the system side (`scannedFaceGap`, as on the store).
  **A copy without the field** (a store before 3.2.0, or a session sent before): the row shows the declared face total
  and number of tickets and no difference ("—"; the totals row's too), and the list shows no difference ("—") for a
  session that declared a scanned-tickets method: never a figure computed on the payments' net. Such a session is not
  sent again for it: the store sends a session again only when it changes (a CLOSED session verified later arrives
  with the values); no production session has scanned tickets before 3.2.0. The list reads the count lines and the
  values of its page in batches (`@BatchSize` on `HoSession`).
- **The sessions list** (final pass): its "Diff." and "Diff. Resp" (`SessionDashboardDTO.cashDifference`,
  `responsibleDifference`: the closure total - (opening cash + sales - cash returns)) add to the system side the
  session's scanned tickets' face - net (`CashierSessionService.scannedFaceGap`), as the details compare a scanned
  method face against face: a right count reads 0 (before, deduction + excess, e.g. +3.80). The sessions report has no
  difference. The head office's sessions list: the rule above (Head office).
- **The sessions dashboard removed** (3.2.0, last step): `SessionDashboard.vue` (in no menu, its own older verification
  form) and its `GET /cashier-session/dashboard` (`getSessionDashboard`, every session of the store read) are gone; its
  addresses `/admin/sessions` and `/responsible/sessions` open the Sessions page (`SessionHistory.vue`), where the
  Responsable verifies (menu Caisse > Sessions, `read:admin-sessions-history`; the form for a CLOSED session not yet
  verified). The search's "Sessions" opens it too (its duplicate "Historique Sessions" removed). Its permissions
  `read:admin-sessions`, `read:responsible-sessions`, `write:responsible-sessions` are removed too: no longer seeded
  (`ZZDataInitializer`) nor listed on the Roles page (`AppRoleAPI`, `RoleManagement.vue`), and deleted from the existing
  roles by `db/3.2.0/update.sql` step 12 (a start never adds them back).

## Scanned tickets taken (3.2.0, step 7)

Admin page "Tickets resto / cadeau encaissés" (menu Caisse, beside Sessions and Journal; not the sale tickets of the
tickets history), read-only, over `scanned_ticket_payment` and its copies taken at the sale. Store only.

- **Access**: `read:admin-scanned-tickets` (Roles page, "Vente"); in ADMIN's set of a new database and given to ADMIN at
  every start of a store (a database of before gets it once); RESPONSIBLE has it on a new database, else ticked on the
  Roles page. The API checks it itself (403); the route and the menu carry it.
- **API** (`ScannedTicketsHistoryAPI`, `ScannedTicketsHistoryService`): `GET /scanned-tickets?dateFrom&dateTo&issuer&
  paymentMethodId&cashierId&barcode&page&size` → `{content, totalElements, totalPages, number, size, totals}`;
  `GET /scanned-tickets/export` (same filters, no page) → `{rows, total, truncated, limit, totals}`, the newest 20,000
  at most (the tickets history's limit); `GET /scanned-tickets/issuers` → the issuers met.
- **Rules**: one row per ticket, newest first by the payment's time (when the ticket was taken). The period is on that
  time (the tickets history's format: `2026-11-13T00:00`, or a day). The issuer is the copy's name, the cashier the user
  who took the payment. A barcode search matches the whole barcode or its last digits (`LIKE '%digits'`, no wildcard
  typed) and leaves the period out. The totals are those of the whole filtered result: tickets, face total, deduction
  (face - counted), counted, applied (what entered the sales), excess kept. One projection query for the rows (no entity
  per row), one for the count and the sums. Every sale status counts (the ticket was taken).
- **Page** (`views/admin/session/ScannedTicketsHistory.vue`): the AdminListPage banner and filters (barcode, dates, issuer,
  method, cashier), the totals above the table, the table (date and time, sale number linking to the sale's details in
  the tickets history `?ticketId=`, session, cashier, method, issuer and code, the barcode in groups of 4 with a copy
  button, printed year, face value, deduction and its %, counted, applied, excess). Excel: the whole filtered result,
  `libs/scanned-tickets-export.js` (`tools/excel-exports-check.mjs`), the barcode kept as text.
- **Later** (the remise step): a status per ticket (in the store, sent, repaid) is a column of `scanned_ticket_payment`
  or a row of a remise table pointing to it; the projection, the filters and the page take one column more.
- **Tests**: `ScannedTicketsHistoryServiceTest` (H2: every ticket and its fields, the period, issuer, method and cashier,
  the barcode whole and by its end, paging, the export and its limit, the issuers, the dates, the 403).

## The session's POS machine (3.2.0, POS machines step 2)

`docs/modules/pos-machines.md`, "The session's POS machine". A session keeps the POS machine registered at the address of
its opening request (none from a PC that is not registered, none for the sessions of before), in the store table
`cashier_session_pos_machine` (not a column of `cashier_session`: the head office and its copies are unchanged). Its
tickets and returns get it through the session. The ticket X / Z of a session prints it in its header (the Z of the
closing too); the sessions list, the session details, the sales history, the returns list and the Journal's day show it
("—" without one), on a store that has registered a POS machine.

Part B, General setup "Le tiroir-caisse appartient" (`CASH_DRAWER_OWNER`): **au caissier** (the default) changes nothing.
**Au poste de caisse**: a session opens only on a registered, active POS machine, one open session per machine, and
selling, putting on hold and returns only from the session's machine (409 `OTHER_POS_MACHINE`, one check in front of the
sales and returns endpoints: the sale, return and closing code is unchanged). **Closing is never restricted**: the
cashier closes their own session from any PC (`POST /cashier-session/close`), a responsible or an admin closes
(`/{id}/close`) and verifies (`/verify`) from any PC. The choice does not change while a session is open. Details and the
endpoint table: `pos-machines.md`, "The drawer belongs to".

Step 3, the day per POS machine, only with the drawer of the POS machine: the Journal's day groups "Sessions de la
journée" per POS machine with a subtotal each ("—" without one), the store's cards staying the total; its print buttons
print the store's ticket X / Z or one machine's (`?posMachineId=`); the journal's A4 print adds the block "Par poste de
caisse" and its Excel a sheet "Postes de caisse", with the page's subtotals (the store ticket's sessions, by their
closing; the journal's lines are unchanged). With the drawer of the cashier, nothing changes. `pos-machines.md`, "The
day per POS machine".

**The closing body (fixed in step 3).** `POST /cashier-session/close` reads every body the same way: the counted lines
(`cashCountLines`, optional), `actualCash` (optional), `notes`, `tillGrant`, `discrepancyConfirmed`, then
`closeSessionWithCashCount`. Until then a body without `cashCountLines` (the old `{actualCash, notes}`, or the till's
`{notes}` when its closing window has no row because its payment methods did not load) went through a closing method
that called the transactional one from inside the service, outside its transaction: the closing's row lock (1.8.0)
answered 500 "no transaction is in progress". That method is removed. `CloseSessionBodyTest`.

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

`ScannedTicketsClosingTest` (3.2.0, step 6): a session with three scanned tickets (two issuers, an excess kept, a
cancelled sale's ticket left out): declared as expected, a wrong face total, a wrong number of tickets, a session closed
before step 6 (not declared), a ticket X, the block (face = deduction + excess + net, PAIEMENTS the net), the
responsible's verification per method, the closing (the row's total is the face total, the cash check ignores it), the
session's details, the verification form's count, the declared closing ticket, the store Z adding two sessions up.

`store/src/test/.../service/StoreReportServiceTest` (the real SessionReportService over several sessions in memory):
Z sums two sessions closed the same day (sales, returns, net, payments, control, float); VAT lines of the same rate
merged and HT + VAT + stamp = TTC; Z ignores a session closed another day and an open one; a session opened yesterday
and closed today in today's Z only; a terminated session and the day's bounds; Z without a session refused with the
date; X with the sessions closed today and the open ones (counted null, pending tickets, a session open since two
days), not yesterday's closed one; a session opened yesterday and closed today in today's X, the X and the Z of today
holding the same sessions; X without a session refused; a method a session did not declare keeps the counted null;
`modifiedAfterClosingCount` on the Z.
