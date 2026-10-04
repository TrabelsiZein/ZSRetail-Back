# ZS Retail — Regression checklist

Companion of `docs/roadmap/head-office-plan.md` (test level L3). The scenarios replayed by hand on the existing profiles to prove that nothing changed. About 45 minutes per profile.

## 1. How to use

**When:** before every merge of a `feature/ho-step-N` branch, on each profile: ERP dev, standalone, franchise pair (the franchise pair on 1.12.x only: its profiles are removed from 2.1, head office plan step 9, task 9.4a). The first run on release 1.12.0 is the baseline (section 7).

**Prepare once per database:**

- One active promotion on an item you can sell, and one cart promotion or promo code.
- One loyalty member with enough points to spend (at least the program minimum).
- One item with stock.
- An ADMIN user and a POS user.

**General Setup values to check before starting:**

| Code | Value | Why |
|---|---|---|
| `LOYALTY_ENABLED` | true | C3, C3b, C4, C6 |
| `ENABLE_SIMPLE_RETURN` | true | C7 |
| `ENABLE_TAX_STAMP` | true | stamp line on receipts and returns |
| `DEFAULT_LOCATION` | set | ticket numbers; ERP imports; franchise receptions |
| `RESPONSIBILITY_CENTER` | set (ERP dev only, not created by default) | E3, E5 |
| `ERP_SYNC_TRACKING_LEVEL` | ALL (ERP dev only) | SUCCESS rows in ERP Logs |

**How to record:** tick each box. Anything different from the expected result is noted in the Baseline table with the scenario id. Behaviour listed in section 6 is not a regression.

**L1** in a scenario means one of the 6 existing test classes already covers part of its rules (not the screens, the database or the printing).

## 2. Common scenarios (every profile)

Run them on the selling instance: the ERP dev instance, the standalone instance, the franchise customer (1.12.x only). C0 also runs on the franchise admin (1.12.x only).

**C0 — Start and login**

- [ ] The application starts with the profile, without errors in the log.
- [ ] Login works for the ADMIN and the POS user.
- [ ] Admin menu entries recorded: one screenshot per profile, referenced in the Baseline notes.

**C1 — Open session**

- [ ] POS user logs in, is sent to the open-session screen, enters an opening float, opens the session.
- [ ] Expected: the till screen (`/pos/items`) opens.
- [ ] Where: `cashier_session` → `status=OPENED`, `opening_cash`, `session_number`.

**C2 — Sale with a promotion** (L1: `PromotionAllItemsScopeTest`, `ItemJsonProxyTest`)

- [ ] Add the promoted item, then reach the cart promotion or enter the promo code; pay.
- [ ] Expected: the discount shows in the cart, in Payment and on the receipt.
- [ ] Where: `sales_header` → `status=COMPLETED`, `discount_source=PROMOTION`, `promotion_id`; `sales_line.discount_source`. A free-quantity promotion adds a line at 100% discount.

**C3 — Sale with loyalty, earning points** (L1: `LoyaltyEarningTiersTest`, `SaleCompletionLoyaltyStampTest`)

- [ ] "Fidélité" → find the member by card, name or phone; pay.
- [ ] Expected: points earned are shown and printed in the loyalty block of the receipt.
- [ ] Where: `sales_header.loyalty_points_earned`; `loyalty_transaction` row `EARNED`; `loyalty_member.loyalty_points` increased.

**C3b — Enrol a new loyalty member** (L1: `LoyaltyMemberPhoneTest`)

- [ ] From the "Carte Fidélité" modal, create a member without a phone, then with 7 digits, then with the phone of an existing member, then with a new 8-digit phone.
- [ ] Expected: refused (phone required), refused (8 digits), refused naming the existing card, accepted.
- [ ] Where: `loyalty_member` → `card_number` is the next `LYL-000000` number, phone saved as 8 digits (spaces and +216 removed).

**C4 — Sale with loyalty, spending points** (L1: `SaleCompletionLoyaltyStampTest`)

- [ ] Member with points; in Payment, use the redemption panel; pay the rest.
- [ ] Expected: the deduction shows on the receipt; the fiscal stamp is still paid in money.
- [ ] Where: `sales_header.loyalty_points_redeemed`, `loyalty_deduction_amount`; `loyalty_transaction` row `REDEEMED`; no `payment` row for the points.

**C5 — Parked ticket resumed** (L1: `SaleCompletionLoyaltyStampTest`)

