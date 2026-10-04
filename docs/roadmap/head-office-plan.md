# ZS Retail — Head office plan (steps, tasks, tests)

Companion of `docs/roadmap/head-office-design.md`. One step = one session. Status is updated at the end of each session.

## 1. How every step runs

**One step = one new discussion in this project.** Open it with: `Head office plan: start step N`.

Order inside a session:

1. Recap of the step and the decisions it needs.
2. Tasks, one at a time: the Claude Code prompt is given in the chat, you run it and paste the result back.
3. Test block: you run the scenarios on your machine, each one is validated.
4. Check questions.
5. The status table below is updated.

The exact prompts are written during the session, from the code as it is that day.

**Rules for every Claude Code task**

- Inventory first: Claude Code lists the files it will touch and waits for your OK.
- One task = one commit, with explicit pathspecs (never `git add .`, because of the CRLF noise).
- Compile and run `mvn test` before the commit.
- The module doc in `docs/modules/` is updated in the same commit.
- Nothing in `erp/` and nothing in the selling services (`SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`) is touched unless the task says so.

**Branch and release** (decided 2026-10-03)

- Every step has its branch `feature/ho-step-N`, created from `release/2.1.0` in both repos and merged back into `release/2.1.0` with `--no-ff`. The feature branch is kept.
- `release/1.12.0` stays for the customers: their fixes go there, not into the head office work.
- No version bump and no `update.sql` per step. The version becomes 2.1.0 once, at the end of the plan, with one `db/2.1.0/update.sql` (`AppVersionGuard` blocks the start without both).

**Regression** (decided 2026-10-03)

- Per step, before its merge: the tests (`mvn test`, plus the frontend checks of the step) and the diff proof. The diff proof shows that nothing in `erp/`, no franchise file and none of the four selling services changed, and it explains every changed file a store also runs.
- The full regression checklist (`docs/roadmap/regression-checklist.md`) runs once, before 2.1 is delivered. It includes the standalone selling pass skipped at step 1.

**Working rules** (decided 2026-10-03, from step 3)

- During tests Claude Code starts and stops the dev backends itself, on JDK 21.
- L2 is a script through the API and the databases (sqlcmd). The browser is used only for one look per new screen.
- A clear mistake with a clear fix is fixed without asking and listed in the report.
- NAV: never 192.168.10.156 (production). Only the test instance 192.168.10.166, only GET. The VPN is opened by Zein on request.
- Dev environment (from steps 4 and 5): the head office (888, `pos_headoffice`), store B (555, `pos_store_b`) and store C (556, `pos_store_c`) are run by the `devenv` scripts of the backend repo from the unpacked WAR (`build.ps1`, `start.ps1`, `stop.ps1`, `status.ps1`; described in the root `CLAUDE.md`), not by STS. Store A (`standalone-dev`, `pos_db_prod`) is not part of the loyalty tests and its data is not changed.
- After any Maven build by Claude Code, STS needs F5 (refresh) and Project > Clean before its Problems list is valid.

**`db/2.1.0/update.sql`: what it must contain** (written once, at the end of the plan; new `ho_` and `hol_` tables come through `ddl-auto` and are listed when the script is written)

