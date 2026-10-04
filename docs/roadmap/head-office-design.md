# ZS Retail — Head office design

Status: version 2, rewritten on 2026-10-04 after the design discussion with Zein before step 6. Version 1 (2026-10-02) is replaced by this file. What steps 0 to 5 built is unchanged; sections 3.1, 3.2 and 3.7 describe it. Sections 3.3 to 3.6 (items, prices, goods, invoices) are new and are built by steps 6 and 7 (see `docs/roadmap/head-office-plan.md`).

Design principle (Zein, 2026-10-03): "No specific cases, everything configurable, so we could make a solution dynamic and cover all needs."

## 1. Goals

- One model that covers every customer type: one shop, one shop with an ERP, a company with many stores (with or without an ERP), franchise networks, and mixes of them.
- No code that knows the word "franchise". A type of customer is a set of settings.
- No regression for existing installs: EMTOP on Business Central, and the franchise profiles being delivered to Happyness.
- New code is added beside existing code. An existing profile keeps working unchanged until it is migrated on purpose.

## 2. The model

### 2.1 Two installation types

| Type | What it does | What it never does |
|---|---|---|
| Store | Sells: sessions, tickets, payments, returns, printing. Always works from its own database | Manage other stores |
| Head office | Manages stores: decides shared data, sends goods, receives copies of what the stores do | Sell. It has no cashier session and no ticket |

- Same application and same WAR. One setting chooses the type: `node.type=STORE` (default) or `HEAD_OFFICE`.
- A head office is optional. One shop alone has none.
- A store is linked to one head office only. A customer may install several head offices; each one is then a separate network (its own items, prices, stock, loyalty register and reports). One head office is enough for a network that mixes own stores and franchise stores.

### 2.2 Every store answers six questions

| # | Question | Possible answers |
|---|---|---|
| 1 | Who decides what the store sells? | The store, the head office, or an ERP |
| 2 | Who decides the selling price? | The store, the head office (imposed or recommended), or an ERP |
| 3 | Where do the goods come from? | The head office (by BL), the store's own suppliers, or an ERP |
| 4 | Does the store pay the head office for the goods? | No (same company) or yes (another company) |
| 5 | Who decides promotions and loyalty? | The store or the head office, each one separately |
| 6 | Whose customers are they? | The store's, an ERP's; shared by the network is a later option |

Each answer is set per store, so one network can mix stores with different answers.

"Franchise" is not something the application knows. A franchise store is a store whose answer to question 4 is yes. The own / franchise label on a store (`Store.kind`) is not used by any rule and leaves the Stores page.

### 2.3 Customers as columns

| Question | One shop alone | EMTOP | ParaFendri | Many stores, no ERP | Happyness (franchise) |
|---|---|---|---|---|---|
| 1. What it sells | Store | ERP | ERP | Head office | Head office |
| 2. Selling price | Store | ERP | ERP | Head office, base or list | Head office, base or list |
| 3. Goods from the head office | No head office | No, ERP | No, ERP | Yes, by BL | Yes, by BL |
| 3. Own suppliers | Yes | No, ERP | No, ERP | Off by default | Off by default |
| 4. Deliveries invoiced | No head office | No | No | No | Yes, at the supply price |
| 5. Promotions | Store | Store | Head office | Head office | Store |
| 5. Loyalty | Store | Store | Head office | Head office | Store |
| 6. Customers | Store | ERP | ERP | Store | Store |
| Head office sees the sales | No head office | No head office | Yes | Yes | Yes |

- ParaFendri (for now): the ERP keeps the stock and handles the shipments between its locations. Its head office ships nothing: no BL, no invoice, no purchases and no stock pages. It decides promotions and loyalty, and sees the sales.
- The Happyness answers on own suppliers and on imposed prices are assumptions to confirm with the customer; both are switches.

### 2.4 Three movements

Everything that travels between a store and the head office is one of three movements.

