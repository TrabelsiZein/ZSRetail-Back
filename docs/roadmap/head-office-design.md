# ZS Retail — Head office design

Status: proposed on 2026-10-02 after reading the docs and the code (release 1.12.0). Each part is confirmed in the step that builds it (see `docs/roadmap/head-office-plan.md`).

This design replaces the idea of separate modes (ERP / standalone / franchise admin / franchise customer) with one model. The words "franchise admin" and "franchise customer" disappear as modes.

## 1. Goals

- One model that covers every customer type: one shop, one shop with an ERP, several own stores (with or without an ERP), franchise networks.
- No regression for existing installs: EMTOP on Business Central, and the franchise profiles being delivered to Happyness.
- New code is added beside existing code. An existing profile keeps working unchanged until it is migrated on purpose.

## 2. The model: 2 installation types, 1 table, 3 movements

### 2.1 Two installation types

| Type | What it does | What it never does |
|---|---|---|
| Store | Sells: sessions, tickets, payments, returns, printing. Always works from its own database. | Manage other stores. |
| Head office | Manages several stores: owns shared data and receives copies of their documents. | Sell. It has no cashier session and no ticket. |

- Same application and same WAR. One setting in the properties file chooses the type of the installation: `node.type=STORE` (default) or `HEAD_OFFICE`.
- This is not a user role. The user roles (`ADMIN`, `RESPONSIBLE`, `POS_USER`) and the role and permission tables are unchanged and exist in both types.
- A head office may run on the same machine as a store, but as a separate instance with its own database.
- The head office edits what it owns with the admin pages that already exist (promotions, loyalty program, members, items). The only new screens are: stores list with sync health, consolidated sales, shipments.

### 2.2 One ownership table

Every kind of data has exactly one owner. The owner edits it. Everyone else receives a read-only copy.

| Domain | Content | Possible owners |
|---|---|---|
| `CATALOGUE` | Items, families, sub-families, barcodes, prices | `LOCAL`, `HEAD_OFFICE`, `ERP` |
| `CUSTOMERS` | Customers | `LOCAL`, `HEAD_OFFICE`, `ERP` |
| `PROMOTIONS` | Promotions and promo codes | `LOCAL`, `HEAD_OFFICE` |
| `LOYALTY` | Program, members, points | `LOCAL`, `HEAD_OFFICE` |
| `SUPPLY` | How goods arrive and who keeps the stock | `LOCAL` (purchases), `HEAD_OFFICE` (shipments), `ERP` |

One more setting, `sales.upstream`, says where copies of tickets, returns and session closings go: nowhere, `ERP`, `HEAD_OFFICE`, or both.

The configuration of a customer is one column of this table:

| Domain | One shop, no ERP | EMTOP | ParaFendri | Own stores, no ERP | Franchise (Happyness) |
|---|---|---|---|---|---|
| Catalogue | Local | ERP | ERP | Head office | Head office |
| Customers | Local | ERP | ERP | Head office | Local |
| Promotions | Local | Local | Head office | Head office | Local |
| Loyalty | Local | Local | Head office | Head office | Local (head office possible) |
| Supply | Local purchases | ERP | ERP | Shipments | Shipments, invoiced |
| Sales go to | Nowhere | ERP | ERP + head office | Head office | Head office |

Details:

- **Rule of thumb for promotions and loyalty** (Zein, 2026-10-02): in a franchise network each store owns them; in a company with several own stores (ParaFendri) the head office owns them. Both are settings, so a customer can differ.
- **Loyalty owned by the head office** means one shared register: any store enrols a member, and every store sees all members and their points. With loyalty owned by the store, members and points are not visible in other stores.
- **Shared loyalty in a franchise network** has a money side: franchisees are separate companies, so one company would give the discount for points granted by another.
- **Local additions.** A store that receives data may also be allowed to add its own records: `catalogue.allow-local` (exists today as `franchise.customer.allow-local-items`) and `promotions.allow-local`. Every record carries its origin, and a record that came from elsewhere is read-only.
- **Franchise is a property of a store, not a mode.** In the head office stores list, each store is `OWN` or `FRANCHISE`. A franchise store's shipments are invoiced at the franchise price. Nothing else differs.
- **Mixed networks** (own stores and franchise stores under one head office) need nothing extra: it is decided store by store.

### 2.3 Three movements

Everything that travels between a store and the head office is one of three movements.

