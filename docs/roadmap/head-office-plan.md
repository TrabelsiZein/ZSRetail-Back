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
- Step 4: `loyalty_member.origin` and `loyalty_program.origin` `varchar(20)` null (null = local); `ho_store.can_edit_members` and `ho_store.can_adjust_points` `bit` null (null = false). New tables through `ddl-auto`: `ho_loyalty_alias`, `ho_loyalty_movement`, `hol_loyalty_member_copy`, `hol_loyalty_movement_copy`.
- Step 5: `ho_store.redeem_requires_online` `bit` null (null = false). The new permission `read:admin-headoffice-loyalty-overspends` is added at startup (no script).

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
| 4 | Shared loyalty, part 1: members and earning | ParaFendri | No (enrol and member changes in LoyaltyAPI and LoyaltyService hooks; the four selling services untouched) | Large | Backend done 2026-10-03 on feature/ho-step-4 (see "Step 4 backend"); frontend and L2 to come |
| 5 | Shared loyalty, part 2: spending and returns | ParaFendri | No (decided 2026-10-03: no hold and confirm, selling services untouched) | Large | Backend done 2026-10-03 on feature/ho-step-4 (see "Step 5 backend"); frontend and L2 with step 4 |
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
| 5.4 | Receipt and POS messages | L2 |

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

Still owed: the step 5 frontend (in progress in the frontend repo) and its screen check, then the merge of steps 4 and 5 into release/2.1.0, the `update.sql` lines of section 1.

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