| Movement | Direction | What travels | If the head office is unreachable |
|---|---|---|---|
| Copies down | Head office to store | What the head office decides: promotions, loyalty program and members, items and prices, BLs and invoices addressed to the store | The store keeps working with its last copy |
| Documents up | Store to head office | Tickets, returns, session closings, loyalty movements, BL confirmations, stock | Documents wait and are sent later; the till is never blocked |
| Live questions | Store asks, head office answers | Shared data at the moment of use: the phone check of an enrol, a fresh balance at the till, a member change; later vouchers and tickets of another store | Only that one feature is unavailable, with a clear message; the sale continues |

Rules:

- The store always starts the exchange. The head office never calls a store. Only the head office must be reachable by the stores, over the LAN/VPN.
- Records are matched by business code, never by database id: item code, family code, promotion code, card number, sales number, BL number.
- The cursor of the copies down comes from the head office, not from the store's clock.
- No conflict is possible by construction: data that has an owner flows one way, documents are created in one place and only added, and shared balances change only at the head office.

## 3. Design by question

### 3.1 The stores list and the settings of a store (built, extended by steps 6 and 7)

- The head office shows stores, never locations. A store's code is its `DEFAULT_LOCATION` value; for an ERP customer that is its location code in the ERP.
- One API key per store. Endpoints under `/ho/**`.
- The Stores page shows, per store: online or offline, version, who decides each domain, and the store's switches.

Settings of a store, set at the head office on the store's row and sent to the store with the heartbeat answer:

| Setting | Default | Built by |
|---|---|---|
| May edit members | Off | Step 4 |
| May adjust points | Off | Step 5 |
| Spending points needs the head office online | Off | Step 5 |
| Enrolling needs the head office online | Off | Step 5 |
| Selling price list | None (base price) | Step 6 |
| May change its selling prices | Off | Step 6 |
| Can purchase from its own suppliers | Off | Step 6 |
| Deliveries are invoiced | Off | Step 7B |
| Supply price: a supply price list, or a percentage off the selling price | None | Step 7B |
| Invoice rhythm: one invoice per BL, or grouped | Per BL | Step 7B |
| Billing details: legal name, tax number, address | Empty | Step 7B |

Who decides each domain (`ownership.catalogue`, `ownership.customers`, `ownership.promotions`, `ownership.loyalty`, `ownership.supply`) and where the sales go (`sales.upstream`) are today written in the store's own properties file and reported to the head office with the heartbeat. Moving them to the Stores page is a possible cleanup at step 9.

### 3.2 Sales copies (built, step 2)

- Every ticket, return and session closing of a linked store reaches the head office, whatever the store's six answers are.
- Store side: a tracking table beside the documents. No new column on `sales_header`, and the ERP field `synchronizationStatus` is not reused.
- Head office side: consolidation tables keyed by store code + document number.

### 3.3 What the store sells (question 1, step 6)

"What the store sells" is the list of items with their families, sub-families and barcodes. Price and stock are separate questions.

Rule: each store's item list has one owner. The owner edits; everyone else only consults.

When the head office is the owner:

1. One item, one code, everywhere. Item `B001` is the same item in every store, which lets the head office add up sales, stock and deliveries across stores.
2. Every item goes to every store in the first version. Limiting an item or a family to some stores is a later addition.
3. A head office item is consult-only at the store.
4. A store's existing item with the same code becomes the head office item. Its other items stay sellable as the store's own.
5. An item deleted or deactivated at the head office becomes inactive at the store. It is never deleted there, so old tickets stay readable.
6. A store's own items are not a separate setting. A store may create its own items only when it can purchase from its own suppliers (3.5). These items stay marked as the store's own; the head office sees them in the sales and does not manage them.

### 3.4 The selling price (question 2, step 6)

A network has two prices that are never mixed: the selling price (what the customer pays at the till) and the supply price (what the store pays the head office for the goods, 3.6).

When the head office decides the selling price:

1. A base price on the item: the normal price for the network.
2. Price lists, managed only at the head office. A list is named (`TOURIST`, `AIRPORT`) and holds only the items whose price differs from the base price.
3. One list per store, or none. A store with no list sells at the base price. A store that needs prices of its own gets a list used by that store only. A network with the same prices everywhere creates no list.
4. Imposed or recommended, per store: the switch "may change its selling prices". Off: the price is imposed. On: the store can put its own price on an item, and keeps it.