| Movement | Direction | What travels | How | If the head office is unreachable |
|---|---|---|---|---|
| Copies down | Head office to store | Data the head office owns: promotions, loyalty program and members, catalogue, shipments addressed to the store | The store pulls what changed since its last cursor and saves it by business code | The store keeps working with its last copy |
| Documents up | Store to head office | Tickets, returns, session closings, loyalty movements | The store pushes; the head office saves by store code + document number, so a repeat is harmless | Documents wait and are sent later; the till is never blocked |
| Live questions | Store asks, head office answers | Shared balances: enrol a member, spend points; later vouchers and tickets of another store | A direct call at the moment of use | Only that one feature is unavailable, with a clear message; the sale continues |

Rules:

- **The store always starts the exchange.** The head office never calls a store, so stores need no public address. Only the head office must be reachable, over HTTPS.
- **Records are matched by business code, never by database id**: item code, family code, promotion code, card number, sales number.
- **The cursor comes from the head office**, not from the store's clock. (Today `FranchiseSyncService` saves the store's own `now()`, which can miss changes.)
- **No conflict is possible by construction**: owned data flows one way, documents are created in one place and only added, and shared balances change only at the head office.

## 3. The ERP link does not move

- A store that is a location in the company's ERP keeps its own ERP sync, exactly as today: imports filtered on its `DEFAULT_LOCATION` and responsibility center, export of tickets, returns and sessions, ERP jobs page and ERP communications log in the store admin.
- A store may have two upstreams at once (ERP and head office). They never carry the same data, and each has its own tracking.
- A head office may import from an ERP for its own needs. For ParaFendri it needs the item list to target promotions, so it runs the existing import jobs with the export jobs off. For a franchisor with an ERP (Happyness), its ERP would later feed items and shipments. A head office never exports store tickets to the ERP.

## 4. Design by domain

### 4.1 Sales copies (documents up)

- Content: ticket header and lines (item code, quantity, prices, discounts, discount source, promotion code), payments, loyalty fields; returns; session closings.
- Store side: a tracking table beside the documents (document type, id, status, attempts, last error). No new column on `sales_header`, and the ERP field `synchronizationStatus` is not reused. The push job finds documents that are new or changed since their last push.
- Head office side: dedicated consolidation tables keyed by store code + document number. The store tables are not reused there, because a ticket points to a session, a user and a customer that do not exist at the head office.
- Sales numbers are already unique across stores: `generateSalesNumber()` builds location code + date + daily sequence.

### 4.2 Promotions owned by the head office

- Created at the head office with the existing promotions page, for all stores or a chosen list.
- Targets travel as codes (item, group items, family, sub-family) and the store resolves them to its local records.
- At the store the promotion is saved in the existing `promotion` table with origin = head office, read-only. The calculation engine is not changed: it keeps reading the local table.
- Open point for the step: a promotion that targets an item the store does not have yet.

### 4.3 Loyalty owned by the head office

- The head office holds the program, the member register and the ledger of all point movements, in the existing loyalty tables and pages.
- Each store keeps a local copy of the program and the members (copies down), so search and earning work offline.
- **Enrol**: live question. The head office issues the card number and checks the phone number across the whole network.
- **Earn**: computed locally inside the sale exactly as today, then sent up with the ticket. Other stores see the new balance at their next pull.
- **Spend**: live question. Points are held at the head office when the cashier validates the payment and confirmed when the ticket copy arrives. A hold with no ticket after a delay is released automatically. Offline: no spending, earning still works.
- **Returns**: in the store that sold the ticket; the movements go up like any other.
- With `LOYALTY=LOCAL` nothing changes: the current code path stays as it is.

### 4.4 Catalogue owned by the head office

- Items, families, sub-families, barcodes and prices travel as copies down, on the same mechanism as promotions.
- The price sent depends on the store: retail price for an own store, franchise price for a franchise store.
- This is today's franchise item sync (`FranchiseItemSyncController`, `FranchiseSyncService`, flag `fromFranchiseAdmin`), rebuilt on the common mechanism.

### 4.5 Shipments

- The head office creates a shipment (BL) for a store. The store pulls it, confirms reception, its stock goes up, and the head office is notified.
- Own store: a stock transfer, no invoice. Franchise store: the same BL plus an invoice at the franchise price.
- This replaces today's franchise flow (POS ticket, then invoice tagged with the customer's location, pulled by the franchisee as a purchase reception in `FranchiseSupplyReceptionService`). It is the one place where franchise behaviour changes.

### 4.6 Stores list and security

