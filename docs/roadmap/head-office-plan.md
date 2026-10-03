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

**`db/2.1.0/update.sql`: what it must contain** (written once, at the end of the plan; new `ho_` and `hol_` tables come through `ddl-auto` and are listed when the script is written)

- Step 3: `promotion.origin` `varchar(20)` null (null = local).
- Step 3: `ho_store.owner_catalogue`, `owner_customers`, `owner_promotions`, `owner_loyalty`, `owner_supply` `varchar(20)` null, and `ho_store.sales_upstreams` `varchar(50)` null (null = the store has not reported yet).

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
| 4 | Shared loyalty, part 1: members and earning | ParaFendri | Enrol only | Large | Not started |
| 5 | Shared loyalty, part 2: spending and returns | ParaFendri | No (decided 2026-10-03: no hold and confirm, selling services untouched) | Large | Not started |
| 6 | Catalogue owned by head office | Own stores without ERP, franchise | No | Medium | Not started |
| 7 | Shipments (BL) | Own stores without ERP, franchise | No (stock in only) | Large | Not started |
| 8 | Franchise profiles moved onto the model | Happyness | No | Medium | Not started |
| 9 | Cleanup: mode checks replaced by ownership questions | Everyone | Yes, mechanical | Medium | Not started |

Sizes are estimates from reading the code.

- ParaFendri is fully served after step 5. Before that it can already run as independent stores on Business Central, which works today.
- EMTOP is not affected by steps 1 to 8: it has no head office configured.
- Steps 1 and 2 are the base for every customer with a head office, Happyness included. Happyness does not wait for them only because today's franchise profiles already contain an older version of the same two things (one shared key, sales push to the admin). It moves onto steps 1 and 2 at step 8.
- Happyness stays on today's franchise profiles until step 8. Decided on 2026-10-02 (D7): Happyness goes live soon, so the order of the steps does not change.

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
- Step 4 needs a second dev store.

### Step 4 — Shared loyalty, part 1: members and earning

Goal: one member register for the network; points earned anywhere are known everywhere. Needs decision D4.

| Task | What | Test |
|---|---|---|
| 4.1 | Program and members as copies down (keys: program code, card number) | L1 |
| 4.2 | Enrol as a live question: head office issues the card number and checks the phone across the network. Local loyalty keeps today's path | L1 `LoyaltyMemberPhoneTest` green; new test for the head office path |
| 4.3 | Loyalty movements travel up with the ticket; head office applies them to its ledger and balances | L1 applying twice changes nothing |
| 4.4 | Store pages: program and members read-only except enrol; clear message when head office is unreachable | L2 |

L2 scenarios (two stores and a head office): enrol in A, visible in B; earn in A, balance in B after sync; head office stopped, enrol refused with a message and earning still works.

### Step 5 — Shared loyalty, part 2: spending and returns

Goal: points earned in one store can be spent in another, with no double spending. Needs decision D5. This is the only step that changes the sale itself.

| Task | What | Test |
|---|---|---|
| 5.1 | Head office: hold, confirm and release of points; old holds released automatically | L1 two holds on the same points: the second is refused |
| 5.2 | Store: when loyalty is owned by head office, `redeemPoints` asks for a hold before the sale is saved; the confirmation travels with the ticket | L1 `SaleCompletionLoyaltyStampTest` and `LoyaltyEarningTiersTest` unchanged and green; sale fails after a hold, the hold is released |
| 5.3 | Returns: movements travel up; head office applies them, balance never below zero | L1 `ReturnRefundLoyaltyTest` unchanged and green |
| 5.4 | Receipt and POS messages | L2 |

L2 scenarios: spend in B the points earned in A; head office stopped, spending is disabled and the sale goes through; with local loyalty everything is identical to today.

Done when: the checklist with local loyalty shows no difference. After this step ParaFendri is fully served.

### Step 6 — Catalogue owned by head office

Goal: stores without an ERP receive items and prices from head office.

| Task | What | Test |
|---|---|---|
| 6.1 | Items, families, sub-families and barcodes on the copies-down mechanism; price chosen by store kind | L1 |
| 6.2 | Store: `fromFranchiseAdmin` generalised to an origin; read-only rules; `catalogue.allow-local` | L1 same answers as today for a franchise customer |
| 6.3 | Customers as an optional domain | L1 |

### Step 7 — Shipments

Goal: head office sends goods to a store with a BL; the store confirms and its stock goes up.

| Task | What | Test |
|---|---|---|
| 7.1 | Head office: shipment document (header, lines, store, status) and its page | L1 |
| 7.2 | Store: pull, reception screen, confirm, stock in with a stock movement, acknowledge | L1 confirming twice adds stock once |
| 7.3 | Franchise store: invoice created from the shipment at the franchise price | L1 |
| 7.4 | Head office stock decreases on shipment | L1 |

### Step 8 — Franchise profiles moved onto the model

Goal: a franchise network runs as a head office and stores; the franchise modes disappear. Needs decision D7.

| Task | What | Test |
|---|---|---|
| 8.1 | Settings presets that replace `franchise-admin` and `franchise-customer` | L1 truth table |
| 8.2 | Migration of a franchise install: stores list from the customers' location codes, cursors, keys | L2 on a copy of the franchise databases |
| 8.3 | Remove `/franchise/**` code once no install uses it | L3 |

### Step 9 — Cleanup of the mode checks

Goal: no more `isStandalone` in the code; every check asks an ownership question.

| Task | What | Test |
|---|---|---|
| 9.1 | Backend: the 51 checks replaced file by file | L1 old and new answers identical for every profile |
| 9.2 | Frontend: the about 120 references replaced | L3 |
| 9.3 | Profile files renamed to presets; `deployment-modes.md` rewritten | L3 |

## 5. Later, not scheduled

- Returns and vouchers across stores (live questions).
- A new head office dashboard: today the head office home is the store's `Home.vue`.
- Head office reports: maybe the store reports with a store filter, to discuss; the queries need a version on the `ho_` tables.
- Profile cleanup at step 9.3: presets for what the installation is, one file per machine outside git for where it runs.
- The group promotion fix (frontend 8c7ce9b) is not in release/1.12.0.
- Importing an existing member list into a head office (step 4 decision).
- Shipments created in the franchisor's ERP.
- Store-to-store transfers without an ERP.