The head office works out the price for each store and sends the item with that one price. The store never sees a list, and the till works the same in every store: `PricingService` and the selling code are not changed.

Example: `B001` has a base price of 10.000; the list `TOURIST` says 11.000. Store A has no list and sells at 10.000; stores B and C have `TOURIST` and sell at 11.000. Changing the list line to 11.500 reaches B and C only.

Outside this part: promotions (a temporary reduction on top of the price, 3.7). Later, on the same model: prices with start and end dates, prices per customer group.

### 3.5 Where the goods come from (question 3, step 7A; the purchase right in step 6)

A store's stock goes up in two ways, each one a setting on the store:

| Setting | What it means |
|---|---|
| Receives goods from the head office (`ownership.supply=HEAD_OFFICE`) | The head office sends goods with a delivery note (BL) |
| Can purchase from its own suppliers | The store buys by itself: purchases, suppliers and its own items |

With an ERP, neither applies: the ERP keeps the stock.

The purchase right (Zein, 2026-10-04):

- Off by default: only the head office purchases.
- When on, it covers the store's own items only. Head office items come only from the head office.
- A store buying head office items from its own suppliers (suppliers who deliver directly to the stores) would be a second switch, added only if a customer needs it.
- A store that decides its own items (one shop alone) purchases as today; the right concerns only a store whose items are decided by the head office.

The BL:

1. The head office creates a BL for one store, with items and quantities.
2. The store receives it, counts the goods and confirms the quantities it really received.
3. The store's stock goes up by what it confirmed. A difference between sent and received is kept and shown at the head office.

| Status | When | Who changes it |
|---|---|---|
| Draft | The head office is preparing it | The head office |
| Sent | The head office validates it. Its stock goes down and the store can see the BL | The head office |
| Received | The store confirmed the quantities. The store's stock goes up | Automatic, from the store's confirmation |
| Invoiced | The BL is in an invoice (only for stores that pay) | Automatic, when the invoice is created |

- A BL always goes to a store. There is one document and one kind of destination; the store plays the role of the "location" (same company) or of the "customer" (franchise) by its settings.
- If the head office cannot be reached when the store confirms, the store's stock still goes up at once and the confirmation is sent later.
- A BL that stays "Sent" is visible in the head office list of BLs.

Who keeps the stock:

- Each store keeps its own stock, so it works without the head office.
- The head office is also a warehouse: it buys from suppliers, keeps its own stock and sends goods. It still never sells at a till.
- The head office receives a copy of every store's stock.

Later, on the same model: a store sending goods back to the head office, a transfer from one store to another, a store asking the head office for goods, BLs created in the head office's ERP (Happyness).

### 3.6 Does the store pay (question 4, step 7B)

- Same company: goods move, no money. A BL only.
- Another company: the head office sells the goods to the store. A BL, then an invoice.

| Document | Its job | Who creates it |
|---|---|---|
| BL | Goods moved: the store's stock goes up | The head office |
| Invoice | Money owed: the store must pay the head office | The head office, from the BL |

The invoice:

- Only BLs with the status "Received" can be invoiced.
- It uses the quantities the store confirmed, not the quantities sent.
- One invoice takes one BL or several BLs of the same store. A BL is invoiced only once.
- Rhythm per store: one invoice per BL, created automatically, or one invoice that groups the BLs of a period.
- The billing details (legal name, tax number, address) come from the store's row.
- At the store it arrives automatically as a purchase invoice, consult-only. The store types nothing except the confirmation of the BL quantities.
- The supply price becomes the store's cost for the item, so its margin is right.
- At the head office each invoice is paid or unpaid, and the head office sees what each store owes.

The supply price, chosen per store:

1. A supply price per item: a base supply price on the item, plus supply price lists when some stores are charged differently (same mechanism as the selling price).
2. A percentage off the store's selling price (for example minus 30%), which fixes the store's margin.

Example: `B001`, selling price 10.000, a BL of 50 received. Store A (same company): no invoice. Store H1 (supply price 6.000): 50 × 6.000 = 300.000. Store H2 (selling price minus 30%): 50 × 7.000 = 350.000.