- [ ] Build a ticket with a loyalty member; "Enregistrer en attente".
- [ ] Expected: `sales_header.status=PENDING`, ticket number already given.
- [ ] Try to close the session: refused while a ticket is parked.
- [ ] "Tickets en attente" → Continuer → pay.
- [ ] Expected: `status=COMPLETED`; points earned and stamp line added once.

**C6 — Return refunded by voucher, then the voucher spent** (L1: `ReturnRefundLoyaltyTest`)

- [ ] "Retours" → load the C4 ticket → return one article as "Bon de retour".
- [ ] Expected: "BON DE RETOUR" printed with number and expiry; the stamp line cannot be returned.
- [ ] Where: `return_header.return_type=RETURN_VOUCHER`; `return_voucher` → amount equal to the refund, `status=PENDING`; `loyalty_transaction` rows `ADJUSTED` / `REVERSED` for the points given back and removed.
- [ ] New sale paid with the voucher (payment method "return voucher").
- [ ] Where: `return_voucher.used_amount` increased, `status=COMPLETED` when fully used; `payment.payment_reference` = voucher number.

**C7 — Return refunded in cash** (L1: `ReturnRefundLoyaltyTest`)

- [ ] "Retours" → load a ticket → "Retour simple (Remboursement en espèces)".
- [ ] Expected: no voucher, nothing printed; the amount is taken off the expected cash in C8.
- [ ] Where: `return_header.return_type=SIMPLE_RETURN`, `status=COMPLETED`.

**C8 — Session closing and its totals**

- [ ] "Fermer la session" → declare the count per payment method → close.
- [ ] Expected: "CLÔTURE DE SESSION" ticket printed (opening float, declared per method, net cash, ticket and return counts); the user is logged out.
- [ ] Where: `cashier_session` → `status=CLOSED`, `real_cash` = float + cash payments − change − cash refunds, `pos_user_closure_cash` = declared total; one `session_cash_count` row per declared line.
- [ ] Responsible: `/admin/sessions` → verify the session.
- [ ] Where: `status=TERMINATED`, `responsible_closure_cash`, `verified_by`.

**C9 — Printing and reprint**

- [ ] Receipts of C2 to C5 show promotion, loyalty block and stamp line; voucher of C6; closing ticket of C8.
- [ ] Reprint one ticket from Tickets History and one return from Returns Management: same content as the original.

## 3. ERP dev

Imports read from NAV and write only to the local database. **E3 to E6 send documents to NAV: test environment only, never run against the real company (GP_HAMMAMI / 005-SCPC).**

Jobs run from ERP → ERP Sync Jobs → open the job → **Run Now** (the scheduler is off in dev).

**E1 — Item and price import**

- [ ] Run Now on `IMPORT_ITEMS`, then `IMPORT_SALES_PRICES_AND_DISCOUNTS`.
- [ ] Expected: job status SUCCESS; a NAV item and its price show at the till.
- [ ] Where: `ERP_SYNC_JOB.last_status`, `last_run_at`; checkpoint moved forward in the job modal; ERP → ERP Logs: `IMPORT_*` rows with status SUCCESS.

**E2 — ERP-mode rules**

- [ ] Item Management has no "Add" button.
- [ ] After C2, `item.stock_quantity` of the sold item is unchanged.

**E3 — Ticket export** (test environment only)

- [ ] Run Now on `EXPORT_TICKETS` after C2 to C5.
- [ ] Expected: Tickets History shows "ERP: <number>" and the synced status.
- [ ] Where: `sales_header.erp_no` set, `synchronization_status=TOTALLY_SYNCHED`; `sales_line.synched=1`; ERP Logs: `EXPORT_TICKET`, `EXPORT_TICKET_LINE`, `UPDATE_TICKET` SUCCESS.

**E4 — Prepare Invoice** (test environment only)

- [ ] Tickets History → an exported ticket → "Prepare Invoice": fiscal registration required, customer name limited to 50 characters.
- [ ] Expected: the ticket goes back to `PARTIALLY_SYNCHED`; after the next `EXPORT_TICKETS`, `TOTALLY_SYNCHED` again.
- [ ] Where: ERP Logs `UPDATE_TICKET` request contains `POS_Invoice` and `Fiscal_Registration`.

**E5 — Return export** (test environment only)