- Head office table of stores: code (the store's `DEFAULT_LOCATION`), name, kind (`OWN` / `FRANCHISE`), one API key per store, active flag, last contact, application version.
- Naming: the head office shows stores, never locations. A store's code is its `DEFAULT_LOCATION` value; for an ERP customer that is its location code in the ERP.
- New endpoints under `/ho/**`. Today's `/franchise/**` endpoints (one shared key for all stores) are left untouched until the migration step.

## 5. Compatibility with today

### 5.1 Today's profiles are columns of the table

When the new settings are absent, they are derived from the existing properties, so no existing install changes its configuration.

| Today | Type | Catalogue | Customers | Promotions | Loyalty | Supply | Sales go to |
|---|---|---|---|---|---|---|---|
| `standalone` | Store | Local | Local | Local | Local | Local | Nowhere |
| `dynamics` (ERP) | Store | ERP | ERP | Local | Local | ERP | ERP |
| `franchise-customer` | Store | Head office | Local | Local | Local | Head office, plus local purchases | Head office |
| `franchise-admin` | Kept as it is until the migration step (it sells and supplies at the same time) | | | | | | |

### 5.2 Regression rules

1. With no head office URL configured, none of the new beans or schedulers exist (same technique as the franchise code today: `@ConditionalOnProperty`).
2. With the new settings absent, behaviour is derived from the old properties and is identical.
3. No column or table is removed or renamed. Every release ships its `db/<version>/update.sql`.
4. The selling services (`SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`) are changed only by the loyalty steps, and only on the path where loyalty is owned by the head office.
5. The `erp/` package is not modified.
6. The legacy franchise profiles and endpoints are not modified until the migration step.

## 6. Facts from the code that this design relies on

- ERP sync is per store: `DynamicsNavRestClient` filters items on `DEFAULT_LOCATION` and prices on the responsibility center; ticket, session and return export are about 1,200 lines (`TicketExportService`, `SessionExportService`, `ReturnExportService`).
- Franchise sync is about 1,100 lines (three services, five controllers). It sends items, families and barcodes down, invoices down, and a sales summary up (totals and lines only).
- `FranchiseSalesPushScheduler` reuses the ERP field `SalesHeader.synchronizationStatus`, so a store cannot have two upstreams today.
- Loyalty is per database: `redeemPoints` and `earnPoints` run inside the sale transaction, members are not synced, and `generateCardNumber()` gives `LYL-000001` in every store.
- Vouchers (`ReturnVoucher`) and return lookups (`findBySalesNumber`) are local to one database.
- The mode flags are read in 51 places in 16 backend files and about 120 places in 18 frontend files. `isStandalone` answers four different questions at once: no ERP, master data editable, stock kept locally, purchases enabled.
- Tests: 6 plain JUnit 5 classes with in-memory stubs, no Spring context.
- Upgrades: `AppVersionGuard` stops the application when the database version differs, so each release needs its `update.sql`.

## 7. Not covered yet

- Return, in store B, of a ticket sold in store A.
- Vouchers and gift cheques used in another store.
- Store-to-store transfers without an ERP.
- Shipments created in the franchisor's ERP (Happyness).
- Head office dashboards beyond the consolidated sales list.
- Licensing of a head office instance.

The first two are live questions and fit the model. None is needed to start.

## 8. Open decisions

| # | Decision | Needed before |
|---|---|---|
| D1 | Where the head office is hosted and how stores reach it (customer server, VPS, VPN) | Step 1 |
| D2 | How the ParaFendri head office gets the item list (import from BC with a reference location) | Step 3 |
| D3 | May a store with head office promotions also create its own | Step 3 |
| D4 | Is enrolling a member allowed offline (recommended: no in the first version) | Step 4 |
| D5 | How reliable the internet is in ParaFendri stores (spending points needs the head office) | Step 5 |
| D6 | Does ParaFendri need returns and vouchers across stores at launch | After step 5 |
| D7 | Decided 2026-10-02: Happyness goes live soon on today's franchise profiles and migrates at step 8 | Decided |
| D8 | Remove the locations list from the store and keep two settings, `DEFAULT_LOCATION` and `RESPONSIBILITY_CENTER`. Today the list is used only to pick the default location and to give the responsibility center that filters NAV prices and discounts (`DynamicsNavRestClient`); ticket and return export already read `RESPONSIBILITY_CENTER` from the general setup. No sale, session or stock record points to a location | Step 9 |