Later, on the same model: royalties (a percentage of the store's sales, computed from the ticket copies the head office already has).

This replaces today's franchise flow (ticket at the franchise admin, then invoice tagged with the customer's location, pulled by the franchisee as a purchase reception). The legacy flow is not touched until the migration step.

### 3.7 Promotions and loyalty (question 5, built, steps 3 to 5)

Two separate settings on the store; all four mixes are allowed.

| | Decided by the store | Decided by the head office |
|---|---|---|
| Promotions | The store creates and manages them; nothing is exchanged | Created once at the head office for all stores or chosen ones; the store only consults; its earlier promotions are switched off |
| Loyalty | Members and points belong to this store only | One register for the network: a member enrols, earns and spends in any store |

Loyalty decided by the head office, as built:

- Enrol: the store always creates the member, with a card number that carries its code; the phone is checked across the network when the head office answers; a duplicate found later is merged.
- Earn: computed inside the sale as before; the movement goes up.
- Spend: against the store's balance, never blocked by default; a fresh balance is asked when a member is selected; overspends are listed in a report at the head office.
- Returns: in the store that sold the ticket; the movements go up.
- The four switches per store (3.1), all off by default, so the till keeps working through a long outage.

For later, only when loyalty is shared between different companies: a report of points given in one store and spent in another.

### 3.8 Customers (question 6)

A loyalty member is a person who shops with the brand (card, phone, points). A customer is an account that a company invoices (name, tax number, address, price group).

Rule: a customer belongs to the company that invoices him.

| Case | Whose customers |
|---|---|
| Franchise | The store's, always |
| With an ERP | The ERP's, as today |
| One company, many stores, no ERP | The store's; shared is a later option |

Decided 2026-10-04: customers stay the store's (or the ERP's) in this plan. Shared customers (one list for the network, like shared loyalty) are useful only for business customers who buy in several stores of one company; they can be added later as one more owned list. The head office still sees the customer of each ticket (code and name in the ticket copy).

### 3.9 What the head office sees and does

| Group of pages | Content | Status |
|---|---|---|
| Stores | The stores, online or offline, each store's answers and switches | Built; new settings with steps 6 and 7 |
| What it decides | Items, base prices and price lists, promotions, loyalty program and members | Promotions and loyalty built; items and prices at step 6 |
| Goods and money | Its suppliers and purchases, its own stock, BLs, invoices to stores, what each store owes | Step 7 |
| What it sees | Tickets, returns and sessions of all stores; stock of all stores; dashboard and reports with a store filter | Sales built; stock at step 7A; dashboard and reports later |

A page exists on a head office only when it is useful for the network's settings: a head office whose stores get their goods from an ERP has no BL, invoice, purchase or stock page.

## 4. The ERP link does not move

- A store that is a location in the company's ERP keeps its own ERP sync, exactly as today.
- A store may have two upstreams at once (ERP and head office). They never carry the same data, and each has its own tracking.
- A head office may import from an ERP for its own needs (ParaFendri: the item list to target promotions, import only, never export).
- A head office with its own ERP that feeds items and BLs (Happyness) is a later addition. In that ERP a franchise store is a customer and an own store is a location; the store's row would carry that ERP code.

## 5. Compatibility with today

### 5.1 Today's profiles

When the ownership settings are absent they are derived from the existing properties, so no existing install changes its configuration.

| Today | Type | Catalogue | Customers | Promotions | Loyalty | Supply | Sales go to |
|---|---|---|---|---|---|---|---|
| `standalone` | Store | Local | Local | Local | Local | Local | Nowhere |
| `dynamics` (ERP) | Store | ERP | ERP | Local | Local | ERP | ERP |
| `franchise-customer` | Store | Head office | Local | Local | Local | Head office, plus local purchases | Head office |
| `franchise-admin` | Kept as it is until the migration step (it sells and supplies at the same time) | | | | | | |

### 5.2 Regression rules

1. With no head office URL configured, none of the new beans or schedulers exist.
2. With the new settings absent, behaviour is derived from the old properties and is identical.
3. No column or table is removed or renamed. The release ships its `update.sql`.
4. The selling services (`SalesHeaderService`, `PromotionCalculationService`, `PricingService`, `ReturnHeaderService`) are not changed.
5. The `erp/` package is not modified.
6. The legacy franchise profiles and endpoints are not modified until the migration step.

