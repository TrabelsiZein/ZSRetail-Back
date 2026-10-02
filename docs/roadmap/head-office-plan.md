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

**Branch and release**

- One branch per step, `feature/ho-step-N`, from the current release branch. It is merged only after the regression checklist passes.
- A step that ships is a version: bump the version and add `db/<version>/update.sql` (`AppVersionGuard` blocks the start otherwise).

## 2. Test levels

| Level | What | When |
|---|---|---|
| L1 | Unit tests: plain JUnit 5 with in-memory stubs, like the 6 existing classes | Every task |
| L2 | Two instances on the dev PC: a head office and a store, each with its own database (same pattern as today's franchise pair: backends on 444 and 888, frontends on 8080 and 8081) | End of every step |
| L3 | Regression checklist on the existing profiles (ERP dev, standalone, franchise pair) | Before every merge |

## 3. Overview

| Step | Result | Needed first by | Changes selling code? | Size | Status |
|---|---|---|---|---|---|
| 0 | Foundations: vocabulary and safety net, no behaviour change | Everyone | No | Small | In progress |
| 1 | Head office installation and stores list: a store shows as online | ParaFendri; Happyness from step 8 | No | Medium | Not started |
| 2 | Sales copies up: tickets, returns, sessions of every store visible at head office | ParaFendri; Happyness from step 8 | No | Medium | Not started |
| 3 | Promotions owned by head office | ParaFendri | No (engine untouched) | Medium | Not started |
| 4 | Shared loyalty, part 1: members and earning | ParaFendri | Enrol only | Large | Not started |
| 5 | Shared loyalty, part 2: spending and returns | ParaFendri | Yes, only when loyalty is owned by head office | Large | Not started |
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

L2 scenarios: the store appears online; head office stopped, the store keeps selling and shows offline; wrong key is rejected.

Done when: L2 passes and the checklist shows no difference on the existing profiles.

### Step 2 — Sales copies up

Goal: every ticket, return and session closing of a store reaches the head office, without touching the selling code.

| Task | What | Test |
|---|---|---|
| 2.1 | Store: tracking table and the query that finds documents to send (new, or changed since last push) | L1 a ticket changed after sending is sent again |
| 2.2 | Payloads for ticket (header, lines, payments, loyalty fields), return and session closing, built from codes, not ids | L1 mapper |
| 2.3 | Head office: consolidation tables and receiving endpoints, saved by store code + document number | L1 a repeated push creates one row |
| 2.4 | Store: push job with retry; pending, sent and error counts on the status card | L1 retry after error |
| 2.5 | Head office page "Sales by store": list, filters by store and dates, ticket detail | L2 |

L2 scenarios: a sale appears at head office; head office stopped, tickets wait then catch up; an ERP store with a head office shows the NAV status and the head office status moving independently.

Done when: L2 passes, and an ERP store's NAV export is unchanged.

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
- Head office dashboards.
- Shipments created in the franchisor's ERP.
- Store-to-store transfers without an ERP.