- [ ] Run Now on `EXPORT_RETURNS` after C6 and C7.
- [ ] Where: `return_header.erp_no`, `TOTALLY_SYNCHED`; `return_line.synched=1`; ERP Logs `EXPORT_RETURN`, `EXPORT_RETURN_LINE`, `UPDATE_RETURN` SUCCESS.

**E6 — Session export** (test environment only)

- [ ] After C8 (session `TERMINATED`), Run Now on `EXPORT_SESSIONS`.
- [ ] Where: `cashier_session.erp_no`, `TOTALLY_SYNCHED`; `payment_header` rows synced; ERP Logs `EXPORT_SESSION`, `EXPORT_PAYMENT_HEADER`, `EXPORT_PAYMENT_LINE` SUCCESS.

## 4. Standalone

**S1 — Create an item**

- [ ] Item Management → Add: name, price, family; save. Edit it and add a barcode.
- [ ] Expected: the item is found at the till by its barcode.
- [ ] Where: `item` row; `item_barcode` row.

**S2 — Purchase reception**

- [ ] Purchases → New: vendor, one line with the S1 item, quantity 10; save.
- [ ] Expected: purchase `PUR-…` listed in purchase history.
- [ ] Where: `purchase_header.status=COMPLETED`; `item.stock_quantity` +10; `stock_movement` row `PURCHASE_RECEPTION`; Reports → Stock movements.

**S3 — A sale takes stock down**

- [ ] Sell one S1 item (can be part of C2 or C5).
- [ ] Expected: stock −1 only when the sale is completed; a parked ticket does not move stock.
- [ ] Where: `item.stock_quantity`; `stock_movement` row `SALE`.

**S4 — A return puts stock back**

- [ ] Return the S1 item (C6 or C7).
- [ ] Where: `item.stock_quantity` +1; `stock_movement` row `CUSTOMER_RETURN_*`.

## 5. Franchise pair

**1.12.x only.** The franchise profiles are removed from 2.1 (head office plan, step 9, task 9.4a): a franchise network runs as a head office and stores (`docs/modules/franchise.md`), checked by the head office L2 scripts. Two instances: the admin and the customer. The common scenarios run on the customer; C0 runs on both.

**F1 — Item sync, admin to customer**

- [ ] Admin: create an item with a franchise sales price above 0.
- [ ] Customer: Item Management → "Sync Items".
- [ ] Expected: the item exists on the customer with the "franchise" badge and cannot be edited; its price is the franchise sales price.
- [ ] Where: customer `item` row; Franchise → Sync Dashboard shows the new sync time.

**F2 — Supply reception at the customer**

- [ ] Admin: a customer whose Default Location equals the customer instance's `DEFAULT_LOCATION`; sell the F1 item to that customer; create its invoice in Invoices.
- [ ] Customer: Sync Dashboard → receive supplies.
- [ ] Expected: purchase `FR-<invoice number>` on the customer, stock increased; admin invoice shows "Received At".
- [ ] Where: customer `purchase_header`, `item.stock_quantity`; admin Invoices list.

**F3 — Sales push, customer to admin**

- [ ] Customer: complete a sale (C2) and wait about one minute.
- [ ] Expected: the ticket appears on the admin in Franchise → Sales Tracking, with its lines.
- [ ] Where: customer `sales_header.synchronization_status=TOTALLY_SYNCHED`; admin `franchise_sales_header` row. Returns and sessions are not pushed (expected today).

## 6. Known differences and open points

> **Known differences** (existing behaviour on 1.12.0, not regressions)
>
> - Franchise supply receptions and manual stock adjustments change `item.stock_quantity` without writing a `stock_movement` row.
> - Session closing: the declared total includes non-cash methods, the expected amount covers cash only. With `ENABLE_CASH_DISCREPANCY_CHECK` on, declaring a card amount may raise a discrepancy.
> - On the franchise customer, `/franchise/client/**` (sync items, receive supplies) needs no login.
> - Sales push from a franchise customer sends tickets only, not returns or sessions.
> - `docs/modules/franchise.md` says Sales Tracking filters by date on the server; it filters by location only.

**Not checked yet**

- Split bill: the tickets it creates may not take stock down.
- A change on a barcode only may not reach the franchise customer (sync filters on the item's update date).

## 7. Baseline

| Profile | Date | Version | Result | Notes |
|---|---|---|---|---|
| ERP dev | | 1.12.0 | | |
| Standalone | | 1.12.0 | | |
| Franchise pair (1.12.x only) | | 1.12.0 | | |