## 6. Facts from the code that this design relies on

Read on 2026-10-04 (backend `release/2.1.0`):

- `item.franchise_sales_price` is not what the franchisor charges: it is the selling price imposed on the franchise store's till (`FranchiseSyncService` copies it into the store's `unitPrice`, and it is required on every item in franchise admin mode). What the franchisor charges is the price on the admin's ticket, which becomes the invoice, then the store's purchase and its cost.
- A price list already exists for ERP customers: the `sales_price` table and `PricingService` (lowest valid price, fallback to `item.unitPrice`), switched on by `pos.pricing.enable-sales-price-group`. It is filled by the ERP import and the data import and has no edit screen. The design above does not use it at the store.
- Purchases, suppliers, stock and stock movements are tied to `isStandalone()`: 17 checks in `PurchaseHeaderAPI`, `VendorAPI`, `StockService` and `StockMovementService`.
- `Store.kind` (`OWN` / `FRANCHISE`) is saved by `StoreService`; no rule reads it.
- ERP sync is per store: `DynamicsNavRestClient` filters items on `DEFAULT_LOCATION` and prices on the responsibility center.
- `FranchiseSalesPushScheduler` reuses the ERP field `SalesHeader.synchronizationStatus`.
- Vouchers (`ReturnVoucher`) and return lookups (`findBySalesNumber`) are local to one database.
- The mode flags are read in 51 places in 16 backend files and about 120 places in 18 frontend files.
- Upgrades: `AppVersionGuard` stops the application when the database version differs.

## 7. Later, on the same model

- An item or a family limited to some stores.
- Prices with start and end dates; prices per customer group.
- A store buying head office items from its own suppliers (second purchase switch).
- Goods sent back to the head office; store-to-store transfers; a store asking for goods.
- BLs and items fed by the head office's ERP (Happyness).
- Royalties on the stores' sales.
- Shared customers.
- A report of loyalty points given in one store and spent in another.
- Return, in store B, of a ticket sold in store A; vouchers used in another store (live questions).
- A "full history, all stores" view of a member's movements at a store.
- A head office dashboard and reports with a store filter.
- The owners of each domain set on the Stores page instead of the store's file.
- Licensing of a head office instance.

## 8. Decisions

| # | Decision | Status |
|---|---|---|
| D1 | The head office runs on the customer's server; stores reach it over LAN/VPN | Decided 2026-10-02 |
| D2 | A head office with an ERP imports the item list from one reference location and never exports | Decided 2026-10-03 |
| D3 | When the head office decides promotions, the store only consults them | Decided 2026-10-03 |
| D4 | Enrolling is allowed offline; duplicates are merged later | Decided 2026-10-03 |
| D5 | Spending never needs the head office by default; per-store switches make a store strict | Decided 2026-10-03 and 04 |
| D6 | Returns and vouchers across stores | Open, after the plan |
| D7 | Happyness goes live on today's franchise profiles and migrates at step 8 | Decided 2026-10-02 |
| D8 | Remove the locations list from the store and keep two settings | Open, step 9 |
| D9 | No own / franchise label: a franchise store is a store with settings | Decided 2026-10-04 |
| D10 | Selling price: base price plus price lists at the head office, one list per store or none, one price per item sent to the store | Decided 2026-10-04 |
| D11 | The purchase right is off by default and covers the store's own items only | Decided 2026-10-04 |
| D12 | A BL always goes to a store; it is invoiced only once received, on the confirmed quantities | Decided 2026-10-04 |
| D13 | Customers stay the store's (or the ERP's); shared customers later | Decided 2026-10-04 |
| D14 | A store has one head office; several head offices are separate networks | Decided 2026-10-04 |
| D15 | Happyness: is the selling price imposed, and may a store buy from its own suppliers | Open, to ask the customer before step 8 |
| D16 | Happyness: supply price per item or a percentage off the selling price; invoice per BL or per period | Open, to ask the customer before step 7B |