- Step 3: `promotion.origin` `varchar(20)` null (null = local).
- Step 3: `ho_store.owner_catalogue`, `owner_customers`, `owner_promotions`, `owner_loyalty`, `owner_supply` `varchar(20)` null, and `ho_store.sales_upstreams` `varchar(50)` null (null = the store has not reported yet).
- Step 4: `loyalty_member.origin` and `loyalty_program.origin` `varchar(20)` null (null = local); `ho_store.can_edit_members` and `ho_store.can_adjust_points` `bit` null (null = false). New tables through `ddl-auto`: `ho_loyalty_alias`, `ho_loyalty_movement`, `hol_loyalty_member_copy`, `hol_loyalty_movement_copy`.
- Step 5: `ho_store.redeem_requires_online` `bit` null (null = false). The new permission `read:admin-headoffice-loyalty-overspends` is added at startup (no script).
- Enrol switch (2026-10-04): `ho_store.enrol_requires_online` `bit` null (null = false).
- Steps 4 and 5 together: the four `ho_store` switch columns and the two `origin` columns above; no data change (a store's own members are switched off by the first pull, not by the script).
- Step 6: `item.origin`, `item_family.origin`, `item_sub_family.origin`, `item_barcode.origin` `varchar(20)` null (null = local).
- Step 6: `item.own_price` `bit` null (null = false), `item.head_office_price` `float` null.
- Step 6: `ho_store.selling_price_list_id` `bigint` null, `ho_store.may_change_prices` and `ho_store.can_purchase` `bit` null (null = false).
- Step 6: new tables through `ddl-auto`: `ho_price_list`, `ho_price_list_line`, `hol_link_right`. The permission `read:admin-headoffice-price-lists` is added at startup (no script). No data change (a store's items become head office items at the first pull, not by the script).
- Step 7A: no column on an existing table. New tables through `ddl-auto`: `ho_delivery`, `ho_delivery_line`, `ho_number_sequence`, `ho_store_stock` (head office), `hol_delivery`, `hol_delivery_line`, `hol_stock_copy` (store). Two new `stock_movement.movement_type` values (`DELIVERY_OUT`, `DELIVERY_IN`, within `varchar(40)`). The permissions `read:admin-headoffice-vendors`, `-purchases`, `-purchase-new`, `-vendor-balance`, `-purchase-invoices`, `-stock`, `-stock-movements`, `-deliveries`, `-network-stock` (head office) and `read:admin-holink-deliveries` (supply store) are added at startup (no script). No data change.
- Step 7B: `ho_store.deliveries_invoiced` `bit`, `billing_legal_name` `varchar(200)`, `billing_tax_number` `varchar(50)`, `billing_address` `varchar(500)`, `supply_price_mode` `varchar(20)`, `supply_price_list_id` `bigint`, `supply_discount_percent` `float`, `invoice_rhythm` `varchar(20)`, all null; `ho_price_list.kind` `varchar(10)` null (null = SELLING); `ho_delivery.invoice_id` `bigint` and `invoice_note` `varchar(500)` null; `hol_delivery.invoice_number` `varchar(30)` null; `purchase_invoice_header.origin` `varchar(20)` null (null = the store's own). New tables through `ddl-auto`: `ho_item_supply_price`, `ho_supply_invoice`, `ho_supply_invoice_line`. Permissions `read:admin-headoffice-supply-prices`, `-supply-invoices`, `-store-balances` and the head office settings `SUPPLY_INVOICE_TAX_STAMP` (false) and `SUPPLY_INVOICE_TAX_STAMP_MILLIMES` (1000) added at startup (no script). No data change.

## 2. Test levels

| Level | What | When |
|---|---|---|
| L1 | Unit tests: plain JUnit 5 with in-memory stubs, like the 6 existing classes | Every task |
| L2 | Two instances on the dev PC: a head office and a store, each with its own database (same pattern as today's franchise pair: backends on 444 and 888, frontends on 8080 and 8081) | End of every step |
| L3 | Per step: the tests and the diff proof. Full regression checklist on the existing profiles (ERP dev, standalone, franchise pair) | Per step before its merge; the full checklist once, before 2.1 is delivered |

## 3. Overview

| Step | Result | Needed first by | Changes selling code? | Size | Status |
|---|---|---|---|---|---|
| 0 | Foundations: vocabulary and safety net, no behaviour change | Everyone | No | Small | Merged into release/1.12.0 (backend 29c897d, frontend aa82ab3) |
| 1 | Head office installation and stores list: a store shows as online | ParaFendri; Happyness from step 8 | No | Medium | Done 2026-10-03, merged into release/2.1.0 (backend 91cb3a8, frontend de4ed05) |
| 2 | Sales copies up: tickets, returns, sessions of every store visible at head office | ParaFendri; Happyness from step 8 | No | Medium | Done 2026-10-03, merged into release/2.1.0 (backend 3a10957, frontend b90ab84) |
| 3 | Promotions owned by head office | ParaFendri | No (engine untouched) | Medium | Done 2026-10-03, merged into release/2.1.0 (backend 604a97c, frontend 57acf81) |
| 4 | Shared loyalty, part 1: members and earning | ParaFendri | No (enrol and member changes in LoyaltyAPI and LoyaltyService hooks; the four selling services untouched) | Large | Done 2026-10-04, merged into release/2.1.0 with step 5 (backend 27e981c, frontend 07b9970) |
| 5 | Shared loyalty, part 2: spending and returns | ParaFendri | No (decided 2026-10-03: no hold and confirm, selling services untouched) | Large | Done 2026-10-04, merged into release/2.1.0 with step 4 (backend 27e981c, frontend 07b9970) |
| 6 | Items and selling prices decided by the head office: price lists, purchase right | Own stores without ERP, franchise | No | Large | Done 2026-10-04, merged into release/2.1.0 (backend 5f8cb30, frontend 47bd949); task 6.8 (images) removed from 2.1, in section 5 |
| 7A | BLs and stock: head office warehouse, delivery to a store, stock of all stores | Own stores without ERP, franchise | No (stock in only) | Large | Done 2026-10-04, merged into release/2.1.0 (backend e697444; frontend by the frontend session) |
| 7B | Invoices and supply price for the stores that pay | Franchise | No | Medium | Done 2026-10-04, merged into release/2.1.0 (backend 7083298; frontend by the frontend session) |
| 8 | Franchise profiles moved onto the model | Happyness | No | Small (recut 2026-10-04: presets only, no migration) | In progress on feature/ho-step-8 |
| 9 | Cleanup: mode checks replaced by ownership questions | Everyone | Yes, mechanical | Medium | Not started |

Sizes are estimates from reading the code.

- ParaFendri is fully served after step 5. Before that it can already run as independent stores on Business Central, which works today.
- EMTOP is not affected by steps 1 to 8: it has no head office configured.
- Steps 1 and 2 are the base for every customer with a head office, Happyness included. Happyness does not wait for them only because today's franchise profiles already contain an older version of the same two things (one shared key, sales push to the admin). It moves onto steps 1 and 2 at step 8.
- Happyness stays on today's franchise profiles until step 8. Decided on 2026-10-02 (D7): Happyness goes live soon, so the order of the steps does not change.
- Update 2026-10-04 (step 8 recut): Happyness never ran the franchise profiles with real data. It starts on the model, with new databases, installed from the step 8 presets. No install needs a migration.

## 4. Steps

### Step 0 — Foundations

Goal: give the new model its names in the code and build the safety net. Nothing behaves differently.

| Task | What | Test |
|---|---|---|
| 0.1 | Check that `mvn test` runs from the command line for Claude Code (JDK 21 + -Dlombok.version=1.18.36 + -gs settings file, see root CLAUDE.md) | The 6 existing test classes are green |
| 0.2 | Add the design and the plan to the repo under `docs/roadmap/` and index them in the root `CLAUDE.md` | Files visible to a new Claude Code session |
| 0.3 | Write `docs/roadmap/regression-checklist.md`: the scenarios to replay per profile (sale with promotion and loyalty, parked ticket, return, session closing, NAV export; item, purchase and stock in standalone; item sync, reception and sales push in franchise) | You run it once on ERP dev and standalone to record the baseline |
| 0.4 | Add enums `NodeType`, `DataDomain`, `DataOwner`. `ApplicationModeService` gains `getNodeType()`, `ownerOf(domain)` and `salesUpstreams()`, derived from the existing properties when the new keys are absent. No call site changes | L1 truth table: the 4 existing profiles give the expected row of the design table |
| 0.5 | Expose installation type and ownership in `GET /config` and in the frontend `app-config` store (new fields only) | Old fields unchanged in the response |

Done when: all tests green, baseline recorded, and nothing in the application reads the new values yet.

### Step 1 — Head office installation and stores list

Goal: start an instance as head office, register stores, and see each store online. Needs decision D1.

| Task | What | Test |
|---|---|---|
| 1.1 | Profile `headoffice-dev` with `node.type=HEAD_OFFICE` and its own database. Opening a cashier session is refused; the POS entry and selling menus are hidden | L1 guard; L2 the head office has no till |
| 1.2 | Head office: `Store` entity (code, name, kind, API key, active, last contact, version) with the generic CRUD, and a "Stores" admin page | L1 CRUD; page follows the five-point checklist |
| 1.3 | Head office: API key filter on `/ho/**` that identifies the calling store. The legacy franchise filter is not touched | L1 good key, bad key, missing key, inactive store |
| 1.4 | Store: `HeadOfficeClient` and settings `headoffice.url`, `headoffice.api-key`. Every new bean exists only when the URL is set. A heartbeat sends store code and version | L1 no bean without URL |
| 1.5 | Stores page shows last contact and online/offline. Store admin gets a "Head office link" status card | L2 |
| 1.6 | Separate head office routes and menu: a page exists on a head office only when declared in its route file, and a shared page reuses the store component (`meta.twinOf`). Horizontal layout (menu on top) on a head office; the store keeps the vertical one | L1 seed of the head office permissions; frontend script for the router rule and the menu; build |

L2 scenarios: the store appears online; head office stopped, the store keeps selling and shows offline; wrong key is rejected.

Done when: L2 passes, the tests and the diff proof are clean.

**Status: done 2026-10-03.**

| Task | Backend | Frontend |
|---|---|---|
| 1.4 | 2c8f7d4 | — |
| 1.5 | d1911dd | aa49e31 |
| 1.6 | 0a9b55a, then 9618a1e (default profile back to `dynamics-dev`) | ea80103 |
| Merge into release/2.1.0 | 91cb3a8 | de4ed05 |

Backend tests at the merge: 22 classes, 177 tests, all green. The standalone selling pass of the regression checklist was skipped at this step; it is part of the full checklist before 2.1 is delivered.

### Step 2 — Sales copies up

Goal: every ticket, return and session closing of a store reaches the head office, without touching the selling code.

| Task | What | Test |
|---|---|---|
| 2.1 | Store: tracking table and the query that finds documents to send (new, or changed since last push) | L1 a ticket changed after sending is sent again |
| 2.2 | Payloads for ticket (header, lines, payments, loyalty fields), return and session closing, built from codes, not ids | L1 mapper |
| 2.3 | Head office: consolidation tables and receiving endpoints, saved by store code + document number | L1 a repeated push creates one row |
| 2.4 | Store: push job with retry; pending, sent and error counts on the status card | L1 retry after error |
| 2.5 | Tickets history with store (new head office page): list, filters by store and dates, ticket detail | L2 |
| 2.6 | Store: the Head office link page grows into status, jobs (frequency, last run, run now) and an exchange log; own tables, `erp/` untouched | Defined in the session (L1 and L2) |

L2 scenarios: a sale appears at head office; head office stopped, tickets wait then catch up; an ERP store with a head office shows the NAV status and the head office status moving independently.

Done when: L2 passes, and an ERP store's NAV export is unchanged.

**Status: done 2026-10-03.**

| Task | Backend | Frontend |
|---|---|---|
| 2.1 | 17e490b | — |
| 2.2 | 9509038 | — |
| 2.3 | 91f6a85 | — |
| 2.4 | 313c6b1, then 6321761 (a document that leaves its finished status after it was sent is sent again) | — |
| 2.5 | c7d301a, then 65c7f62 (store filter of the consolidated lists) | b3f6174, then cc399b4 (screen check fixes) |
| 2.6 | 2c57c58 | 3399d43 |
| Docs | f85e1b4 | — |
| Merge into release/2.1.0 | 3a10957 | b90ab84 |

What exists after the step:
- Store with `headoffice.url` and `sales.upstream` including `HEAD_OFFICE`: every ticket, return and session closing is tracked in `hol_sales_copy` and pushed by the `SALES_PUSH` job (30 s settle delay, batches, retry, nothing written to the selling tables). The Head office link page shows the status with pending / sent / error counts, the jobs (frequency, run now) and the exchange log.
- Head office: consolidation tables `ho_ticket`, `ho_return`, `ho_session` and their lines, saved by store and document number; pages Tickets history, Sessions, Returns (menu Sales) and the home cards on the consolidated dashboard.
- A store without `headoffice.url`: no new bean; the `ho_` and `hol_` tables exist through `ddl-auto` and stay empty.

Tests:
- Backend at the merge: 35 classes, 265 tests, all green (also on the feature branch before the merge).
- Frontend: build passes. A cache-free production build stops on 200 `no-console` errors in 48 files that step 2 does not touch (already on release/2.1.0); the regular build passes from the ESLint cache. Step 2's own files pass the production lint.
- L2 on this PC (standalone store + head office pair), all passed: a new cash sale reaches the head office (ticket, lines, payment, home cards); a return reaches it linked to its original ticket and a session closing with the same totals as the store; head office stopped: the link goes OFFLINE with one heartbeat row, a sale completes normally, the document stays PENDING with 0 attempts over two push cycles, then is sent once the head office is back, with no duplicate ticket.
- Diff proof against release/2.1.0: 0 files in `erp/`, 0 franchise files, `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService` unchanged; no new code reads or writes `SalesHeader.synchronizationStatus`.
- Not run at this step: the ERP store scenario (NAV status and head office status moving independently), and the full regression checklist (once, before 2.1 is delivered).

### Step 3 — Promotions owned by head office

Goal: a promotion created at head office applies at the tills of the chosen stores. Needs decisions D2 and D3.

| Task | What | Test |
|---|---|---|
| 3.1 | Copies-down mechanism: head office returns the changes since a cursor plus the new cursor; the store pulls per domain according to `ownerOf(domain)` | L1 cursor never comes from the store clock |
| 3.2 | `origin` on `promotion` (default local) with its `update.sql`. Edit and delete of a non-local promotion are refused; the page shows it read-only | L1 guard; local promotions unchanged |
| 3.3 | Promotion payload by codes; store saves by promotion code; head office chooses target stores (all or a list) | L1 mapping; `PromotionAllItemsScopeTest` still green |
| 3.4 | Item list at head office for ERP customers (D2) | L2 item picker works |
| 3.5 | Promotion that targets an item missing in a store: rule and report on the status card | L1 |

L2 scenarios: created at head office, applied at the store till; changed and deactivated at head office, followed by the store; a store outside the list does not receive it.

Done when: L2 passes and the promotion engine files are unchanged.

**Status: done 2026-10-03.**

| Task | Backend | Frontend |
|---|---|---|
| 3.0 lint (`no-console` off in every mode) | — | 1997f0c |
| 3.1 copies down | a3b664e | 836c790 |
| 3.2 origin and guards | ddf2769, then 0683d83 (rule fix: a store that owns its promotions edits every promotion) | 9af050d |
| 3.3 payload by codes, target stores | d0c86c0 | 9af050d |
| 3.4 head office with an ERP | 597f16a, then d19c1ef (dev profile on the NAV test instance) | 09c0ca4 |
| 3.5 missing targets, tracking | b2b6521 | 836c790 |
| 3.6 ownership in the heartbeat | 96cfba0 | 4088ed3 |
| Docs | adb768a | — |
| L2 fixes | 800bd04 (changing a promotion's store list while keeping a store: unique key violation), 1cd57d8 (docs) | 8c7ce9b (an item group promotion kept only one item) |
| Merge into release/2.1.0 | 604a97c | 57acf81 |

Decisions of step 3:
- When the head office owns promotions (`ownership.promotions=HEAD_OFFICE`, needs `headoffice.url`), the store only consults them: create, edit, delete and deactivate answer 409 for every promotion, and the page is consult-only. There is no `promotions.allow-local`.
- Its local promotions are switched off automatically: the first pull sets them inactive, with one exchange log row.
- A store that owns its promotions edits everything in its table, whatever the origin (the origin only shows a "Head office" badge).
- Each store reports what it owns (and where its sales go) with its heartbeat (3.6). The head office picker offers only stores whose promotions are not LOCAL; unknown (older version) can be chosen.
- A head office with an ERP (D2) imports from one reference location, which must hold all the items. It imports locations, families, sub-families, items and barcodes, and never exports: export jobs are switched off at startup and refused by a guard.

What exists after the step:
- Head office: promotions page with target stores (all or a list); every change goes into `ho_down_change` under a per-domain sequence; `GET /ho/down/{domain}` gives the changes since a cursor. The usage count of a promotion is the tickets of every store. Stores page shows what each store owns. With an ERP: ERP jobs, communications log and reference location pages.
- Store that pulls: job `COPIES_DOWN` (thread ho-link), saves by promotion code with origin `HEAD_OFFICE`; a promotion whose item, family or group item is missing waits and is retried; a code used by a local promotion is an error; tracking in `hol_down_record`, cursor in `hol_down_cursor`. The link page shows "Received from the head office".
- A store without `headoffice.url`: no new bean, the promotion API and page work as before; `promotion.origin` exists through `ddl-auto` and stays null. The group promotion fix (8c7ce9b) is an intended change for every store.

Tests:
- Backend at the merge: 45 classes, 334 tests, all green (run in a separate worktree of the feature branch).
- Frontend: lint of the changed files in production mode and `npm run build`, clean; cache-free build proved in a worktree (task 3.0).
- L2 on this PC (standalone store + head office pair), 12 scenarios, all passed: created at the head office and sold at the store till with the promotion; changed and deactivated, followed; store list (outside the list receives nothing, added arrives, taken off is deleted when unused and kept inactive when used); delete and lock of a used promotion; one promotion per type checked with the price calculation API; missing item waits then applies; code clash; local promotions switched off and writes refused; head office stopped, the store keeps its last copy and sells; ownership reported; received counts. Head office with an ERP on the NAV test instance (GET only): export jobs blocked (seen in the log, the API and a forced due job), imports 60 locations, 34 families, 375 sub-families, 1,776 items, 1,542 barcodes.
- Diff proof against release/2.1.0: 0 files in `erp/`, 0 franchise files, `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService` and `PromotionAllItemsScopeTest` unchanged. Every changed file a store also runs is either behind the head office link, pull or owned conditions, or answers as before when `ownership.promotions` is not `HEAD_OFFICE` (no shipped profile sets it).

Still owed:
- The full regression checklist, once, before 2.1 is delivered (with the ERP store scenario of step 2).
- Group promotions already saved in stores keep one item until they are edited again.
- `db/2.1.0/update.sql` lines of step 3 (see section 1).
- Test data of L2 stays in the dev databases (decided 2026-10-03).

### Steps 4 and 5 — decisions (Zein, 2026-10-03)

These decisions replace the tables below where they differ (enrol as a live question in 4.2, enrol refused when the head office is stopped, the hold and confirm of 5.1 and 5.2). The tasks are rewritten at the start of step 4.

- Nothing at the till is ever blocked by the head office being unreachable.
- Enrol: the store always creates the member locally, with a card number that carries the store code. When the head office answers, the phone is checked across the network first. The member is sent up later. A duplicate phone found at the head office is merged: points moved to the existing card, the extra card deactivated.
- Spend: against the store's balance, never blocked by default. When a member is selected at the till, the store asks the head office for the current balance (short timeout). Overspends are listed in a report at the head office. A per-store setting can require the head office online to spend. No hold and confirm, and the selling services stay untouched.
- Per store, set at the head office and enforced there: "can edit members" and "can adjust points". Both go through the head office and need it online.
- A store's members that existed before the switch are switched off. Importing an existing member list is a later tool.
- Enrol switch (Zein, 2026-10-04): per store at the head office, `enrolRequiresOnline` (default false, sent with the heartbeat answer like the rights). When true, the store enrols a member only when the head office answers the phone check of that enrol (503 with a clear message otherwise); sales, earning and finding a member are not affected. Backend 21f1984, L2 scenario 17.
- All the "requires the head office online" switches (`redeemRequiresOnline`, `enrolRequiresOnline`) are off by default (Zein, 2026-10-04): the till keeps working through a long outage; a store is made strict on purpose, on the head office Stores page.
- Step 4 needs a second dev store.

### Step 4 — Shared loyalty, part 1: members and earning

Goal: one member register for the network; points earned anywhere are known everywhere. Needs decision D4.

| Task | What | Test |
|---|---|---|
| 4.1 | Program and members as copies down (keys: program code, card number) | L1 |
| 4.2 | Enrol at the store, never blocked: when the head office answers within 3 s the phone is checked across the network first (a known phone is refused naming its card); otherwise checked at the store. The store creates the member with its own card number (`LYL-<store code>-000001`) and sends it up later; a phone found at the head office on upload is merged into the existing card (alias, points moved). Local loyalty keeps today's path | L1 `LoyaltyMemberPhoneTest` unchanged and green; enrol online, known phone, offline; merge with points moved |
| 4.3 | Loyalty movements travel up with the ticket; head office applies them to its ledger and balances | L1 applying twice changes nothing |
| 4.4 | Store pages: members shared with the head office (edit and deactivate with the right, clear messages for 403, 409, 503), program read-only, POS offers the existing card on a duplicate phone; link page block of what is sent; Stores page rights. Done: frontend adace27, 934e417, ece22dd (described in `docs/modules/head-office.md`, "Step 4 pages (frontend)") | L2 |

L2 scenarios (two stores and a head office): enrol in A, visible in B; earn in A, balance in B after sync; head office stopped, enrol in A still works (checked in A only) and is merged later when the phone was known; earning still works.

**Step 4 backend** (done 2026-10-03, branch `feature/ho-step-4`; the tasks above are replaced by the decisions of steps 4 and 5 and the step 4 prompt). Described in `docs/modules/head-office.md`, "Shared loyalty (step 4)".

| Part | Backend |
|---|---|
| 1. Head office side: register on the copies down (`PROGRAM:`, `MEMBER:`), members up with merge (alias), movements up once with the overspend, phone check, member edit, store rights on the Stores API and in the heartbeat answer, `LYL-HO-000001`, startup check | 2b71f94 |
| 2. Store side: enrol (live phone check, then local, `LYL-<store>-000001`), job `LOYALTY_PUSH`, `LOYALTY` pull (balance = head office + not applied yet), local records switched off, member changes through the head office, program and adjustments refused, `GET /loyalty/network`, link page API | a2c9cc7 |

Choices made in the session (listed in the report to Zein):
- The phone uniqueness at a store counts only the members of the network register held there: the local cards switched off at the switch do not block a number (otherwise a customer with an old card could never enrol).
- A member function travels by code; the side that receives a code it does not have creates it with the name sent.
- A phone held only by a deactivated card is merged into it, as today's rule names a deactivated card.
- A card received from the head office whose number belongs to a local card of the store (only possible with pre-step-4 `LYL-000001` cards at the head office) is not applied: `ERROR`, retried, shown on the link page.
- The store code `HO` is refused on the Stores page (its cards would take the head office's numbers).
- Member deactivation and customer link from a store go through the same head office edit as the form; a blank function there keeps the member's own.
- An answer lost after the head office applied a movement counts it twice at the store until it is sent again (forwards, never backwards); the head office sends the member again on the repeat.

Tests: 49 classes, 382 tests, all green (the four loyalty tests unchanged). Diff proof against release/2.1.0: 0 files in `erp/`, 0 franchise files, the four selling services and the four loyalty tests unchanged. Intended contract changes: the heartbeat answer has two more fields (`canEditMembers`, `canAdjustPoints`), the link status a 13th field (`loyalty`).

Still owed for step 4: the frontend (store loyalty pages, link page block, Stores page rights), L2 with a second dev store (rewritten scenarios: enrol in A visible in B; earn in A, balance in B; head office stopped, enrol in A still works and is merged later; rights; local members switched off), the `update.sql` lines of section 1, the merge into release/2.1.0.

### Step 5 — Shared loyalty, part 2: spending and returns

Goal: points earned in one store can be spent in another. Decided 2026-10-03 (decisions of steps 4 and 5): spending stays against the store's balance and is never blocked by default; the till asks the head office for a fresh balance when a member is selected; overspends are reported at the head office; a per-store setting can require a fresh balance to spend. No hold and confirm: the selling services are not changed. Steps 4 and 5 are delivered together (one L2, one merge).

| Task | What | Test |
|---|---|---|
| 5.1 | Fresh balance at the till: when a member is selected the store asks the head office for the member (short timeout), saves it with the pull's balance rule and answers whether the balance is fresh; head office unreachable: the store's copy, never failing. Head office: overspend report (list and count) of the removals that found the balance lower | L1 fresh balance online and offline; overspend listed once |
| 5.2 | Spending in the sale exactly as today, against the store's balance; its movement goes up (step 4). Per store at the head office, `redeemRequiresOnline` (default false, sent with the heartbeat answer): when true, `LoyaltyService.redeemPoints` (hook, head-office-owned path only) refuses spending unless the member was refreshed from the head office in the last 2 minutes; earning and the sale without points still work. Manual adjustments from a store go through the head office (`canAdjustPoints`). The selling services are not changed | L1 `SaleCompletionLoyaltyStampTest` and `LoyaltyEarningTiersTest` unchanged and green; strict store refuses without a fresh balance and allows with one; a lenient store never refuses; adjustment with and without the right |
| 5.3 | Returns: movements travel up; head office applies them, balance never below zero | L1 `ReturnRefundLoyaltyTest` unchanged and green |
| 5.4 | Receipt and POS messages; pages: fresh balance at the till, adjust points with the right, spending setting on the Stores page and the link page, overspend report and home tile. Done: frontend 25cd826, 581ce28, 0756bb0, 7b0c1e3, and the enrol switch pages 37c50a0 (described in `docs/modules/head-office.md`, "Step 5 pages (frontend)") | L2 |

L2 scenarios: spend in B the points earned in A (fresh balance shown when the member is selected); head office stopped: a lenient store spends against its own balance and the overspend appears in the head office report once the movements arrive, a strict store refuses spending with the message and the sale without points goes through; a partial then full return in A gives the same balance at the head office; a store adjustment with and without the right; with local loyalty everything is identical to today.

**Step 5 backend** (done 2026-10-03, on `feature/ho-step-4`, delivered with step 4). Described in `docs/modules/head-office.md`, "Shared loyalty (step 4)" and "Overspend report".

| Part | Backend |
|---|---|
| 1. Head office: `GET /ho/loyalty/members/{card}`, `POST /ho/loyalty/members/{card}/adjust`, `redeemRequiresOnline` on the store row and in the heartbeat answer, overspend report and its permission | 0b2d3eb |
| 2. Store: `GET /loyalty/member/{id}/fresh`, the `beforeRedeem` guard, adjustments through the head office, returns proved | af952aa |
| 3. Exchange log: a repeated failure of any link job written once, one row when it works again | 22e4891 |
| 4. Docs: plan rows 4.2, 5.1, 5.2, design 4.3 and 5.2 | this commit |

Choices made in the session:
- The freshness of a card is kept in memory: a restart forgets it and the till asks again. A change answered by the head office (edit, adjustment) also makes the card fresh.
- Before the first heartbeat answer after a start (about 15 s), `redeemRequiresOnline` is unknown and spending is not refused.
- The 2-minute window is inclusive (exactly 2 minutes is still fresh).
- The overspend report lists by head office reception time and reuses the filters of the consolidated sales lists; new page permission `read:admin-headoffice-loyalty-overspends` (24 head office permissions).
- The exchange log episode is per job: an exchange of one domain that goes through ends the episode of the copies down job.

Tests: 49 classes, 395 tests, all green; the four loyalty tests unchanged.

**L2 of steps 4 and 5** (2026-10-04, by script, `devenv/l2-loyalty.ps1`; environment in `devenv/`, described in the root `CLAUDE.md`): head office (888, `pos_headoffice`), store B (555, `pos_store_b`, code `STORE-B`) and store C (556, `pos_store_c`, code `STORE-C`), new databases with 20 items copied read-only from `pos_db_prod`; store A not started. 16 scenarios, all passed on the third run (report `C:\zsretail-dev\logs\l2-loyalty-002550.txt`): local loyalty at C; C switched (3 local records off, one row, phone enrolled again); program at B and C, writes 409; enrol at B known at the head office and C; same phone at C refused with `existingCardNumber`; offline enrol at B and C merged (698 on the surviving card everywhere); earn at B in the ledger with the store code; spend at C equal everywhere; fresh balance online and offline; strict store (canRedeem false, 409, sale without points, works when back); overspend (0 everywhere, one row of 34850, count +1); partial then full return equal everywhere; rights (403, 200, 503); head office edit and adjustment reach B and C; one failure row and one recovery row per job; tickets with loyalty fields at the head office.

Bugs found and fixed: 5725e14, the local card numbering (`findMaxCardSequence`) failed on a store back to local loyalty that holds network cards ("Conversion failed"); only all-digit cards count now, same result elsewhere. Earlier commits of the session: cb357dd (enrol 409 fields, step 4 frontend docs), dae555b (devenv and store profiles).

Enrol switch (2026-10-04, backend 21f1984): scenario 17 added (strict enrol at B: head office stopped, enrol 503 with the message and a sale works; back, enrol works). All 17 passed (report `C:\zsretail-dev\logs\l2-loyalty-103425.txt`). Runs 4 to 6 failed scenarios 4, 5, 11 and 12 on a dev environment problem, not the code: right after a head office start, loading classes out of the nested jars of the 162 MB WAR took tens of seconds; a thread dump showed the request holding the receiver's lock in the class loader and the stores' retries queued behind it. The instances now run from the exploded WAR (devenv build.ps1 and start.ps1); starts went from 121 to 214 s down to 77, 36 and 17 s. Observation for production: the head office applies loyalty uploads one at a time; when it is slow, the stores time out after 10 s and resend, which the exactly-once keys make harmless.

Done when: the checklist with local loyalty shows no difference. After this step ParaFendri is fully served.

### Steps 4 and 5 — record

**Status: done 2026-10-04, merged into release/2.1.0 (backend 27e981c, frontend 07b9970).** Delivered together on one branch `feature/ho-step-4`: one L2, one merge. Described in `docs/modules/head-office.md` ("Shared loyalty (step 4)", "Overspend report", "Step 4 pages (frontend)", "Step 5 pages (frontend)") and `docs/modules/loyalty.md`.

| Part | Backend | Frontend |
|---|---|---|
| Head office register: copies down of the program and every member, members up with merge, movements up once (overspend), phone check, member edit, store rights, `LYL-HO-000001`, startup check | 2b71f94 | ece22dd (Stores page rights) |
| Store side: enrol (live phone check, then local, `LYL-<store>-000001`), job `LOYALTY_PUSH`, `LOYALTY` pull (balance = head office + not applied yet), local records switched off, member changes through the head office, program refused, `GET /loyalty/network`, link page API | a2c9cc7 | adace27 (store loyalty pages, POS duplicate card), 934e417 (link page block) |
| Step 5 head office: fresh member, store adjustments, `redeemRequiresOnline`, overspend report and permission | 0b2d3eb | 0756bb0 (Stores page switch), 7b0c1e3 (overspend page and home tile) |
| Step 5 store: fresh balance at the till, spend guard (`beforeRedeem` hook), adjustments through the head office, returns proved | af952aa | 25cd826 (fresh balance at the till, adjust points), 581ce28 (link page setting) |
| Exchange log: a failing link job writes one row when it starts and one when it works again | 22e4891 | — |
| Enrol 409 with `existingCardNumber`, `existingCardActive` | cb357dd | 25cd826 |
| Enrol switch `enrolRequiresOnline` (decision 2026-10-04) | 21f1984 | 37c50a0 |
| Fix found at L2: local card numbering ignores network cards | 5725e14 | — |
| Dev environment (`devenv/`, store B and C profiles; exploded WAR) and `standalone-dev` linked to the dev head office | dae555b, e613cbd, 5b87ffb | — |
| Docs | e6dacd7, 38253cc, 16ba74c, ceca4af, a9481a7 | — |
| Merge into release/2.1.0 | 27e981c | 07b9970 |

Decisions: see "Steps 4 and 5 — decisions" above and the step 5 rows. In short: nothing at the till waits for the head office; enrol is local with the store's card prefix (phone checked across the network when the head office answers; duplicates found later are merged into the card that has the phone, the other card an inactive alias); spending is against the store's balance (overspends reported at the head office); no hold and confirm, the selling services untouched; member edit, deactivation and point adjustment from a store go through the head office with per-store rights; the program is the head office's; a store's members from before the switch are switched off.

**The four per-store switches** (head office Stores page, sent with the heartbeat answer, enforced by the head office or by the store's hook), all **off by default**, so a store keeps working through a long head office outage; a store is made stricter on purpose:

| Switch | When on |
|---|---|
| `canEditMembers` | The store may edit and deactivate members (through the head office) |
| `canAdjustPoints` | The store may adjust points (through the head office) |
| `redeemRequiresOnline` | Spending needs a balance refreshed from the head office in the last 2 minutes |
| `enrolRequiresOnline` | Enrolling needs the head office's answer to the phone check (503 otherwise) |

What exists after the steps:
- Head office: the loyalty pages own the network register (program, members, ledger with the stores' movements as `STORE:<code>`); tables `ho_loyalty_alias`, `ho_loyalty_movement`; `/ho/loyalty/*` for the stores; the overspend report and its home tile; the four switches on the Stores page.
- Store with `ownership.loyalty=HEAD_OFFICE`: members and program received (`LOYALTY` copies down), enrol, earning, spending and returns as today plus the job `LOYALTY_PUSH` (tables `hol_loyalty_member_copy`, `hol_loyalty_movement_copy`); the fresh balance at the till; the pages consult-only except what the rights allow; the link page shows what is sent.
- Store with local loyalty, or without a head office: unchanged (no new bean; the four loyalty tests unchanged).

Tests at the merge: backend 50 classes, 399 tests, all green, run in a separate worktree of the branch head. Frontend: production lint and build per frontend commit (frontend session).

L2 (2026-10-04, `devenv/l2-loyalty.ps1`, head office 888, store B 555, store C 556): 17 scenarios, all passed (report `C:\zsretail-dev\logs\l2-loyalty-103425.txt`): 1 local loyalty at C as before; 2 C switched, local members off with one log row, a phone enrolled again; 3 program at B and C, program writes 409; 4 enrol at B known at the head office and C; 5 same phone at C refused with `existingCardNumber`; 6 offline enrol at B and C merged, one surviving card with both stores' points, equal everywhere; 7 earning at B in the head office ledger with the store code, right at C; 8 spending at C of points earned at B, equal everywhere; 9 fresh balance online true, offline false with spending allowed; 10 strict store: canRedeem false, 409, sale without points, spending back when online; 11 overspend: 0 everywhere, one overspend row with the right points, the count agrees; 12 partial then full return, equal after each sync; 13 rights 403, 200, 503; 14 head office edit and adjustment reach B and C; 15 one failure row and one recovery row per job; 16 tickets with loyalty fields at the head office; 17 strict enrol 503 offline, sale works, enrol works when back.

Diff proof against release/2.1.0 (before the merge): 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, the four loyalty tests (`LoyaltyMemberPhoneTest`, `LoyaltyEarningTiersTest`, `SaleCompletionLoyaltyStampTest`, `ReturnRefundLoyaltyTest`) and `PromotionAllItemsScopeTest` unchanged. Changed files a store also runs, and why a store with local loyalty or without a head office behaves as before:

| File | Why unchanged for such a store |
|---|---|
| `LoyaltyService` | Calls `LoyaltyNetworkHooks` only when such a bean exists (head office, or loyalty owned by the head office); none otherwise. `normalizePhone` public, the duplicate message extracted, same text |
| `LoyaltyAPI` | New branches only when `StoreLoyaltyNetwork` exists (loyalty owned by the head office) |
| `LoyaltyMemberRepository` | New query methods; `findMaxCardSequence` counts only all-digit cards: same result where only `LYL-000001` cards exist (250 = 250 on `pos_db_prod`, read only) |
| `LoyaltyProgramRepository` | A new query method only |
| `LoyaltyMember`, `LoyaltyProgram` | A nullable `origin` column (`ddl-auto`), not read with local loyalty |
| `ApplicationModeService`, `NodeOwnership` | A new getter; an explicit `ownership.loyalty=HEAD_OFFICE` without the URL is refused (no shipped profile sets it); the new interval key is checked only with the URL |
| `ZZDataInitializer` | One permission added to the head office list only |
| `headoffice/*` (`Store`, `StoreService`, `HeadOfficeHeartbeatAPI`) | Head office beans only; `ho_store` exists empty on a store |
| `holink/*` (`HeadOfficeClient`, `HeadOfficeLinkAPI`, `HeadOfficeCallResult`, `HeadOfficeLinkStatusDTO`, `HeadOfficeLinkStatus`, `LinkJobScheduler`, `SalesPushJob`, `CopiesDownPuller`, `LinkExchangeLog`) | Exist only with `headoffice.url`. With the URL and local loyalty: the new heartbeat fields are read and unused, the link status `loyalty` is null; the exchange log writes a repeated failure once (intended for every link job, step 5 F) |
| `application-standalone-dev.properties` | Dev profile only (store A linked to the dev head office, on request) |

Frontend shared pages changed, each with its guard: `LoyaltyMembersManagement.vue`, `LoyaltyProgramManagement.vue`, `ItemSelection.vue`, `Payment.vue` — `loyaltyFromHeadOffice` (not a head office and `/config` `ownership.LOYALTY === 'HEAD_OFFICE'`); `fresh` checks only then, `loyaltyCanRedeem` true without one. `Home.vue` — overspend tile and columns only on a head office with the permission (store columns unchanged, `xl=3`). `HeadOfficeLinkStatus.vue` — the loyalty block only when `GET /admin/holink/status` gives `loyalty`. Head office only: `StoresManagement.vue`, `LoyaltyOverspends.vue`, the head office routes and menu. Labels added in `en`, `fr`, `ar`.

**Production note**: the head office applies loyalty uploads (members, movements, member changes) one at a time, to keep the phone checks and the balances right. When it is slow, stores time out after 10 s and resend, which the exactly-once keys make harmless, but uploads of all stores queue behind each other: consider it when sizing the head office server.

Still owed to the full L3 (once, before 2.1 is delivered): the regression checklist on the existing profiles (ERP dev, standalone, franchise pair) with local loyalty, including a sale with points, a return and a parked ticket; the screen check of the steps 4 and 5 pages on the dev pair; the `update.sql` lines of section 1.

### Steps 6 and 7 — recut (2026-10-04)

Steps 6 and 7 are recut from the design version 2 (`docs/roadmap/head-office-design.md`, sections 2.2 and 3.3 to 3.6), written after the design discussion with Zein on 2026-10-04. Step 7 is split into 7A and 7B so that steps 8 and 9 keep their numbers.

Decisions that the tasks below apply:

- No own / franchise label. A franchise store is a store whose deliveries are invoiced. Every difference between stores is a setting on the store's row.
- Selling price: a base price on the item, plus price lists at the head office; one list per store or none; the head office works out one price per item for each store. The store never sees a list and `PricingService` is not changed.
- "May change its selling prices", per store, off by default.
- "Can purchase from its own suppliers", per store, off by default. It covers the store's own items only; head office items come only from the head office. A store's own items exist only with this right.
- Every item goes to every store in the first version; a head office item is consult-only at the store; a store's item with the same code becomes the head office item and its other items stay sellable as its own; an item deleted or deactivated at the head office becomes inactive at the store.
- Customers stay the store's (the old task 6.3 is removed).
- A BL always goes to a store. Status: Draft, Sent, Received, Invoiced. It is invoiced only once received, on the quantities the store confirmed.
- Supply price, per store: a supply price per item (base and lists), or a percentage off the store's selling price.

### Step 6 — Items and selling prices decided by the head office

Goal: a store whose items are decided by the head office receives its items and one selling price per item. The head office sets prices with a base price and price lists. A store buys from its own suppliers only with the right.

| Task | What | Test |
|---|---|---|
| 6.0 | Docs: the design version 2 and this recut plan in `docs/roadmap/` | Files visible to a new Claude Code session |
| 6.1 | Head office: items, families, sub-families and barcodes become changes of the copies down (domain `CATALOGUE`), every item for every store. The item pages exist on the head office routes | L1 payload by codes; one change per sequence |
| 6.2 | Store: pull of the catalogue when `ownership.catalogue=HEAD_OFFICE` (needs `headoffice.url`). `origin` on item, family, sub-family and barcode (null = local). Saved by code; an existing item with the same code becomes the head office item; deleted or deactivated at the head office gives inactive at the store | L1 mapping, same code, deactivation; a store without the setting is unchanged |
| 6.3 | Store guards: a head office record is consult-only (409 on edit and delete); the pages show the badge and hide the actions. The legacy `fromFranchiseAdmin` path is not touched | L1 guard; same answers as today for a franchise customer profile |
| 6.4 | Head office: price lists (list and lines) and their page; "selling price list" on the store's row; the item sent to a store carries the price worked out for that store; a list line change reaches the stores on that list | L1 price per store: no list, list with the item, list without the item, list change |
| 6.5 | "May change its selling prices" on the store's row (sent with the heartbeat answer). On: the store can put its own price on a head office item and the pull keeps it. Off: refused | L1 own price kept across a pull; refused when off |
| 6.6 | "Can purchase from its own suppliers" on the store's row. Off: no purchases, no suppliers and no own items on a store whose items are the head office's. On: purchases and suppliers open, own items can be created, purchase lines accept own items only | L1 off and on; a head office item in a purchase line is refused; a store that decides its own items purchases as today |
| 6.7 | Stores page: the three new settings; the own / franchise label leaves the page (the column stays) | L2 |
| 6.8 | Item images from the head office. Removed from version 2.1 (Zein, 2026-10-04): moved to section 5, "Later, not scheduled" | — |

L2 scenarios (head office, stores B and C): an item created at the head office is sold at B and C; changed and deactivated, followed; an item of C with the same code becomes the head office item; a price list set on C only: C sells at the list price and B at the base price; a list line change reaches C only; "may change" on at C: its own price survives a pull; purchase right off at B: purchases refused; on at C: own item created, purchased, stock up, sold, and seen in the head office tickets; head office stopped: both stores sell with their last copy.

Done when: L2 passes, the four selling services are unchanged, and the franchise profiles answer as before.

Not in this step: a head office item's stock at a store rises only by BLs, which arrive at step 7A; until then the tests use opening stock or an adjustment.

**Step 6 backend** (done 2026-10-04, branch `feature/ho-step-6`). Described in `docs/modules/head-office.md`, "Catalogue owned by the head office (step 6)" and "Price lists (task 6.4)".

| Part | Backend |
|---|---|
| 6.0 Docs: design version 2, steps 6 to 8 recut | a61f027 |
| 1. Head office (6.1, 6.4): domain `CATALOGUE` (families, sub-families, items, barcodes, every record for every store, by codes, price per store), hooks in the catalogue services and the data import, code change refused, `TAX_STAMP` never sent; price lists and their API; `ho_store` selling price list, `mayChangePrices`, `canPurchase` (the two rights in the heartbeat answer); startup backfill in chunks | 09de724 |
| 2. Store (6.2, 6.3, 6.5, 6.6): `CATALOGUE` pull (same code taken over, inactive never deleted, missing family or item waiting, barcode moved with its exchange row and the old `item.barcode` field cleared), own price, guards (head office records consult-only, purchase right, sales prices, imports), rights saved in `hol_link_right`, `GET /catalogue/network`, link status block, `/config` `catalogueFromHeadOffice` | 72166a0 |
| 3. Docs, the startup WARN on `sales_price` rows, 400 for a code too long | this commit |

Choices made in the session (inventory approved by Zein, 2026-10-04):
- The catalogue and the price lists exist only on a head office without an ERP; ParaFendri's stores keep `CATALOGUE=ERP`.
- The code of an item, family or sub-family cannot change at the head office once created (409); a barcode value can (the old one becomes inactive at the stores).
- Packs travel with their components inside the item record. A barcode is sent active only while its item is active (the scan does not check the item).
- A barcode clash: the head office wins, one `WARNING` exchange row per moved barcode; an old `item.barcode` field of another item with the same value is cleared.
- The own price has its own endpoints (`PUT`/`DELETE /item/{id}/own-price`); a full `PUT` of a head office item is always 409. When the right goes off, the own prices give way to the head office price at the next cycle (`DownHandler.prepare`).
- Only the two new rights are saved at the store; the four loyalty switches stay in memory.
- Purchase right off: writes 409, reads open. Catalogue and sales price imports refused; vendor import follows the right. `sales_price` writes refused, a WARN at startup and a count in the status when rows exist on head office items (`PricingService` not changed).
- Images do not travel (task 6.8, removed from 2.1: section 5); `ItemImageController` has no guard.
- A data import at the head office records its codes at the end, in chunks of 500.
- Startup backfill in chunks of 500 with one number per record.
- L2 (decision 18): stores B and C receive the head office items (1,778 imported from NAV in `pos_headoffice`) as a volume test; their profiles get `ownership.catalogue=HEAD_OFFICE` at L2.

Tests: 56 classes, 443 tests, all green. Diff proof against release/2.1.0: 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. Intended contract changes: the heartbeat answer has two more fields (`mayChangePrices`, `canPurchase`), the link status a 14th field (`catalogue`), `/config` a last field (`catalogueFromHeadOffice`); an explicit `ownership.catalogue=HEAD_OFFICE` is now checked at startup (no shipped profile sets it).

**L2 of step 6 backend** (2026-10-04, `devenv/l2-catalogue.ps1`, head office 888, store B 555, store C 556; the store B and C profiles now carry `ownership.catalogue=HEAD_OFFICE`). 15 scenarios, all passed on the third run (report `C:\zsretail-dev\logs\l2-catalogue-134535.txt`): 1 B and C with a local catalogue as before (item create, edit, delete; vendor; purchase), C's `LOCAL-C1` with stock, a barcode row and the old barcode field, the cost of two of its items noted; 2 switch and first pull (all records applied, the noted items head office items with stock and cost kept, `LOCAL-C1` active and sold, `TAX_STAMP` not replaced); 3 an item created at the head office sold at B and C, both tickets at the head office; 4 changed (name, base price, VAT, family), deactivated, reactivated, deleted (inactive at the stores, rows kept); 5 an item and its sub-family received before their family (paging): waiting, then applied; 6 a new barcode scanned at B, a clash with `LOCAL-C1` (row and old field) moved with one `WARNING` row each, a scan finds only the head office item; 7 a pack with its component, waiting while the component is missing; 8 a price list on C only, a line change reaching C only, a line deleted, C moved to another list, a used list neither deleted nor deactivated; 9 guards 409 at C on item, family, sub-family, barcode, adjust-stock allowed, a code change at the head office 409; 10 own price kept across pulls and a head office price change, given back, refused when the right is off and reset at the next cycle with the head office stopped (one row); 11 purchase right off at B (writes 409, reads 200), on at C (own item, vendor, purchase, stock, sale, ticket at the head office; a head office item in a purchase and a head office code 409); 12 rights kept through a restart of C with the head office stopped; 13 head office stopped: both stores sell, back: the change and the tickets arrive; 14 `sales_price` count in the status and the network answer, writes and catalogue imports 409, a head office import of 121 items reaching B and C; 15 a barcode of 91 characters 400.

Timings on this PC (head office backfill measured from the `Started` log line; first pull from the moment the stores answer, until their cursor reaches the head office's last change): first run (1,777 items, 3,728 records) backfill about 1.7 s, first pull 54 s at B and 55 s at C; later runs (3,858 and 3,988 records) 2.6 s and 2 s, 54 to 62 s.

Fixes found at L2: 103132e (a barcode over 90 characters answered 500 on a head office, now 400), be626d3 (`GET /admin/purchase-invoices` and the eligible purchases answered 500 without a date filter: `LocalDate.MIN`/`MAX` are outside the SQL Server date range; `InvoiceService` has the same pattern, not changed, not tested). `l2-loyalty.ps1` passed 17 of 17 on the same databases with the catalogue switched (report `l2-loyalty-135523.txt`); its scenario 15 now expects one `COPIES_DOWN` failure row per pulled domain (CATALOGUE and LOYALTY each write theirs).


### Step 6 — record

**Status: done 2026-10-04, merged into release/2.1.0 (backend 5f8cb30, frontend 47bd949).** Branch `feature/ho-step-6` in both repos. Described in `docs/modules/head-office.md` ("Catalogue owned by the head office (step 6)", "Price lists (task 6.4)", "Step 6 pages (frontend): head office", "Step 6 pages (frontend): store"), `docs/deployment-modes.md` and `docs/modules/pricing.md`.

| Part | Backend | Frontend |
|---|---|---|
| 6.0 Design version 2, steps 6 to 8 recut | a61f027 | — |
| 6.1, 6.4 Head office: domain `CATALOGUE` (every record for every store, by codes, price per store), hooks in the catalogue services and the data import, code change refused, `TAX_STAMP` never sent; price lists and their API; the three settings on the store row, the two rights in the heartbeat answer; startup backfill in chunks | 09de724 | e35b381 (price lists page), 5fb5c1b (Stores page settings, kind removed) |
| 6.2, 6.3, 6.5, 6.6 Store: `CATALOGUE` pull, guards, own price, purchase right, rights saved, `GET /catalogue/network`, link status block, `/config` `catalogueFromHeadOffice` | 72166a0 | 0928f9e (item pages), 68c027a (purchase, vendor, data import pages), 32cd1be (link page block) |
| Docs, startup WARN on `sales_price` rows, 400 for a code too long | 5e1c581 | — |
| Fixes found at L2: a barcode over 90 characters (400), the purchase invoice list without a date filter | 103132e, be626d3 | — |
| L2 script, store B and C profiles on the head office catalogue | d40d9e3 | — |
| `GET /item/search` gives `origin` (purchase item picker) | 2c92282 | e7f1556 (picker of the new purchase page back on `/item/search`, own items by `origin`) |
| The invoice list and eligible tickets without a date filter (same fix as be626d3) | bc45f3a | — |
| Frontend pages in the module doc, this record | this commit | — |
| Merge into release/2.1.0 | 5f8cb30 | 47bd949 |

Decisions (inventory approved by Zein, 2026-10-04; see "Steps 6 and 7 — recut" and the step 6 backend notes above):
- The catalogue and the price lists exist only on a head office without an ERP. Every item goes to every store; a store's item with the same code becomes the head office item (stock and cost kept); deleted or deactivated at the head office gives inactive at the store; the store's other local items stay its own.
- Base price `item.unitPrice` plus price lists (one list per store or none); the store receives one price per item; `PricingService` unchanged. A list used by a store cannot be deleted or deactivated.
- A head office record is consult-only at the store; stock adjustment is always allowed. `mayChangePrices`: an own price kept across pulls, given back by one action, reset at the next cycle when the right goes off. `canPurchase`: off, no purchases, vendors or own items; on, own items only in purchases. Both rights are saved at the store; never received = off.
- The code of an item, family or sub-family is final at the head office; a barcode clash moves the barcode to the head office item (one `WARNING` row) and clears an old `item.barcode` field; packs travel with their components; `sales_price` writes and catalogue imports are refused at the store.
- Images are not sent (task 6.8, removed from 2.1: section 5). `Store.kind` stays in the table and the API, read by no rule, off the Stores page.

**The three settings of a store for step 6** (head office Stores page; the two rights sent with the heartbeat answer and saved at the store):

| Setting | Default | When set |
|---|---|---|
| Selling price list | None (base price) | The store receives the list's price for the items on the list |
| `mayChangePrices` | Off | The store may put its own price on a head office item and keeps it |
| `canPurchase` | Off | The store may purchase from its own suppliers and create its own items (own items only in purchases) |

What exists after the step:
- Head office without an ERP: items, families, sub-families, barcodes and packs on the copies down (`CATALOGUE`); the price lists page and API; the three settings on the Stores page.
- Store with `ownership.catalogue=HEAD_OFFICE` (URL, standalone, no franchise flag): the catalogue received and consult-only, own price with the right, purchases with the right, `hol_link_right`, the catalogue block of the link page; the item, purchase, vendor and data import pages follow `/config` `catalogueFromHeadOffice`.
- Every other store (no setting, ERP, franchise customer even with `headoffice.url`): unchanged, no catalogue bean.

Tests at the merge: backend 57 classes, 446 tests, all green, run in a separate worktree of the merge. Frontend: production lint and build per frontend commit (frontend session).

L2 (2026-10-04, `devenv/l2-catalogue.ps1`, head office 888, store B 555, store C 556): 15 scenarios, all passed (report `C:\zsretail-dev\logs\l2-catalogue-134535.txt`), listed under "L2 of step 6 backend" above. Timings: head office startup backfill about 1.7 s for 3,728 records (2 to 2.6 s for 3,858 to 3,988 records); full first pull 54 s at B and 55 s at C (54 to 62 s at the later runs). `l2-loyalty.ps1`: 17 of 17 on the same databases with the catalogue switched.

Diff proof against release/2.1.0 (before the merge): 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. Changed files a store also runs, and why a store without the catalogue setting behaves as before:

| File | Why unchanged for such a store |
|---|---|
| `ItemAPI`, `ItemFamilyAPI`, `ItemSubFamilyAPI`, `ItemBarcodeAPI`, `ItemCompositionAPI`, `PurchaseHeaderAPI`, `PurchaseInvoiceAPI`, `VendorAPI`, `DataImportAPI`, `SalesPriceAPI` | The guard is looked up with `ObjectProvider`: no bean, the request runs as before. `ItemAPI.update` keeps `ownPrice` and `headOfficePrice` from the stored row (null there). The own price endpoints answer 404. `/item/search` has one more field, `origin` (null). The code change (409) and code length (400) answers come only from head office hooks |
| `ItemService`, `ItemFamilyService`, `ItemSubFamilyService`, `ItemBarcodeService`, `ItemCompositionService`, `DataImportService` | The head office hooks are looked up with `ObjectProvider`: none on a store. `deleteById` (and the pack `save`) now run in a transaction: same result |
| `Item`, `ItemFamily`, `ItemSubFamily`, `ItemBarcode` | Nullable columns (`origin`, `own_price`, `head_office_price`), read-only in JSON, not read without the setting |
| `ItemRepository`, `ItemFamilyRepository`, `ItemSubFamilyRepository`, `ItemBarcodeRepository`, `ItemCompositionRepository`, `SalesPriceRepository` | New query methods only |
| `NodeOwnership`, `ApplicationModeService`, `AppConfigAPI`, `AppConfigDTO` | An explicit `ownership.catalogue=HEAD_OFFICE` is now checked (no shipped profile sets it); `/config` has one more last field, false |
| `holink/*` (`HeadOfficeClient`, `HeadOfficeCallResult`, `HeadOfficeLinkStatusDTO`, `HeadOfficeLinkAPI`, `HeartbeatJob`, `CopiesDownPuller`, `DownHandler`, `LinkExchangeLog`) | Exist only with `headoffice.url`; with it and a local catalogue the two rights are read and unused, the status `catalogue` is null, `prepare()` does nothing |
| `headoffice/*` | Head office beans only; `ho_price_list` and `ho_price_list_line` exist empty on a store |
| `ZZDataInitializer` | One permission added to the head office list only |
| `PurchaseInvoiceService`, `InvoiceService` | Without a date filter the lists and eligible documents use 0001-01-01 and 9999-12-31 instead of `LocalDate.MIN`/`MAX` (500 before): an intended fix for every store |
| `application-store-b-dev.properties`, `application-store-c-dev.properties` | Dev profiles only |

Still owed: the screen check of the step 6 pages on the dev pair; the full regression checklist (once, before 2.1 is delivered); the `update.sql` lines of section 1. Task 6.8 (images) is removed from 2.1 (section 5).

### Step 7A — BLs and stock

Goal: the head office buys, keeps its stock and sends goods to a store with a BL; the store confirms what it received and its stock goes up; the head office sees the stock of every store.

| Task | What | Test |
|---|---|---|
| 7A.1 | Head office as a warehouse: suppliers, purchases and stock pages on the head office routes; its stock goes up by its purchases. It still has no till | L1 |
| 7A.2 | Head office: BL document (header, lines, store, status Draft, Sent, Received, Invoiced) and its page, with the list of BLs per store and status. Validating a BL sets it to Sent and takes the goods out of the head office stock | L1 status rules; stock out once |
| 7A.3 | Store (`ownership.supply=HEAD_OFFICE`): BLs addressed to it arrive by the copies down; reception screen; the store confirms the quantities received; stock in with a stock movement, also when the head office is unreachable | L1 confirming twice adds stock once |
| 7A.4 | The confirmation travels up: the BL becomes Received with the confirmed quantities; a difference between sent and received is kept and shown at the head office | L1 a repeated confirmation changes nothing |
| 7A.5 | Stock of every store copied up; head office page of the stock per store and item | L1 |

L2 scenarios: a BL sent to B is received and confirmed with a difference, stock up at B and down at the head office; head office stopped during the confirmation, stock up at B at once and the BL Received when it is back; a BL for C is not visible at B; a store with an ERP or with `ownership.supply` not `HEAD_OFFICE` has no BL page.

**Step 7A backend** (done 2026-10-04, branch `feature/ho-step-7a` from `feature/ho-step-6` at 5e1c581, not merged). Described in `docs/modules/head-office.md`, "Head office as a warehouse (task 7A.1)", "BLs (task 7A.2, head office)", "BLs at the store (tasks 7A.3, 7A.4)", "Stock of the stores (task 7A.5)" and the four "Step 7A pages (frontend)" sections.

| Part | Backend |
|---|---|
| Decision 6: a stock adjustment writes its `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` movement (`ItemService.adjustStock`), no quantity change | 73c961f |
| 7A.1 Head office as a warehouse: no purchase or stock code changed (`isStandalone()` = no ERP); seven page permissions; no CATALOGUE change from a purchase or a stock change, proved | f78cc8c |
| 7A.2 BLs at the head office: `ho_delivery`, `ho_delivery_line`, `ho_number_sequence`; drafts; validation all or nothing (row locked, stock checked, `BL-000001`, `DELIVERY_OUT` per line, recorded for its store only, domain `SUPPLY`) | 9900f91 |
| 7A.3 + 7A.4 Store: `ownership.supply=HEAD_OFFICE` (startup rules), `hol_delivery`, `hol_delivery_line`, `SupplyDownHandler`, reception (`/admin/deliveries`, stock in once, offline, waiting lines), job `SUPPLY_PUSH`; head office `POST /ho/supply/confirmations` (Received, exactly once per number); `/config` `supplyFromHeadOffice`; link status `supply`; ADMIN topped up with `read:admin-holink-deliveries` at start | c035941 |
| 7A.5 Stock of the stores up (`hol_stock_copy`, `POST /ho/supply/stock`, `ho_store_stock`) and the head office stock API; docs, this record | this commit |

Choices made in the session (inventory approved by Zein, 2026-10-04):
- Stock not sufficient at validation: all or nothing, 409 listing each short item, unless `ALLOW_NEGATIVE_STOCK=true` at the head office.
- Cancelling a Sent BL: not in 7A (section 5). The store confirms 0 and the head office corrects its stock with an adjustment (which now writes its movement).
- More received than sent: accepted; the store's stock goes up by what it counted; the head office shows the difference with its sign and does not change its own stock (for any difference).
- `ownership.supply=HEAD_OFFICE` requires `ownership.catalogue=HEAD_OFFICE` (startup refusal); a BL line resolves only to an item of origin `HEAD_OFFICE`; a store's own item never receives a BL.
- `isStandalone()` on a head office means "no ERP": the purchase, vendor, purchase invoice and stock code works unchanged there; with an ERP it answers 403 as before.
- BL number given at validation (`BL-000001`); a draft has none. No unique constraint on the number column (one null only in SQL Server): the locked sequence row makes it unique.
- Items on a BL: active products and packs, never `TAX_STAMP`, once per BL. A store that reported another `ownership.supply`: 409; not reported yet: accepted.
- Stock copied up only by the stores whose goods come from the head office; all their products and packs, own items flagged; one job `SUPPLY_PUSH` for confirmations then stock.
- A line whose item is missing at the store: received (counted) and sent up at once; only its stock in waits, applied once by the next cycles.
- Permission of the store page given to ADMIN at every start when it lacks it (decision 13, the head office's top-up generalised: `addMissingPermissions`).
- CATALOGUE check (Zein): a purchase saves the item through `itemRepository.save` (no catalogue hook), the stock moves with native updates (no hook), an adjustment and a BL validation save no item: none records a CATALOGUE change (tests `HeadOfficeWarehouseTest`, `HoDeliveryServiceTest.noCatalogueChange`). Only a save through `ItemService.save` records one: an Items page edit (whatever field, cost and minimum stock included) and an image upload; each makes every store pull that one item and write nothing. Not changed: telling a real change from a cost-only edit needs the stored state before the save, which the persistence context hides when a caller edits the loaded item in place, so a filter there could lose a real change.

Tests: 61 classes, 481 tests, all green, in the worktree `D:\ZS Retail\Apps\_worktrees\back-7a` (JDK 21). New: `ItemStockAdjustmentTest`, `HeadOfficeWarehouseTest`, `HoDeliveryServiceTest`, `SupplyRoundTripTest`, `OnHeadOfficeSupplyConditionTest`; extended: `OnHeadOfficeCatalogueConditionTest`, `QueryParameterBindingTest`, `ZZDataInitializerRolesTest`, `AppConfigAPITest`, `HeadOfficeLinkAPITest`.

Diff proof against the base 5e1c581: 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. Intended contract changes: `/config` a last field (`supplyFromHeadOffice`), the link status a 15th field (`supply`), an explicit `ownership.supply=HEAD_OFFICE` now checked at startup (no shipped profile sets it), two `StockMovementType` values, adjustments now write a movement. Changed files a store also runs:

| File | Why a store without `ownership.supply=HEAD_OFFICE` behaves as before |
|---|---|
| `ItemAPI`, `ItemService` | `adjust-stock` calls `ItemService.adjustStock`: the same quantity update, plus its movement (decision 6, every standalone store); `ItemAPI` lost an unused field |
| `StockService`, `StockMovementService`, `StockMovementType` | New methods and two enum values, called only by the BL code (head office, supply stores) |
| `NodeOwnership`, `ApplicationModeService`, `ConditionalOnHeadOfficeSupply`, `OnHeadOfficeSupplyCondition` | New checks only on an explicit `ownership.supply`; `isSupplyFromHeadOffice` false otherwise; the derived owners are unchanged (a franchise customer still derives `SUPPLY=HEAD_OFFICE` and never matches) |
| `AppConfigAPI`, `AppConfigDTO` | A last field, false |
| `ZZDataInitializer` | Head office list longer; the supply top-up only when `isSupplyFromHeadOffice`; other stores seed and keep exactly their roles |
| `HeadOfficeClient`, `HeadOfficeLinkAPI`, `HeadOfficeLinkStatusDTO` | Link beans only (`headoffice.url`); two new calls never made without the supply beans; `supply` null |
| `holink/*` new classes | `@ConditionalOnHeadOfficeSupply`: absent |
| `headoffice/*` new classes | Head office without an ERP only; `ho_` tables exist empty in a store database |


### Step 7A — record

**Status: done 2026-10-04, merged into release/2.1.0 (backend e697444; the frontend is merged by the frontend session).** Branch `feature/ho-step-7a` in both repos. Described in `docs/modules/head-office.md` ("Head office as a warehouse (task 7A.1)", "BLs (task 7A.2, head office)", "BLs at the store (tasks 7A.3, 7A.4)", "Stock of the stores (task 7A.5)" and the four "Step 7A pages (frontend)" sections).

| Part | Backend | Frontend |
|---|---|---|
| Decision 6: a stock adjustment writes its movement | 73c961f | — |
| 7A.1 Head office as a warehouse (purchases, vendors, purchase invoices, stock, seven page permissions) | f78cc8c | 6208e7b (Supply pages) |
| 7A.2 BLs at the head office (drafts, validation all or nothing, `BL-000001`, `DELIVERY_OUT`, domain `SUPPLY`) | 9900f91 | 8ac5489 (BL page) |
| 7A.3, 7A.4 Store: BLs received, reception (stock in once, offline, waiting lines), `SUPPLY_PUSH`, head office confirmations | c035941 | ef799b0 (reception page) |
| 7A.5 Stock of the stores up, head office stock API | 9c913c2 | 5d7743b (network stock page) |
| An item edit keeps the stored stock | 1eccde8 | — |
| Merge of release/2.1.0 (step 6) into the branch | 63203c9 | 933799d |
| Fixes found at L2: the confirmation push failed every time (lazy BL lines built outside the transaction); editing a draft BL answered 500 (unique key of the lines) | 5c0488f, e9495c2 | — |
| `belowZero` on the two head office stock endpoints | a8dc85b | — |
| L2 script, store B profile with `ownership.supply=HEAD_OFFICE` | c231b2c | — |
| Docs: the four frontend sections, this record, step 6 hashes | this commit | — |
| Merge into release/2.1.0 | e697444 | by the frontend session |

Decisions: see the step 7A backend notes above (stock not sufficient refused all or nothing unless `ALLOW_NEGATIVE_STOCK`; no cancel of a Sent BL; more received than sent accepted and shown, the head office stock unchanged for any difference; supply needs the catalogue; a BL line only on a head office item; number at validation; stock copied up only by the stores supplied by the head office; a waiting line counted and sent up at once, its stock in applied once later; the reception permission topped up on ADMIN at each start).

What exists after the step:
- Head office without an ERP: a warehouse (vendors, purchases, purchase invoices, stock and movements on its own routes, no till); BLs (draft, validate, list per store and status with the differences); the stock of every supplied store next to its own, with `belowZero`.
- Store with `ownership.supply=HEAD_OFFICE` (and the catalogue from the head office): BLs received by the copies down, a reception page, stock in at once (also offline), the confirmations and the stock copied up by `SUPPLY_PUSH`, the `supply` block of the link page.
- Every other store (supply local, ERP, franchise customer even with `headoffice.url`): unchanged, except that a stock adjustment now writes its movement and an item edit keeps the stored stock (both intended for every standalone store).

Tests at the merge: backend 63 classes, 487 tests, all green, run in a separate worktree of the merge. Frontend: production lint and build per frontend commit (frontend session).

L2 (2026-10-04, `devenv/l2-supply.ps1`, instances built from the worktree; head office 888, store B 555 with the catalogue and `ownership.supply=HEAD_OFFICE`, store C 556 with the catalogue and its supply local): 12 scenarios, all passed on the third run (report `C:\zsretail-dev\logs\l2-supply-145427.txt`); the second run found the two fixes above. 1 the head office buys 100: stock 100, one movement; 2 a draft for B edited, deleted, created again, validated (`BL-000006`, Sent, stock out with one `DELIVERY_OUT` per line), a second validation 409 with the stock unchanged; 3 a shortage 409 listing both items, nothing changed; 4 B receives the BL, C sees nothing, has no reception API (404), a BL for C 409; 5 B confirms 48 of 50: stock +48 with one movement, a second confirmation 409, the head office BL Received with 48 and −2; 6 head office stopped while B confirms: stock up at once, the BL Received when it is back, once; 7 the same confirmation sent twice accepted twice and changes nothing, other quantities rejected; 8 a line whose item is missing at B (paging: the item's family arrives after it) waits, then its stock goes in exactly once; 9 3 received for 2 sent: accepted, +1 shown; 10 a sale at B: its stock at the head office stock API, then nothing to send; 11 an adjustment writes `ADJUSTMENT_IN`, an item edit sending an old stock keeps the stored one; 12 ADMIN of B has `read:admin-holink-deliveries` without a manual step, C has not. On the same build: `l2-catalogue.ps1` 15 of 15 (report `l2-catalogue-145702.txt`; its scenario 1 now also sets `ownership.supply=LOCAL`) and `l2-loyalty.ps1` 17 of 17 (`l2-loyalty-150357.txt`). Head office catalogue backfill during that run: 16.6 s for 4,370 records, 8.0 s for 4,500 measured alone right after (1.7 to 2.6 s at step 6): not investigated further.

Diff proof against release/2.1.0 (before the merge): 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. The changed files a store also runs are those of the step 7A backend table above (`ItemAPI`, `ItemService`, `StockService`, `StockMovementService`, `StockMovementType`, `NodeOwnership`, `ApplicationModeService`, the supply condition, `AppConfigAPI`, `AppConfigDTO`, `ZZDataInitializer`, the link classes), plus `SupplyPushService` and `HoDeliveryService` (the two fixes, supply beans and head office only) and the store B dev profile.

Still owed: the screen check of the step 7A pages on the dev pair; the full regression checklist (once, before 2.1 is delivered); the `update.sql` lines of section 1 (the 7A tables come through `ddl-auto`; `hol_stock_copy`, `ho_store_stock`, `ho_delivery`, `ho_delivery_line`, `ho_number_sequence`, `hol_delivery`, `hol_delivery_line` to list when the script is written).

### Step 7B — Invoices and supply price

Goal: a store that pays receives, for its received BLs, an invoice at its supply price. Needs decision D16 (Happyness: supply price per item or a percentage; invoice per BL or per period).

| Task | What | Test |
|---|---|---|
| 7B.1 | Store's row: "deliveries are invoiced", billing details (legal name, tax number, address), supply price mode (a supply price list, or a percentage off the selling price), invoice rhythm (per BL or grouped) | L1 |
| 7B.2 | Supply prices: a base supply price on the item and supply price lists, on the mechanism of task 6.4 | L1 supply price per store in both modes |
| 7B.3 | Head office: invoice from the Received BLs of one store, on the confirmed quantities; one or several BLs; a BL only once; created automatically when the rhythm is per BL; the BL becomes Invoiced | L1 |
| 7B.4 | Store: the invoice arrives as a purchase invoice, consult-only; the supply price becomes the item's cost | L1 arriving twice creates one |
| 7B.5 | Head office: paid or unpaid on an invoice, and what each store owes | L1 |

L2 scenarios: a store that does not pay: its Received BL cannot be invoiced; a store that pays, per BL: 50 sent, 48 confirmed, an invoice of 48 at the supply price arrives at the store as a purchase invoice and the item's cost follows; grouped rhythm: two Received BLs in one invoice, a third one still Sent is refused; percentage mode gives the selling price minus the percentage.

**Step 7B backend** (done 2026-10-04, branch `feature/ho-step-7b` from `feature/ho-step-7a` at 63203c9, release/2.1.0 with step 6 merged in; merged with the step, see "Step 7B — record"). Described in `docs/modules/head-office.md`, "Supply prices and invoicing settings (step 7B, part 1)", "Supply invoices (step 7B, part 2)", "Supply invoices at the store (step 7B, part 3)".

| Part | Backend |
|---|---|
| 1. Store invoicing settings (`ho_store`), price list kind `SELLING` / `SUPPLY`, base supply price (`ho_item_supply_price`), the supply price of a store (`PRICE_LIST`, `PERCENT_OFF`) | 435ab69 |
| 2. Supply invoices at the head office (`ho_supply_invoice`, lines, `FHO-yyyy-000001`, BLs `INVOICED`, per-BL invoice at the confirmation, tax stamp setting, paid / unpaid, balances, records `INV:` on the copies down) | e3e74fb |
| 3. Store: the invoice received as a purchase invoice (vendor `HEAD_OFFICE`, origin, cost of the head office items, BL numbered, vendor guard); docs, this record | this commit |

Choices made in the session (inventory approved by Zein):
- VAT per line from the item's `defaultVAT`, amounts to the millime. Tax stamp: head office setting `SUPPLY_INVOICE_TAX_STAMP`, off by default; when on, one `TAX_STAMP` line at VAT 0 with its own amount `SUPPLY_INVOICE_TAX_STAMP_MILLIMES` (1000 by default). An invoice needs something received (never the stamp alone); its date is neither in the future nor before the last invoice.
- The price is the supply price of the invoicing date (section 5: price frozen on the BL line at validation).
- An item without a supply price: refused, listed; an automatic invoice is not created and the BL keeps the reason (`invoice_note`), the confirmation is accepted.
- Vendor `HEAD_OFFICE` at the store, created at the first invoice, consult-only. The supply price becomes `lastDirectCost`, `lastDirectNetCost` and `costPrice` of head office items only.
- No cancellation (credit notes later), no partial payment. Settings changed later never touch an existing invoice.
- Base supply price in its own table, not on `item`. Invoice numbers `FHO-yyyy-NNNNNN`, one sequence per year. One invoice line per BL line.
- The store purchase invoice tables are reused (`purchase_invoice_header` / `purchase_invoice_line` + `origin`); no `purchase_header` is created; `PurchaseInvoiceService` is not changed.

Tests: 65 classes, 500 tests, all green, in the worktree `D:\ZS Retail\Apps\_worktrees\back-7b`. New: `HoSupplyPriceServiceTest`, `HoSupplyInvoiceServiceTest`, `SupplyInvoiceRoundTripTest`; extended: `OnHeadOfficeCatalogueConditionTest`, `OnHeadOfficeSupplyConditionTest`, `QueryParameterBindingTest`, `ZZDataInitializerRolesTest`.

Diff proof against `feature/ho-step-7a` (63203c9): 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, `PurchaseInvoiceService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. Changed files a store also runs:

| File | Why a store behaves as before |
|---|---|
| `VendorAPI` | The head office vendor guard is looked up with an `ObjectProvider`: absent on every store without `ownership.supply=HEAD_OFFICE` |
| `PurchaseInvoiceHeader`, `PurchaseInvoiceHeaderRepository` | A nullable `origin` column (null on every existing invoice) and a new query method |
| `ReceivedDelivery`, `ReceivedDeliveryDTO`, `DeliveryReceptionService`, `SupplyDownHandler` | Supply beans only (7A condition); a BL record without `kind` is handled as in 7A |
| `ZZDataInitializer` | The setting `SUPPLY_INVOICE_TAX_STAMP` and three permissions on a head office only |
| `headoffice/*` | Head office without an ERP only; the new `ho_` tables and columns exist empty in a store database |

### Step 7B — record

**Status: done 2026-10-04, merged into release/2.1.0 (backend 7083298; the frontend is merged by the frontend session).** Branch `feature/ho-step-7b` in both repos. Described in `docs/modules/head-office.md` ("Supply prices and invoicing settings (step 7B, part 1)", "Supply invoices (step 7B, part 2)", "Supply invoices at the store (step 7B, part 3)", the two "Step 7B pages (frontend)" sections and "Network stock: "below zero only" through the API").

| Part | Backend | Frontend |
|---|---|---|
| 1. Invoicing settings of a store, price list kind, base supply price, supply price of a store | 435ab69 | b398fea (Stores page), 273aba9 (price lists), 0c513a4 (supply prices) |
| 2. Supply invoices at the head office (per BL and grouped, stamp, paid / unpaid, balances, `INV:` records) | e3e74fb | 067dbf6 (invoices), d765b1d (to invoice), 51d8582 (what the stores owe), af18201 (BL page) |
| 3. Store: the invoice as a purchase invoice, vendor `HEAD_OFFICE`, costs | 055757b | 7bbef7f (store side) |
| Four fixes: supply list base price, nothing received, invoice date, the stamp's own amount | cee300c | 4a0504c (supply list base price) |
| L2 script `devenv/l2-invoices.ps1` | 0483c4b | — |
| Merge of release/2.1.0 (step 7A merged) into the branch | ee8691a | — |
| The preview gives the items without a supply price as lines too (`missingPrice`) | 01f51ca | 16fb967 |
| L2 script fixes: one-element arrays sent as arrays; store C's supply traces cleaned at the end (l2-supply counts them) | 8c6c1b1, b8b5f1c | — |
| Network stock "below zero only" through the API (backend of step 7A) | a8dc85b | d7a60a5 |
| Docs: the frontend sections, this record | 8020ba1 | — |
| Merge into release/2.1.0 | 7083298 | by the frontend session |

Decisions: see "Choices made in the session" above (VAT per line, the stamp off by default with its own amount, the price of the invoicing date, an item without a supply price refused and listed, vendor `HEAD_OFFICE` consult-only, supply price as the cost of head office items only, no cancellation and no partial payment, the store purchase invoice tables reused). Added at L2: the preview shows the lines of the items without a supply price, outside the totals.

What exists after the step:
- Head office without an ERP: per store, deliveries invoiced or not, billing details, supply price mode (a supply price list or a percentage off the selling price) and invoice rhythm; base supply prices and supply price lists; supply invoices (`FHO-yyyy-NNNNNN`) per BL at the confirmation or grouped by hand with a preview, the BLs `INVOICED`; paid / unpaid and what each store owes.
- Store with `ownership.supply=HEAD_OFFICE`: the invoice arrives by the copies down as a consult-only purchase invoice of the vendor `HEAD_OFFICE`, the supply price becomes the cost of the head office items, the BL shows its invoice number.
- Every other store (supply local, ERP, franchise customer even with `headoffice.url`): unchanged.

Tests on the branch before the merge: 66 classes, 506 tests, all green. Tests at the merge (7083298, run in a separate worktree of the merge): 66 classes, 506 tests, all green. Frontend: production lint and build per frontend commit (frontend session).

L2 (2026-10-04, instances built from the worktree, build 8c6c1b1; head office 888, store B 555 supplied and, for the run, invoiced, store C 556 supplied for the run only): `l2-invoices.ps1` 13 of 13 (reports `l2-invoices-160421.txt` and, with the cleanup of C, `l2-invoices-163742.txt`). The first run on ee8691a gave 7 of 13, the script's fault: PowerShell unrolled one-element arrays into objects (400 on list lines and on a supply price); no product bug found. 1 B's invoicing settings saved, kept when absent from a `PUT`; 2 base supply prices and a supply list line, `basePrice` the base supply price; 3 `PER_BL`: invoiced by itself on the confirmed quantities, a line received 0 left out, VAT per item; 4 B pulls the invoice once: purchase invoice of `HEAD_OFFICE`, origin `HEAD_OFFICE`, the head office items' costs set, its own item's cost unchanged; 5 the `HEAD_OFFICE` vendor and the invoice consult-only at B (409, 404); 6 an item without a supply price: the BL stays Received with its reason, invoiced by hand once the price is set; 7 grouped: preview writing nothing, one invoice of two BLs, invoiced twice 409; 8 percentage mode (refused without a percentage, 30% off the selling price or the store's selling list); 9 a BL received at 0: no invoice, no note, 409; 10 invoice dates (tomorrow, before the last invoice, the preview too: 400); 11 the stamp on and off, at B too; 12 paid, unpaid, balances; 13 store C supplied but not invoiced: its BL Received, 409, nothing at C. Then once on the same build: `l2-supply.ps1` 12 of 12 (`l2-supply-170859.txt`; 9 of 12 before the cleanup of C, the residue of scenario 13), `l2-catalogue.ps1` 15 of 15 and `l2-loyalty.ps1` 17 of 17 (on ee8691a).

Checks at L2: `belowZero=true` on both head office stock endpoints run on SQL Server, without a store and with each store (items below zero at the head office, at a store, own items). Head office catalogue backfill, measured again with 4,763 records: 1.8 and 2.1 s with the head office alone, 3.8 s with the three instances starting together (as at step 6); the 8 to 16.6 s of step 7A came from a loaded machine (the starts themselves took 62 to 78 s instead of about 20 s, right after a build). About 1 ms per change row, one `INSERT` each (IDENTITY ids, no batching): a JDBC batch would cut it, not needed now.

Diff proof against release/2.1.0 (before the merge): 0 files in `erp/`, 0 franchise files; `SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`, `PurchaseInvoiceService`, the four loyalty tests and `PromotionAllItemsScopeTest` unchanged. The changed files a store also runs are those of the step 7B backend table above (`VendorAPI`, `PurchaseInvoiceHeader`, `PurchaseInvoiceHeaderRepository`, `ReceivedDelivery`, `ReceivedDeliveryDTO`, `DeliveryReceptionService`, `SupplyDownHandler`, `ZZDataInitializer`), plus `SupplyInvoiceWriter` and `SupplyVendorGuard` (new, supply beans only).

Still owed: the screen check of the step 7B pages on the dev pair; the full regression checklist (once, before 2.1 is delivered); the `update.sql` lines of section 1 (step 7B: the `ho_store`, `ho_price_list`, `ho_delivery`, `hol_delivery` and `purchase_invoice_header` columns listed there; `ho_item_supply_price`, `ho_supply_invoice`, `ho_supply_invoice_line` through `ddl-auto`); credit notes and a price frozen on the BL line (section 5).

### Step 8 — Franchise profiles moved onto the model

Goal: a franchise network runs as a head office and stores; the franchise modes disappear. Needs decisions D7 and D15.

**Recut (Zein, 2026-10-04, after the step 8 inventory).** No install runs the franchise profiles with real data. Happyness was only set up on them: its stores still run their old POS, and it starts on the model with new databases at the head office and at the stores. A franchise network is therefore installed directly from the two presets of task 8.1. Nothing has to be migrated. Consequences:

- The franchisor's head office never sells; the stores sell (inventory decision 1, design 2.1).
- Task 8.2 (migration of a franchise install) is dropped, not on hold. The questions it raised (sales history to send up, a fresh database or a converted one) no longer apply.
- The starting settings of a franchise network (inventory decisions 2 and 4, design D15 and D16): price right and purchase right both off (imposed selling price, no own suppliers); a base selling price and a base supply price on the items, supply price mode `PRICE_LIST` without a list, invoice rhythm `PER_BL`. Each one stays a setting of the store row and can be changed per store.
- The legacy profiles and `/franchise/**` stay as they are during step 8 (design 5.2, rule 6). Their removal (task 8.3) is now possible and is done with step 9.

| Task | What | Test |
|---|---|---|
| 8.1 | Settings presets that replace `franchise-admin` and `franchise-customer` (the franchise column of the design, section 2.3): profiles `network-headoffice` and `network-store`; procedure "Installing a franchise network on the model" in `docs/modules/franchise.md` | L1 truth table on the real profile files |
| 8.2 | Dropped 2026-10-04: no franchise install with real data to migrate | — |
| 8.3 | Remove the franchise code (`/franchise/**`, the two profiles, the franchise flags and fields): to do with step 9 | L3 |

**Step 8 backend** (2026-10-04, branch `feature/ho-step-8` from `release/2.1.0` at cb5c2e5, before step 7B is merged; not merged). Described in `docs/deployment-modes.md`, "Presets", and `docs/modules/franchise.md`, "Installing a franchise network on the model".

| Part | Backend |
|---|---|
| 8.0 Recut (no install to migrate, 8.2 dropped, 8.3 with step 9 as 9.4, D7, D15 and D16), task 6.8 out of 2.1 | 2e3a803 |
| 8.1 Profiles `network-headoffice` and `network-store`, `NetworkPresetTruthTableTest` (the four real files: owners, sales upstreams, the head office switches, the beans of each side; the two franchise profiles locked as they resolve today), the procedure | this commit |

No Java file is changed and no franchise file is touched: two new profile files, one new test, docs. Nothing changes for an install that does not start with one of the two new profiles. The procedure names the step 7B settings and pages; it is complete once step 7B and its pages are merged. Frontend: nothing (the presets give flags the frontend already reads).

Still owed: the merge after step 7B (the overview rows 7B and 8 and the section after the step 7B table are edited on both branches: a small conflict to resolve in the docs); an L2 of the procedure on the dev pair once 7B is merged (head office from `network-headoffice`, a store from `network-store`, the chain of step 5 of the procedure).

### Step 9 — Cleanup of the mode checks

Goal: no more `isStandalone` in the code; every check asks an ownership question.

| Task | What | Test |
|---|---|---|
| 9.1 | Backend: the 51 checks replaced file by file | L1 old and new answers identical for every profile |
| 9.2 | Frontend: the about 120 references replaced | L3 |
| 9.3 | Profile files renamed to presets; `deployment-modes.md` rewritten | L3 |
| 9.4 | Task 8.3, moved here: the franchise code removed (`/franchise/**`, `franchise-admin` and `franchise-customer`, the franchise flags, the franchise branches of `ItemAPI`, `ItemService`, `InvoiceService`, `ZZDataInitializer`, the franchise pages); no column dropped (design 5.2, rule 3) | L3 (backend done with 9.4a; the frontend part with task 9.2) |

**Step 9 backend** (in progress, branch `feature/ho-step-9` from `feature/ho-step-8` at 7d263dd). Decisions of the inventory (Zein, 2026-10-04): explicit owners that contradict `application.standalone` are refused at startup; invoices from POS tickets ask `CUSTOMERS != ERP`, locations ask `SUPPLY != ERP`; a leftover `franchise.admin` / `franchise.customer=true` is refused once 9.4 is done; `/config` keeps `standalone`, the three franchise fields leave with 9.4. Scope: the whole step in 2.1, no `isStandalone` check left at the end; the stock checks of the selling path after the L2 of step 7B frees the dev instances.

| Part | Backend |
|---|---|
| 9.1a Startup: owners agree with `application.standalone` (and no franchise flag with ERP flags) | 9ef8247 |
| 9.1b The four questions (`isCatalogueFromErp`, `isCustomersFromErp`, `isSupplyFromErp`, `hasErp`), no call site changed; `ModeQuestionTruthTableTest` (profile files and grid) | a278363 |
| 9.1c Catalogue and customer gates (13): `ItemAPI` ×4, `ItemFamilyAPI`, `ItemSubFamilyAPI`, `DataImportAPI` ×2 → `isCatalogueFromErp`; `CustomerAPI`, `InvoiceAPI` ×3 → `isCustomersFromErp`; `ModeGateTest`, `support/TestModes` | 5432604 |
| 9.1d Supply gates (13): `PurchaseHeaderAPI` ×5, `PurchaseInvoiceAPI` (its `ensureStandalone` renamed `ensureSupplyNotFromErp`), `VendorAPI` ×3, `LocationAPI` ×3, `ItemAPI` adjust-stock → `isSupplyFromErp` | a143441 |
| 9.1e Startup and `/config` (4): `ZZDataInitializer` passenger customer → `!isCustomersFromErp`, ERP seed ×2 → `hasErp`; `AppConfigAPI` `standalone` → `!hasErp` (`AppConfigAPITest` unchanged: same `/config` per profile) | 76bc8a5 |
| 9.4a Franchise code removed (task 8.3 → 9.4): `controller/franchise`, `service/franchise`, `dto/franchise`, `FranchiseApiKeyFilter`, `FranchiseSales*` entities and repositories, the two franchise profiles; the franchise branches of `ItemAPI`, `ItemService`, `InvoiceService`, `InvoiceAPI`, `ZZDataInitializer`, `AppRoleAPI`, `LicenseFilter`, `SecurityConfig`; the franchise fields of `Item`, `Customer`, `InvoiceHeader` (columns kept; old payloads ignored, never refused); `/config` without `franchiseAdmin`, `franchiseCustomer`, `allowLocalItems`; `ApplicationModeService` and `NodeOwnership` without the franchise flags, `NodeOwnership.resolve(env, standalone)`; a leftover `franchise.admin` / `franchise.customer=true` stops the startup; the `franchise.*=false` lines out of the profile files. Tests: `LegacyFranchiseFieldsTest`, refusals in `ApplicationModeOwnershipTest` and the condition tests, the franchise rows out of eight test classes | cb68ac4 |
| 9.1f Stock (13): `StockService` ×6, `StockMovementService` ×7 → `isSupplyFromErp`; `StockModeTest` (every profile file); `InMemoryStock` takes the installation. Selling path: called at every sale and return; the L2 replay of a sale and a return waits for the dev instances | dfb39c1 |
| 9.1g `isStandalone()` and `isErpMode()` deleted; `NodeOwnership.resolve(env)` the one reader of `application.standalone` (`ApplicationModeService` has no mode field left); `isCatalogueFromHeadOffice`, `isSupplyFromHeadOffice`, `isHeadOfficeErpSet`, `isHeadOfficeStandaloneSet` without a direct read (an owner question; the dropped `standalone` terms were redundant with the startup checks); `/item/standalone-quick-product` renamed `/item/quick-product` (`QuickProductRequestDTO`, `createQuickProduct`); every message, comment and doc line that said "standalone" for "without an ERP" reworded (list in the report of 2026-10-04) | 61d8675 |
| Test: `ZZDataInitializerModeTest`, the three startup branches of 9.1e on the real `init()` (with an ERP, without one) | this commit |
| Merges: release/2.1.0 (step 7B) into feature/ho-step-8 (b193ead, 67 classes, 510 tests), feature/ho-step-8 into feature/ho-step-9 (3710bc9, 72 classes, 515 tests) | — |
| 9.3a The six presets (`store`, `store-erp`, `headoffice`, `headoffice-erp`, `network-store-erp`; `network-store` rewritten in 9.3b), mode keys only, every owner stated; `PresetTruthTableTest` freezes the answers of the old profile files (ten rows: the six shapes and the dev machines) | 596f1bb |
| 9.3b `application.standalone` removed (refused at startup, the message names the presets); the ERP owns catalogue, customers and supply together or none; the machine file (`MachineFileEnvironmentPostProcessor`: `-Dzsretail.machine-file`, else `${catalina.base}/conf/zsretail/<context name>.properties`; no default, refused without one); the eleven old profile files removed, their values moved as they were into `deploy/dev` and `deploy/customers`, plus `deploy/machine-model.properties`; `network-store` rewritten as a preset, `network-headoffice` folded into `headoffice`; `PresetTruthTableTest` on the presets and machine files, `MachineFileTest`, `support/Installations` | 6aa0d88 |
| 9.3c The devenv scripts on machine files (`common.ps1` `Machine`, `start.ps1` `-Dzsretail.machine-file`, `Set-DevMachineValue`, `setup-stores.ps1`, `l2-supply.ps1`); a `spring.profiles.active` left in the server's options refused; `deployment-modes.md` rewritten (presets, machine file, the `deploy/` table, IDE setting, new installation and upgrade procedures); `franchise.md`, `head-office.md`, `CLAUDE.md` on machine files | this commit |

Grid of `ModeQuestionTruthTableTest` (61,440 configurations; `node.type=STORE` reads like absent, promotions and loyalty only absent or `HEAD_OFFICE`):
- Before 9.1a: 10,418 accepted, 8,580 answering differently. 4,830 had a franchise flag with ERP flags (`application.standalone` false or absent); 2,386 had `application.standalone=true` with an explicit owner `ERP`; 1,364 had ERP flags with catalogue, customers or supply not `ERP` (e.g. `ownership.supply=LOCAL` alone). The full first grid (368,640, with `STORE` and promotions or loyalty `LOCAL`/`ERP`) gave 51,336 accepted and 42,354 differing, of the same three kinds.
- After 9.1a: 1,700 accepted, none differing. The 138 accepted ones that agreed and are now refused have a franchise flag with ERP flags (all three ERP domains set to `ERP` explicitly).
- After 9.4a: 872 accepted (every configuration with a franchise flag is refused), none differing.
- Every profile file agreed before and after.
Still owed for step 9: the L2 replay of a sale and a return on the stock checks of 9.1f (selling path), once the L2 of step 7B frees the dev instances; the frontend one-line change to `/item/quick-product` (task 9.2); after the step 7B merge, the annotation `ConditionalOnHeadOfficeStandalone` and `isHeadOfficeStandaloneSet` renamed (7B adds beans that use them); task 9.3 (presets and a file per machine); the merge after step 7B. Zein, 2026-10-04: the whole step goes into 2.1.

## 5. Later, not scheduled

Notes of Zein, 2026-10-03 and 04:

- Design principle, in his words: "No specific cases, everything configurable, so we could make a solution dynamic and cover all needs."
- How loyalty movements are shared, to discuss: today each store keeps its own movements and only the balance is shared. Open idea: a "full history, all stores" view on a store's member page that asks the head office.
- Returns in one store of a ticket sold in another; vouchers across stores (live questions).
- Design discussion of 2026-10-04: the design is rewritten (version 2) and steps 6 and 7 are recut from it (see "Steps 6 and 7 — recut"). What is left for later is listed in the design, section 7.
- A new head office dashboard (today the head office home is the store's `Home.vue`) and head office reports (maybe the store reports with a store filter, to discuss; the queries need a version on the `ho_` tables).

Also:
- Item images from the head office (former task 6.8, removed from version 2.1 by Zein on 2026-10-04). Today images do not travel: a store keeps its own picture on a head office item and the pull never overwrites it. How they are stored and what sending them would take (an `imageVersion` in the item copy, a head office image endpoint, a store step that saves the file under its own id): `docs/modules/head-office.md`, "Catalogue owned by the head office", Images.
- Profile cleanup at step 9.3: presets for what the installation is, one file per machine outside git for where it runs.
- The group promotion fix (frontend 8c7ce9b) is not in release/1.12.0.
- The purchase invoice fix (backend be626d3) and the invoice fix (backend bc45f3a), lists and eligible documents without a date filter (`LocalDate.MIN`/`MAX` outside the SQL Server date range, 500), are not in release/1.12.0.
- Importing an existing member list into a head office (step 4 decision).
- BLs created in the franchisor's ERP.
- Store-to-store transfers without an ERP; goods sent back to the head office; a store asking the head office for goods.
- Cancelling a Sent BL that is not received yet (decided 2026-10-04: not in 7A). It needs a Cancelled status, the head office stock given back with a movement, and a rule for a store that confirmed offline before the cancellation reached it (its count wins: the BL becomes Received again and the stock goes out again).
- Price frozen on the BL line at validation (decided 2026-10-04: not in 7B, the invoice takes the supply price of its date). It would add a `supply_price` to `ho_delivery_line`, set at validation for a store whose deliveries are invoiced, and the invoice would use it.
- Credit notes for a supply invoice (cancellation or correction) and partial payments.
- A second purchase switch: a store buying head office items from its own suppliers.
- Network setup screen at the head office: a map of all the stores, checks of their settings, "add a store" models. Logged as an improvement, not scheduled.
- An item or a family limited to some stores; prices with dates; royalties; shared customers.
