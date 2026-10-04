# Head Office Module

**Status**: in progress. Task 1.1 done: installation type, `headoffice-dev` profile, guards. Task 1.2 done: stores list and API keys. Task 1.3 done: store key filter on `/ho/**`, `GET /ho/ping`. Task 1.4 done: the store's heartbeat to the head office (`POST /ho/heartbeat`, head office link on the store). Task 1.5 done: computed status on the Stores page, "Head office link" page on the store. Task 1.6 done: separate head office routes and menu, horizontal layout on a head office. Step 2 in progress: task 2.1 done (the store's tracking table and the search for documents to send, see "Sales copies"); task 2.2 done (the copies of a ticket, a return and a session closing); task 2.3 done (consolidation tables and `POST /ho/sales/*` on the head office); task 2.4 done (the store's push job with retry, counts on `GET admin/holink/status`). Task 2.5 backend done (consolidated sales API, home cards, page permissions; see "Consolidated sales API"; its pages to come); task 2.6 backend done (jobs with editable frequency and run now, exchange log; see "Head office link: jobs and exchange log"); the pages of 2.5 and 2.6 come with the frontend session. Step 3 in progress: task 3.1 done (the copies down mechanism, see "Copies down"); task 3.2 done (`origin` on `promotion` and the write guards, see `docs/modules/promotion.md`); task 3.3 done (target stores, payload by codes, received promotions, network usage count; see "Promotions owned by the head office"); task 3.5 done (missing targets WAITING and retried, tracking table, `GET admin/holink/received/{domain}`, counts in the link status). Rule fix: on a store whose promotions are local every promotion is written as before, whatever its origin (`docs/modules/promotion.md`). Task 3.6 done (what each store owns, sent with the heartbeat, see "Store API"). Task 3.4 done (head office with an ERP: imports only, export jobs never run, ERP reference location, profile `headoffice-dynamics-dev`; see "Head office with an ERP"). Step 3 frontend done: task 3.0 (lint) and the pages of tasks 3.2 to 3.6, see "Step 3 pages (frontend)". Step 4 backend done (shared loyalty: members and earning, see "Shared loyalty (step 4)"): part 1 the head office side (register, copies down, members and movements up, phone check, member edit, store rights), part 2 the store side (enrol, `LOYALTY_PUSH`, `LOYALTY` pull, member changes through the head office, link page API); steps 4 and 5 done and merged (frontend and L2 included). Step 6 backend done (catalogue and selling prices decided by the head office, price lists, store rights and guards; see "Catalogue owned by the head office (step 6)" and "Price lists (task 6.4)"); step 6 done and merged (frontend and L2 included), task 6.8 (images) after step 7B. Step 7A done (backend, frontend, L2 of 12 scenarios) (the head office as a warehouse, BLs, reception at the store, confirmation up, stock of the stores; see "Head office as a warehouse (task 7A.1)", "BLs (task 7A.2, head office)", "BLs at the store (tasks 7A.3, 7A.4)", "Stock of the stores (task 7A.5)"); its pages: "Step 7A pages (frontend)", four sections. Step 7B done (backend, frontend, L2 of 13 scenarios) (supply prices, invoices at the head office, invoices received at the store; see "Supply prices and invoicing settings (step 7B, part 1)", "Supply invoices (step 7B, part 2)", "Supply invoices at the store (step 7B, part 3)"); its pages: "Step 7B pages (frontend)", two sections, and "Network stock: "below zero only" through the API". Target model and steps: `docs/roadmap/head-office-design.md` and `docs/roadmap/head-office-plan.md`.

### Overview
- Two installation types, same WAR: a **store** sells; a **head office** manages several stores and never sells (no cashier session, no ticket).
- Set by `node.type=STORE` (default) or `node.type=HEAD_OFFICE`. Not a user role: `ADMIN`, `RESPONSIBLE`, `POS_USER` and the role tables are unchanged.
- A head office runs as its own instance with its own database, possibly on the same machine as a store.

### Installation type
- Backend: `ApplicationModeService.getNodeType()` and `isHeadOffice()`, resolved at startup by `config/NodeOwnership.java` (see `docs/deployment-modes.md`, "Ownership model").
- Head office only beans: `@ConditionalOnHeadOffice` (`config/OnHeadOfficeCondition.java`) reads `node.type` with the same parsing as the startup (`NodeOwnership.nodeTypeOf`: trimmed, case-insensitive, `STORE` when absent). On a store such a bean is not created, so its endpoints answer 404 (except under `/ho/**`, which answers 401 on a store: see "Store API").
- `GET /config` returns `nodeType`; the frontend store exposes the getter `appConfig/nodeType`.
- On a head office, sales go nowhere when `sales.upstream` is absent, whatever the mode flags.

**Startup checks** (the application does not start):

| Head office with | Message starts with |
|---|---|
| `franchise.admin=true` or `franchise.customer=true` | `Invalid combination: node.type=HEAD_OFFICE with franchise...=true` |
| an explicit owner `HEAD_OFFICE` (`ownership.<domain>`) | `Invalid value 'HEAD_OFFICE' for property ownership.<domain>: on a head office ... the owner cannot be HEAD_OFFICE` |
| a non-empty `sales.upstream` | `Invalid value '<value>' for property sales.upstream: a head office ... never sells` |
| `headoffice.offline-after-seconds` below 1 or not a whole number (task 1.5) | `Invalid value '<value>' for property headoffice.offline-after-seconds: a whole number of seconds, at least 1` |

An explicit owner `ERP` or `LOCAL` is accepted (for example a head office that imports items from the ERP, decision D2).

### Guards (task 1.1)
Backend:
- **No cashier session.** `CashierSessionService.openSession` and `save()` of a **new** session throw `IllegalStateException("This installation is a head office: cashier sessions cannot be opened.")`. `POST /cashier-session/open` answers 400 with that plain string (shown by `OpenSession.vue`); the generic `POST /cashier-session` answers 500 with the same string. Existing sessions are saved as before.
- Every selling endpoint that needs an open session (`process-sale`, `save-pending`, `complete-pending`, `cancel-pending`, `process-return`) is therefore refused too, with today's "No open cashier session found".
- **No cashier login.** `JWTAuthenticationFilter.successfulAuthentication`: when `isHeadOffice()` and the user's `AppRole.isPosRole` is true (the flag the login response sends as `isPosRole`), the answer is 403 `{"code":403,"msg":"This installation is a head office: cashier accounts cannot sign in here."}` and no token. `Login.vue` shows `msg`. On a store the check is not evaluated.
- Known gap, out of task 1.1: the generic CRUD endpoints (`POST`/`PUT` on `/sales-header`, `/payment`, `/sales-line`, `/return-header`, `/return-voucher`) are not guarded, on a head office as on a store.

Frontend: since task 1.6 a head office has only its own routes (see "Head office pages and menu"), so no POS route and no selling page exists there.
- Router guard (`src/router/index.js`), before the cashier session check: a store route redirects to its head office twin, or to the head office home. A head office never calls `/cashier-session/current`.
- `getHomeRouteForLoggedInUser` (`src/auth/utils.js`) returns the head office home (`admin-headoffice-home`) on a head office; `Login.vue` uses it instead of sending cashier roles straight to the POS.
- The six POS routes still carry `meta.pos: true` (store routes are not changed); nothing reads it since task 1.6.

### Head office pages and menu (task 1.6)
**Rule.** On a head office nothing is visible by default: a page exists there only when it is declared in `src/router/headoffice-routes.js`. A page shared with the store is never copied: its head office route points to the same component.

**Routes** (`src/router/headoffice-routes.js`, spread into the router):
- Paths start with `/headoffice`; route name and `meta.resource` are `admin-headoffice-<page>`, `meta.action` is `read`, and every route has `meta.headOffice: true`.
- A shared page names its store route in `meta.twinOf`.
- The guard step uses `installationRedirect(to)` in `src/navigation/head-office.js`, right after the login check:

| Installation | Route | Goes to |
|---|---|---|
| Head office | head office route, or a common page (`login`, `misc-not-authorized`, `error-404`, `admin-license-expired`: `HEAD_OFFICE_COMMON_ROUTES`) | itself |
| Head office | store route with a twin | the twin, params, query and hash kept (e.g. `/admin/users?userId=7` → `/headoffice/settings/users?userId=7`) |
| Head office | any other store route (POS, selling pages, reports, Head office link...) | head office home |
| Store | head office route | `home` |
| Store | Head office link page without `headoffice.url` | `home` (task 1.5, unchanged) |

- The later guard steps read `to.meta.twinOf || to.name`, so a twin follows the rules of its store route. The license exception covers the company information twin, which holds the license upload. "Standalone only" sends data import home on a head office without `application.standalone=true`.
- Store routes and the store menu entries are unchanged. The Stores route moved from `/admin/headoffice/stores` to `/headoffice/stores`; the old address shows the 404 page. The backend API keeps `/admin/headoffice/stores`.
- `HEAD_OFFICE_HIDDEN_ROUTES` and `HEAD_OFFICE_ONLY_ROUTES` (tasks 1.1, 1.2) are gone.

**Pages on a head office today:**

| Menu | Page | Head office route | Path | Store twin (`twinOf`) |
|---|---|---|---|---|
| Home | Dashboard | `admin-headoffice-home` | `/headoffice` | `home` |
| Network | Stores | `admin-headoffice-stores` | `/headoffice/stores` | none (head office only) |
| Sales | Tickets history | `admin-headoffice-tickets` | `/headoffice/tickets` | none (head office only, task 2.5) |
| Sales | Sessions | `admin-headoffice-sessions` | `/headoffice/sessions` | none (head office only, task 2.5) |
| Sales | Returns | `admin-headoffice-returns` | `/headoffice/returns` | none (head office only, task 2.5) |
| Catalogue | Items | `admin-headoffice-items` | `/headoffice/items` | `admin-item-management` |
| Catalogue | Families | `admin-headoffice-item-families` | `/headoffice/item-families` | `admin-item-families` |
| Catalogue | Sub-families | `admin-headoffice-item-subfamilies` | `/headoffice/item-subfamilies` | `admin-item-subfamilies` |
| Catalogue | Barcodes | `admin-headoffice-item-barcodes` | `/headoffice/item-barcodes` | `admin-item-barcodes` |
| Catalogue | Promotions | `admin-headoffice-promotions` | `/headoffice/promotions` | `admin-promotions` |
| Customers & loyalty | Customers | `admin-headoffice-customers` | `/headoffice/customers` | `admin-customers` |
| Customers & loyalty | Loyalty programs | `admin-headoffice-loyalty-programs` | `/headoffice/loyalty/programs` | `admin-loyalty-programs` |
| Customers & loyalty | Loyalty members | `admin-headoffice-loyalty-members` | `/headoffice/loyalty/members` | `admin-loyalty-members` |
| Customers & loyalty | Member functions (required to enrol a member) | `admin-headoffice-loyalty-member-functions` | `/headoffice/loyalty/member-functions` | `admin-loyalty-member-functions` |
| Customers & loyalty | Loyalty transactions (empty until step 4) | `admin-headoffice-loyalty-transactions` | `/headoffice/loyalty/transactions` | `admin-loyalty-transactions` |
| Customers & loyalty | Loyalty overspends (step 5) | `admin-headoffice-loyalty-overspends` | `/headoffice/loyalty/overspends` | none (head office only) |
| Settings | Company information and license | `admin-headoffice-company-information` | `/headoffice/settings/company-information` | `admin-company-information` |
| Settings | General setup | `admin-headoffice-general-setup` | `/headoffice/settings/general-setup` | `admin-general-setup` |
| Settings | Users | `admin-headoffice-users` | `/headoffice/settings/users` | `admin-users` |
| Settings | Roles | `admin-headoffice-roles` | `/headoffice/settings/roles` | `admin-roles` |
| Settings | Data import (standalone only) | `admin-headoffice-data-import` | `/headoffice/settings/data-import` | `admin-data-import` |
| ERP (with an ERP only, task 3.4) | ERP jobs | `admin-headoffice-erp-jobs` | `/headoffice/erp/jobs` | `admin-erp-jobs` |
| ERP (no menu link) | ERP job statistics, opened from ERP jobs; permission of ERP jobs | `admin-headoffice-erp-job-statistics` | `/headoffice/erp/jobs/statistics/:jobId?` | `erp-job-statistics` |
| ERP (with an ERP only) | ERP communications log | `admin-headoffice-erp-communications` | `/headoffice/erp/communications` | `admin-erp-communications` |
| ERP (with an ERP only) | ERP reference location | `admin-headoffice-erp-reference-location` | `/headoffice/erp/reference-location` | none (head office only) |

Each page is one route: details and edits are dialogs on the page. Every other store page is absent until its step adds it: print labels, sales prices and discounts, warranty, purchases, vendors, reports, ERP and franchise pages, and the selling pages hidden in task 1.1.

The home page is the store's `Home.vue`. On a head office its sales cards read `GET admin/headoffice/dashboard/today` (task 2.5, the copies of all stores, see "Consolidated sales API") and show Today's sales and Today's returns only, each half a row: open sessions and pending tickets are always 0 there. A store still reads `GET admin/dashboard/today` and shows its four cards. Its quick links come from the head office menu: Stores, Tickets history, Sessions, Items, Promotions, Customers, Loyalty members, Users.

The three Sales pages are described under "Consolidated sales API", "Head office pages".

**Menu** (`src/navigation/headoffice/index.js`): Home · Network · Sales · Catalogue · Customers & loyalty · ERP (only on a head office with an ERP, task 3.4) · Settings. The Sales links use the store menu's titles (Sales history, Sessions, Returns).
- Two levels only: links, and groups of links. A link names its head office route and takes the route's permission. A group has no permission of its own and shows when one of its links is allowed.
- An unknown route name throws when the module loads.
- Read on a head office only:

| Reader | Function |
|---|---|
| `VerticalNavMenu.vue`: the small-screen menu of the horizontal layout. The store filters stay for a store | `headOfficeMenu()` |
| `HorizontalNavMenu.vue` (top bar): a top-level group carries its title in `header`. The template's stub `src/navigation/horizontal/index.js` is gone | `headOfficeHorizontalMenu()` |
| `SearchBar.vue`: the menu links filtered with `$can`, instead of the store list and its legacy roles | `headOfficeSearchData()` |
| `Home.vue`: Stores, Items, Promotions, Customers, Loyalty members, Users, filtered with `$can` | `headOfficeQuickLinks()` |
| `RoleManagement.vue` | `headOfficePermissionGroups()`, `headOfficePermissionTitle()` |

- Data import is left out when not standalone, the same rule as on a store.
- The store menu is still `src/navigation/vertical/index.js`; only the Network group moved out (it was hidden on a store).

**Layout.** `appConfig/fetchAppConfig` sets the layout from `nodeType` before the app is mounted: `horizontal` (menu on top) on a head office, `vertical` on a store, and also when `/config` fails.
- The horizontal wrapper `src/layouts/horizontal/LayoutHorizontal.vue` has the Navbar (language, search, user menu), the license warning banner and the API error popup.
- Arabic right to left and small screens (below 1200 px the menu is the slide-in vertical menu) work in the Vuexy template. This was checked in the browser during the inventory, with the layout switched in memory; the finished task is checked at L2 (table below).
- Shared code, used by the horizontal wrapper only for now:
  - `src/layouts/mixinApiErrorPopup.js`: listens for `show-sweetalert-error` (emitted by `jwtService`) and shows the error. It removes its listener when the layout is destroyed, and its title is the i18n key `common.unexpectedError`.
  - `src/layouts/components/LicenseWarningBanner.vue`: shown while the license status is `WARNING`.
- `LayoutVertical.vue` keeps its own copy of both, unchanged, and can switch to the mixin and the component at step 9.

**Navbar.** The search bar shows when the user can read the home page of the installation: `admin-headoffice-home` on a head office, `home` on a store.

**Roles page.** On a head office it lists only the head office permissions, grouped like the menu and named by the menu titles (translated), and the counters count only those. A role keeps its store permissions, which are unused there. On a store the list is as before, without any head office permission.

### Step 3 pages (frontend)
Task 3.0: `.eslintrc.js` has `no-console` off in every mode (decided 2026-10-03: the console calls of the old files stay). Proved with `npm run build` in a fresh git worktree with the ESLint cache moved aside: build complete, 186 files linted, no error.

**Promotions, on a head office** (`PromotionsManagement.vue`, shared; the head office part only when `nodeType` is `HEAD_OFFICE`):
- Form: a Stores section, component `src/views/admin/headoffice/PromotionStoresField.vue` (loaded only there): All stores (default) or Chosen stores. In the list a store whose `ownership.PROMOTIONS` is `LOCAL` shows "Owns its promotions" and cannot be checked (one already in the list can be unchecked); a store with unknown ownership shows "Ownership unknown (older version)" and can be chosen; an inactive store is marked. Chosen stores with none checked: Create / Save disabled, "Choose at least one store."
- Create: `POST /admin/headoffice/promotions` `{promotion, allStores, storeIds}`. Edit: `PUT /promotion/{id}`, then `PUT /admin/headoffice/promotions/{id}/targets` only when the stores changed.
- List: a Stores column before Status from `GET /admin/headoffice/promotions/targets`: "All stores", or "n stores" with the codes and names on hover. Stores from `GET /admin/headoffice/store-options`.

**Promotions, on a store**:
- `ownership.PROMOTIONS === 'HEAD_OFFICE'` (`/config`): consult only. No Add button; the actions column has only a view button; a click on a row opens the form read-only (title "Promotion", every field disabled through a disabled `fieldset`, only Close); a banner "Promotions are managed by the head office" above the list and in the form.
- Otherwise the page works as before, and a store without a head office renders exactly as before (no column, no listener on the rows, no badge).
- In both cases a "Head office" badge next to the code of a promotion whose `origin` is `HEAD_OFFICE`.

**Head office link page** (`HeadOfficeLinkStatus.vue`): label of the job `COPIES_DOWN` ("Copies from the head office"); the exchange log already shows `DOWN` ("From the head office"). New block "Received from the head office", only when `GET status` gives `received` (a store that pulls): Applied / Waiting / Error counts of the domain (a domain selector when there are several), the list of `GET received/{domain}` (code, name, status, reason, information, since) with a status filter, errors first; refreshed with the rest of the page.

**Stores page** (`StoresManagement.vue`): columns "Owned by the head office" (the domains whose owner is `HEAD_OFFICE`, or "Nothing") and "Sales go to" (ERP, Head office, or "Nowhere"); "Unknown (older version)" when the store reported nothing. A details button (eye) opens the table of the five domains (the store, Head office or ERP) and the sales upstreams.

**ERP pages** (task 3.4): routes with `meta.erpOnly`; the router guard sends them to the home page when `/config` gives `standalone: true`, and the menu hides the ERP group then (`ERP_ONLY` in `src/navigation/headoffice/index.js`). No store route carries `erpOnly`. The jobs, statistics and communications pages are the store pages (twins); the ERP reference location page (`src/views/admin/headoffice/ErpReferenceLocation.vue`) shows the setup order (import the locations, choose the location, enable the item imports, with links to the ERP jobs page) and the choice among the imported locations. `HEAD_OFFICE_PERMISSIONS` lists each permission once (the statistics route shares the ERP jobs permission): 23, equal to the backend list.

**Checks (2026-10-03)**: lint of the changed files in production mode and `npm run build`, both clean. Screens through Chrome, both dev backends with the step 3 code, nothing saved: head office Stores page (columns, details), promotions list and form (Stores column; Stores section; a store owning its promotions not choosable, unknown choosable, the empty list warning); store link page (a store with local promotions: no received block, no `COPIES_DOWN` job, as before); store promotions page as before; with `ownership.PROMOTIONS` set to `HEAD_OFFICE` in memory: banner, no Add, view buttons, a row opens the form read-only; the "Head office" badge; ERP route sent home in standalone mode; with ERP in memory the ERP menu group and the reference location page (its API answers 404 on `headoffice-dev`, which has no ERP). Not seen: the received block with data and the ERP pages against an ERP head office (closing prompt). After the restart with the 3.4 code a head office ADMIN must log out and in to get the three ERP permissions (abilities are built at login).

### Step 4 pages (frontend)
Frontend commits adace27 (store loyalty pages), 934e417 (link page), ece22dd (Stores page), on `feature/ho-step-4`.

**Store loyalty pages** (only when `/config` gives `ownership.LOYALTY = HEAD_OFFICE`; a store with local loyalty, or without a head office, renders as before). Shared helper `src/views/admin/holink/loyalty-network.js` (reads `GET /loyalty/network`).
- Members (`LoyaltyMembersManagement.vue`): a banner (members shared with the head office); edit and deactivate only with `canEditMembers`; adjust points hidden (step 4; step 5 brings it back with `canAdjustPoints`); the 403, 409 and 503 answers shown in the member card with a translated title. A 409 on enrol reloads the list (the network member was saved here).
- Program (`LoyaltyProgramManagement.vue`): read-only with a banner, no create, no actions.
- POS loyalty modal (`ItemSelection.vue`): a duplicate phone naming an active card offers that member (`GET /loyalty/member/by-card/{card}`); an inactive card is only named. From the step 5 backend the 409 also gives `existingCardNumber` and `existingCardActive`, so the card no longer needs to be read from the message.

**Head office link page**: the job `LOYALTY_PUSH` labelled "Loyalty to the head office"; a new block "Sent to the head office: loyalty" (`HeadOfficeLinkLoyalty.vue`), only when `GET /admin/holink/status` gives `loyalty`: the store's rights, the `PENDING` / `SENT` / `ERROR` counts of members and movements (a click picks the list), and `GET /admin/holink/loyalty/{members|movements}` with a status filter, errors first, 20 per page, refreshed with the rest of the page. The received block shows the counts of every domain received (promotions, loyalty); `LOYALTY` labelled.

**Stores page** (`StoresManagement.vue`): two switches "Can edit members" and "Can adjust points" in the store form (sent with `POST` and `PUT`) and in the details (saved at once with a `PUT` of that field; the switch goes back when the save fails). The code `HO` is refused in the form with a clear message; the backend's 400 stays the guard.

Labels in `en`, `fr`, `ar`. The step 5 pages (fresh balance at the till, spending setting, store adjustments, overspend report) come with the step 5 frontend session.

### Step 5 pages (frontend)
Frontend commits 25cd826 (till and members page), 581ce28 (link page), 0756bb0 (Stores page), 7b0c1e3 (overspend report), on `feature/ho-step-4`. Store parts only when `/config` gives `ownership.LOYALTY = HEAD_OFFICE`; a store with local loyalty, or without a head office, renders as before.

**Till** (`ItemSelection.vue`, `Payment.vue`, helper `loyalty-network.js`): when a member is selected, and when the Payment page opens with one, `GET /loyalty/member/{id}/fresh` is called without waiting: the member shows at once with the store's balance and is updated when the answer comes. `fresh` false: "Balance of the last sync" with the reason. `canRedeem` false: the points input and Apply are disabled with the reason, and points already applied are removed (also when the new balance is lower). A duplicate phone offers the card from `existingCardNumber` / `existingCardActive` (the message text only when the fields are absent).

**Members page** (`LoyaltyMembersManagement.vue`): Adjust points when `GET /loyalty/network` gives `pointsAdjustable`; its 400, 403, 409 and 503 answers shown in the member card.

**Head office link page** (`HeadOfficeLinkLoyalty.vue`): the loyalty block shows whether spending needs the head office online (`status.loyalty.redeemRequiresOnline`: Yes, No, or Unknown before the first heartbeat answer).

**Stores page** (`StoresManagement.vue`): a third switch "Spending points requires the head office online" (`redeemRequiresOnline`) in the form (`POST`, `PUT`) and in the details (saved at once), like the two loyalty rights.

**Overspend report** (head office, `src/views/admin/headoffice/LoyaltyOverspends.vue`): route `admin-headoffice-loyalty-overspends`, path `/headoffice/loyalty/overspends`, permission `read:admin-headoffice-loyalty-overspends` (24 head office permissions, as the backend), menu Customers & loyalty. `GET /admin/headoffice/loyalty/overspends` with search, store and date filters and paging; the count and points of the period from `overspends/count` above the table. Head office home: a tile with the count and points of every period, opening the page, shown with the page's permission only (the tiles go to 3 per row).

Labels in `en`, `fr`, `ar`.

**Enrol switch** (frontend 37c50a0, backend 21f1984):
- Stores page: a fourth switch "Enrolling a member requires the head office online" (`enrolRequiresOnline`) in the form (`POST`, `PUT`) and in the details (saved at once); the help text rewritten for the four switches.
- Link page, loyalty block: the setting (Yes, No, Unknown before the first heartbeat answer).
- Enrol at a store whose loyalty is owned by the head office: a 503 shows the backend text titled "Head office unreachable", in the POS loyalty modal (with "Continue without a card": the sale goes on) and in the create dialog of the members page. Local loyalty unchanged.
- Labels in `en`, `fr`, `ar`.

### Step 6 pages (frontend): head office
Frontend commits e35b381 (price lists), 5fb5c1b (Stores page), on `feature/ho-step-6`. Head office without an ERP only.

**Price lists** (`src/views/admin/headoffice/PriceLists.vue`): route `admin-headoffice-price-lists`, path `/headoffice/price-lists`, permission `read:admin-headoffice-price-lists` (25 head office permissions, as in the backend), menu Catalogue. The route carries `meta.standaloneOnly`, the inverse of `erpOnly`: the router guard sends it to the home page when `/config` gives `standalone: false`, and the menu hides it then (`STANDALONE_ONLY` in `src/navigation/headoffice/index.js`).
- Lists: `GET /admin/headoffice/price-lists`. Columns: code, name, status, line count, store count. Search and paging run in the browser. Create (code in upper case, name, active) with `POST`. Edit (name, active; the code is read-only and not sent) with `PUT /{id}`. Delete with `DELETE /{id}` after a confirmation. The 400 and 409 answers show the backend's `error` text.
- Lines, in a dialog: `GET /{id}/lines` with `search`, `page`, `size` (10). Columns: item code, item name, base price (excl. VAT), list price (excl. VAT).
  - The price is changed in place and saved on Enter or blur with `PUT /{id}/lines [{itemCode, price}]`. An invalid or refused price goes back to the saved value.
  - Add a line: the item picker of the promotions page (`GET /item/search`, active items). The price starts at the item's base price. A warning shows when the item is already on the list (its price is replaced).
  - Delete a line: `DELETE /{id}/lines/{lineId}` after a confirmation.
  - The list counts are reloaded when the dialog closes after a change.

**Stores page** (`StoresManagement.vue`): a block "Selling prices and purchases" in the form and in the details, after the loyalty switches, shown only when `/config` gives `standalone: true`.
- "Selling price list": a select of the active lists by code, plus "None (base price)".
- Switches "May change its selling prices" (`mayChangePrices`) and "Can purchase from its own suppliers" (`canPurchase`), with one help text for the three settings.
- Create: `sellingPriceListId`, `mayChangePrices` and `canPurchase` are sent with the `POST`.
- Edit: the two switches go with the `PUT`, then `PUT /admin/headoffice/stores/{id}/selling-price-list {priceListId}` only when the list changed.
- Details: each setting is saved at once (list with `selling-price-list`, switches with a `PUT` of that field). It goes back when the save fails, and the backend's text is shown.
- The own / franchise kind left the page: no column, no form field, and it is no longer sent. The backend uses `OWN` at creation and keeps the stored value on edit. The labels `kind`, `kindOwn` and `kindFranchise` were removed.

Labels in `en`, `fr`, `ar`.

**Checks**: lint of the changed files in production mode and `npm run build`, both clean before each commit. Not seen in the browser: the dev backends were down and the latest artifact predates step 6 (left for L2).

### Step 6 pages (frontend): store
Frontend commits 0928f9e, 68c027a, 32cd1be, on `feature/ho-step-6`.

**Rule.** The new behaviour exists only when `GET /config` gives `catalogueFromHeadOffice: true` (`appConfig/isCatalogueFromHeadOffice`). `ownership.CATALOGUE` is never read: a franchise customer shows `HEAD_OFFICE` there and keeps its own branches. Without the flag every page renders as before.

**Shared code** `src/views/admin/holink/catalogue-network.js`:
- A mixin with `catalogueFromHeadOffice`, `catalogueCanPurchase`, `catalogueMayChangePrices`, `catalogueCreateClosed` and `isHeadOfficeRecord(record)` (`origin === 'HEAD_OFFICE'`).
- `GET /catalogue/network` is read only with the flag. Until it answers, and when it fails, both rights count as off.
- `apiErrorText(error, fallback)` returns a plain-text body, else `{error}`, else `{message}`.

**Items** (`ItemManagement.vue`):
- A "Head office" badge on head office items. Such an item has View (the form read-only, with a consult-only note: no barcode, pack or image changes), the barcode list and Adjust stock. It has no Edit and no Delete.
- With `mayChangePrices`: "Change the price" (`PUT /item/{id}/own-price {unitPrice}`, not for packs) and, when the item has an own price, "Back to the head office price" (`DELETE`, after a confirmation). Without the right, neither action is shown.
- The pricing cell shows an "Own price" badge with the head office price beside it.
- The store's own items keep every action.

**Families, sub-families** (`ItemFamiliesManagement.vue`, `ItemSubFamiliesManagement.vue`):
- Badge, and an eye button that opens the form read-only (fieldset disabled, Close only).
- On a head office (`nodeType` `HEAD_OFFICE`), the code is read-only on edit. The item code already was everywhere.

**Barcodes** (`ItemBarcodes.vue`): badge. "Add product" (`/item/standalone-quick-product`) only with the purchase right. It is the only quick product creation; the POS has none.

**Without the purchase right:**
- No Add item, family, sub-family or product, with a note in the table toolbar.
- Purchases, purchase invoices and vendors stay readable, with a warning banner. Hidden: new purchase, set paid, create invoice from a purchase, new invoice, add and edit vendor.
- The new purchase page shows the banner and no Create button.

**With the purchase right:** the new purchase item picker offers only the store's own items. It reads `/item/search`, which gives `origin` for each item since backend 2c92282, and keeps the items whose origin is not `HEAD_OFFICE` (frontend e7f1556; own items hidden from the POS can be purchased again). An info banner says so.

**Data import** (`DataImport.vue`): Families, Sub-families, Items, Barcodes and Sales prices cannot be chosen, with a note. Vendors follow the purchase right.

**Head office link page** (`HeadOfficeLinkStatus.vue`):
- A block "Catalogue from the head office" when the status gives `catalogue`: the two rights (Yes, No), the number of items with an own price, and a warning when `salesPriceRowsOnHeadOfficeItems` is above 0.
- The received block labels the `CATALOGUE` domain.

**409 answers:** these APIs answer in plain text. The store layout's error popup and the page toasts show the backend text.

Labels in `en`, `fr`, `ar` (`admin.catalogueNetwork.*`, `admin.holink.catalogue.*`).

**Checks**: lint of the changed files in production mode and `npm run build`, clean before each commit. Not yet seen in the browser (L2).

### Step 7A pages (frontend): head office warehouse
Frontend commit 6208e7b, on `feature/ho-step-7a`. Head office without an ERP only.

**Supply pages.** A menu group Supply holds seven twins of the store pages: Vendors, Purchase history, New purchase, Vendor balance, Purchase invoices, Stock report, Stock movements.
- Routes `admin-headoffice-vendors`, `-purchases`, `-purchase-new`, `-vendor-balance`, `-purchase-invoices`, `-stock`, `-stock-movements`, under `/headoffice/supply/...`. The permission of each is `read:<route name>`. There are 32 head office permissions, the same list as `ZZDataInitializer.HEAD_OFFICE_ADMIN_PERMISSIONS`.
- Every route carries `meta.standaloneOnly`. The router guard sends them home when `/config` gives `standalone: false`, and the menu hides them then (`STANDALONE_ONLY`).
- The later guard steps read `twinOf`, so the store's "standalone only" rule for purchases and vendors applies to the twins as well.

**Links.** The pages link to each other by route name: vendors → vendor balance, and purchase history ↔ new purchase. All three targets have a twin, so no link falls back to the head office home.

**Head office checks.** None of these pages needs a cashier session, a till or a location. The step 6 purchase-right check is off on a head office, so every write action is shown there.

**Stock movements.** The BL types `DELIVERY_OUT` and `DELIVERY_IN` have translated labels (`admin.reports.stockMovements.types`) in the type filter, the table, the chart and the Excel export. The other types still show their code, as before. This is the one change to a store page; the store routes and the store menu are unchanged.

Labels in `en`, `fr`, `ar` (`admin.headoffice.supplyMenu`, the two types).

**Checks:** lint of the changed files in production mode and `npm run build`, both clean. Not seen in the browser (L2).

**Not in this part:** the BL page, the network stock page and the store reception page. They wait for the backend API.

### Step 7A pages (frontend): BLs at the head office
Frontend commit 8ac5489, on `feature/ho-step-7a`. Head office without an ERP only.

**Page** `src/views/admin/headoffice/Deliveries.vue`:
- Route `admin-headoffice-deliveries`, path `/headoffice/supply/deliveries`, `meta.standaloneOnly`.
- First link of the Supply menu, permission `read:admin-headoffice-deliveries` (33 head office permissions, the same list as the backend).
- API `/admin/headoffice/deliveries`.

**List.** Number (or "Draft (no number yet)"), store, status badge (Draft, Sent, Received, Invoiced), document date, sent, received, quantities sent / received, and a "Difference" badge.
- Filters: search (number, note), store, status, date from and to (on the document date), "With a difference only".
- Paging by the API, 20 per page.

**New BL and draft edit**, in a dialog:
- Store: the active stores from `store-options`. A store that reported `ownership.SUPPLY` other than `HEAD_OFFICE` is listed but cannot be chosen.
- Date (today by default) and note.
- Lines from the item search (`/item/search`: active items, products and packs only, never the tax stamp). The head office stock is read when an item is added (`GET /item/{id}`), and from `headOfficeStock` when a draft is opened.
- A quantity above the stock is highlighted and named in a warning, without blocking the save. The same item twice is refused in the page.
- `POST` creates, `PUT /{id}` replaces the draft.

**Draft actions.** Edit, delete (after a confirmation), and validate.
- Validate asks for a confirmation: the BL gets its number, the goods leave the head office stock and the store will see it.
- A stock shortage (409) is shown as the list of short items, cut from the backend text. Any other 400 or 409 shows the backend text.

**Sent, Received, Invoiced: consult-only.**
- The detail (`GET /{id}`) shows store, status, date, sent at and by, received at and by (store clock), when the confirmation reached the head office, the note and the store's note.
- Per line: quantity sent, quantity received and the difference (received − sent, coloured).

**Print** (non-draft, from the list or the detail):
- `DeliveryNoteTemplate.vue`, written with the purchase invoice print styles and document builder (`getPurchaseInvoicePrintDocumentHtml`).
- Content: company header, number, date, store, lines with quantities sent and received (received blank until confirmed), total sent, the note, and two signature areas (sent by, received by).

Labels in `en`, `fr`, `ar` (`admin.headoffice.deliveries.*`).

**Checks**: lint of the changed files in production mode and `npm run build`, both clean. Not seen in the browser (L2).

### Step 7A pages (frontend): reception at the store
Frontend commit ef799b0, on `feature/ho-step-7a`.

**Rule.** The page exists only when `GET /config` gives `supplyFromHeadOffice: true` (`appConfig/isSupplyFromHeadOffice`), never on a franchise customer. `navigation/head-office.js` lists it in `SUPPLY_ROUTES` and `SUPPLY_PERMISSIONS`:
- The router guard sends the route home without the flag. On a head office it has no twin and goes to the head office home.
- `VerticalNavMenu` removes its link without the flag.
- The Roles page lists `read:admin-holink-deliveries` ("Réception des BL", group "Paramètres & Outils") only with the flag.

Without the flag every store page renders as before.

**Page** `src/views/admin/holink/DeliveryReception.vue`, route `admin-holink-deliveries`, path `/admin/holink/deliveries`. Its menu link sits right after "Head office link". API `/admin/deliveries`.
- **List.** The BLs to receive first (all of them, up to 200), then the received ones (paged). Columns: number, document date, quantities sent / received, a "Difference" badge, and badges for items not here yet or lines whose stock waits. Received BLs also show the confirmation to the head office: Waiting to be sent, Sent, or Refused (the reason on hover).
- **Receive**, in a dialog (`GET /{id}`, then `POST /{id}/receive {note, lines: [{lineNo, quantityReceived}]}`):
  - One quantity per line, a whole number of 0 or more, defaulting to the quantity sent.
  - A line whose item is not in the store yet is flagged: its stock goes in once the item arrives.
  - A note (500 characters).
  - A quantity above the quantity sent asks for a confirmation that names the items.
  - Afterwards: the stock went up, and the confirmation goes to the head office now or as soon as it can be reached, plus how many lines still wait for their item.
  - 400 and 409 (already received) show the backend text.
- **A received BL is consult-only.** The detail shows the head office note, sent at, received at and by, the store's note, the confirmation status and its error. Per line: sent, received, difference, and "stock waits for the item".
- **Print** uses `DeliveryNoteTemplate.vue` from the head office page. Its "Deliver to" block shows only when the BL has store fields, so the store prints its own company header.

**Head office link page** (`HeadOfficeLinkStatus.vue`):
- A block "Delivery notes from the head office" when the status gives `supply`. It shows counts to receive and received, the confirmations Waiting to be sent / Sent / Refused, and a warning when `stockWaiting` is above 0. A button opens the reception page when the user has its permission.
- The job `SUPPLY_PUSH` and the received domain `SUPPLY` are labelled.

Labels in `en`, `fr`, `ar` (`admin.holink.deliveries.*`, `admin.holink.supply.*`).

**Checks:** lint of the changed files in production mode and `npm run build`, both clean. Not seen in the browser (L2).

### Step 7A pages (frontend): network stock
Frontend commit 5d7743b, on `feature/ho-step-7a`. Head office without an ERP only.

**Page** `src/views/admin/headoffice/NetworkStock.vue`:
- Route `admin-headoffice-network-stock`, path `/headoffice/supply/network-stock`, `meta.standaloneOnly`.
- Supply menu, after Stock movements. Permission `read:admin-headoffice-network-stock` (34 head office permissions, the same list as the backend).

**Tab "Head office items"** (`GET /admin/headoffice/stock`):
- One row per head office item: code, name, head office stock, then one column per store from `stores`.
- Each store header shows its code, its name, and the time of its last stock report (`lastStockAt`, head office clock), or "No report yet".
- A store that never reported the item shows "-". Below zero is red, zero is grey.
- The item code and name columns stay fixed (`stickyColumn`) while the store columns scroll sideways.

**Tab "Stores' own items"** (`GET /admin/headoffice/stock/own`): store, item code, item name, stock, read at the store (`storeTime`), received (`receivedAt`).

**Filters:**
- Search (code, name) and store (`storeId`, from `store-options`), with paging by the API, 20 rows by default.
- "Below zero only": since step 7B the page sends `belowZero=true` to the API (see "Network stock: "below zero only" through the API" below).

Labels in `en`, `fr`, `ar` (`admin.headoffice.networkStock.*`).

**Checks:** lint of the changed files in production mode and `npm run build`, both clean. Not seen in the browser (L2).

### Step 7B pages (frontend): supply prices and invoicing settings
Frontend commits b398fea (Stores page), 273aba9 (price lists), 0c513a4 (supply prices), on `feature/ho-step-7b`. Head office without an ERP only. Backend: step 7B part 1 (435ab69), "Supply prices and invoicing settings (step 7B, part 1)".

**Stores page** (`StoresManagement.vue`): a block "Invoicing of the deliveries" in the store form and in the details, after the selling prices block, shown only when `/config` gives `standalone: true` (like the selling price list). The fields are a shared component, `src/views/admin/headoffice/StoreInvoicingFields.vue`.
- Switch "Deliveries invoiced" (`deliveriesInvoiced`). The other fields show only when it is on.
- Billing legal name (200), tax number (50), billing address (500).
- Supply price: "Price list" (`supplyPriceMode` `PRICE_LIST`, the default) or "Percentage off the selling price" (`PERCENT_OFF`).
  - Price list: a select of the active lists of kind `SUPPLY`, plus "None (base supply price)".
  - Percentage: a number from 0 to 100; out of range blocks the save.
- Invoices: "One per delivery note" (`invoiceRhythm` `PER_BL`, the default) or "Grouped (made by hand)" (`GROUPED`).
- Saving. The fields go with the store's `POST` / `PUT`. With the switch off, only `deliveriesInvoiced: false` is sent and the other settings stay as they are. Blank billing fields are sent empty, which clears them.
- The supply price list goes with the `POST` at creation; afterwards `PUT /admin/headoffice/stores/{id}/supply-price-list {priceListId}`, only for an invoiced store in price list mode, when it changed.
- In the details the block is saved with its own button ("Save the invoicing"), enabled when something changed. A refusal (400) shows the backend text and reloads the stores.
- The selling price list select now offers selling lists only (a list without a kind is a selling list).

**Price lists page** (`PriceLists.vue`):
- Kind "Selling" or "Supply" chosen at creation (`POST` with `kind`), read-only on edit ("The kind cannot change once the list is created").
- A Kind column (badge) and a kind filter (All kinds, Selling, Supply), applied to the lists already loaded.
- Lines of a supply list: the help text speaks of supply prices before VAT. Since backend cee300c (frontend 4a0504c, part 2) the reference column is "Base supply price" (`basePrice` is the base supply price on a supply list, null when none), and a new line starts at the item's base supply price, read from `GET /admin/headoffice/supply-prices` by its exact code (empty when none). A selling list is unchanged: base selling price, a new line starts at it.

**Supply prices page** (`src/views/admin/headoffice/SupplyPrices.vue`):
- Route `admin-headoffice-supply-prices`, path `/headoffice/supply/supply-prices`, `meta.standaloneOnly`. Supply menu, right after Delivery notes. Permission `read:admin-headoffice-supply-prices` (35 head office permissions, the same list as the backend).
- `GET /admin/headoffice/supply-prices` with search (code, name) and paging, 20 rows by default. Columns: item code, item name, base selling price, base supply price (before VAT).
- The supply price is edited in the row; a changed row is highlighted, and a wrong value (not a number, below 0) blocks the save.
- Changes are kept across pages and searches. "Save (n)" sends them together with one `PUT` `[{itemCode, supplyPrice}]`; an empty price is sent as `null`, which deletes the base supply price. "Discard" drops them.
- All or none: on a refusal (400) nothing is saved, the backend text is shown, and the edits stay so they can be corrected.

Labels in `en`, `fr`, `ar` (`admin.headoffice.stores.invoicing.*`, `admin.headoffice.priceLists.kind*`, `admin.headoffice.supplyPrices.*`).

**Checks:** lint of the changed files in production mode and `npm run build`, both clean. Not seen in the browser (L2).

**Not in this part:** the invoices pages (step 7B parts 2 and 3). They wait for the backend API.

### Step 7B pages (frontend): supply invoices
Frontend commits 067dbf6 (invoices), d765b1d (to invoice), 51d8582 (what the stores owe), af18201 (BL page), 7bbef7f (store side), 4a0504c (supply list base price), 16fb967 (preview lines without a supply price), on `feature/ho-step-7b`. Backend 055757b and the fix cee300c. Head office pages without an ERP only (`meta.standaloneOnly`); 37 head office permissions, the same list as the backend.

**Supply invoices page** (`src/views/admin/headoffice/SupplyInvoices.vue`):
- Route `admin-headoffice-supply-invoices`, path `/headoffice/supply/supply-invoices`. Supply menu, after Supply prices. Permission `read:admin-headoffice-supply-invoices`. API `/admin/headoffice/supply-invoices`.
- Tab "Invoices": number (with its BL numbers), store and buyer, date, total before VAT, total with VAT, Paid / Unpaid badge (with the paid date).
  - Filters: store (`store-options`), payment (paid and unpaid, unpaid, paid), date from and to.
  - Paging by the API, 20 rows by default.
  - The query `storeId`, `paid` (`true` / `false`) and `invoiceId` (opens that invoice) set the page when it opens.
- Detail (`GET /{id}`): seller and buyer as the invoice recorded them, date, BLs, note, lines (`SupplyInvoiceLines.vue`: BL, code, name, quantity, unit price before VAT, VAT %, totals before and with VAT), totals (`SupplyInvoiceTotals.vue`).
- Paid: a switch with the paid date (today by default) and a note, saved with `PATCH /{id}/paid {paid, paidDate, note}`; unpaid sends `{paid: false}`.
- Print (list and detail): `SupplyInvoiceTemplate.vue`, mounted the way of the BL print with the purchase invoice print styles. It shows the seller snapshot (and the head office logo), the number, date, buyer with tax number and store code, lines by BL, totals, BL numbers and the note.
- Tab "To invoice":
  - Store: the active stores whose deliveries are invoiced (`GET /admin/headoffice/stores`, `deliveriesInvoiced`), with their invoice rhythm.
  - Its received BLs not invoiced (`GET /to-invoice?storeId=`): number, date, received, quantity received. `invoiceNote` (why the automatic invoice failed) is shown in orange. BLs are ticked one by one or all at once.
  - Date (today by default) and note.
  - "Preview" (`POST /preview {storeId, deliveryIds, invoiceDate}`) shows the lines and totals. The items without a supply price (`missingPrices`) are listed in a red alert. Since backend 01f51ca (frontend 16fb967) each BL line of such an item also comes as a preview line in its place (`missingPrice: true`: BL, item code, name, quantity, no line number and no price; outside the totals): the table shows it in red with "No supply price".
  - "Create the invoice" (`POST /` with the note) is enabled only after a preview of the current choice and date with no price missing; changing the BLs or the date drops the preview. The new invoice then opens.
  - Every 400 and 409 shows the backend text: the BLs, the store setting, a percentage missing, nothing received, a date in the future or before the last invoice.

**What the stores owe** (`src/views/admin/headoffice/StoreBalances.vue`):
- Route `admin-headoffice-store-balances`, path `/headoffice/supply/store-balances`, Supply menu after Supply invoices, permission `read:admin-headoffice-store-balances`.
- `GET /admin/headoffice/supply-invoices/balances`: one row per store with an invoice: invoices, total, paid, unpaid (with the number of unpaid invoices), amounts with VAT, and the unpaid total above the table.
- A click on a row opens the supply invoices page filtered on that store, unpaid.

**BL page** (`Deliveries.vue`): the `INVOICED` status is labelled (Invoiced, Facturé, مفوتر). The invoice number (`invoiceNumber`) is shown under the status in the list and in the detail, as a link to the supply invoices page opened on that invoice (`invoiceId`). Without an invoice, `invoiceNote` is shown as "Not invoiced automatically" (on hover in the list, in full in the detail).

**Store side**, only when `/config` gives `supplyFromHeadOffice: true` (without it the pages render as before):
- Purchase invoices (`PurchaseInvoiceManagement.vue`): a "Head office" badge on the invoices whose vendor code is `HEAD_OFFICE`, in the list and the detail. The page has no edit or delete on an invoice, so they stay consult-only; their notes give the BL numbers.
- Vendors (`VendorManagement.vue`): the `HEAD_OFFICE` vendor is marked "Head office" and has no edit button (the API answers 409).
- BL reception (`DeliveryReception.vue`, a page that exists only with the flag): "Invoiced by the head office: <number>" under each received BL and in its detail.

Labels in `en`, `fr`, `ar` (`admin.headoffice.supplyInvoices.*`, `admin.headoffice.storeBalances.*`, `admin.headoffice.deliveries.notInvoiced`, `admin.holink.deliveries.invoice`).

**Checks:** lint of the changed files in production mode and `npm run build`, both clean before each commit. Not seen in the browser (L2).

### Network stock: "below zero only" through the API
Frontend commit d7a60a5, on `feature/ho-step-7b`. Backend a8dc85b.

The network stock page (`NetworkStock.vue`, step 7A) sends `belowZero=true` to `GET /admin/headoffice/stock` and `GET /admin/headoffice/stock/own` when "Below zero only" is on, together with the search, the store and the paging.
- Head office items: an item whose stock is below zero at the head office, or in the store chosen, or in any active store when no store is chosen.
- Stores' own items: an item whose stock is below zero.

The page no longer reads up to 25 pages of 200 rows to filter them itself, and the note "Below zero among the first 5,000 items only" and its label are gone. The help text under the tabs stays.

**Checks:** lint of the changed file in production mode and `npm run build`, both clean. Not seen in the browser (L2).

### Add a page to the head office
1. **Shared page** (the same data as on the store): add a route to `src/router/headoffice-routes.js` with path `/headoffice/<...>`, name and `meta.resource` `admin-headoffice-<page>`, `meta.action: 'read'`, `meta.headOffice: true`, `meta.requiresAuth: true`, the store page's component, and `meta.twinOf` set to the store route name. Do not modify the store page, its store route or the store menu.
   **Head office only page** (different data): put the component under `src/views/admin/headoffice/` and add the route the same way, without `twinOf`.
2. Add one link to `src/navigation/headoffice/index.js`, in the right group. The link names the route and gives the title key and the icon; the permission comes from the route. The Roles page takes its group and label from this link.
3. Add the permission `read:admin-headoffice-<page>` to `ZZDataInitializer.HEAD_OFFICE_ADMIN_PERMISSIONS` and to the expected list in `ZZDataInitializerRolesTest`. At the next start a head office's ADMIN role receives it; nothing is ticked by hand.
4. Add any new title key to `en`, `fr` and `ar`.
5. If the store page navigates by route name to another store page, that page needs a twin too; otherwise the link goes to the head office home. A hard-coded path is listed in the task's inventory (only `to="/"` today, which reaches the home twin).
6. Update the table above.

### Seed on a new database
- `ZZDataInitializer.initUsers` (runs only when the user table is empty): on a head office only the `admin` account is created; the default responsible and cashier accounts are not. The three roles are still created. On a store the seed is unchanged. Existing users are never deleted.
- Everything else is seeded as on a standalone store (payment methods, General Setup keys, passenger customer, tax stamp item, company info, `APP_VERSION`).
- Roles (`ensureDefaultRoles`): on a head office the ADMIN role is created with today's permissions plus `HEAD_OFFICE_ADMIN_PERMISSIONS`, one `read:admin-headoffice-<page>` per head office route (task 1.6; the same list as `src/router/headoffice-routes.js`).
- At every start a head office gives its existing ADMIN role any head office permission it lacks (`addMissingHeadOfficePermissions`). Nothing is removed, and the role is saved only when one was missing. RESPONSIBLE and POS_USER are never changed.
- On a store the three roles are created exactly as before, and an existing role is never changed.
- `read:admin-headoffice` (the Network menu permission of tasks 1.2 to 1.5) is no longer seeded or listed. It stays in older databases, unused.

### Stores list (task 1.2)
The head office keeps one record per store; a store's API key (task 1.3) identifies it on `/ho/**`.

**Data**: entity `headoffice/model/Store`, table `ho_store` (prefix `ho_`: head office tables are recognisable in a store database, where they exist through `ddl-auto` and stay empty). No `update.sql`: Hibernate creates the table and its unique index.

| Field | Rule |
|---|---|
| `code` | Required, unique. The store's `DEFAULT_LOCATION` value, saved trimmed and uppercase, compared without regard to case. Cannot be changed after creation |
| `name` | Required, trimmed |
| `kind` | `OWN` (default) or `FRANCHISE` (enum `StoreKind`) |
| `active` | From `_BaseEntity`, default true. An inactive store's key is refused |
| `canEditMembers`, `canAdjustPoints` | Step 4, loyalty rights of the store (`can_edit_members`, `can_adjust_points`). False on create unless sent; `PUT` applies each when sent and leaves it otherwise (no default in the entity, so a `PUT` without them never resets them); null in a row made before step 4, read as false. Sent to the store with each heartbeat answer and enforced by the head office (see "Shared loyalty (step 4)") |
| `redeemRequiresOnline` | Step 5 (`redeem_requires_online`), same rules as the two rights (false on create unless sent, kept by a `PUT` without it, null read as false). True: the store spends points only with a balance refreshed from the head office in the last 2 minutes. Sent with each heartbeat answer |
| `enrolRequiresOnline` | Enrol switch (decided 2026-10-04, `enrol_requires_online`), same rules (false on create unless sent, kept by a `PUT` without it, null read as false). True: the store enrols a member only when the head office answers the phone check of that enrol (503 otherwise). Sent with each heartbeat answer |
| `sellingPriceListId` | Step 6, task 6.4 (`selling_price_list_id`): the store's selling price list (`ho_price_list.id`), null = the base price. Accepted by `POST` (an unknown or inactive list: 400); the generic `PUT` ignores it: it is changed with `PUT /{id}/selling-price-list` (below), which sends the items of the old and the new list to this store again. Never sent to the store (it receives one price per item). Only on a head office without an ERP (elsewhere a list is refused with 400) |
| `mayChangePrices` | Step 6, task 6.5 (`may_change_prices`), same rules as the loyalty switches (false on create unless sent, kept by a `PUT` without it, null read as false). True: the store may put its own selling price on a head office item and keeps it across the pulls. Sent with each heartbeat answer |
| `canPurchase` | Step 6, task 6.6 (`can_purchase`), same rules. True: a store whose catalogue is the head office's may purchase from its own suppliers and create its own items. Sent with each heartbeat answer |
| `lastContact`, `appVersion` | Null until the first heartbeat. Written only by `POST /ho/heartbeat` (task 1.4): head office clock; version trimmed, blank gives null, cut to 255 characters (`Store.APP_VERSION_LENGTH`). `/ho/ping` does not write them. Read-only in JSON, never taken from the admin API |
| `apiKeyHash` | SHA-256 (hex) of the API key. Never serialized (`@JsonIgnore`), never in `toString`, never logged |

**API**: `StoreAPI`, `/admin/headoffice/stores` (JWT, like the other admin APIs; outside `/ho/**`). The service and the API carry `@ConditionalOnHeadOffice`: on a store these URLs answer 404.

| Request | Answer |
|---|---|
| `GET /`, `GET /{id}` | The store's JSON as before (no hash) plus `status` and `secondsSinceContact` (task 1.5, see "Status" below); `GET /{id}` 404 when unknown |
| `GET /count`, `GET /{id}/exists` | Generic `_BaseController` reads |
| `GET /findByField` | Generic search; 400 on `apiKeyHash` |
| `POST /` `{code, name, kind, active, canEditMembers, canAdjustPoints}` | 201 `{store, apiKey}`. 400 when code or name is missing, and (step 4) for the code `HO`, kept for the head office's own loyalty cards `LYL-HO-...`: `The code HO is kept for the head office's own loyalty cards (LYL-HO-...): choose another code.`; 409 when the code exists. `lastContact`, `appVersion` and any hash sent are ignored |
| `PUT /{id}` with `canEditMembers`, `canAdjustPoints` (step 4) | Each applied when sent, kept when absent |
| `PUT /{id}` | Applies `name`, `kind`, `active` (each when sent). 400 when the code differs from the stored one |
| `PUT /{id}` with `mayChangePrices`, `canPurchase` (step 6) | Each applied when sent, kept when absent; `sellingPriceListId` is ignored here |
| `PUT /{id}/selling-price-list` (step 6) | Body `{"priceListId": 3}`, or `{"priceListId": null}` for none. 200 the store; 400 for an unknown or inactive list, a value that is not a number, or on a head office with an ERP; 404 unknown store. The items of the old and the new list are recorded for this store only, in the same transaction |
| `POST /{id}/regenerate-key` | 200 `{store, apiKey}`; the old key stops working at once |
| `DELETE /{id}` | 204 before the first contact; 409 "This store has already contacted the head office: deactivate it instead." afterwards |

**API key**: generated by the server with `SecureRandom`, 32 bytes, URL-safe Base64 without padding (43 characters). Returned only by the creation and by regenerate-key, stored only as its SHA-256 hash. The page shows it once, with a copy button; it goes in the store's settings (`headoffice.api-key`, see "Connect a store"). A lost key is replaced with regenerate-key.

**Key check**: `StoreService.check(code, key)` returns a `KeyCheck`: the outcome `ACCEPTED`, `UNKNOWN_STORE`, `WRONG_KEY` or `INACTIVE_STORE`, and the store when accepted. The code is trimmed and compared without regard to case; the key's hash is compared with `MessageDigest.isEqual` (constant time). An unknown code is compared against a fixed hash too, so the answer time does not tell which codes exist. An inactive store is reported as such only when its key is right (otherwise `WRONG_KEY`). `authenticate(code, key)` keeps its contract (the accepted store, or empty) and uses `check`.

**Status** (task 1.5): computed by `StoreService.statusOf` on each read, with the head office clock; never stored (no column).

| `status` | When |
|---|---|
| `INACTIVE` | `active` is false, whatever the last contact |
| `NEVER` | active, no `lastContact` |
| `ONLINE` | active, `lastContact` no older than `headoffice.offline-after-seconds` (exactly at the threshold is still ONLINE) |
| `OFFLINE` | active, `lastContact` older than the threshold |

- `headoffice.offline-after-seconds`: head office setting, default `180` (three missed heartbeats at the default 60 s interval). Below 1 or not a whole number: the head office does not start.
- `secondsSinceContact`: whole seconds since `lastContact`, same clock, never negative; null before the first contact. The page shows "x min ago" from it, so a browser with a wrong clock does not contradict the badge.
- The list item is `StoreListItemDTO`: the `Store` with `@JsonUnwrapped` (same fields as before, the hash still out), then the two values. `StoreAPI` overrides `getAll` and `getById`; create, update, regenerate-key and `findByField` answer as before.

**Frontend**: page `src/views/admin/headoffice/StoresManagement.vue`, route `admin-headoffice-stores` (`/headoffice/stores` since task 1.6, `meta.resource` checked by CASL), menu group **Network** → **Stores** of the head office menu.
- Columns: code, name, kind, status, last contact, version. Status is the computed badge (ONLINE green, OFFLINE red, NEVER light grey, INACTIVE dark; `store-status.js`); last contact shows the date and time and "x min ago" under it (task 1.5). The list refreshes silently every 30 s while the page is open (no spinner, no toast; a load in progress is not doubled), stopped when the page is left. Actions: create, edit (code read-only), deactivate / activate, regenerate key (with confirmation), delete (shown only while `lastContact` is empty).
- After create or regenerate, a dialog shows the key once, with a copy button (Clipboard API on HTTPS or localhost, otherwise copy from the selected field: plain HTTP on the LAN has no Clipboard API) and the warning that it cannot be shown again.
- On a store: the route is redirected to `home` like every head office route, the store menu has no Network group, and the Roles page lists no head office permission (task 1.6, "Head office pages and menu").
- Permissions: ADMIN only, by default. Since task 1.6 a head office tops up its ADMIN role at startup (see "Seed on a new database"), also for a database created before; log out and in afterwards (abilities are built at login).

### Store API `/ho/**` (task 1.3)
Store-to-head-office calls, server to server: no user, no JWT, no CORS. The store sends its code and key on every call.

**Headers**: `X-Store-Code` (the store's code; trimmed, any case) and `X-Store-Key` (the key shown once on the Stores page).

**Security chain**: `headoffice/security/HeadOfficeApiSecurityConfig`, a Spring Security chain of its own for `/ho/**`, `@Order(0)`, before the main `SecurityConfig` (order 1), which is unchanged for every other path (login, `/franchise/**` and the rest answer as before).
- Stateless, CSRF off (`POST /ho/heartbeat`).
- No JWT filter in it: a user token never opens `/ho/**`. The main chain ignores the two store headers: a store key opens nothing outside `/ho/**`.
- Every request needs the authority `HO_STORE`, which only the filter gives: `/ho/**` fails closed.
- The chain exists on both installation types (no condition). On a store the filter does not exist, so `/ho/**` answers 401 to everyone.

**Filter**: `headoffice/security/StoreApiKeyFilter`, head office only (`@ConditionalOnHeadOffice`). It runs in that chain only: its servlet registration is disabled (`FilterRegistrationBean`, head office only), and it skips any path not starting with `/ho/`. It calls `StoreService.check`.
- Accepted: a fresh security context with the `Store` as principal (`PreAuthenticatedAuthenticationToken`, authority `HO_STORE`). A user token sent on the same request is dropped. Controllers read the store with `@AuthenticationPrincipal Store store`.
- **Controllers answer DTOs only, never the `Store` principal** (it carries the key hash). The principal is detached: changes go through the repository by id, never by saving the principal.
- After the key check comes the license filter (402 below), then the endpoint.

| Case | Answer |
|---|---|
| Header missing or blank, unknown store, wrong key, inactive store | 401 `{"code":401,"msg":"Invalid store credentials."}`, `application/json;charset=UTF-8`, the same for all four |
| Valid key, head office license missing or expired | 402 `{"licenseError":true,"status":"MISSING"}` (or `"EXPIRED"`), from `LicenseFilter`. The store shows it as `REFUSED`, "the head office has no valid license": the head office is reachable and the key is good, but the head office license is not valid |
| Valid key and license | the endpoint's answer |
| Any `/ho/**` on a store installation | 401, same body, with or without a user token |

**Log** (head office, WARN, one line per refusal): `Head office: /ho request refused (<reason>) for store '<code>' from <remote address> on <path>`, reason `missing header`, `unknown store`, `wrong key` or `inactive store`. Code and path come from the caller: control characters removed, cut to 100 characters. The key is never logged. Accepted calls are not logged.

**`GET /ho/ping`** (`HeadOfficePingAPI`, head office only): `{"storeCode":"RS01","serverTime":"2026-10-02T23:15:04.123+01:00"}` (`HeadOfficePingDTO`; ISO-8601 with milliseconds and offset, `Z` at UTC). Writes nothing: `lastContact` and `appVersion` are the heartbeat's job (task 1.4).

**`POST /ho/heartbeat`** (`HeadOfficeHeartbeatAPI`, head office only, task 1.4): body `{"appVersion":"1.12.0"}` (`HeadOfficeHeartbeatDTO`; a missing body counts as no version). Sets `lastContact` (head office clock) and `appVersion` on the calling store through `StoreService.recordContact`, by id with a two-column update (`StoreRepository.updateContact`): the principal is never saved, and `apiKeyHash`, `code`, `name`, `kind`, `active` and `updatedAt` are not touched. Answers like `/ho/ping`: `{"storeCode":"RS01","serverTime":"..."}`, the same instant as `lastContact`. Step 4: the answer (`HeadOfficeHeartbeatAnswerDTO`, a `HeadOfficePingDTO` with two more fields) also carries the store's loyalty rights, `{"storeCode":"RS01","serverTime":"...","canEditMembers":false,"canAdjustPoints":false,"redeemRequiresOnline":false,"enrolRequiresOnline":false}` (null in the row: false; step 5 added `redeemRequiresOnline`, the enrol switch `enrolRequiresOnline`; step 6 `mayChangePrices` and `canPurchase`, last); a store of an older version reads the first two fields only. `GET /ho/ping` keeps its two fields. The store side is described under "Head office link".

**What each store owns** (task 3.6): with each heartbeat the store also sends `ownership` (every `DataDomain` to its `DataOwner`, as `GET /config` gives it) and `salesUpstreams` (empty list = nowhere), e.g. `{"appVersion":"2.1.0","ownership":{"CATALOGUE":"ERP","CUSTOMERS":"ERP","PROMOTIONS":"HEAD_OFFICE","LOYALTY":"LOCAL","SUPPLY":"ERP"},"salesUpstreams":["ERP","HEAD_OFFICE"]}` (`HeadOfficeClient.heartbeatBody`, from `ApplicationModeService`). The head office saves them in the same two-column update, now eight columns (`StoreRepository.updateContact`): `ho_store.owner_catalogue`, `owner_customers`, `owner_promotions`, `owner_loyalty`, `owner_supply` (`VARCHAR(20)`) and `sales_upstreams` (`VARCHAR(50)`, names comma-separated in enum order, `""` = nowhere). Lenient: keys and values trimmed, any case; an owner its domain does not allow, or a value it cannot read, is saved as null; unknown upstream names are dropped; the heartbeat is never refused for them. A heartbeat without these fields (a store of an older version, or no body) saves null everywhere: **unknown**, also after an earlier report. Never read from the admin API.

JSON (`Store`, so the stores list and `GET /admin/headoffice/stores/{id}`): `ownership` `{"CATALOGUE":"ERP",...,"SUPPLY":null}` in `DataDomain` order, null when unknown; `salesUpstreams` `["ERP","HEAD_OFFICE"]`, `[]` for nowhere, null when unknown. The columns themselves are not in the JSON; both fields are ignored when a client sends them back. Also in `GET /admin/headoffice/store-options` (`ownership`, last key) and in the `stores` of `GET /admin/headoffice/promotions/{id}/targets`. A store whose `PROMOTIONS` owner is `LOCAL` owns its promotions and never pulls them: the targets API still accepts it (the report can be late), the page marks it.

**L2 checks** (pair on this PC, license valid on both, a store `RS01` created on the head office's Stores page):

| Request | Head office (888) | Store (444) |
|---|---|---|
| `GET /ho/ping`, no header | 401 | 401 |
| `GET /ho/ping`, `RS01` + its key | 200 `{storeCode, serverTime}` | 401 |
| `GET /ho/ping`, wrong key / unknown code / `RS01` deactivated | 401, WARN line with the reason | — |
| `GET /ho/ping`, admin JWT only | 401 | 401 |
| `GET /admin/headoffice/stores`, `RS01` + its key, no JWT | 403 (main chain, as before) | 403 |
| `POST /login` as admin | 200 with token, as before | 200 with token, as before |

### Head office link: the store side (task 1.4)
A store with `headoffice.url` set sends a heartbeat to its head office. Store-side code: package `com.digithink.zsretail.holink` (`client`, `dto`, `enumeration`, `service`, `scheduler`); the head office side stays in `headoffice`.

**Settings** (the store's properties file):

| Key | Rule |
|---|---|
| `headoffice.url` | Head office base URL including the context path, e.g. `http://localhost:888/zsretail/api`; trailing slashes are tolerated. Absent or blank: no link. None of the link beans exists (`@ConditionalOnHeadOfficeLink`, `config/OnHeadOfficeLinkCondition`, which reads the key like the startup: `NodeOwnership.isHeadOfficeLinkSet`) and the store behaves as before |
| `headoffice.api-key` | The key shown once on the head office Stores page; trimmed; sent in `X-Store-Key`; never written in a log line |
| `headoffice.heartbeat-interval-seconds` | Default `60` |

**Startup checks** (the store does not start), only when `headoffice.url` is set, in `NodeOwnership.resolve` next to the head office checks:

| Store with | Message starts with |
|---|---|
| `node.type=HEAD_OFFICE` | `Invalid combination: node.type=HEAD_OFFICE with headoffice.url set` |
| `headoffice.api-key` missing or blank | `Missing value for property headoffice.api-key: required when headoffice.url is set` |
| `headoffice.heartbeat-interval-seconds` below 1 or not a whole number | `Invalid value '<value>' for property headoffice.heartbeat-interval-seconds` |
| `headoffice.sales-push.from-date` not a `yyyy-MM-dd` date (task 2.1) | `Invalid value '<value>' for property headoffice.sales-push.from-date` |
| `headoffice.sales-push.batch-size` outside 1..1000 or not a whole number (task 2.4) | `Invalid value '<value>' for property headoffice.sales-push.batch-size` |
| `headoffice.sales-push.interval-seconds` below 1 or not a whole number (task 2.4) | `Invalid value '<value>' for property headoffice.sales-push.interval-seconds` |
| `headoffice.log-retention-days` below 1 or not a whole number (task 2.6) | `Invalid value '<value>' for property headoffice.log-retention-days` |
| `headoffice.pull.interval-seconds` below 1 or not a whole number (task 3.1) | `Invalid value '<value>' for property headoffice.pull.interval-seconds` |
| `headoffice.loyalty-push.interval-seconds` below 1 or not a whole number (step 4) | `Invalid value '<value>' for property headoffice.loyalty-push.interval-seconds` |
| `headoffice.supply-push.interval-seconds` below 1 or not a whole number (step 7A) | `Invalid value '<value>' for property headoffice.supply-push.interval-seconds` |

And whether the URL is set or not (task 2.4, decision 4): a store whose explicit `sales.upstream` includes `HEAD_OFFICE` without `headoffice.url` does not start (`Missing value for property headoffice.url: required when sales.upstream includes HEAD_OFFICE ('<value>')`). Only an explicit value is checked: the franchise customer profile derives `HEAD_OFFICE` for its legacy push and has no `headoffice.url`, and it starts as before. A head office with a non-empty `sales.upstream` keeps its own message (step 1).

Without the URL these keys are not checked. The URL scheme is not checked: HTTP is accepted inside the tunnel (decision D1).

**Heartbeat**: `HeadOfficeClient.heartbeat()` sends `POST <url>/ho/heartbeat` with `{"appVersion": "<app.version>"}` (the pom version, same source as `AppVersionGuard`) and two headers: `X-Store-Code` = `DEFAULT_LOCATION` from the general setup (read at each call and trimmed, so a change needs no restart) and `X-Store-Key`. It uses its own `RestTemplate` on Apache HttpClient, not the shared bean: connect 5 s, read 10 s. The client never throws:

| State (`HeadOfficeLinkState`) | When | Message |
|---|---|---|
| `PENDING` | No heartbeat yet since the start | `no heartbeat yet` |
| `ONLINE` | 200 with a readable body | none; the head office `serverTime` is kept |
| `REFUSED` | 401 | `store code or key refused by the head office` |
| `REFUSED` | 402 | `the head office has no valid license` |
| `ERROR` | Any other status, other 2xx and 3xx included | `unexpected answer from the head office: HTTP <code>` |
| `ERROR` | 200 whose body cannot be read (HTML, broken JSON, empty) | `unreadable answer from the head office (...)` |
| `ERROR` | `DEFAULT_LOCATION` cannot be read (database) | `DEFAULT_LOCATION could not be read (...)`; no call |
| `OFFLINE` | Connection error, unknown host, timeout | `head office unreachable (<cause>)` |
| `NOT_CONFIGURED` | `DEFAULT_LOCATION` empty | no call is made |

**Status**: `HeadOfficeLinkStatus`, in memory only (no table; `PENDING` again after a restart): state, last attempt and last success (store clock), last message, head office `serverTime` of the last success. A failure keeps the last success. Shown on the store's "Head office link" page (task 1.5, below).

**Thread**: since task 2.6 the heartbeat is the job `HEARTBEAT` (`holink/scheduler/HeartbeatJob`), run by `LinkJobScheduler` (see "Head office link: jobs and exchange log"): first heartbeat 15 s after the start, then every frequency (saved from the page, or `headoffice.heartbeat-interval-seconds`), counted from the end of the previous call. It runs on its own thread `ho-link-1`, not with `@Scheduled`: Spring Boot's default scheduler has one thread (`scheduling-1`) shared by `ErpSyncScheduler` and `FranchiseSalesPushScheduler`, so a call blocked up to 15 s would delay them, and a long ERP job would hold the heartbeat back. The thread pool is deliberately not a bean: a `TaskScheduler` bean would replace Spring Boot's default one and move the existing jobs onto it. Only this thread calls the head office; no request, sale or session waits for it. Exception since step 4, with loyalty owned by the head office: the live questions of enrolling (phone check) and of a member change are made from the user's request thread, on their own `RestTemplate` with short timeouts (connect 2 s, read 3 s); a sale never waits for the head office. Every job of the link (the sales push since task 2.4) runs on the same thread, so they never run at the same time.

**Log** (store): one INFO line at start for all the jobs (`Head office link: jobs on ho-link-1: HEARTBEAT every 60 s, SALES_PUSH every 60 s`), one INFO line per state change (e.g. `Head office link: ONLINE -> OFFLINE (head office unreachable (ConnectException: Connection refused))`), DEBUG while the state stays the same. The key is never written in a log line.

### Head office link page: the store side (task 1.5)
**API**: `HeadOfficeLinkAPI`, `/admin/holink` (JWT, like the other admin APIs), `@ConditionalOnHeadOfficeLink`: without `headoffice.url` (and on a head office) these URLs answer 404. Nothing in the selling path calls them.

| Request | Answer (`HeadOfficeLinkStatusDTO`) |
|---|---|
| `GET /status` | `{state, message, lastAttempt, lastSuccess, serverTime, headOfficeUrl, storeCode, intervalSeconds, pendingCount, sentCount, errorCount}`: the in-memory status, the URL without trailing slashes, `DEFAULT_LOCATION` read now (null when empty or unreadable), the interval. Never the key. Task 2.4: the three counts of `hol_sales_copy` by status (0 for a status without rows); null when the store does not copy its sales to the head office (no push job), or when the count cannot be read (the status is still answered) |
| `POST /check` | Runs one heartbeat now and answers the status after it: the "Run now" of the `HEARTBEAT` job (`LinkJobScheduler.runNow`), on `ho-link-1`, queued behind a run in progress, waited for up to 30 s; when the wait ends first, or before the thread is started, the current status is answered and no call is made from the request thread |
| `GET /jobs`, `PUT /jobs/{code}/interval`, `POST /jobs/{code}/run`, `GET /log` | Task 2.6, see "Head office link: jobs and exchange log". `intervalSeconds` in `GET /status` is the heartbeat frequency in force (saved or default) |
| `GET /status`, field `received` (task 3.5, last field) | Per domain pulled, the received records by status: `{"PROMOTIONS": {"APPLIED": 12, "WAITING": 1, "ERROR": 0}}` (every status present); null when the store pulls nothing, or when the counts cannot be read (the status is still answered) |
| `GET /status`, field `loyalty` (step 4, last field) | When loyalty is owned by the head office: `{"members": {"PENDING": 0, "SENT": 3, "ERROR": 0}, "movements": {"PENDING": 2, "SENT": 40, "ERROR": 0}, "canEditMembers": true, "canAdjustPoints": false, "redeemRequiresOnline": false, "enrolRequiresOnline": false}` (every status present; rights and settings from the last heartbeat answer, null before it); null otherwise, or when the counts cannot be read |
| `GET /status`, field `catalogue` (step 6) | When the catalogue is the head office's: `{"fromHeadOffice": true, "linkState": "ONLINE", "mayChangePrices": false, "canPurchase": true, "ownPriceCount": 0, "salesPriceRowsOnHeadOfficeItems": 0}` (the rights as saved, null when never received); null otherwise, or when it cannot be read. Same map as `GET /catalogue/network` |
| `GET /status`, field `supply` (step 7A, last field) | When the store's goods come from the head office: `{"toReceive": 1, "received": 4, "confirmations": {"PENDING": 0, "SENT": 4, "ERROR": 0}, "stockWaiting": 0}` (BLs by status, confirmations up by status, confirmed lines whose stock waits for their item; task 7A.5 adds `stockToSend` and `stockSentAt`); null otherwise, or when the counts cannot be read |
| `GET /loyalty/{kind}?status=&page=&size=` (step 4) | `kind` `members` or `movements` (any case). `{kind, counts, records, totalElements, page, size}`, `ERROR` first, then `PENDING`, then `SENT`, newest first in each; `size` default 20, at most 200. Members: `{cardNumber, name, phone, status, attempts, lastError, lastPushDate, outcome, survivingCardNumber}`; movements: `{key, cardNumber, type, points, delta, date, status, attempts, lastError, lastPushDate}`. 404 `{"error":"Loyalty is not owned by the head office on this store"}`; 400 for another kind or a bad status |
| `GET /received/{domain}?status=` (task 3.5) | `{domain, counts: {APPLIED, WAITING, ERROR}, records: [{code, name, status, reason, info, receivedAt, statusSince}]}`, `ERROR` first, then `WAITING`, then `APPLIED`, each by code. `domain` any case (`promotions`); `status` one status (any case), blank or `all` = every status. 404 `{"error":"No copies down of '<domain>' on this store"}` when the store does not pull that domain; 400 `{"error":"Invalid status ..."}` |

**`GET /config`** gets `headOfficeLinked` (task 1.5): true when `headoffice.url` is set (`ApplicationModeService.isHeadOfficeLinked()`, same check as the condition). The frontend store keeps it as `appConfig/isHeadOfficeLinked` (default false, also when `/config` fails). Step 6 adds `catalogueFromHeadOffice` (see "Catalogue owned by the head office"), step 7A `supplyFromHeadOffice`, last field (see "BLs at the store").

**Frontend**: page `src/views/admin/holink/HeadOfficeLinkStatus.vue`, route `admin-holink-status` (`/admin/holink/status`, `meta.resource` checked by CASL), menu **Settings** → **Head office link** (last entry).
- Shows the state as a badge with a translated label for each of the six states (PENDING grey, ONLINE green, OFFLINE and REFUSED red, ERROR and NOT_CONFIGURED orange), the backend message as a detail line (English, as sent), last success, last attempt, the head office URL, the store code (or "not set" when `DEFAULT_LOCATION` is empty), and a **Check now** button (disabled while the check runs). A failed `DEFAULT_LOCATION` read is `ERROR` with its own message, not a separate state.
- Exists only when `headOfficeLinked` is true: otherwise the route is redirected to `home`, the menu entry is hidden and the Roles page does not list the permission (`HEAD_OFFICE_LINK_ROUTES`, `HEAD_OFFICE_LINK_PERMISSIONS` in `src/navigation/head-office.js`). A head office is never linked.
- Task 2.6: the page has three parts, see "Head office link: jobs and exchange log", "Page".

**Permission** `read:admin-holink-status` ("Lien siège", Roles page group "Paramètres & Outils"), ADMIN only by default. Seeded (`ZZDataInitializer.HEAD_OFFICE_LINK_ADMIN_PERMISSIONS`) when the ADMIN role is created on a store that has `headoffice.url`; the 4 profiles without the URL seed exactly the roles of before. **A store database whose ADMIN role already exists** (every existing install, and a store linked after its first start) does not get it: on that store, once `headoffice.url` is set, open the Roles page, tick "Lien siège" for ADMIN, save, then log out and in (abilities are built at login). No startup top-up.

### Sales copies: the store side (step 2)
Every finished ticket, return and session closing of a store reaches the head office as a copy (design 2.3 and 4.1, "documents up"). Nothing changes in how the store sells: the documents are found by a query, not by a hook in the selling services, and no column of theirs is added or written. A store that also exports to NAV keeps its ERP export as it is: the two upstreams are independent, each with its own tracking. `SalesHeader.synchronizationStatus` belongs to the ERP export and is neither read nor written here.

**When it runs** (decision 4): only on a store with `headoffice.url` set whose sales upstreams include the head office (`ApplicationModeService.salesUpstreams()`, key `sales.upstream`). Its beans carry `@ConditionalOnHeadOfficeSalesPush` (`config/OnHeadOfficeSalesPushCondition`, which calls `NodeOwnership.isHeadOfficeSalesPushSet`: the same reading as the startup, mode flags read from the environment). A linked store without that upstream keeps the heartbeat only.

**Finished documents.** Only a finished document is sent the first time; a document that was never finished is never sent. **Once a document has a tracking row, any later change is sent whatever its new status, and the head office copy takes the new status** (a ticket cancelled after it was sent is sent again as `CANCELLED`, and the head office row becomes `CANCELLED`: still one row, not counted as a sale).

| Type | Entity | Finished | Not finished |
|---|---|---|---|
| `TICKET` | `SalesHeader` | `COMPLETED`; `REFUNDED` (never written by the code today, but a refunded ticket was completed first) | `PENDING` (parked), `CANCELLED` (a parked ticket cancelled, or emptied by a split bill) |
| `RETURN` | `ReturnHeader` | `COMPLETED`, the only status a return gets (validated when it is created) | — |
| `SESSION` | `CashierSession` | `CLOSED` (counted by the cashier), `TERMINATED` (verified by the responsible) | `OPENED` |

The ERP export takes `COMPLETED` tickets and returns and `TERMINATED` sessions. The head office also gets a session when it is `CLOSED`, then again when it is `TERMINATED`.

**Can a finished document leave its status?** (checked in step 2, item 1)
- Through the selling services, no. `CANCELLED` is written only from `PENDING` (`cancelPending`, guarded; a split bill that empties the parked ticket). `REFUNDED` is never written (a return does not change the ticket). A completed ticket's later changes are a payment method change and an invoice prepared. A return is created `COMPLETED` and never changed. `OPENED` is written only when a session is opened; a session only goes `CLOSED` → `TERMINATED` (no reopen).
- Through the generic CRUD, yes: `PUT /sales-header/{id}`, `PUT /return-header/{id}` and `PUT /cashier-session/{id}` (`_BaseController.update`, not guarded, see "Guards") can write any status. The rule above covers it: the change moves `updated_at`, the search finds the tracked document, the push sends it with its new status.
- The search reads documents of every status and dates sessions by their opening date (never empty), so a tracked document is found again whatever happens to it, a reopened session included. Before item 1 sessions were dated by their closing date, which a reopen could clear.
- Not covered: a document deleted at the store after it was sent (generic `DELETE /{id}`) stays at the head office as it was; its tracking row becomes `ERROR` "the document no longer exists in the store".

**Change detection relies on `updated_at`.** Every change to a document once it is finished goes through a save of its header, which moves `updated_at` (`_BaseService.save`, `@PreUpdate`): payment method changed (the ticket is saved), invoice prepared, session closed then verified (the cash count lines are saved in the same transaction as the session), real cash recomputed. Lines and payments of a completed ticket are not edited in any other way; return lines and the voucher amount are written once. What changes without its header (a voucher's status and used amount, an item or customer renamed) is not in the copy, or travels with the next copy.

**Tracking table** `hol_sales_copy` (entity `holink/model/SalesCopy`), one row per document. Prefix `hol_`: store tables of the head office link (`ho_` is kept for head office tables). Like `ho_store`, the `hol_` tables exist in every database through `ddl-auto` and stay empty where nothing writes to them. No `update.sql` (decided for the plan: one `db/2.1.0/update.sql` at the end).

| Column | Meaning |
|---|---|
| `document_type` | `TICKET`, `RETURN`, `SESSION`. Unique with `local_id` (`uk_hol_sales_copy_document`) |
| `local_id` | Id of the document in `sales_header`, `return_header` or `cashier_session` |
| `document_number` | Sales number, return number, session number |
| `document_date` | Sales date, return date, session opening date: pending copies are sent oldest first |
| `status` | `PENDING` (to send), `SENT` (accepted by the head office as it is now), `ERROR` (rejected, or the store could not build it; retried) |
| `attempts` | Pushes of this version of the document that got an answer for it, or that the store could not build. An unreachable head office is not counted. Back to 0 when the document changes |
| `last_error` | Reason of the last rejection or build failure (1000 characters); null after an accepted push or a change |
| `last_push_date` | Store clock of the last push that got an answer for this document |
| `content_hash` | SHA-256 of the copy last accepted by the head office: a document marked as changed whose copy is unchanged (e.g. the ERP export wrote its own fields) is marked `SENT` again without being sent |

Index `ix_hol_sales_copy_queue` (`document_type`, `status`, `attempts`, `document_date`): the push's queue query (task 2.4). Plus `_BaseEntity` columns (`created_at`, `updated_at`...).

**Cursor table** `hol_sales_cursor` (`SalesCopyCursor`), one row per type: change time and id of the last document read.

**The search** (task 2.1): `SalesCopyFinder.discover`, over `SalesDocumentSource` (JPQL in `holink/repository/JpaSalesDocumentSource`, read only, through the shared entity manager; no existing repository is changed). Per type, in its own transaction:
1. Read the documents of any status whose change time (`updated_at`, or the document date when it is empty) comes after the cursor, in (change time, id) order, dated from `headoffice.sales-push.from-date` (sales date, return date, session opening date; never empty, so a tracked document is always read again after a change).
2. A finished document without a row gets a `PENDING` row. A tracked document that is `SENT` or `ERROR` becomes `PENDING` again, attempts back to 0 and error cleared; the accepted hash is kept. A tracked `PENDING` document is left as it is. An unfinished document without a row is skipped: when it is finished later, its `updated_at` moves and it is read again.
3. The cursor moves to the last document read.

| Bound | Value | Why |
|---|---|---|
| Page | 500 documents per type per search | A long history is read over several cycles |
| Settle delay | Documents changed less than 30 s ago wait for a later search | `updated_at` is written before the transaction commits: read too early, an uncommitted change could be passed by the cursor |
| Timeout | 15 s per type (transaction timeout, applied to each statement) | The store database reads with locks (`READ_COMMITTED_SNAPSHOT` is off in `pos_db_prod`): a row held by a long ERP export transaction makes the read wait. On timeout that type is reported and searched again at the next cycle, from the same cursor; the other types go on |

A cycle with nothing new runs three queries that start after the cursor and return nothing; no tracking row is read or written. There is no index on `updated_at` in the existing tables (no change to them): the database scans the table, which is the price of not touching them.

Known limit: the cursor follows the store clock. If the clock goes back, a document changed while it is behind the cursor is missed until its next change. Tunisia has no daylight saving time.

**The copies** (task 2.2): DTOs in `headoffice/dto` (the contract the head office owns, like the heartbeat DTO), built by `holink/service/SalesCopyMapper` (pure mapping; the caller loads the header and its lines). References travel as business codes plus a readable name, never a database id: the head office does not have the store's items, customers, users or sessions. No store code in the body: the head office takes the authenticated store. Every DTO ignores unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`). Dates are ISO strings (`2026-10-03T10:15:30`). Lines, payments and count lines keep the store's order (by id) and are numbered from 1 (`lineNo`). ERP fields (`synchronizationStatus`, `erpNo`, `synched`) are not copied.

| Copy | Fields |
|---|---|
| `TicketCopyDTO` | `salesNumber`, `salesDate`, `completedDate`, `status`; `subtotal`, `taxAmount`, `discountAmount`, `discountPercentage`, `totalAmount`, `paidAmount`, `changeAmount`; `discountSource`, `promotionCode`, `promotionName` (header discount); `customerCode`, `customerName`; `cashierLogin` (username), `cashierName`; `sessionNumber`; `loyaltyCardNumber`, `loyaltyMemberName`, `loyaltyPointsEarned`, `loyaltyPointsRedeemed`, `loyaltyDeductionAmount`; `invoiced`, `invoiceNumber`, `tableNumber`, `notes`; `lines`, `payments` |
| `TicketLineCopyDTO` | `lineNo`, `itemCode`, `itemName`, `quantity`, `unitPrice`, `unitPriceIncludingVat`, `vatPercent`, `vatAmount`, `discountPercentage`, `discountAmount`, `discountSource`, `promotionCode`, `lineTotal`, `lineTotalIncludingVat` |
| `PaymentCopyDTO` | `paymentMethodCode`, `paymentMethodName`, `amount`, `paymentDate`, `titleNumber`, `dueDate` |
| `ReturnCopyDTO` | `returnNumber`, `returnDate`, `status`, `returnType`, `originalSalesNumber`, `totalReturnAmount`, `discountPercentage`, `cashierLogin`, `cashierName`, `sessionNumber`, `voucherNumber`, `voucherAmount`, `voucherExpiryDate` (as written at the return; the voucher's later use is not copied), `notes`, `lines` |
| `ReturnLineCopyDTO` | `lineNo`, `itemCode`, `itemName`, `quantity`, `unitPrice`, `unitPriceIncludingVat`, `lineTotal`, `lineTotalIncludingVat`, `notes` |
| `SessionCopyDTO` | `sessionNumber`, `status`, `cashierLogin`, `cashierName`, `openedAt`, `closedAt`, `openingCash`, `realCash`, `posUserClosureCash`, `responsibleClosureCash`, `verifiedByLogin`, `verifiedByName`, `verifiedAt`, `verificationNotes`, `counts` |
| `SessionCountCopyDTO` | `lineNo`, `counterType` (`POS_USER`, `RESPONSIBLE`), `paymentMethodCode`, `paymentMethodName` (null for cash), `denominationValue`, `quantity`, `lineTotal`, `referenceNumber` |

The item, customer, member and payment method names are the store's names when the copy is built: a rename travels with the next copy of that document.

### Sales copies: the head office side (task 2.3)
**Tables** (head office only, package `headoffice`, prefix `ho_`; like `ho_store` they also exist empty in a store database). The store tables are not reused: a ticket there points to a session, a user and a customer that do not exist at the head office. No `update.sql`: Hibernate creates the tables and constraints.

| Table (entity) | Content | Key |
|---|---|---|
| `ho_ticket` (`HoTicket`) | The fields of `TicketCopyDTO`, `store_id` (FK to `ho_store`) | Unique `uk_ho_ticket_store_number` (`store_id`, `sales_number`) |
| `ho_ticket_line` (`HoTicketLine`) | The fields of `TicketLineCopyDTO`, `ticket_id` | — |
| `ho_ticket_payment` (`HoTicketPayment`) | The fields of `PaymentCopyDTO`, `line_no` in the order received, `ticket_id` | — |
| `ho_return` (`HoReturn`) / `ho_return_line` (`HoReturnLine`) | The fields of `ReturnCopyDTO` / `ReturnLineCopyDTO`, `return_id` | Unique `uk_ho_return_store_number` (`store_id`, `return_number`) |
| `ho_session` (`HoSession`) / `ho_session_count` (`HoSessionCount`) | The fields of `SessionCopyDTO` / `SessionCountCopyDTO`, `session_id` | Unique `uk_ho_session_store_number` (`store_id`, `session_number`) |

A header owns its lines (`cascade = ALL`, `orphanRemoval`, ordered by `line_no`). `created_at` is the first reception, `updated_at` the last one. Sales numbers are already unique across stores (location code + date + sequence); the key includes the store anyway, so two stores with the same number never collide.

**Endpoints** (`HeadOfficeSalesAPI`, head office only, under the `/ho/**` chain: store key, then license, see "Store API"):

| Request | Body | Answer |
|---|---|---|
| `POST /ho/sales/tickets` | JSON array of `TicketCopyDTO` | 200 `{"results":[{"documentNumber":"...","accepted":true,"message":null}, ...]}` (`SalesCopyAnswerDTO`), one result per document in batch order |
| `POST /ho/sales/returns` | JSON array of `ReturnCopyDTO` | same |
| `POST /ho/sales/sessions` | JSON array of `SessionCopyDTO` | same |

**Rules** (`headoffice/service/SalesCopyReceiver`):
- The store is the authenticated principal from `StoreApiKeyFilter`; a store code in the body is ignored. The principal is never saved: new rows reference it by id (`StoreRepository.getOne`).
- Each document is saved in its own transaction and gets its own result: a bad document is rejected and the next ones are saved.
- Saved by store + document number. A document received again replaces its row's content, lines and payments included (cleared and added again): a repeated push leaves one row, a changed document is not duplicated.
- Rejected (`accepted: false`, the reason in `message`, at most 500 characters): an empty document (`empty document`); a missing number, date or status (`salesNumber is required`, `returnDate is required`...); a line without item code or quantity (`line 2: itemCode is required`); a payment without method code or amount (`payment 1: paymentMethodCode is required`); a session without `openedAt`; anything the database refuses (most specific cause, e.g. `SQLException: String or binary data would be truncated.`).
- Log (head office): one INFO line per batch, `Head office: 50 tickets received from store 'RS01' (49 accepted, 1 rejected)`, and one WARN line per rejected document with its number and reason.

**Settings** (the store's properties file; checked at startup only when `headoffice.url` is set, in `NodeOwnership.resolve`):

| Key | Rule |
|---|---|
| `sales.upstream` | Where sales copies go (step 0 key): `HEAD_OFFICE` on a store without ERP, `ERP,HEAD_OFFICE` on an ERP store (the ERP export does not read this key today; ERP stays in the list so the value tells the truth). Without `headoffice.url` an explicit `HEAD_OFFICE` stops the startup (task 2.4) |
| `headoffice.sales-push.from-date` | Optional. `yyyy-MM-dd`, trimmed; documents dated before that day are ignored (a session by its opening date). Absent or blank: the whole history. Any other value stops the startup: `Invalid value '<value>' for property headoffice.sales-push.from-date: a date as yyyy-MM-dd` |
| `headoffice.sales-push.batch-size` | Documents per request, default `50`, from 1 to 1000 (task 2.4) |
| `headoffice.sales-push.interval-seconds` | Seconds between two push cycles, default `60`, at least 1 (task 2.4). Since task 2.6 this is the default of the `SALES_PUSH` job: a frequency saved from the Head office link page wins (`SalesPushSettings.getDefaultIntervalSeconds()`, `LinkJobService`) |

### Sales copies: the push (task 2.4)
`holink/service/SalesPushService`, run as the job `SALES_PUSH` (`holink/scheduler/SalesPushJob`, task 2.6) by `LinkJobScheduler`; all `@ConditionalOnHeadOfficeSalesPush`.

**When**: on the `ho-link-1` thread, after the heartbeat. First cycle 20 s after the start (the first heartbeat is at 15 s), then the job's frequency (saved from the page, or `headoffice.sales-push.interval-seconds`) after the end of the previous cycle. While a long history is caught up (a search page full, a full batch with documents never tried, or the cycle time bound reached) the next cycle comes 5 s later; the heartbeat, due in between, runs first. A cycle never throws: a failure is logged and the next cycle comes at the usual interval. Nothing in the selling path or the ERP export calls it, and it writes no selling table (copies are built in read-only transactions).

**One cycle**:
1. The search (task 2.1).
2. Up to 2 batches per document type, types in turn (tickets, returns, sessions, tickets...), each of `batch-size` documents: never tried first (fewest attempts), then oldest document first. The first round also takes `ERROR` documents (retried); the second takes `PENDING` only, so a document is sent at most once per cycle.
3. Per batch: each copy is built in its own read-only transaction (15 s timeout). A copy whose hash equals the hash of the copy the head office accepted last is marked `SENT` without being sent. The others go in one request (`HeadOfficeClient.push`, `POST /ho/sales/<type>`, same headers and timeouts as the heartbeat; the body is written by the client's own mapper, dates as ISO strings).
4. No new batch is started once the cycle has run 20 s: catching up a long history does not hold the heartbeat back.

| Outcome | Tracking row |
|---|---|
| Accepted | `SENT`, `content_hash` of the copy sent, attempts + 1, `last_error` null, `last_push_date` now |
| Rejected (one result with `accepted: false`) | `ERROR`, the reason in `last_error`, attempts + 1, `last_push_date` now; retried at later cycles, after the documents never tried |
| Missing from a delivered answer | `ERROR`, `no result from the head office for this document`, attempts + 1 |
| The store cannot build it (deleted: `the document no longer exists in the store`; read failure: `the store could not build the copy (...)`) | `ERROR`, attempts + 1; the rest of the batch is sent |
| Not delivered: head office unreachable, key refused (401), no license (402), any other status, unreadable answer, `DEFAULT_LOCATION` empty or unreadable | **No change** (no attempt, no error stored); the cycle stops and the documents wait for the next one |
| Copy unchanged since accepted | `SENT` again, not sent, no attempt |

One rejected document never stops the others: each follows its own result.

**Log** (store): the jobs' start line (`Head office link: jobs on ho-link-1: ...`); one INFO line per cycle that found or sent something (`Head office sales push: 50 sent, 0 rejected, 0 not built, 0 unchanged; 120 new, 0 changed; now 70 pending, 905 sent, 0 in error`); one INFO line when delivery fails (`not delivered, documents stay pending (OFFLINE: head office unreachable (...))`), DEBUG while it keeps failing, INFO `delivered again`; one WARN line the first time a document is not accepted, DEBUG on its next tries; WARN when a search or a cycle fails. Nothing at INFO on an idle cycle.

**Counts**: `GET admin/holink/status` gives `pendingCount`, `sentCount`, `errorCount` (see "Head office link page").

### Head office link: jobs and exchange log (task 2.6, store side)
Backend of the jobs and exchange log of the store's Head office link page (model: the ERP jobs page and the ERP communications log; nothing in `erp/` is used or changed). Package `holink`, beans `@ConditionalOnHeadOfficeLink`. The page stays under the permission `read:admin-holink-status`.

**Jobs.** A job is a bean implementing `holink/scheduler/LinkJob`: a code, a default frequency (its properties value), a first delay and `run()`. `LinkJobScheduler` runs every job bean on `ho-link-1`: first run after the job's first delay, then the frequency in force after the end of the previous run, or 5 s later while the job has more to do. Which jobs exist is decided by the settings (the bean conditions), never by the user.

| Code | Class | Exists when | Order | First run | Default frequency |
|---|---|---|---|---|---|
| `HEARTBEAT` | `HeartbeatJob` | `headoffice.url` set | 1 | 15 s after the start | `headoffice.heartbeat-interval-seconds` (60) |
| `SALES_PUSH` | `SalesPushJob` | and the sales upstreams include the head office (decision 4) | 2 | 20 s | `headoffice.sales-push.interval-seconds` (60) |
| `COPIES_DOWN` | `CopiesDownJob` (task 3.1) | and at least one domain is owned by the head office | 3 | 25 s | `headoffice.pull.interval-seconds` (60) |
| `LOYALTY_PUSH` | `LoyaltyPushJob` (step 4) | and loyalty is owned by the head office | 4 | 30 s | `headoffice.loyalty-push.interval-seconds` (60) |
| `SUPPLY_PUSH` | `SupplyPushJob` (step 7A) | and the goods come from the head office (`NodeOwnership.isSupplyFromHeadOffice`) | 5 | 35 s | `headoffice.supply-push.interval-seconds` (60) |

**Add a job** (later steps): one bean implementing `LinkJob` with the condition that decides whether it exists, an `@Order` for its place in the list and a new code; it writes its own exchange log rows through `LinkExchangeLog`. Nothing else changes: the scheduler, the jobs list, the frequency, run now and the log pick it up.

**Frequency**: saved from the page in `hol_job.interval_seconds`; while nothing is saved, the properties value is the default. From 10 s to 86,400 s (24 h). Kept in memory (`LinkJobService`) and read at each run: a change applies without a restart, and the API reschedules the job so its next run comes one new frequency from now. The table is read once (retried while it cannot be read; the defaults apply meanwhile).

**Each run** is recorded: `lastRunAt` (start, store clock), `lastResult` (`SUCCESS`, `WARNING`, `ERROR`), `lastMessage`, `lastDurationMs`, in memory and in `hol_job` (a failed write is logged and the memory copy kept). A job that throws is recorded as `ERROR` `job failed (...)` and the next run comes at its usual time.

| Job | `SUCCESS` | `WARNING` | `ERROR` |
|---|---|---|---|
| `HEARTBEAT` | `ONLINE` | — | `<state>: <message>` (e.g. `OFFLINE: head office unreachable (...)`) |
| `SALES_PUSH` | `<n> sent, 0 rejected, 0 not built, <n> unchanged`, or `nothing to send` | some documents rejected or not built | `not delivered, documents stay pending (...)`, `search failed (...)`, `cycle failed (...)` |

**Run now**: on `ho-link-1` like the scheduled runs, queued behind a run in progress, waited for up to 30 s; never from the request thread.

**Table** `hol_job` (`holink/model/LinkJobState`): `code` (unique), `interval_seconds` (null = default), `last_run_at`, `last_result`, `last_message` (1000), `last_duration_ms`, plus `_BaseEntity` columns. A row is created at the job's first run or first change; a row whose job no longer exists is not listed.

**Exchange log** `hol_exchange_log` (`holink/model/LinkExchange`, `LinkExchangeLog`): `exchange_date` (start, store clock; index `ix_hol_exchange_log_date`), `job`, `direction` (`UP`, `DOWN`), `record_count`, `result` (`SUCCESS`, `WARNING`, `ERROR`), `error` (first problem, 1000 characters), `duration_ms`, plus `_BaseEntity` columns.
- One row per exchange that sent something or failed. `SALES_PUSH`: one row per batch request (records = documents sent plus those the store could not build; `SUCCESS` all accepted, `WARNING` some rejected or not built, `ERROR` not delivered or nothing accepted; error = `<number>: <reason>` of the first problem, or `<state>: <message>` when not delivered), plus one `ERROR` row with 0 records for a failed search. A cycle with nothing to send, and copies marked unchanged, write no row. `HEARTBEAT`: a row only when its state changes (0 records; `SUCCESS` when it becomes `ONLINE`, otherwise `ERROR` with `<state>: <message>`).
- **A failure that repeats is written once** (step 5, every link job): `SALES_PUSH` not delivered or search failed, `COPIES_DOWN` not delivered, page not applied, cursor not saved or local records not set inactive, `LOYALTY_PUSH` not delivered or search failed go through `LinkExchangeLog.recordFailure`. While the job keeps failing, a failure already written in this episode (same text) writes nothing more (DEBUG line); a different reason writes its row. The episode ends at the job's next exchange that goes through (its batch or page row shows it), or at its next run that is not an `ERROR` (`LinkJobScheduler` calls `afterRun`), which then writes one `SUCCESS` row with 0 records. A failure after that starts a new episode and is written again. Episodes are in memory: a restart writes the first failure again. The Jobs table keeps the current state of every run (last result and message) as before; batch rows are unchanged; the heartbeat already wrote only its state changes.
- Writing never makes an exchange fail: each row is saved in its own transaction (`REQUIRES_NEW`), after the push saved its tracking rows; a failure is logged at WARN and swallowed.
- Purge: rows older than `headoffice.log-retention-days` (default 30) are deleted at the first run after the start, then at most once a day (`LinkExchangeLog.purgeIfDue`, called after each job run).

**API** (`HeadOfficeLinkAPI`, `/admin/holink`, JWT; 404 without `headoffice.url`):

| Request | Answer |
|---|---|
| `GET /jobs` | `[{code, intervalSeconds, defaultIntervalSeconds, customInterval, minimumIntervalSeconds, maximumIntervalSeconds, lastRunAt, lastResult, lastMessage, lastDurationMs, nextRunAt}]` (`LinkJobDTO`), in job order. `intervalSeconds` is the frequency in force; `customInterval` true when one is saved; `nextRunAt` null before the jobs start. The page shows a translated label per `code` |
| `PUT /jobs/{code}/interval` | Body `{"intervalSeconds": n}` (`null` or no body: back to the default). 200 with the job; 400 `{"error":"intervalSeconds must be from 10 to 86400 seconds"}` or `{"error":"intervalSeconds must be a whole number of seconds"}`; 404 `{"error":"No job '<code>' on this store"}`; 500 `{"error":"frequency not saved (...)"}` when the database refuses. The code is matched without regard to case |
| `POST /jobs/{code}/run` | Runs the job now (see "Run now"); 200 with the job after the run (or as it is when the 30 s wait ended first); 404 unknown job |
| `GET /log` | Parameters `job` (a code; blank = every job), `result` (`SUCCESS`, `WARNING`, `ERROR`, any case; blank or `all` = every result), `dateFrom`, `dateTo` (`yyyy-MM-dd`: whole day, or `yyyy-MM-ddTHH:mm[:ss]`), `page` (from 0), `size` (default 20, 1 to 200). Newest first. Answer `{content: [{id, exchangeDate, job, direction, recordCount, result, error, durationMs}], totalElements, totalPages, number, size}`; 400 `{"error": ...}` on a value that cannot be read |

**Page** (task 2.6 frontend, `HeadOfficeLinkStatus.vue`, same route and permission), modelled on the ERP jobs page and the ERP communications log:
1. **Status**: as in task 1.5, plus the sales copies counts (waiting to be sent, sent, in error) from `GET /status`; hidden when the three counts are null (no push job).
2. **Jobs**: one row per job of `GET /jobs`, never more: translated name and code, frequency ("every 1 min", with a default or custom badge), last run and its duration, next run, last result, last message, and two buttons. The pencil opens a dialog: a whole number of seconds within `minimumIntervalSeconds` and `maximumIntervalSeconds` (Save is disabled outside them), or **Back to default** (body `null`, enabled only when `customInterval`). **Run now** is disabled for that job while its call runs (up to 30 s), then the row shows the answer.
3. **Exchange log**: `GET /log` with the filters job (the jobs of `GET /jobs`), result and from / to (`yyyy-MM-ddTHH:mm`), 20 rows per page by default, newest first; a row opens a dialog with the full error.

The page had no automatic refresh before task 2.6; the three parts now refresh silently every 30 s (no spinner, no toast, skipped while a load or a check runs). Labels in `admin.holink.*` (en, fr, ar).

**Settings**: `headoffice.log-retention-days`, default `30`, a whole number of days at least 1; checked at startup only when `headoffice.url` is set (`Invalid value '<value>' for property headoffice.log-retention-days: a whole number of days, at least 1`).

### Copies down (step 3)
Data the head office owns reaches its stores as copies down (design 2.3): the store pulls what changed since its cursor and saves it by business code. Generic: one class per domain on each side, so a later step (loyalty, catalogue, shipments) adds two classes and its domain in `NodeOwnership.COPIES_DOWN_DOMAINS`. Step 3 serves `PROMOTIONS`.

**When it runs** (store): only with `headoffice.url` set and the domain owned by the head office (`ownership.<domain>=HEAD_OFFICE`). The job and the puller carry `@ConditionalOnHeadOfficePull` (URL set and at least one domain owned by the head office, `NodeOwnership.isHeadOfficePullSet`); a domain's handler carries `@ConditionalOnHeadOfficeOwned(<domain>)` (`NodeOwnership.isOwnedByHeadOffice`). A store without `headoffice.url`, or with every domain local, has none of these beans. An explicit `ownership.promotions=HEAD_OFFICE` without `headoffice.url` stops the startup: `Missing value for property headoffice.url: required when ownership.promotions is HEAD_OFFICE ('<value>')`. Step 4: the same for `ownership.loyalty=HEAD_OFFICE`; step 6 for `ownership.catalogue=HEAD_OFFICE`; step 7A for `ownership.supply=HEAD_OFFICE` (`NodeOwnership.COPIES_DOWN_DOMAINS` is `CATALOGUE`, `PROMOTIONS`, `LOYALTY`, `SUPPLY`). A head office keeps its own message for an owner `HEAD_OFFICE`. Known case: a franchise customer (catalogue and supply derived `HEAD_OFFICE`) that also sets `headoffice.url` gets the job without a handler: the catalogue handler needs `NodeOwnership.isCatalogueFromHeadOffice` (an explicit value, standalone, no franchise flag), so it never pulls the catalogue (step 6, "Catalogue owned by the head office").

**Head office side** (package `headoffice`, `@ConditionalOnHeadOffice`):

| Table (entity) | Content |
|---|---|
| `ho_down_sequence` (`HoDownSequence`) | One row per domain: `last_version`, the domain's last change number |
| `ho_down_change` (`HoDownChange`) | One row per (`domain`, `record_code`, `store_id`), `store_id` null = every store (also stores created later); `change_version` = the number of the record's last change for that store. Unique `uk_ho_down_change`, index `ix_ho_down_change_version` (`domain`, `change_version`) |

- `CopiesDownFeed.recordChange(domain, code, stores)`, called inside the writer's transaction (`Propagation.MANDATORY`): increments the domain's number (`update ... set last_version = last_version + 1`, the row stays locked until the commit, so one domain's numbers are given in commit order), then moves the rows of the stores concerned to it. A change of targets passes the old and the new stores together (`StoreTargets.union`): a store taken off gets the code as removed.
- "Changed" covers created, edited, activated or deactivated, deleted, and targets changed: each is one `recordChange`.
- **Cursor**: the change number, made from the head office data only; never a clock. A pull reads the domain's committed number first (the horizon), then the codes changed for the store (its rows and the every-store rows) after the cursor and up to the horizon, oldest first. A change committed during the pull has a higher number and comes with the next pull. A cursor above the horizon (head office database restored) starts again from 0.
- Startup (`ApplicationReadyEvent`): each served domain gets its sequence row, and each existing record without a change row gets one with its targets (backfill: promotions created before step 3 reach every store). Step 6 (a catalogue holds thousands of records): the records without a row are written in chunks of 500 (`CopiesDownFeed.BACKFILL_CHUNK`), each chunk in its own transaction with one update of the sequence (`HoDownSequenceRepository.incrementBy`); each record still gets its own number in the order of `currentTargets`, so the rows are exactly those of one `recordChange` per record (`CopiesDownFeedTest.backfillGivesTheSameRowsAsBefore`, promotions and loyalty). A start stopped in the middle goes on at the next start.
- One `DownDomainProvider` per domain: `load(store, codes)` answers the copy of each code that exists and is addressed to the store; any other code is answered as removed.

**`GET /ho/down/{domain}?cursor=&limit=`** (`HeadOfficeDownAPI`, `/ho/**` chain: store key, then license). `domain` any case (`promotions`). `cursor` blank for the first pull. `limit` codes per page, default 100, at most 500 (0 or less: 100).

| Case | Answer |
|---|---|
| OK | 200 `{"domain":"PROMOTIONS","records":[...],"removed":["P7"],"cursor":"42","more":false}` (`CopiesDownAnswerDTO`); records by business codes, never a database id |
| `more` true | another page waits; `cursor` is the number of the page's last code |
| Cursor not a whole number ≥ 0 | 400 `{"error":"Invalid cursor '<value>': send back the cursor of the last answer"}` |
| Domain unknown or without copies down | 404 `{"error":"No copies down for domain '<value>'"}` |

**Store side** (package `holink`):
- `HeadOfficeClient.pull(domain, cursor, limit)`: `GET` with the two store headers, the cursor encoded strictly. Same failure states as the heartbeat; a page without a cursor, with a cursor over 200 characters or of another domain is `ERROR` "unreadable answer".
- `hol_down_cursor` (`DownCursor`): one row per `domain`, `cursor_value` (200) saved exactly as received and sent back unchanged.
- `CopiesDownPuller`, run by the job `COPIES_DOWN` (`CopiesDownJob`, order 3, after the sales push, first run 25 s after the start, default frequency `headoffice.pull.interval-seconds`, 60; editable on the page like the other jobs). Per domain, in `DataDomain` order:
  0. `prepare()` (step 6, nothing by default): the catalogue gives the head office price back to the own prices when the right is off. Runs also when the head office is unreachable; a failure is one `ERROR` row.
  1. `deactivateLocal()`: the handler sets inactive the active local records of the domain (they are kept); when there were some, one exchange log row (`WARNING`, records = how many, `<DOMAIN>: <n> local records set inactive: the domain is owned by the head office`).
  2. Pages of 100 codes: pull, `apply(records, removed)` by the domain's `DownHandler`, then save the page's cursor. Up to 10 pages per cycle and no new page after 20 s; while `more` stays true the next cycle comes 5 s later.
  3. `retry()`: the handler applies again what it could not apply earlier (task 3.5), at every cycle, also when the head office is unreachable.

| Outcome of a pull | Store |
|---|---|
| Delivered | Records saved, removed codes handled, then the cursor saved. Exchange row only when the page had records or removed codes: `SUCCESS`, or `WARNING` with the first problem (`<DOMAIN>: <code>: <reason>`) |
| Nothing new | No change, no exchange row |
| Unreachable, 401, 402, other status, unreadable answer | Nothing changes: no record, no cursor; the store keeps its last copy. One `ERROR` row, 0 records, `<DOMAIN>: <state>: <message>` |
| The handler cannot apply the page (database) | Cursor not moved; one `ERROR` row; the page comes again |
| Cursor not saved | One `ERROR` row; the page comes again and applying it again changes nothing |

- Job run: `SUCCESS`, `WARNING` (records waiting or in error) or `ERROR` (not delivered, not applied); message per domain, e.g. `PROMOTIONS: 3 applied, 1 unchanged, 1 removed, 0 waiting, 0 in error`, `PROMOTIONS: nothing new`, `PROMOTIONS: not delivered, the store keeps its last copy (OFFLINE: ...)`.
- Log (store): INFO when a pull brought something, when local records were set inactive and when delivery fails or comes back; DEBUG otherwise.

**Settings** (store): `headoffice.pull.interval-seconds`, default `60`, a whole number of seconds at least 1, checked only when `headoffice.url` is set.

### Promotions owned by the head office (step 3)
A promotion created at the head office reaches the chosen stores and applies at their tills; the promotion engine is not changed (it reads the store's `promotion` table). Store side: `ownership.promotions=HEAD_OFFICE` with `headoffice.url`. Write guards and the `origin` column: `docs/modules/promotion.md`.

**Head office: target stores** (task 3.3, head office only data, no column on `promotion`):
- Table `ho_promotion_store` (`HoPromotionStore`): `promotion_id`, `store_id` (plain ids), unique `uk_ho_promotion_store`. No row: every store (the default, including stores created later). Rows: only those stores. An empty list is refused. A change of list keeps the rows of the stores that stay and only deletes or inserts the others (L2 fix: Hibernate flushes inserts before deletes, so deleting and inserting the same key in one transaction broke the unique key).
- `HoPromotionService` (`@ConditionalOnHeadOffice`) is the `PROMOTIONS` provider of the feed and implements `PromotionHeadOfficeHooks`, which `PromotionService` calls on a head office only (`ObjectProvider`, no bean on a store):
  - `PromotionService.save` (create, edit, activate, deactivate through `POST`/`PUT /promotion`): a change for the promotion's stores; a code changed (unused promotion) also records the old code, so its stores remove it.
  - `PromotionService.deleteById`: a change for its stores (they get a removal), its target rows deleted.
  - Target change: a change for the old and the new stores together.
  - `getUsageCount` adds `networkUsageCount(code)`: the consolidated tickets (`ho_ticket.promotion_code`) and lines (`ho_ticket_line.promotion_code`) of every store with that code. A head office never sells (its own count is 0), so the edit lock of a used promotion and the delete refusal work for the whole network. Known limit: a store's local promotion that has the same code counts too (stricter lock, never looser).

**Admin API** (`HoPromotionTargetAPI`, `/admin/headoffice/promotions`, JWT, head office only):

| Request | Answer |
|---|---|
| `GET /targets` | `[{promotionId, code, allStores, storeIds, stores: null}]`, one per promotion (the list column) |
| `GET /{id}/targets` | `{promotionId, code, allStores, storeIds, stores: [{id, code, name, active}]}` (stores sorted by code); 404 `{"error":"Not found"}` |
| `PUT /{id}/targets` | Body `{"allStores": true}` or `{"allStores": false, "storeIds": [1, 2]}`. 200 with the view above; 400 `{"error":"Choose every store, or at least one store."}` (empty list, or no body) or `{"error":"Unknown store id(s): [9]"}`; 404. Unchanged targets record nothing |
| `POST /` | Body `{"promotion": {...as POST /promotion...}, "allStores": false, "storeIds": [1]}`; targets absent: every store. Creates the promotion and its targets in one transaction, so no store outside the list ever hears of it. 201 `{promotion, targets}`; 400 `{"error"}` for invalid targets or promotion (e.g. an empty ITEM_GROUP); 500 `{"error"}` otherwise |

The head office page creates a promotion for a list with `POST /admin/headoffice/promotions`. Created with `POST /promotion` and then given a list, the other stores would get a removal of a code they never applied (harmless, but noise in their exchange log). Editing a promotion stays `PUT /promotion/{id}`; deleting `DELETE /promotion/{id}`.

**Payload** (`PromotionCopyDTO`, `headoffice/dto`, by codes only, never a database id): `code`, `name`, `description`, `promotionType`, `scope`, `itemCode`, `itemFamilyCode`, `itemSubFamilyCode`, `groupItemCodes` (sorted), `getItemCode` (benefit item), `minimumQuantity`, `minimumAmount`, `benefitType`, `discountPercentage`, `discountAmount`, `freeQuantity`, `startDate`, `endDate` (`yyyy-MM-dd`), `requiresCode`, `dayOfWeek`, `timeStart`, `timeEnd` (`HH:mm:ss`), `priority`, `active`. Unknown fields are ignored. `PromotionCopyDTO.of(promotion)` builds it on both sides: two promotions with equal copies are the same promotion.

Known limit: the copy names its item, family, sub-family and benefit item by code at the moment of the pull. A code renamed at the head office (an item or family code changed after the promotion was sent) does not send the promotion again: it travels with the promotion's next change (edit, target change). A store whose item kept the old code keeps applying the promotion as received; one whose item has the new code only gets it at that next change.

**Store: received promotions** (`PromotionDownHandler`, `@ConditionalOnHeadOfficeOwned(PROMOTIONS)`), each record in its own transaction:

| Case | Store |
|---|---|
| New code | Saved with `origin=HEAD_OFFICE`, codes resolved to the store's item, family, sub-family, group items and benefit item (`PromotionService.saveReceived`: the normalisation of `save`, audit user `HEAD_OFFICE`) |
| Code of a `HEAD_OFFICE` promotion | Updated on the same row; the "used promotion" lock does not apply (the head office decides) |
| Same copy as the promotion the store has (compared by `PromotionCopyDTO`, after the normalisation) | Nothing written (`unchanged`) |
| Code of a local promotion | Not saved, `ERROR` `code already used by a local promotion of this store`; the local promotion is not touched. The head office admin changes its code. Retried at every cycle (applies if the local one is gone) |
| Item, family, sub-family or benefit item not in the store; ITEM_GROUP with none of its items (task 3.5) | Not applied: `WAITING`, reason `not in this store: item I9` (also `family F9`, `sub-family SF9`, `benefit item I7`, `group items I8, I9`). A promotion the store already has becomes inactive (the rest unchanged). Retried at every cycle from the copy kept in `hol_down_record`, without a new change from the head office: it applies by itself once the record exists |
| ITEM_GROUP with some items missing | Saved with the items the store has, `APPLIED` with the information `group items not in this store: I9` |
| Unreadable record, no code | Error; the next records go on |
| Removed: a `HEAD_OFFICE` promotion never used in a sale here | Deleted |
| Removed: used here | `active=false`, kept (an inactive one stays as it is); targeted again later: updated again (and active as sent) |
| Removed: a local promotion with that code, or no promotion | Nothing |

**Tracking** (task 3.5): table `hol_down_record` (`DownRecord`, `DownRecordLog`), one row per (`domain`, `record_code`), unique `uk_hol_down_record`: `record_name`, `status` (`APPLIED`, `WAITING`, `ERROR`), `reason` (1000), `info` (1000), `payload` (the copy last received, `NVARCHAR(MAX)`), `received_at` (store clock of the copy; a copy received again unchanged does not move it), `status_since`, plus `_BaseEntity` columns. A row is written only when something changed, so the same answer applied twice writes nothing. A removal deletes the row. Generic: later domains use the same table.
- Every cycle, after the pulls, `retry()` applies again the `WAITING` and `ERROR` rows (by code) from their copy; the codes the pulls of this cycle just applied are skipped. The retries run also when the head office is unreachable (they need only the store's data). A record still waiting keeps the job result `WARNING`; it writes no exchange log row (a retry is not a pull).
- Job counts: `applied`, `unchanged`, `removed`, `waiting`, `in error` cover the pulls and the retries of the cycle.

**Local promotions** (Zein's correction, 2026-10-03): at every cycle, before the pull, the active promotions whose origin is null or `LOCAL` are set inactive (kept, origin unchanged; `PromotionRepository.findActiveNotFrom`), with one exchange log row giving how many. While promotions are owned by the head office no write through the API can activate them again.

### Shared loyalty (step 4)
One member register for the network (design 2.3, 4.3; decisions of the step 4 prompt). A member enrolled in any store is known in every store, and the points earned anywhere are known everywhere. Nothing at the till depends on the head office being reachable: finding a member and earning work from the store's own copy; only enrolling (phone check), changing a member and, from step 5, spending, ask the head office.

**When it is active**: on a store with `ownership.loyalty=HEAD_OFFICE` and `headoffice.url` set (without the URL the store does not start, see "Copies down"). With loyalty `LOCAL` nothing changes: no new bean, `LoyaltyService` runs exactly as before (the four loyalty tests are unchanged). Every head office holds a register (owned there) and serves the `LOYALTY` domain; a store that keeps its loyalty local never pulls it.

**Shared code** (`service` package, used by both sides):
- `LoyaltyNetworkHooks`: what shared loyalty adds to `LoyaltyService`, implemented by one bean at most: `HoLoyaltyService` on a head office, `StoreLoyaltyHooks` on a store whose loyalty is owned by the head office (part 2). `LoyaltyService` calls it only when present (`@Autowired(required = false)`, null in the tests that build the service by hand): the card number of a new member, which members count for the phone uniqueness, before and after a member is saved (created, edited, toggled, linked to a customer, adjusted), after a program is saved and before it is deleted. In the sale only `redeemPoints` calls it (step 5, `beforeRedeem`, before anything is written: a store may need a fresh balance); `earnPoints` and `applyReturn` do not.
- `LoyaltyCardNumbers`: `LYL-HO-000001` at the head office, `LYL-<store code>-000001` at a store (code trimmed, uppercase), each the highest number of its prefix plus one (`LoyaltyMemberRepository.findCardNumbersLike`, a LIKE with `!` escaping `%`, `_`, `[`). Today's `LYL-000001` (loyalty `LOCAL`, `findMaxCardSequence`) is unchanged; it would fail on the new formats (it casts what follows `LYL-` to a number), which is why the network numbering does not use it. A store whose code is `HO` would take the head office's numbers: the Stores API refuses that code.
- `LoyaltyLedger`: how a movement changes a balance and the totals, the rules of `LoyaltyService`: `EARNED` adds, `REDEEMED` and `REVERSED` remove, `ADJUSTED` adds or removes (sign from the row's balance before and after); never below zero, what could not be removed is the overspend; `REVERSED` lowers the earned total, `REDEEMED` raises the redeemed total by what was removed, an `ADJUSTED` addition linked to a sale (points given back after a return) lowers the redeemed total, a manual one raises the earned total.
- `MemberFunctionCodes`: the member function travels by code; the receiving side uses its own function of that code and creates it (code and name as received) when missing.
- `origin` (`RecordOrigin`, `VARCHAR(20)`, null = `LOCAL`) on `loyalty_member` and `loyalty_program` (`ddl-auto`; never read from JSON on the program): `HEAD_OFFICE` marks the network register at a store (part 2).

**Head office side** (package `headoffice`, `@ConditionalOnHeadOffice`):
- The program, the members and the ledger are the existing loyalty tables and pages (loyalty is owned there). `HoLoyaltyService` is the `LOYALTY` provider of the copies down feed and the hooks of `LoyaltyService`: every change made through the pages (member created, edited, toggled, linked, points adjusted; program created, edited, closed, deactivated, deleted) is recorded for every store. Cards created there are `LYL-HO-000001`; every member counts for the phone uniqueness (the register is the network's).
- **Copies down**, domain `LOYALTY`, every store (`StoreTargets.all()`): record code `PROGRAM:<program code>` for the program (`LoyaltyProgramCopyDTO`: `kind` `PROGRAM`, the fields of the program, `earningTiers` sorted, `active`; only an active program is answered, a closed or deleted one is answered as removed) and `MEMBER:<card>` for each member (`LoyaltyMemberCopyDTO`: `kind` `MEMBER`, `cardNumber`, names, `phone`, `email`, `birthDate`, `memberFunctionCode` and name, `customerCode`, `loyaltyPoints`, `totalPointsEarned`, `totalPointsRedeemed` as the head office holds them, `active`, `enrolledAt`). By codes only, never an id. Startup backfill: the active programs and every member existing before step 4 reach every store.
- Tables (head office only data, prefix `ho_`, `ddl-auto`):

| Table (entity) | Content | Key |
|---|---|---|
| `ho_loyalty_alias` (`HoLoyaltyAlias`) | A card a store enrolled for a phone that already had a card: not a member, an inactive alias of `member_id`; `store_id` that enrolled it | Unique `uk_ho_loyalty_alias_card` (`card_number`) |
| `ho_loyalty_movement` (`HoLoyaltyMovement`) | A movement received from a store, applied once: card as sent, `member_id` applied to, `type`, `points`, `delta`, `overspend_points`, `sales_number`, `return_number`, `program_code`, `store_date`, `transaction_id` (the ledger row) | Unique `uk_ho_loyalty_movement_store_key` (`store_id`, `store_key`); index on `member_id` |

**Endpoints** (`HeadOfficeLoyaltyAPI`, `HoLoyaltyReceiver`, under the `/ho/**` chain: store key, then license; the store is the principal). Each record in its own transaction, one at a time (phone checks and balances are read then written), so a bad record is rejected with its reason and the next ones go on; every change is recorded for every store.

| Request | Body | Answer |
|---|---|---|
| `POST /ho/loyalty/members` | JSON array of `LoyaltyMemberCopyDTO` (members the store enrolled, balance 0) | `{"results":[{cardNumber, accepted, message, outcome, survivingCardNumber, member}]}` (`LoyaltyMemberResultDTO`), batch order |
| `POST /ho/loyalty/movements` | JSON array of `LoyaltyMovementCopyDTO`: `key` (the store's `loyalty_transaction` id), `cardNumber`, `type`, `points`, `delta` (signed), `salesNumber`, `returnNumber`, `programCode`, `date`, `expiryDate`, `description`, `createdBy` | `{"results":[{documentNumber: key, accepted, message}]}` (`SalesCopyAnswerDTO`) |
| `GET /ho/loyalty/members/by-phone?phone=` | — | `{"found": true, "member": {...}}` (the card holding the normalized phone, the active one first, as the duplicate message names it), or `{"found": false, "member": null}` (also for a blank number) |
| `PUT /ho/loyalty/members/{cardNumber}` | `LoyaltyMemberEditDTO`: `firstName`, `lastName`, `phone`, `email`, `birthDate`, `memberFunctionCode`, `memberFunctionName`, `customerCode`, `active` (null: unchanged) | 200 the member (`LoyaltyMemberCopyDTO`). 403 `{"error":"This store may not change loyalty members: the head office gives the right on its Stores page."}` without `canEditMembers`; 404 unknown card (an alias edits its surviving member); 400 names or function missing, phone missing or not 8 digits; 409 phone of another card (`Ce numéro est déjà utilisé par la carte ...`, the text of `LoyaltyService`) |

Members up (outcome):

| Case | Head office |
|---|---|
| New phone | `CREATED`: the member with the store's card number, balance 0 (its points arrive as movements), `created_by` `STORE:<code>`, `created_at` = the store's `enrolledAt`, function by code (created when missing), customer by code (or none) |
| Card already a member (sent again) | `EXISTS`, nothing written |
| Phone already on another card (the active one first; deactivated cards count, as in today's rule) | `MERGED`: the card becomes an alias of that member (`ho_loyalty_alias`), `survivingCardNumber` names it, `member` is that member; sent again, the same answer and no second alias |
| No card number, no first or last name | Rejected with the reason |

Movements up:
- Applied once: a key already received from that store is answered `accepted` with `already applied` and changes nothing (the member is recorded again, so the store gets it at its next pull). The same key from another store is another movement.
- To the member of the card, or of the member an alias card was merged into. Unknown card: rejected `unknown card <card>: the member has not reached the head office yet` (the store retries).
- `LoyaltyLedger.apply` with the store's `delta` (computed from the type when absent); never below zero, the overspend is kept in `ho_loyalty_movement.overspend_points`, in the ledger row's description and in a WARN line (the report comes in step 5).
- One `loyalty_transaction` row per movement at the head office (the ledger the pages show): the type and points sent, the head office balance before and after, the program by code, `created_by` `STORE:<code>`, description `Store RS01, sale #<n>, return #<n>, card <alias>, (overspend: n points could not be removed): <the store's description>`, cut to 500.
- A manual adjustment (`ADJUSTED` without a sale number) needs `canAdjustPoints`: rejected otherwise (`this store may not adjust points (right off on the head office Stores page)`). Points given back after a return (`ADJUSTED` with a sale) do not.
- Log (head office): one INFO line per batch (`Head office: 3 loyalty movements received from store 'RS01' (3 accepted, 0 rejected)`), a WARN line per rejected record and per overspend, an INFO line per merge.

Step 5, two more store calls (same chain, `HoLoyaltyReceiver`):

| Request | Answer |
|---|---|
| `GET /ho/loyalty/members/{cardNumber}` | 200 the member as the head office holds it now (`LoyaltyMemberCopyDTO`, the fresh balance a till asks for when a member is selected); an alias card answers its surviving member; 404 `{"error":"Unknown card ... at the head office"}` |
| `POST /ho/loyalty/members/{cardNumber}/adjust` | Body `{"delta": 50, "reason": "...", "adjustedBy": "cashier1"}` (`LoyaltyPointsAdjustDTO`). Needs `canAdjustPoints`: 403 `{"error":"This store may not adjust points: the head office gives the right on its Stores page."}`. Applied with the head office's own `LoyaltyService.adjustPoints` (an `ADJUSTED` row, never below zero) by `STORE:<code> (<user>)`, so it is the same as an adjustment made on the head office page, and every store gets the new balance. 200 the member; 400 delta 0 or no reason; 404 unknown card. Nothing is written at the store except the answer: no store movement, so nothing is sent twice |

**Overspend report** (step 5, `HoLoyaltyReportService`, `HoLoyaltyReportAPI`, JWT, head office only). A store spends against its own balance, never blocked by default; a removal that reaches the head office when the balance is already lower stops at zero, and the points missing are kept on its `ho_loyalty_movement` row (`overspend_points`). A movement sent again is not applied again, so it is listed once.

| Request | Answer |
|---|---|
| `GET /admin/headoffice/loyalty/overspends?page=&size=&storeId=&dateFrom=&dateTo=&search=` | `{content, totalElements, totalPages, number, size}`, newest first (head office reception time). Filters and their rules as the consolidated sales lists (`ConsolidatedSalesService.HistoryQuery`: size 10 by default, 1 to 200; dates `yyyy-MM-dd` or `yyyy-MM-ddTHH:mm`, 400 otherwise); `search` contains, any case, on the card sent, the member's card and name, the sale and the return number. Row: `id`, `receivedAt`, `storeDate`, `storeId`, `storeCode`, `storeName`, `cardNumber` (as sent, may be an alias), `memberCardNumber`, `memberName`, `type`, `salesNumber`, `returnNumber`, `points` (asked), `overspendPoints` (missing), `transactionId` (the ledger row) |
| `GET /admin/headoffice/loyalty/overspends/count?dateFrom=&dateTo=` | `{"count": 2, "points": 45}` in the period, every period when absent (home page) |

Page permission `read:admin-headoffice-loyalty-overspends` (seeded on a head office ADMIN, 24 head office permissions; suggested route `admin-headoffice-loyalty-overspends`, path `/headoffice/loyalty/overspends`, menu Customers & loyalty).

Member edit from a store: a blank `memberFunctionCode` keeps the member's own function (a deactivation or a customer link sends the member as it is); a member with no function at all needs one (400).

**Store side** (package `holink`, all `@ConditionalOnHeadOfficeOwned(LOYALTY)`: the URL set and `ownership.loyalty=HEAD_OFFICE`; none of these beans exists otherwise):

| Class | Role |
|---|---|
| `StoreLoyaltyHooks` | The `LoyaltyService` hooks: card `LYL-<store code>-000001` (`DEFAULT_LOCATION`, uppercase; empty: 409 `DEFAULT_LOCATION is empty in the general setup: the card number of a network member carries the store code.`); origin `HEAD_OFFICE`; a `hol_loyalty_member_copy` row `PENDING` in the transaction that creates the member; the phone unique among the members of the register held here (origin `HEAD_OFFICE`): the local cards switched off do not block a number |
| `StoreLoyaltyNetwork` | Enrol, and the member changes through the head office (below); `GET /loyalty/network` |
| `LoyaltyCopyWriter` | Writes what the head office sends: members by card, the program by code, merges, the local records switched off |
| `LoyaltyDownHandler` | The `LOYALTY` handler of the copies down (`PROGRAM:` and `MEMBER:` records, tracked in `hol_down_record`, errors retried every cycle) |
| `LoyaltyPushService`, `LoyaltyPushJob` | The job `LOYALTY_PUSH`: members, then movements, up |
| `LoyaltyNetworkAPI` | `GET /loyalty/network` |

**Enrol** (`POST /loyalty/member`, till and admin page; `LoyaltyAPI` calls `StoreLoyaltyNetwork.enrol`):
1. When the phone has 8 digits, `GET /ho/loyalty/members/by-phone` with the live timeouts (connect 2 s, read 3 s, `HeadOfficeClient.findLoyaltyMemberByPhone`, its own `RestTemplate`; the request thread waits for it).
2. Found: that member is saved here (origin `HEAD_OFFICE`, so the cashier finds it at once) and the answer is today's 409 `Ce numéro est déjà utilisé par la carte LYL-HO-000001 (SAMI BEN)` (with `, carte désactivée` for an inactive card). No card is created. The 409 body also names the card: `{"error": "...", "existingCardNumber": "LYL-HO-000001", "existingCardActive": true}` (`StoreLoyaltyNetwork.PhoneTakenException`); the same body when the phone belongs to a member of the register held here (checked before `LoyaltyService`). With loyalty `LOCAL` the 409 body stays `{"error": "..."}`.
   **Enrol switch** (decided 2026-10-04): with `enrolRequiresOnline` set for the store at the head office (last heartbeat answer), a phone check without an answer (unreachable, refused, timeout, 5xx) ends the enrol with 503 `{"error":"The head office cannot be reached: this store enrols a member only after the head office has checked the phone number. Try again later; the sale can go on without the card."}` (`StoreLoyaltyNetwork.ENROL_NEEDS_HEAD_OFFICE`); nothing is created. A phone that is not 8 digits is not checked and gets today's 400 first. Sales, earning, spending and finding a member are not concerned. Off (default, and while unknown before the first heartbeat answer): step 3 below, as tested at L2.
3. Not found, or no answer (unreachable, refused, timeout; an INFO line `Loyalty: phone not checked at the head office, checked here only (...)`): `LoyaltyService.createMember` as today, with the hooks above. Enrolling never fails because of the head office.
4. The `LOYALTY_PUSH` job sends the member up. A merge answer (the phone had another card in the network, enrolled elsewhere while this store was offline or before its pull) deactivates the card here and saves the surviving member; this card's movements go to it at the head office.

**Member changes** (`StoreLoyaltyNetwork`, called by `LoyaltyAPI` instead of `LoyaltyService`): `PUT /loyalty/member/{id}` (the form: function by id, sent as its code; customer by id, sent as its code), `PUT /loyalty/member/{id}/toggle-active` and `PUT /loyalty/member/{id}/link-customer` (the member as it is, function blank) go to `PUT /ho/loyalty/members/{card}` with the live timeouts; the head office answer is saved here and returned as today's `LoyaltyMemberDTO`.

| Case | Answer at the store (`{"error": ...}`) |
|---|---|
| Head office unreachable, refused (401, 402), 5xx, unreadable | 503 `The head office cannot be reached: changing a loyalty member needs it. Try again later.` |
| Store without `canEditMembers` | 403 with the head office text |
| Card not known there yet (enrolled here, not sent yet) | 409 `The head office does not know this card yet: it is sent within a minute. Try again later.` |
| Phone of another card of the network / bad data | 409 / 400 with the head office text |
| A local card switched off at the switch | 409 `This card is not in the network register (a local card switched off when loyalty moved to the head office).` |
| Unknown id, function or customer | 400 |

**Refused at the store** (`LoyaltyAPI`, before `LoyaltyService`): `POST`, `PUT`, `DELETE /loyalty/programs...` and `POST /loyalty/programs/{id}/deactivate` answer 409 `The loyalty program is managed by the head office.` Reads, search, the transactions pages and the POS are unchanged.

**Point adjustment from the store** (step 5; step 4 refused it): `POST /loyalty/member/{id}/adjust` (same body `{delta, reason}`, checked as before: 400 delta 0 or no reason) goes to `POST /ho/loyalty/members/{card}/adjust` with the live timeouts, the user's login as `adjustedBy` (`StoreLoyaltyNetwork.adjust`). Applied there only; the answer is saved here (head office balance + this store's movements not applied there yet) and the card is fresh. No movement is written here, so nothing goes up twice. 403 with the head office text without `canAdjustPoints`; 503 `The head office cannot be reached: adjusting points needs it. Try again later.`; 409 for a card not sent yet or a local card.

**Fresh balance at the till** (step 5, `GET /loyalty/member/{id}/fresh`, `StoreLoyaltyNetwork.refresh`; the POS calls it when a member is selected): asks `GET /ho/loyalty/members/{card}` with the live timeouts (connect 2 s, read 3 s), saves the answer with the balance rule of the pull, records the card as fresh (`LoyaltyFreshness`, in memory, by card; a restart forgets it and the till asks again). Never fails the till:

| Case | Answer `{member, fresh, refreshedAt, message, redeemRequiresOnline, canRedeem}` |
|---|---|
| Head office answers | `fresh: true`, `refreshedAt` (store clock), `message: null`, `member` with the new balance (an alias card answers its surviving member) |
| Unreachable, refused, timeout | `fresh: false`, this store's copy, `message` `The head office does not answer: the balance shown is this store's.` |
| Card not sent yet (404 there) | `fresh: false`, `The head office does not know this card yet: the balance shown is this store's.` |
| A local card switched off | `fresh: false`, the not-in-register text |
| Unknown id | 400 `{"error"}` |

`redeemRequiresOnline`: this store's setting (false when unknown); `canRedeem`: false only when the setting is on and the card is not fresh (the POS disables spending then).

**Spending** (step 5): in the sale exactly as today, against the store's balance, and the movement goes up; nothing is blocked by default. With `redeemRequiresOnline` set for the store at the head office (last heartbeat answer), `LoyaltyService.redeemPoints` calls the hook `beforeRedeem` (`StoreLoyaltyHooks`), which refuses (409, `IllegalStateException`) `Points cannot be spent: this store spends points only with a balance checked with the head office in the last 2 minutes, and it could not be checked. Select the card again when the head office answers, or complete the sale without spending points.` unless the card was refreshed within the last 2 minutes (exactly 2 minutes still counts). The sale fails with that reason as for any loyalty refusal (the selling services are not changed); the same sale without points, and earning, go through. Unknown setting (before the first heartbeat answer after a start) or false: never refused. With loyalty `LOCAL` the hook does not exist.

**`GET /loyalty/network`** (JWT; 404 when loyalty is not owned by the head office): `{"ownedByHeadOffice": true, "linkState": "ONLINE", "canEditMembers": true, "canAdjustPoints": false, "programEditable": false, "pointsAdjustable": false, "redeemRequiresOnline": false, "freshWindowSeconds": 120, "enrolRequiresOnline": false}` (rights null before the first heartbeat answer; `HeadOfficeLinkStatus` keeps those of the last answer that carried them, through failures; `pointsAdjustable` is `canAdjustPoints` since step 5).

**Copies down at the store** (`LoyaltyDownHandler`, `LoyaltyCopyWriter`), each record in its own transaction:

| Record | Store |
|---|---|
| `PROGRAM:<code>` | Saved by code, origin `HEAD_OFFICE`, tiers replaced; when active, every other active program here is set inactive: the store applies one program, the head office's. Earning (`LoyaltyService.getActiveProgram`) uses it as before |
| `PROGRAM:<code>` removed (closed or deleted there) | Set inactive, kept (its transactions point to it) |
| `MEMBER:<card>` | Saved by card, origin `HEAD_OFFICE` (function by code, created when missing; customer by code or none), balance as below. Unchanged: nothing written |
| A code of a local record (made before the switch) | Not saved, `ERROR` `card number already used by a local member of this store` (`program code already used by a local program of this store`), retried every cycle |
| Before each pull | The active members and programs with origin null or `LOCAL` are set inactive, kept; one exchange row `WARNING` `LOYALTY: <n> local records set inactive: the domain is owned by the head office`. Their movements are never sent |

**The balance shown at the store never goes backwards because of the order of sync.** A member's balance here = the head office balance of the last copy received + this store's movements the head office has not applied yet: its `loyalty_transaction` rows (and those of a card of this store merged into it) without a `SENT` row in `hol_loyalty_movement_copy`, replayed in order with `LoyaltyLedger` (never below zero). The totals the same way. Why it holds:
- The sale writes the balance as today; the push never touches it. Only a copy (pull, live answer, merge answer) rewrites it, with the formula above.
- The push and the pull run one after the other on `ho-link-1`, and a movement is `SENT` only after the head office applied it: a copy received later already contains it, so each movement is counted once, there or here.
- Pulled before the push: head office balance (without ours) + ours = what the sale showed, plus what other stores added. Pushed then pulled: head office balance (with ours) + nothing pending.
- An answer lost after the head office applied a movement: the movement stays pending here, so the next copy counts it twice (forwards, never backwards) until it is sent again; the head office answers `already applied` and sends the member again, which puts the balance right at the next pull.
- Another store's spending lowers the balance at the next pull: that is the network's balance, not the order of sync.

**Push** (`LoyaltyPushService`, job `LOYALTY_PUSH` on `ho-link-1`, after the other jobs at start: first run 30 s, then the frequency):
1. Search: every `loyalty_transaction` row of a member with origin `HEAD_OFFICE` without a `hol_loyalty_movement_copy` row gets one, `PENDING` (500 per cycle; a scalar query, no sale is loaded). No hook in the selling services: earning, spending and returns write their rows as before, and those rows are never changed, so a movement is never missed or sent twice.
2. Members: up to 2 batches of 50 (`POST /ho/loyalty/members`), never tried first; the first batch also retries `ERROR`. Balance 0 in the upload: points travel as movements. `MERGED`: `LoyaltyCopyWriter.merge`. 
3. Movements: up to 4 batches of 50 (`POST /ho/loyalty/movements`), oldest first; a movement waits (stays `PENDING`, no attempt) while the member of its card is not `SENT`. The card sent is the member's card when the movement was made (the alias after a merge).
4. No new batch after 20 s; more waiting: the next cycle 5 s later.

| Outcome | Tracking row |
|---|---|
| Accepted (also `already applied`) | `SENT`, attempts + 1, `last_error` null, `last_push_date` |
| Rejected, or missing from the answer | `ERROR` with the reason, attempts + 1; retried at later cycles |
| The store cannot build it (member or movement deleted) | `ERROR`, attempts + 1 |
| Not delivered (unreachable, 401, 402, other status, unreadable) | No change; the cycle stops |

Exchange log: one row per batch (`UP`, records sent plus not built; `SUCCESS`, `WARNING` with the first problem, `ERROR` when nothing was accepted or not delivered) and one `ERROR` row for a failed search. Job result: `ERROR` (not delivered, search failed), `WARNING` (rejections), `SUCCESS`; message `members: 1 sent (0 merged), 0 rejected; movements: 3 sent, 0 rejected, 0 not built` or `nothing to send`. Log (store): INFO per cycle that found or sent something, when delivery fails and when it comes back; DEBUG otherwise.

Tables (store, prefix `hol_`, `ddl-auto`):

| Table (entity) | Content | Key |
|---|---|---|
| `hol_loyalty_member_copy` (`LoyaltyMemberCopy`) | A member enrolled here: `member_id`, `card_number`, `status` (`PENDING`, `SENT`, `ERROR`), `attempts`, `last_error` (1000), `last_push_date`, `outcome` (`CREATED`, `EXISTS`, `MERGED`), `surviving_card_number` | Unique `uk_hol_loyalty_member_copy_card`; indexes on (`status`, `attempts`) and `surviving_card_number` |
| `hol_loyalty_movement_copy` (`LoyaltyMovementCopy`) | A movement of a member of the register: `local_id` (the `loyalty_transaction` id, the key sent), `card_number`, `type`, `points`, `delta`, `movement_date`, `status`, `attempts`, `last_error`, `last_push_date` | Unique `uk_hol_loyalty_movement_copy_local`; indexes on (`status`, `local_id`) and `card_number` |

**Settings** (store): `ownership.loyalty=HEAD_OFFICE` (needs `headoffice.url`); `headoffice.loyalty-push.interval-seconds`, default 60, a whole number of seconds at least 1, checked only with the URL.

**Not done**: program changes at a store; a hold of points at the head office before a sale (decided 2026-10-03: no hold and confirm, spending against the store's balance, overspends reported); importing an existing member list (later tool); a member deleted at the head office (there is no delete); returns and vouchers in another store than the one that sold the ticket.

**Returns** (step 5, nothing new in the code): processed in the store that sold the ticket, by `LoyaltyService.applyReturn` as today; its `ADJUSTED` (points given back, linked to the sale) and `REVERSED` rows go up like any movement, and the head office applies them with the same rules, so after a partial then a full return its balance and totals equal the store's (`SharedLoyaltyRoundTripTest.returnsMatch`).

### Catalogue owned by the head office (step 6)
Items, families, sub-families and barcodes decided by the head office reach every store whose catalogue is the head office's; each item carries the one selling price worked out for that store (design 3.3, 3.4). `PricingService` and the selling services are not changed: the till reads `item.unitPrice` as before.

**When it is active**
- Head office side: only on a head office **without an ERP** (`node.type=HEAD_OFFICE` and `application.standalone=true`, `@ConditionalOnHeadOfficeStandalone`, `NodeOwnership.isHeadOfficeStandaloneSet`). A head office with an ERP (ParaFendri) serves no `CATALOGUE` domain and has no price lists: its items come from `erp/`, which has no hooks, and its stores keep `CATALOGUE=ERP`.
- Store side: `@ConditionalOnHeadOfficeCatalogue` (`NodeOwnership.isCatalogueFromHeadOffice`): `headoffice.url` set, `ownership.catalogue=HEAD_OFFICE`, `application.standalone=true`, no franchise flag. Startup refusals: an explicit `ownership.catalogue=HEAD_OFFICE` without the URL, with `franchise.customer=true` or `franchise.admin=true`, or with `application.standalone=false` (see `docs/deployment-modes.md`).
- **A franchise customer never gets it**, even with `headoffice.url`: it derives `CATALOGUE=HEAD_OFFICE` for its legacy item sync, but the condition requires no franchise flag, and an explicit value with the flag stops the startup. `fromFranchiseAdmin`, `FranchiseSyncService`, `/franchise/**` and the franchise branches of `ItemAPI` are not touched and still run first. `/config` reports `ownership.CATALOGUE=HEAD_OFFICE` for it as before; the frontend reads `catalogueFromHeadOffice` instead.
- Every other store (no setting, ERP, franchise): no catalogue bean, every API answers as before (the guards below are looked up with `ObjectProvider` and absent).

**Head office side** (`HoCatalogueService`, the `CATALOGUE` provider of the feed and `CatalogueHeadOfficeHooks`):
- Record codes `FAMILY:<code>`, `SUBFAMILY:<code>`, `ITEM:<code>`, `BARCODE:<barcode>` (`CatalogueKind`), every record for every store (`StoreTargets.all()`), plus per-store rows for the price lists (below). Inside a page a store applies families, then sub-families, items, barcodes.
- Every save and delete through `ItemService`, `ItemFamilyService`, `ItemSubFamilyService`, `ItemBarcodeService` (hooks in the services, inside their transaction), every pack change (`ItemCompositionService`: the parent item, old and new parent) and the quick product (default family and sub-family included) is one change. A data import (`DataImportService`, FAMILIES, SUBFAMILIES, ITEMS, BARCODES) records the codes it saved at the end, in chunks of 500 per transaction (a crash between the import and the recording loses those changes; they travel with the next change of each record). An item's save also records its barcodes; deleting an item deletes its price list lines and records its barcodes.
- The code of an item, a family or a sub-family cannot change once created: 409 `The code of the item B001 cannot be changed: the stores know it by its code. Create a new one and deactivate this one.` (`CatalogueCodeChangeException`). A barcode value may change: the old value is answered as removed. A code longer than 90 characters (record codes hold 100) is refused with 400 on the item, family, sub-family and barcode APIs (`CatalogueCodeTooLongException`); an existing or imported one is skipped with a WARN line.
- The system item `TAX_STAMP` (created by every installation) and its barcodes never travel.
- On a store every API of these services answers as before (no hooks bean).

**Payload** (by codes only, never a database id; the `of(...)` of each DTO is used on both sides):

| Record | Fields |
|---|---|
| `FAMILY:` (`CatalogueFamilyCopyDTO`) | `kind`, `code`, `name`, `description`, `displayOrder`, `active` |
| `SUBFAMILY:` (`CatalogueSubFamilyCopyDTO`) | the same and `familyCode` |
| `ITEM:` (`CatalogueItemCopyDTO`) | `kind`, `itemCode`, `name`, `description`, `type`, `unitPrice` (the price for the pulling store: the line of its selling price list, otherwise `item.unitPrice`), `defaultVAT`, `unitOfMeasure`, `category`, `brand`, `itemDiscGroup`, `maximumAuthorizedDiscount`, `showInPos`, `barcode` (the old single field), `familyCode`, `subFamilyCode`, `components` (`[{itemCode, quantity}]`, active ones, sorted), `active` |
| `BARCODE:` (`CatalogueBarcodeCopyDTO`) | `kind`, `barcode`, `itemCode`, `description`, `isPrimary`, `active` = barcode active **and** item active (a scan does not check the item) |
| Never sent | `stockQuantity`, `minStockLevel`, `costPrice`, `lastDirectCost`, `lastDirectNetCost`, `franchiseSalesPrice`, `fromFranchiseAdmin`, `erpExternalId`, `imageUrl` / `imageFilename` (task 6.8) |
| Inactive at the head office | sent with `active=false` |
| Deleted at the head office | answered as removed |

**Store side** (package `holink`):

| Class | Role |
|---|---|
| `CatalogueDownHandler` | The `CATALOGUE` handler: records applied in kind order, each in its own transaction, tracked in `hol_down_record` (`APPLIED`, `WAITING`, `ERROR`), retried every cycle; `prepare()` gives the head office price back when the price right is off |
| `CatalogueCopyWriter` | Writes the copies by code (rules below) |
| `StoreCatalogueGuard` | Every 409 of the store APIs, the own price actions, the status map; one WARN line at startup when `sales_price` rows exist on head office items |
| `CatalogueRights` | The two rights, saved in `hol_link_right` |
| `CatalogueNetworkAPI` | `GET /catalogue/network` |

| Case | Store |
|---|---|
| New code | Saved with `origin=HEAD_OFFICE` |
| Same code as a local record | The head office values replace the store's, price included (`ownPrice` off); `origin` becomes `HEAD_OFFICE` (a query: the column is not updatable through a save). Stock, cost fields, image, ERP and franchise fields kept. Information on the tracking row: `a local item of this store became the head office item` |
| A barcode used by another item here | Moved to the head office item (the head office wins) |
| The old `item.barcode` field of another item holds a head office barcode (a `BARCODE:` record, or the `barcode` field of an `ITEM:`) | Cleared on that item: the till looks there after the barcode table, so a scan finds only the head office item |
| Each moved barcode | One exchange log row `WARNING`, e.g. `CATALOGUE: barcode 619... moved from the store's item L1 and removed from the old barcode field of the store's item L2 to the head office item B001` |
| Family, sub-family, item or pack component missing here | `WAITING`, `not in this store: family F1, sub-family SF1` (or `item I9`, `component items I8, I9`), retried every cycle |
| Item marked as own price | `unitPrice` kept, the head office price saved in `headOfficePrice` |
| Same copy as the store has | Nothing written |
| `active=false`, or removed | Inactive, never deleted; a removed code whose record is local is not touched |
| The store's other local items | Not switched off (unlike promotions and loyalty) |
| Pack | Components as sent (added, deleted, quantities) |

New columns (`ddl-auto`): `origin` `VARCHAR(20)` (`RecordOrigin`, null = local, read-only in JSON, not updatable through a save) on `item`, `item_family`, `item_sub_family`, `item_barcode`; `item.own_price` `bit` (null = false) and `item.head_office_price` `float` (both read-only in JSON; `ItemAPI.update` keeps them from the stored row). A store that owns its catalogue edits every record, whatever the origin.

**Rights** (decision 9): `mayChangePrices` and `canPurchase` come with each heartbeat answer (`HeartbeatJob` → `CatalogueRights.received`). The last values received are saved in `hol_link_right` (`code` unique, `granted`, `received_at`; written only when a value changes) and used after a restart and while the head office is unreachable. Never received: off. An answer without them (older head office) keeps the saved values. The four loyalty switches stay in memory (step 5 rule: unknown = not required).

**Guards** (catalogue owned by the head office; "head office record" = `origin=HEAD_OFFICE`):

| Request | Answer |
|---|---|
| `PUT`, `DELETE /item/{id}`, `PUT /item/{id}/package-flag` on a head office item | 409 `This item is managed by the head office: it is consult-only on this store.` |
| `PUT`, `DELETE /item-family/{id}`, `/item-sub-family/{id}`, `/item-barcode/{id}` on a head office record | 409, same text for a family, sub-family, barcode |
| `POST`, `PUT`, `DELETE /item-composition` whose pack (old or new parent) is a head office item; `POST /item-barcode` or `PUT` moving a barcode to a head office item | 409 (item text) |
| `PUT /item/{id}/own-price` `{"unitPrice": 12.5}` | Right on: 200 the item, `ownPrice=true` (the head office price stays in `headOfficePrice`). Right off: 409 `This store may not change the price of head office items: the head office gives the right on its Stores page.` 400 for an own item or a price missing, not a number or below 0; 404 unknown item |
| `DELETE /item/{id}/own-price` | 200: the head office price back, `ownPrice` off; allowed whatever the right; 400 for an own item |
| Own price endpoints on any other store | 404 `The items of this store are not decided by a head office.` |
| `POST /item/{id}/adjust-stock` | Always allowed, as before |
| Without the purchase right: `POST /item`, `/item/standalone-quick-product`, `/item-family`, `/item-sub-family`, `/item-barcode` | 409 `This store cannot create items, families, sub-families or barcodes: its items come from the head office (purchase right off on the head office Stores page).` |
| Without the purchase right: `POST /purchase-header/process-purchase`, `PATCH /purchase-header/{id}/set-paid`, the generic `POST`, `PUT`, `DELETE /purchase-header`, `POST /admin/purchase-invoices`, `POST`, `PUT`, `DELETE /vendor` | 409 `Purchases are closed on this store: its goods come from the head office (purchase right off on the head office Stores page).` Reads (history, details, lists) stay open |
| With the purchase right: `process-purchase` with a head office item | 409 `Head office items come only from the head office and cannot be purchased here: B001.` |
| With the purchase right: creating with a code used by a head office record | 409 `The code B001 is used by a head office item.` (family, sub-family, barcode alike), instead of the database error |
| `POST /admin/import/execute` FAMILIES, SUBFAMILIES, ITEMS, BARCODES, SALES_PRICES | 409 (plain text, like the other answers of that API) `Items, families, sub-families, barcodes and sales prices come from the head office on this store: they cannot be imported here.`; VENDORS follows the purchase right |
| `POST`, `PUT`, `DELETE /sales-price` | 409 `Selling prices come from the head office on this store: sales prices cannot be written here.` (see `docs/modules/pricing.md`) |
| The store's own items | Sellable and editable whatever the rights |
| `ItemImageController` | Not guarded: images do not travel (task 6.8); a store may put its own picture on a head office item and the pull never overwrites it |

**Item search**: `GET /item/search` (the purchase item picker) gives `origin` for each item: `HEAD_OFFICE`, or null for an own item (on every installation; null where the catalogue is not the head office's).

**Status**: `GET /catalogue/network` (JWT; 404 on any other store) and the `catalogue` block of `GET /admin/holink/status`: `{fromHeadOffice, linkState, mayChangePrices, canPurchase, ownPriceCount, salesPriceRowsOnHeadOfficeItems}`. `GET /admin/holink/received/catalogue` lists the records like any domain. `GET /config` field `catalogueFromHeadOffice` (last).

**Images** (task 6.8, not started): files `uploads/pos-images/{family|subfamily|item}/{database id}.jpg` (JPEG, at most 400×400), the entity holding only `"<id>.jpg"`. Sending them would need an `imageVersion` in the copy, a `GET /ho/catalogue/images/{type}/{code}` and a store step that saves the file under its own id; to decide after L2.

### Price lists (task 6.4)
Head office without an ERP only (`HoPriceListService`, `HoPriceListAPI`, `@ConditionalOnHeadOfficeStandalone`).

| Table (entity) | Content | Key |
|---|---|---|
| `ho_price_list` (`HoPriceList`) | `code` (trimmed, uppercase, at most 50, cannot change), `name`, `active` | Unique `code` |
| `ho_price_list_line` (`HoPriceListLine`) | `price_list_id`, `item_id` (plain ids), `price` (same meaning as `item.unitPrice`, 0 or more) | Unique `uk_ho_price_list_line` (`price_list_id`, `item_id`); index on `item_id` |

Rules:
- A list holds only the items whose price differs from the base price. A store has one list or none (`ho_store.selling_price_list_id`); the price sent to a store is the line of its list for the item, otherwise the base price.
- A line created, changed (another price) or deleted records the item for the stores on that list only (`StoreTargets.of(stores)`); a list used by no store sends nothing. The base price (an item edit) reaches every store.
- Changing a store's list records the items of the old and the new list for that store only.
- A list used by a store cannot be deactivated or deleted (409 `This price list is the selling price list of 2 store(s): choose another list for them on the Stores page first.`); an unused list is deleted with its lines. An inactive list cannot be given to a store.

**API** `/admin/headoffice/price-lists` (JWT; 404 elsewhere; errors `{"error"}`):

| Request | Answer |
|---|---|
| `GET /`, `GET /{id}` | `{id, code, name, active, lineCount, storeCount}`, by code |
| `POST /` `{code, name, active}` | 201; 400 code or name missing, code too long; 409 code exists |
| `PUT /{id}` `{name, active}` | 200; 400 another code; 409 deactivating a list used by a store |
| `DELETE /{id}` | 204; 409 used by a store; 404 |
| `GET /{id}/lines?search=&page=&size=` | Page of `{id, itemId, itemCode, itemName, basePrice, price}` by item code; search on code and name; size 20 by default, at most 200 |
| `PUT /{id}/lines` `[{itemCode, price}]` (or `itemId`) | 200 the lines written; all or none: 400 naming the first unknown item or bad price |
| `DELETE /{id}/lines/{lineId}` | 204; 404 |

Page permission `read:admin-headoffice-price-lists` (seeded on a head office ADMIN, 25 head office permissions; the page comes with the frontend session, task 6.7).

### Head office as a warehouse (task 7A.1)
A head office **without an ERP** buys from its suppliers and keeps its own stock with the store's code and pages (design 3.5); it still has no till. Nothing in the purchase or stock code changes for it:
- `isStandalone()` means "no ERP". A head office without an ERP has `application.standalone=true`, so the 17 checks of `PurchaseHeaderAPI`, `VendorAPI`, `PurchaseInvoiceAPI`, `StockService` and `StockMovementService` let it purchase, keep vendors and purchase invoices, raise its stock and write `stock_movement` rows, exactly as a standalone store. `StoreCatalogueGuard` (the purchase right) does not exist on a head office.
- A head office **with an ERP** (`standalone=false`) answers as before: purchases, vendors and purchase invoices 403, stock not kept. Its frontend pages are `standaloneOnly`.
- **No CATALOGUE change from stock or cost** (checked in step 7A): a purchase writes the item's last costs with `itemRepository.save` (no catalogue hook), the stock moves with the native updates of `ItemRepository` (no hook), a stock adjustment goes through `ItemService.adjustStock` (no item save) and a BL validation through `StockService` (task 7A.2). None records a change, so no store pulls the item again; the item copy carries no stock and no cost anyway. Only a save through `ItemService.save` records one: an edit on the Items page (whatever field changed, cost and minimum stock included) and an image upload (`ItemImageController`); the stores then pull that one item and write nothing (`unchanged`). Proved by `HeadOfficeWarehouseTest`.
- The Items page sends the whole item, `stockQuantity` included (shown read-only there). Since step 7A `ItemAPI.update` keeps the stored stock, as it keeps the last costs: an edit made while the stock moves no longer writes back the quantity the page loaded (head office and store alike, `ItemStockAdjustmentTest.editKeepsStoredStock`).

Page permissions, one per head office route (seeded on a head office ADMIN at each start; frontend routes `standaloneOnly`, twins of the store pages): `read:admin-headoffice-vendors`, `-purchases` (history), `-purchase-new`, `-vendor-balance`, `-purchase-invoices`, `-stock` (stock report), `-stock-movements` (32 head office permissions).

### BLs (task 7A.2, head office)
A BL (delivery note) sends goods from the head office stock to one store (design 3.5, decision D12). Head office without an ERP only (`HoDeliveryService`, `HoDeliveryAPI`, `@ConditionalOnHeadOfficeStandalone`); elsewhere the API answers 404 and the domain `SUPPLY` is not served. No price on a BL: step 7B invoices the received BLs.

| Table (entity) | Content |
|---|---|
| `ho_delivery` (`HoDelivery`) | `delivery_number` (`BL-000001`, null while `DRAFT`; index `ix_ho_delivery_number`, no unique constraint because SQL Server allows one null only in a unique column: the sequence makes the numbers unique), `store_id` (`ho_store.id`), `status` (`DRAFT`, `SENT`, `RECEIVED`, `INVOICED`; index with the store), `document_date`, `sent_at`, `sent_by`, `received_at` (store clock), `received_by` (store login), `confirmation_received_at` (head office clock), `note` (500), `store_note` (500) |
| `ho_delivery_line` (`HoDeliveryLine`) | `delivery_id`, `line_no` (unique with the BL), `item_id` (head office item), `item_code`, `item_name` (as when the line was written), `quantity_sent`, `quantity_received` (null until received) |
| `ho_number_sequence` (`HoNumberSequence`) | `code` (unique; `BL`), `last_value`: incremented inside the validation's transaction (the row stays locked until the commit; a rollback gives the number back). The row is created at the start (`HoDeliveryService.initialise`) |

**Status rules**

| Status | Set by | Allowed |
|---|---|---|
| `DRAFT` | `POST` | Edit (`PUT`: store, date, note, lines replaced), delete, validate. No number, no stock moved, not visible to any store |
| `SENT` | `POST /{id}/validate` | Nothing at the head office; the store receives it (task 7A.3). Cancelling a sent BL is not in 7A (plan, section 5): the store confirms 0 and the head office corrects its stock |
| `RECEIVED` | the store's confirmation (task 7A.4) | — |
| `INVOICED` | step 7B | never set in 7A |

**Validation**, all or nothing in one transaction: the BL row is locked (`findForUpdate`, SQL Server `UPDLOCK`: a second validation waits and then gets 409); the store is checked again; each line's item must still be deliverable; unless `ALLOW_NEGATIVE_STOCK=true` (general setup of the head office) the stock of every line must be sufficient, otherwise 409 listing each short item (`Stock not sufficient at the head office: B001: 20 in stock, 50 on the BL; B003: 0 in stock, 1 on the BL.`) and nothing changes. Then: the number, `SENT`, `sent_at` and `sent_by`; each line leaves the stock with `StockService.decrementForDelivery` (atomic; a stock taken meanwhile by another validation is caught there and everything rolls back) and one `DELIVERY_OUT` movement (`reference_type` `BL`, `reference_id` the BL, the number as note); the BL is recorded for its store only: `CopiesDownFeed.recordChange(SUPPLY, "BL:<number>", StoreTargets.of(store))`. No item is saved: no CATALOGUE change (`HoDeliveryServiceTest.noCatalogueChange`).

**Lines**: an item by `itemId` or `itemCode`, active, of type `PRODUCT` or `PACKAGE` (a pack leaves as one item, as at the till), never `TAX_STAMP`, once per BL; quantity a whole number above 0. A BL holds head office items only: a store's own items never exist at the head office.

**Store of a BL**: known and active (400); when the store reported `ownership.supply` (heartbeat, `ho_store.owner_supply`) and it is not `HEAD_OFFICE`: 409 `The store C does not receive goods from the head office (ownership.supply=LOCAL in its settings).` A store that has not reported yet is accepted.

**Copy down** (`DeliveryCopyDTO`, record `BL:<number>`, domain `SUPPLY`): `number`, `documentDate` (`yyyy-MM-dd`), `sentAt` (`yyyy-MM-ddTHH:mm:ss`, head office clock), `status`, `note`, `lines` `[{lineNo, itemCode, itemName, quantitySent}]`; never a database id. `load` answers only the numbered BLs of the pulling store (any other code is removed), so a BL for C is never seen by B. Startup backfill: every numbered BL for its store.

**API** `/admin/headoffice/deliveries` (JWT; errors `{"error"}`):

| Request | Answer |
|---|---|
| `GET /?storeId=&status=&search=&dateFrom=&dateTo=&difference=&page=&size=` | `{content, totalElements, totalPages, number, size}`, newest first. `status` one status or `all`; `search` on the number and the note; dates `yyyy-MM-dd` on the document date; `difference=true`: BLs with a line received in another quantity; size 20 by default, at most 200. Row: `{id, number, storeId, storeCode, storeName, status, documentDate, sentAt, sentBy, receivedAt, receivedBy, confirmationReceivedAt, note, storeNote, lineCount, quantitySent, quantityReceived, difference, lines: null}`. 400 for a status, date or page that cannot be read |
| `GET /{id}` | The same with `lines` `[{lineNo, itemId, itemCode, itemName, quantitySent, quantityReceived, difference, headOfficeStock}]` (`difference` = received − sent); 404 |
| `POST /` `{storeId, documentDate, note, lines: [{itemCode or itemId, quantity}]}` | 201 the draft. 400 with the line: `Choose the store of the BL.`, `Unknown store id 9.`, `The store C is inactive.`, `A BL needs at least one line.`, `Line 2: unknown item B999.`, `Line 1: the item OFF is inactive.`, `Line 1: the item S1 is a service: it has no stock.`, `Line 1: the tax stamp cannot be delivered.`, `Line 1: the quantity must be a whole number above 0.`, `Line 3: the item B001 is already on line 1.`; 409 for a store whose goods do not come from the head office |
| `PUT /{id}` | 200 the draft replaced; 409 `Only a draft BL can be changed: BL-000001 is SENT.`; 404 |
| `DELETE /{id}` | 204; 409 when not a draft; 404 |
| `POST /{id}/validate` | 200 the BL `SENT`; 409 not a draft, store, stock; 400 an item no longer deliverable; 404 |

Page permission `read:admin-headoffice-deliveries` (33 head office permissions).

### BLs at the store (tasks 7A.3, 7A.4)
A store whose goods come from the head office receives its BLs by the copies down, confirms what it counted, and its stock goes up at once; the confirmation travels up as a document (design 2.4, 3.5).

**When it is active**: `@ConditionalOnHeadOfficeSupply` (`NodeOwnership.isSupplyFromHeadOffice`): `headoffice.url` set, an **explicit** `ownership.supply=HEAD_OFFICE`, `application.standalone=true`, no franchise flag. Startup refusals of an explicit `ownership.supply=HEAD_OFFICE`: without the URL (`SUPPLY` is now in `COPIES_DOWN_DOMAINS`), with `franchise.customer=true` or `franchise.admin=true`, with `application.standalone=false`, and without `ownership.catalogue=HEAD_OFFICE` (`Invalid combination: ownership.supply=HEAD_OFFICE without ownership.catalogue=HEAD_OFFICE. A BL names head office items by their code ...`). Beans: `DeliveryReceptionService`, `SupplyDownHandler`, `SupplyPushService`, `SupplyPushJob`, `DeliveryReceptionAPI`.
- **A franchise customer never gets them**, even with `headoffice.url`: it derives `SUPPLY=HEAD_OFFICE` for its legacy reception, but the condition requires an explicit value and no franchise flag, and an explicit value with the flag stops the startup. `FranchiseSupplyReceptionService` (`@ConditionalOnProperty franchise.customer`), `/franchise/**` and `FranchiseClientSyncController` are not touched.
- Every other store (no setting, ERP, `ownership.supply=LOCAL`): none of the beans; `/admin/deliveries` answers 404; `/config` `supplyFromHeadOffice` false; no `SUPPLY_PUSH` job.

**Tables** (store, prefix `hol_`, `ddl-auto`):

| Table (entity) | Content |
|---|---|
| `hol_delivery` (`ReceivedDelivery`) | `delivery_number` (unique), `document_date`, `sent_at`, `note` (the head office note), `status` (`TO_RECEIVE`, `RECEIVED`), `received_at` (store clock), `received_by` (login), `store_note`, and the confirmation up: `push_status` (null until confirmed, then `PENDING`, `SENT`, `ERROR`), `attempts`, `last_error` (1000), `last_push_date`; index `ix_hol_delivery_push` |
| `hol_delivery_line` (`ReceivedDeliveryLine`) | `delivery_id`, `line_no` (unique with the BL), `item_code`, `item_name`, `item_id` (this store's item; null while it is not here), `quantity_sent`, `quantity_received` (null until confirmed), `stock_applied` (null until confirmed; false while the item is missing) |

**Received** (`SupplyDownHandler`, domain `SUPPLY`, after the catalogue in the same cycle): a new BL number is saved `TO_RECEIVE`, each line resolved to this store's item with that code **and origin `HEAD_OFFICE`** (a store's own item with the same code is never used); tracked in `hol_down_record` (`APPLIED`; information `items not in this store yet: B009`). A number already here is never changed (a BL does not change once sent; a received one belongs to the store's count). A removed code only drops its tracking row: a BL is a document, never deleted.

**Reception** (`DeliveryReceptionService.receive`, one transaction, the row locked): the store confirms the quantity of each line; a line absent from the body, or without a quantity, is received as sent; a quantity above the quantity sent is accepted (the store counted it; the frontend asks to confirm). Each line whose item is here and whose quantity is above 0 goes into the stock (`StockService.incrementForDelivery`) with one `DELIVERY_IN` movement (`reference_type` `BL`, `reference_id` the `hol_delivery` row, the number as note), `stock_applied` true; a line of 0 needs nothing. A line whose item is missing keeps its quantity and waits (`stock_applied` false). The BL becomes `RECEIVED`, `push_status` `PENDING`. Nothing calls the head office: it works offline. A second confirmation: 409 `This BL has already been received: BL-000001.`, nothing changes.

**Waiting lines** (`SupplyDownHandler.retry`, every cycle, also when the head office is unreachable): the lines of the BLs to receive get their item when it has arrived; the confirmed lines that wait get their stock once their item is here (each BL in its own transaction, locked). A BL whose stock still waits counts as waiting: the `COPIES_DOWN` run is `WARNING` with `BL:BL-000001: stock in waits: not in this store: item B009`.

**Confirmation up** (`SupplyPushService`, job `SUPPLY_PUSH`, order 5, first run 35 s, then `headoffice.supply-push.interval-seconds`, 60, editable on the link page): `POST /ho/supply/confirmations` with `[{number, receivedAt, receivedBy, note, lines: [{lineNo, itemCode, quantityReceived}]}]` (`DeliveryConfirmationDTO`), batches of 20, at most 2 per cycle (the first retries the rejected ones). It does not wait for the waiting lines: the quantities are facts of the count. Accepted: `SENT`, never sent again. Rejected: `ERROR` with the reason, retried. Not delivered (unreachable, 401, 402, unreadable answer): nothing changes, the cycle stops, one exchange log row per failure episode (`LinkExchangeLog.recordFailure`). One exchange row per batch.

**Head office receiver** (`HeadOfficeSupplyAPI`, `/ho/**` chain, head office without an ERP; `HoDeliveryService.receiveConfirmations`, one transaction per BL, the row locked, one result per BL in batch order `{documentNumber, accepted, message}`):

| Case | Answer |
|---|---|
| A `SENT` BL of this store, every line once, item codes matching, quantities 0 or more | Accepted: `RECEIVED`, `quantity_received` per line, `received_at`, `received_by`, `store_note`, `confirmation_received_at` (head office clock). The head office stock is not changed (the goods left at the validation): a difference is shown, not booked |
| The same BL again with the same quantities (an answer lost) | Accepted, nothing changes |
| Already received with other quantities | Rejected `already received with other quantities` |
| Unknown number, another store's BL, a draft | Rejected `unknown BL BL-000001 for this store` |
| No number; a line not on the BL or with another item code; a quantity missing or below 0; a line twice; a line missing | Rejected (`number is required`, `line 3 does not match the BL`, `line 1: quantityReceived must be 0 or more`, `line 1 is given twice`, `every line of the BL is required`) |

Log (head office): one INFO line per batch, one WARN line per rejected confirmation.

**API** `/admin/deliveries` (store, JWT; 404 elsewhere; errors `{"error"}`):

| Request | Answer |
|---|---|
| `GET /?status=&page=&size=` | `{content, totalElements, totalPages, number, size}`, newest first; `status` `TO_RECEIVE`, `RECEIVED` or `all` (400 otherwise). Row: `{id, number, documentDate, sentAt, note, status, receivedAt, receivedBy, storeNote, pushStatus, lastError, lineCount, quantitySent, quantityReceived, difference, missingItems, stockWaiting, lines: null}` |
| `GET /{id}` | The same with `lines` `[{lineNo, itemCode, itemName, itemHere, quantitySent, quantityReceived, difference, stockApplied, storeStock}]`; 404 |
| `POST /{id}/receive` `{note, lines: [{lineNo, quantityReceived}]}` | 200 the BL `RECEIVED` (`storeStock` null in this answer: `GET /{id}` reads the new stock); 409 already received; 400 `Unknown line 9 on BL-000001.`, `Line 1 is given twice.`, `Line 1: the quantity received must be a whole number, 0 or more.`, a note over 500; 404 |

**Link page**: `GET /admin/holink/status` field `supply` (see "Head office link page"); `GET /admin/holink/received/supply` lists the BLs received like any domain; the job `SUPPLY_PUSH` in the jobs list and the exchange log.

**Permission** `read:admin-holink-deliveries` (the reception page): ADMIN gets it when the role is created on such a store, and **at every start** when it lacks it (`ZZDataInitializer.addMissingPermissions`, the same top-up as a head office's pages; decision 13): an existing store switched to `ownership.supply=HEAD_OFFICE` needs no manual tick. Only that permission is added; nothing is removed; other roles are not changed.

### Stock of the stores (task 7A.5)
Every store whose goods come from the head office copies its stock up; the head office shows it per store and item, next to its own stock (design 3.5, 3.9). Only those stores (decision 10): a linked store without `ownership.supply=HEAD_OFFICE` sends no stock, as before.

**Store** (`SupplyPushService`, the same job `SUPPLY_PUSH`, after the confirmations): table `hol_stock_copy` (`StockCopy`: `item_id` unique, `item_code`, `quantity_sent`, `sent_at`), the quantity the head office accepted last. Each run sends, in batches of 500 (at most 2 per run), the items of type `PRODUCT`, `PACKAGE` or none, except `TAX_STAMP`, whose `coalesce(stock_quantity, 0)` differs from `quantity_sent` or without a row (`StockCopyRepository.findToSend`), and the codes of the rows whose item no longer exists (`findRemoved`): `POST /ho/supply/stock` `{takenAt, items: [{itemCode, itemName, quantity, own}], removed: [codes]}` (`StockReportDTO`; `own` true for an item whose origin is not `HEAD_OFFICE`). Delivered: `quantity_sent` is the quantity sent (a change made after the read goes with a later batch), removed rows deleted, one exchange row `SUCCESS`. Not delivered: nothing changes (one failure row per episode). Nothing changed: nothing sent, no row. Nothing in the selling path reads or writes `hol_stock_copy`: the change is found by a query.

**Head office** (`HoNetworkStockService`, head office without an ERP): `POST /ho/supply/stock` saves the batch in one transaction: table `ho_store_stock` (`HoStoreStock`: `store_id`, `item_code` unique together, `item_name`, `quantity`, `own_item`, `store_time` (store clock of the read), `received_at`); a code without a value or over 100 characters is skipped; removed codes are deleted. Answer `{saved, removed}`; 400 `{"error"}` for a `takenAt` that cannot be read.

**API** `/admin/headoffice/stock` (JWT; 404 elsewhere):

| Request | Answer |
|---|---|
| `GET /?storeId=&search=&page=&size=` | `{stores: [{id, code, name, lastStockAt}], content: [{itemCode, itemName, headOffice, byStore: {"<storeId>": quantity}}], totalElements, totalPages, number, size}`: the head office items (products and packs, not `TAX_STAMP`) by code, search on code and name, with the head office stock and each store's; `storeId` one store, absent: every active store by code. A store that never sent an item has no entry for it. 400 for an unknown store or a page below 0 |
| `GET /own?storeId=&search=&page=&size=` | The stores' own items (not from the head office): `{content: [{storeId, storeCode, storeName, itemCode, itemName, quantity, storeTime, receivedAt}], ...}` by store and code |
| `belowZero=true` on both (optional, filtered in the query) | `GET /`: only the items whose stock is below zero in a column shown (the head office, or the store `storeId`, or any active store); `GET /own`: only the own items below zero. Absent or false: as above |

`GET /admin/holink/status` field `supply` gets two keys at the store: `stockToSend` (items whose stock waits to be sent) and `stockSentAt` (last accepted push, null before). Page permission `read:admin-headoffice-network-stock` (34 head office permissions).


### Supply prices and invoicing settings (step 7B, part 1)
What a store whose deliveries are invoiced pays for the goods (design 3.6). Head office without an ERP only. Nothing here is ever sent to a store: the supply price is used only when an invoice is created (part 2).

**Settings on the store row** (`ho_store`, set with the generic `POST` / `PUT /admin/headoffice/stores`, each field applied when sent, kept when absent; never sent with the heartbeat):

| Column | Rule |
|---|---|
| `deliveries_invoiced` bit | The store's received BLs are invoiced; null read as false |
| `billing_legal_name` (200), `billing_tax_number` (50), `billing_address` (500) | Trimmed; blank clears; longer: 400 `The billing legal name is longer than 200 characters.` Copied onto each invoice |
| `supply_price_mode` | `PRICE_LIST` (null reads as it) or `PERCENT_OFF` |
| `supply_price_list_id` | A price list of kind `SUPPLY`; null = the base supply price. Set at creation or with `PUT /admin/headoffice/stores/{id}/supply-price-list` `{"priceListId": 4}` (or null); the generic `PUT` ignores it. 400 for an unknown, inactive or selling list |
| `supply_discount_percent` | `PERCENT_OFF` mode, from 0 to 100 (400 `The supply discount must be from 0 to 100 %.`) |
| `invoice_rhythm` | `PER_BL` (null reads as it): one invoice per BL, created when the confirmation arrives; `GROUPED`: by hand |

**Price list kinds**: `ho_price_list.kind` `SELLING` (null = every list made before) or `SUPPLY`, given at creation (`POST` `{"kind": "SUPPLY"}`, default `SELLING`), cannot change (400). `GET /admin/headoffice/price-lists?kind=SUPPLY` filters (400 for another value); `kind` is in every answer and `storeCount` counts the stores using the list as their list of its kind. A selling list given as a supply list, or the reverse: 400 `The price list FRANCHISE is a supply price list, not a selling price list.` A list used by a store as its supply list cannot be deactivated or deleted (409 `This price list is the supply price list of 1 store(s): ...`). A supply list line records nothing on the copies down (no store has it as its selling list). The lines of a supply list (`GET /{id}/lines`, and the answer of `PUT /{id}/lines`) give the base supply price as `basePrice` (null when none); a selling list still gives the selling price.

**Base supply price**: table `ho_item_supply_price` (`item_id` unique, `price` before VAT), kept apart from `item` (the item form sends the whole item, and the item copy must never carry it). `HoSupplyPriceAPI` `/admin/headoffice/supply-prices`:

| Request | Answer |
|---|---|
| `GET /?search=&page=&size=` | `{content: [{itemId, itemCode, itemName, sellingPrice, supplyPrice}], totalElements, totalPages, number, size}`: products and packs (not `TAX_STAMP`), by code; `supplyPrice` null when none |
| `PUT /` `[{itemCode or itemId, supplyPrice}]` | All or none; `supplyPrice: null` deletes; 400 for an unknown item or a price below 0 |

**The supply price of a store** (`HoSupplyPriceService.pricesFor`, before VAT, to the millime half up):
- `PRICE_LIST`: the line of the store's supply list for the item, else the base supply price, else none (the invoice then refuses the item, part 2).
- `PERCENT_OFF`: the selling price the head office works out for that store (its selling list line, else `item.unitPrice`; never a price the store set itself) × (1 − percent / 100). No percentage set: 409.

Page permission `read:admin-headoffice-supply-prices` (35 head office permissions).


### Supply invoices (step 7B, part 2)
The head office invoices the received BLs of a store whose deliveries are invoiced (design 3.6, decision D12). `HoSupplyInvoiceService`, `HoSupplyInvoiceAPI`, head office without an ERP only.

| Table (entity) | Content |
|---|---|
| `ho_supply_invoice` (`HoSupplyInvoice`) | `invoice_number` (unique, `FHO-yyyy-000001`), `store_id`, `invoice_date`, `subtotal` (before VAT), `tax_amount`, `total_amount` (with VAT and the stamp), buyer snapshot (`buyer_name`: the billing legal name, else the store name; `buyer_tax_number`, `buyer_address`), seller snapshot (`seller_name`, `seller_tax_number`, `seller_address` from the head office `CompanyInformation`: company name, matricule fiscal, address, postal code, city, country), `note`, `paid` (false at creation), `paid_date`, `paid_note`, `created_by` (`AUTO` for a per-BL invoice) |
| `ho_supply_invoice_line` (`HoSupplyInvoiceLine`) | `invoice_id`, `line_no`, `delivery_number` (null for the stamp), `item_id`, `item_code`, `item_name`, `quantity` (confirmed), `unit_price` (supply price before VAT), `vat_percent` (the item's `defaultVAT`, 0 when none), `vat_amount`, `line_total`, `line_total_including_vat`; one line per BL line received above 0 |
| `ho_delivery` + `invoice_id`, `invoice_note` | The invoice of the BL; why its automatic invoice was not created |
| `ho_number_sequence` | Code `FHO-<year>`: one sequence per year of the invoice date |

**Creating** (one transaction, the BL rows locked): every BL `RECEIVED`, of that store, not invoiced; the store's deliveries invoiced. Amounts to the millime: line total = quantity × supply price; VAT = line total × rate; header sums. Supply prices of the invoice date (`HoSupplyPriceService`; price frozen at BL validation: plan, section 5). **Tax stamp**: head office setting `SUPPLY_INVOICE_TAX_STAMP` (seeded `false` on a head office only); when `true`, one last line `TAX_STAMP`, quantity 1, `SUPPLY_INVOICE_TAX_STAMP_MILLIMES` / 1000 (its own head office setting, seeded 1000; the till ticket's `TAX_STAMP_VALUE_MILLIMES` is not read), VAT 0, no BL. **Nothing received**: when every chosen BL was received at 0, 409 `Nothing was received on these BLs: nothing to invoice.` (never an invoice of the stamp alone). **Invoice date**: 400 when in the future (`The invoice date cannot be in the future.`) or before the date of the last invoice issued (`The invoice date cannot be before the date of the last invoice (FHO-2026-000002 of 2026-10-06).`): the numbers follow the dates. The preview applies both rules. Then: the number, the BLs `INVOICED` with `invoice_id` (`invoice_note` cleared), and the record `INV:<number>` of the domain `SUPPLY` for that store only (`HoDeliveryService.load` and `currentTargets` hand the `INV:` codes to the invoice service; a store never receives another store's invoice or supply price).

**Rhythm `PER_BL`**: `HoDeliveryService.receiveConfirmations` calls `afterReceived` for each BL it set `RECEIVED` (not for a confirmation sent again), after that BL's transaction: the invoice is created in its own transaction with today's date. When it fails (no supply price, no percentage) the confirmation is still accepted, the BL stays `RECEIVED` and keeps the reason in `invoice_note` (e.g. `No supply price for the store B: B002.`); it is invoiced by hand once fixed. A store whose deliveries are not invoiced, or `GROUPED`, or a BL received at 0 on every line: nothing happens (no invoice, no `invoice_note`).

**Not in 7B**: cancelling an invoice (a credit note, later), partial payments.

**API** `/admin/headoffice/supply-invoices` (JWT; errors `{"error"}`):

| Request | Answer |
|---|---|
| `GET /?storeId=&paid=&dateFrom=&dateTo=&page=&size=` | `{content, totalElements, totalPages, number, size}`, newest first. Row: `{id, invoiceNumber, storeId, storeCode, storeName, invoiceDate, subtotal, taxAmount, totalAmount, buyer..., seller..., note, paid, paidDate, paidNote, deliveryNumbers, lines: null}` |
| `GET /{id}` | The same with `lines` `[{lineNo, deliveryNumber, itemCode, itemName, quantity, unitPrice, vatPercent, vatAmount, lineTotal, lineTotalIncludingVat}]`; 404 |
| `GET /to-invoice?storeId=` | `[{id, number, documentDate, receivedAt, quantityReceived, invoiceNote}]`: the store's `RECEIVED` BLs not invoiced, oldest first, without those received at 0 on every line; 400 unknown store |
| `POST /preview` `{storeId, deliveryIds, invoiceDate}` | The invoice it would be (no id, no number), `missingPrices` listing the items without a supply price; those items are also in `lines`, in their place by BL: item code, item name, BL number, quantity, `missingPrice: true`, no line number, price or amounts (the flag is absent from priced lines and from the copies sent to the stores); totals count only the priced lines; writes nothing; same 400 / 409 on the BLs |
| `POST /` `{storeId, deliveryIds, invoiceDate, note}` | 201. 400: `Choose the store of the invoice.`, `Choose at least one received BL.`, `Unknown BL id 9.`, a date that cannot be read. 409: `The deliveries of the store C are not invoiced (setting on the Stores page).`, `BL-000004 is SENT: only received BLs can be invoiced.`, `BL-000003 is not a BL of the store B.`, `BL-000001 is already invoiced.`, `No supply price for the store B: B002, B009.`, a percentage missing |
| `PATCH /{id}/paid` `{paid, paidDate, note}` | Paid (date today when absent) or unpaid (date cleared); 400 without `paid`; 404 |
| `GET /balances` | What each store owes: `[{storeId, storeCode, storeName, invoiceCount, total, paid, unpaid, unpaidCount}]`, stores with an invoice, by code (amounts with VAT) |

`GET /admin/headoffice/deliveries/{id}` (and the list) gives `invoiceId`, `invoiceNumber`, `invoiceNote`.

Page permissions `read:admin-headoffice-supply-invoices`, `read:admin-headoffice-store-balances` (37 head office permissions).


### Supply invoices at the store (step 7B, part 3)
On a store whose goods come from the head office (the 7A supply beans), an invoice of the head office arrives by the copies down (record `INV:<number>`, kind `INVOICE`; a record without `kind` is a BL) and becomes a purchase invoice, consult-only, once. No store setting: an invoice arrives only when the head office invoices that store.

| Part | Rule |
|---|---|
| `SupplyDownHandler` | Dispatches on `kind`; `INVOICE` goes to `SupplyInvoiceWriter` in its own transaction, tracked in `hol_down_record` (`APPLIED`, information `items not in this store: B009`; `ERROR` retried every cycle) |
| `SupplyInvoiceWriter` | Saved once by `invoice_number` (`PurchaseInvoiceHeaderRepository.findByInvoiceNumber`); the same number again writes nothing. Header: vendor `HEAD_OFFICE`, `NO_GROUPING`, totals copied (never recomputed), notes `Head office invoice - BL BL-000001, BL-000002 - <note>`, seller snapshot into `snapshotVendor*`, `origin` `HEAD_OFFICE`, written by `HEAD_OFFICE`. Lines: one per invoice line, the item with that code and origin `HEAD_OFFICE` (the stamp: the store's `TAX_STAMP` item); an item not here: no item, description `BL-000001 - B009 <name>`. No `purchase_header` is created (the BL moved the stock), so the store can never invoice it again |
| Vendor `HEAD_OFFICE` | Created at the first invoice: the seller's name ("Head office" when none), phone `N/A`, address and tax number. `SupplyVendorGuard` (in `VendorAPI` through an `ObjectProvider`, absent elsewhere): create with that code (any case), update or delete it, or give that code to another vendor: 409 `The head office vendor is consult-only: it holds the invoices of the head office.` / `The vendor code HEAD_OFFICE is kept for the head office's invoices.` |
| Cost | For each line naming a head office item: `lastDirectCost`, `lastDirectNetCost` and `costPrice` = the supply price before VAT (`itemRepository.save`, no stock change). The store's own items and the tax stamp are never touched |
| `purchase_invoice_header.origin` (new, null = the store's own) | `HEAD_OFFICE` for a received invoice. The purchase invoice list already gives the vendor code (badge on `HEAD_OFFICE`); `PurchaseInvoiceService` is not changed. The purchase invoice API has no edit or delete, so a received invoice is consult-only by construction |
| `hol_delivery.invoice_number` (new) | Set on each BL of the invoice; `GET /admin/deliveries` and `/{id}` give `invoiceNumber` |

### Consolidated sales API (task 2.5, head office)
What the head office pages Tickets history, Sessions history and Returns, and the home cards, read (pages: task 2.5 frontend, "Head office pages" below).

**Common rules**
- `ConsolidatedSalesAPI` (`headoffice/controller`) and `ConsolidatedSalesService` (`headoffice/service`), both `@ConditionalOnHeadOffice`: on a store these URLs answer 404. JWT like the other admin APIs; the license filter applies (402). As everywhere in the application, the API checks no permission: the pages are protected by their route permission in the frontend.
- They read the consolidation tables only (`ho_ticket`, `ho_return`, `ho_session` and their lines), never the store tables.
- Field names follow the store's own pages (`GET /sales-header/history`, `/cashier-session/history`, `/return-header/history` and their details) where the data is the same, so the new pages can follow the store pages. References are small objects with the store's property names (`customer.name`, `createdByUser.fullName`, `cashierSession.sessionNumber`, `item.itemCode`, `paymentMethod.name`). Every row adds `storeId`, `storeCode`, `storeName`, `receivedAt` (first reception) and `lastReceivedAt` (last reception), head office clock.
- Not there, compared with the store pages: ERP fields (`synchronizationStatus`, `erpNo`, `synched`, `fiscalRegistration`), the store's database ids (`cashierId`, user and item ids), voucher use (`usedAmount`, voucher status), the session `paymentSummary` (the count lines are given instead).
- Dates are ISO strings (`2026-10-03T10:15:30`); `id` is the head office row id.

**Lists**: `GET /admin/headoffice/tickets`, `GET /admin/headoffice/sessions`, `GET /admin/headoffice/returns`.

| Parameter | Tickets | Sessions | Returns | Rule |
|---|---|---|---|---|
| `page` | yes | yes | yes | From 0, default 0; below 0: 400 |
| `size` | yes | yes | yes | Default 10; from 1 to 200 (0 or less gives 10, more than 200 gives 200) |
| `storeId` | yes | yes | yes | Head office store id (from `store-options`); absent: every store |
| `dateFrom`, `dateTo` | `salesDate` | `openedAt` | `returnDate` | `yyyy-MM-dd` (from: 00:00, to: end of the day) or `yyyy-MM-ddTHH:mm[:ss]` (exact, inclusive). Anything else: 400 `{"error":"Invalid dateFrom '<value>': expected yyyy-MM-dd or yyyy-MM-ddTHH:mm"}` |
| number: parameter `salesNumber`, `sessionNumber`, `returnNumber` | `salesNumber` | `sessionNumber` | `returnNumber` | Contains, any case |
| `status` | `TransactionStatus` | `SessionStatus` | `TransactionStatus` | Name, any case; blank or `all`: any |
| `sessionNumber` | yes (exact) | — (it is the number filter) | yes (exact) | With `storeId`, the "View tickets / View returns" links of a session, as on the store pages (`?session=`) |

Order: newest first (`salesDate`, `openedAt`, `returnDate` descending, then id). Answer: `{"content":[rows], "totalElements":n, "totalPages":n, "number":page, "size":size}`, the shape of the store history endpoints.

**Ticket row** (`content[]` of tickets): `id`, `storeId`, `storeCode`, `storeName`, `salesNumber`, `salesDate`, `subtotal`, `taxAmount`, `discountAmount`, `discountPercentage`, `totalAmount`, `paidAmount`, `changeAmount`, `status`, `notes`, `invoiced`, `invoiceNumber`, `customer` (`{customerCode, name}`, null for a walk-in ticket), `createdByUser` (`{username, fullName}`; `fullName` is the login when the store sent no name), `cashierSession` (`{sessionNumber}` or null), `receivedAt`, `lastReceivedAt`, `salesLinesCount`, `paymentsCount`.

**`GET /admin/headoffice/tickets/{id}`**: the row without the two counts, plus `completedDate`, `discountSource`, `promotion` (`{code, name}` or null), `loyaltyMember` (`{cardNumber, name}` or null), `loyaltyPointsEarned`, `loyaltyPointsRedeemed`, `loyaltyDeductionAmount`, `tableNumber`, and
- `salesLines[]`: `lineNo`, `item` (`{itemCode, name}`), `quantity`, `unitPrice`, `discountPercentage`, `discountAmount`, `discountSource`, `promotion` (`{code}` or null), `vatPercent`, `vatAmount`, `unitPriceIncludingVat`, `lineTotal`, `lineTotalIncludingVat`;
- `payments[]`: `lineNo`, `paymentMethod` (`{code, name}`), `totalAmount`, `paymentDate`, `titleNumber`, `dueDate`.

404 `{"error":"Not found"}` for an unknown id (the same for sessions and returns).

**Session row**: `id`, `storeId`, `storeCode`, `storeName`, `sessionNumber`, `cashierFullName` (the login when there is no name), `cashierUsername`, `openedAt`, `closedAt`, `status`, `openingCash`, `realCash`, `posUserClosureCash`, `responsibleClosureCash`, `salesCount`, `totalSalesAmount`, `returnsCount`, `totalReturnsAmount`, `simpleReturnsAmount`, `voucherReturnsAmount`, `cashDifference`, `responsibleDifference`, `verificationNotes`, `verifiedByName`, `verifiedAt`, `receivedAt`, `lastReceivedAt`.
- The totals come from the copies of the same store with that session number: finished tickets (`COMPLETED`, `REFUNDED`) and completed returns (simple or voucher by `returnType`). They are complete once the session's tickets and returns have arrived.
- `cashDifference` = `posUserClosureCash` − (`openingCash` + `totalSalesAmount` − `simpleReturnsAmount`), `responsibleDifference` the same with `responsibleClosureCash`, rounded to 2 decimals, null when that count is empty: the store page's formula.

**`GET /admin/headoffice/sessions/{id}`**: `{session: <row>, salesCount, totalSalesAmount, returnsCount, totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, counts: [...]}`, `counts[]`: `lineNo`, `counterType` (`POS_USER`, `RESPONSIBLE`), `paymentMethod` (`{code, name}`, null for cash), `denominationValue`, `quantity`, `lineTotal`, `referenceNumber`.

**Return row**: `id`, `storeId`, `storeCode`, `storeName`, `returnNumber`, `returnDate`, `returnType`, `totalReturnAmount`, `notes`, `status`, `discountPercentage`, `originalSalesHeader` (`{id, salesNumber, salesDate, totalAmount}`: the head office ticket of the same store with that number; `id`, `salesDate`, `totalAmount` null when that ticket has not arrived), `returnVoucher` (`{voucherNumber, voucherAmount, expiryDate}` or null), `createdByUser` (`{username, fullName}` or null), `cashierSession` (`{sessionNumber}` or null), `receivedAt`, `lastReceivedAt`.

**`GET /admin/headoffice/returns/{id}`**: `{returnHeader: <row>, returnLines: [...]}`, `returnLines[]`: `lineNo`, `item` (`{itemCode, name}`), `quantity`, `unitPrice`, `unitPriceIncludingVat`, `lineTotal`, `lineTotalIncludingVat`, `notes`.

**`GET /admin/headoffice/store-options`**: `[{id, code, name, active}]` by code, for the store filter of the three pages (the Stores page's `GET /admin/headoffice/stores` belongs to that page and carries more).

**`GET /admin/headoffice/dashboard/today`** (home cards): the fields of the store's `GET admin/dashboard/today`, all stores together, today on the head office clock.

| Field | Head office value |
|---|---|
| `todaySalesCount`, `todaySalesAmount` | Tickets with `salesDate` today and a finished status (`COMPLETED`, `REFUNDED`): count and sum of `totalAmount`. A ticket cancelled after it was sent no longer counts |
| `todayReturnsCount`, `todayReturnsAmount` | Returns with `returnDate` today and status `COMPLETED` (the store counts by `createdAt`, without a status) |
| `openSessionsCount` | Sessions `OPENED`: 0 by design (sessions arrive once closed) |
| `pendingTicketsCount` | Tickets `PENDING`: 0 by design (parked tickets are never sent) |

The head office home hides these last two cards.

**Pages and permissions** (for the frontend session; "Add a page to the head office" applies): three head office only pages (no `twinOf`), components under `src/views/admin/headoffice/`.

| Page | Route name = `meta.resource` | Suggested path | Permission (seeded on a head office ADMIN since task 2.5) | Calls |
|---|---|---|---|---|
| Tickets history | `admin-headoffice-tickets` | `/headoffice/tickets` | `read:admin-headoffice-tickets` | `tickets`, `tickets/{id}`, `store-options` |
| Sessions history | `admin-headoffice-sessions` | `/headoffice/sessions` | `read:admin-headoffice-sessions` | `sessions`, `sessions/{id}`, `store-options` |
| Returns | `admin-headoffice-returns` | `/headoffice/returns` | `read:admin-headoffice-returns` | `returns`, `returns/{id}`, `store-options` |

The three permissions are in `ZZDataInitializer.HEAD_OFFICE_ADMIN_PERMISSIONS` (and the copy in `ZZDataInitializerRolesTest`); an existing head office ADMIN role gets them at the next start (`addMissingHeadOfficePermissions`). The frontend must add the three routes with these exact names, so the two lists stay equal.

**Head office pages** (task 2.5 frontend): `src/views/admin/headoffice/HeadOfficeTicketsHistory.vue`, `HeadOfficeSessionsHistory.vue`, `HeadOfficeReturns.vue`, shared code in `consolidated-sales.js` (store options, status labels, formats). New pages, not the store pages: they follow the store pages' layout, columns and detail, read only.
- Lists: a Store column first (code and name), then the store page's columns; the cashier on tickets; a status column on returns. Filters: number, from / to (date and time on tickets, day on sessions and returns, as on the store pages), store (`store-options`, inactive stores marked), status, and the exact session number on tickets and returns.
- Details: ticket with lines, payments (reference = `titleNumber`, due date) and summary, the loyalty member, the reception dates; session with its totals (sales, returns simple and voucher, both differences, as computed by the API), verification and the count lines in two tables (cashier, responsible); return with its original ticket (date and total, or "not received from the store yet"), voucher and lines.
- Links open in a new tab, filtered on the store: a session's View tickets / View returns (`?storeId=&session=`), a ticket's or a return's session (`/headoffice/sessions?storeId=&number=`), a return's original ticket (`/headoffice/tickets?storeId=&ticketId=`, opens its detail).
- Labels in `admin.headoffice.sales.*` and the store pages' keys (en, fr, ar). Nothing changed in the store pages, routes or menu.

### Connect a store
1. At the head office, **Network → Stores**, create the store with **code = the store's `DEFAULT_LOCATION`** (General Setup of the store). For an ERP store that is its NAV location code.
2. Copy the key from the dialog: it is shown once (a lost key is replaced with regenerate-key).
3. In the store's properties file (the profile it runs with), set `headoffice.url` (the head office base URL, with `/zsretail/api`) and `headoffice.api-key` (the key). In `application-standalone-dev.properties` and `application-dynamics-dev.properties`: uncomment the two `headoffice.*` lines at the end and replace `PASTE_KEY_HERE` with the key.
4. To copy the store's sales to the head office (step 2), also set `sales.upstream`: `HEAD_OFFICE` on a store without ERP, `ERP,HEAD_OFFICE` on an ERP store (the NAV export is not affected). In the two dev profiles: uncomment the `#sales.upstream=...` line under the `headoffice.*` lines. Optional: `headoffice.sales-push.from-date`, `batch-size`, `interval-seconds` (see "Sales copies"). Without `sales.upstream` the store keeps the heartbeat only.
   Shared loyalty (step 4): set `ownership.loyalty=HEAD_OFFICE` too. At the first pull the store's own members and program are switched off (kept); give the store its rights on the head office Stores page (`canEditMembers`). Optional: `headoffice.loyalty-push.interval-seconds`.
5. Restart the store. About 15 s after the start its log shows `Head office link: PENDING -> ONLINE`; the head office Stores page shows the store's last contact and version. At start the log lists the jobs (`Head office link: jobs on ho-link-1: HEARTBEAT every 60 s, SALES_PUSH every 60 s` with `sales.upstream`); then from about 20 s one `Head office sales push: ... sent ...` line per cycle until the history is caught up.

**L2 checks (task 1.4)** (the pair on this PC, `RS01` connected as above):

| Case | Store log | Head office |
|---|---|---|
| Both running | `PENDING -> ONLINE`, then nothing more at INFO | Stores page: `RS01` last contact moves every 60 s, version `1.12.0` |
| Head office stopped | `ONLINE -> OFFLINE (head office unreachable ...)`; selling unchanged | — |
| Head office started again | `OFFLINE -> ONLINE` at the next heartbeat | last contact moves again |
| Wrong key in the store file, or `RS01` deactivated | `-> REFUSED (store code or key refused by the head office)` | WARN `wrong key` / `inactive store` |
| `DEFAULT_LOCATION` emptied in the store | `-> NOT_CONFIGURED` | last contact stops |
| `headoffice.url` commented out again | no `Head office link` line | — |

**L2 checks (task 1.5)** (same pair; on the store, "Lien siège" ticked for ADMIN as described above):

| Case | Store: Head office link page | Head office: Stores page |
|---|---|---|
| Both running | ONLINE, last success and last attempt within the last minute, URL, store code | `SHOWROOM-S` ONLINE, "just now" or "1 min ago"; a store never connected shows NEVER |
| Head office stopped | Check now: OFFLINE with "head office unreachable (...)"; last success kept | (stopped) |
| Head office started, store stopped for more than 3 min | — | the store turns OFFLINE within 30 s of passing 180 s, without reloading the page |
| Store deactivated at the head office | Check now: REFUSED, "store code or key refused by the head office" | INACTIVE |
| Store without `headoffice.url` | no menu entry; `/admin/holink/status` redirected to home; `GET /admin/holink/status` 404 | — |

### Convention for the head office code
- **Same data, same page.** Pages that edit data the head office owns (items, promotions, loyalty, customers, users, settings) are the existing pages, never copies.
- **Different data, new page.** What exists only on a head office (stores list, tickets / sessions / returns of the stores in `ho_ticket`, `ho_return`, `ho_session` since task 2.3, shipments, the `/ho/**` endpoints) is new code in its own folder:
  - backend: feature package `com.digithink.zsretail.headoffice`, with the same sub-packages as `erp/` (`controller`, `service`, `model`, `repository`, `dto`, `scheduler`);
  - frontend: `src/views/admin/headoffice/` (admin pages of a feature live under `views/admin/<feature>`, like `views/admin/franchise`), routes `admin-headoffice-*` under `/headoffice/...` in `src/router/headoffice-routes.js` (task 1.6, "Add a page to the head office").
- Existing store pages are not modified to serve the head office: a shared page gets a head office route with `meta.twinOf`. First content: the stores list (task 1.2).
- **Store side of the link.** Code that runs on a store and calls the head office lives in `com.digithink.zsretail.holink`, never in `headoffice`; its beans carry `@ConditionalOnHeadOfficeLink` (task 1.4), or `@ConditionalOnHeadOfficeSalesPush` for the sales copies (task 2.1). Its tables are prefixed `hol_` (task 2.1). Its pages live in `src/views/admin/holink/`, routes `admin-holink-*` (task 1.5).

### Profile `headoffice-dev`
`src/main/resources/application-headoffice-dev.properties`:
- `node.type=HEAD_OFFICE`, `server.port=888`, database `pos_headoffice` (same SQL Server and login as the dev profiles).
- `application.standalone=true`, ERP and ERP sync off, `franchise.admin=false`, `franchise.customer=false`.
- Own log file `C:/zsretail-headoffice/backend.log` and image folder `uploads/headoffice/pos-images`, so it does not share them with the store instance.

### Profile `headoffice-dynamics-dev` (task 3.4)
`src/main/resources/application-headoffice-dynamics-dev.properties`: the head office with an ERP. Same database `pos_headoffice`, port 888, log file and image folder as `headoffice-dev`, which stays the head office without ERP.
- `node.type=HEAD_OFFICE`, `application.standalone=false`, `franchise.admin=false`, `franchise.customer=false`.
- `erp.dynamicsnav.*`: the settings of `application-dynamics-test.properties`, the **NAV test instance** (`192.168.10.166:24/test4`). Never the NAV of `application-dynamics-dev.properties` or `application-dynamics-prod.properties`: that is the customer's production NAV (correction from Zein, 2026-10-03). A comment at the top of the file says so, and `HeadOfficeErpProfileTest` fails if the URL or host of the dev or prod profile appears in it.
- `erp.sync.enabled=true`, `erp.sync.scheduler.delay=60000`: the import jobs enabled from the ERP jobs page run; all are seeded disabled.
- Switching one `pos_headoffice` database between the two profiles: the ERP jobs are seeded at the first start in ERP mode; started again with `headoffice-dev` (standalone) nothing runs them (no scheduler) and the item pages allow creating by hand again.

### Head office with an ERP (task 3.4, decision D2)
The head office of an ERP customer (ParaFendri) gets its items, families, sub-families and barcodes from Business Central with the existing import jobs, filtered on one **ERP reference location** chosen at the head office. It exports nothing. A head office without ERP keeps creating items by hand or by data import. Nothing in `erp/` is changed: what is specific lives in `headoffice` and only exists on a head office.

**What happens with `node.type=HEAD_OFFICE`, `application.standalone=false`, `erp.dynamicsnav.enabled=true`, `erp.sync.enabled=true`** (read in the code, every place where ERP mode changes behaviour):

| Place | ERP mode does | On a head office |
|---|---|---|
| Startup (`NodeOwnership`) | Owners derived from the ERP row: catalogue, customers, supply `ERP`; promotions, loyalty `LOCAL` | Accepted. Sales go nowhere (a head office never sells, whatever the flags). An explicit owner `HEAD_OFFICE` stays refused |
| `GET /config` | `standalone: false`, `ownership` with `ERP` | Fine: the frontend uses `standalone` to show the ERP pages of the head office (below) |
| `ZZDataInitializer` (every start) | Seeds the ERP checkpoint settings and, on an empty table, the 12 ERP jobs: imports disabled, `EXPORT_RETURNS` and `EXPORT_SESSIONS` **enabled**; seeds `ERP_SYNC_TRACKING_LEVEL`, `ERP_SKIP_CHEQUE_PAYMENTS`; no passenger customer | Guarded: `HeadOfficeErpJobs.switchOff` runs right after it (it takes the initializer as a dependency) and before any scheduled task: the exports and the price import disabled, no next run. The settings are harmless; no passenger customer is needed (no sale) |
| `ErpSyncScheduler` (`erp.sync.enabled`) | Runs every enabled job when due, through `ErpSyncJobRunner.run` | Guarded: `HeadOfficeErpGuard` (aspect) refuses the jobs of `NOT_ON_HEAD_OFFICE` in the runner with a warning (status `WARNING: This ERP job does not run on a head office: ...`); imports run as on a store |
| ERP jobs API `admin/erp/jobs` | Lists every job; run now, enable, change, statistics by id | Guarded by the same aspect: the list leaves them out; `POST {id}/run`, `PATCH {id}`, `PUT {id}`, `GET {id}/statistics` answer 404 `{"error":"This ERP job does not run on a head office"}`. The store page works as it is |
| ERP communications log `admin/erp/communications` | Read-only log of the ERP calls | Fine as it is |
| Import jobs (`DynamicsNavRestClient`) | Items through the stock keeping units of `DEFAULT_LOCATION` (warning "DEFAULT_LOCATION is not configured" without it); prices and discounts on the responsibility center of the location marked default; locations, families, sub-families, barcodes, customers, deletions unfiltered | Fine once the reference location is chosen (below). Consequence: the head office has the items that have a stock keeping unit at that location; choose the location that carries the network's catalogue (e.g. the central warehouse) |
| `ItemAPI`, `ItemFamilyAPI`, `ItemSubFamilyAPI`, `CustomerAPI`, `LocationAPI` | Create (and change or delete locations) refused: the data comes from the ERP | Intended: the head office catalogue is the ERP's. Promotions target the imported items by code |
| `DataImportAPI` | Refused (standalone only) | Intended; the head office Data import page is already hidden when not standalone (task 1.6) |
| `PurchaseHeaderAPI`, `PurchaseInvoiceAPI`, `VendorAPI` | Refused | Fine: a head office does not buy (shipments come at step 7) |
| `StockService`, `StockMovementService` | No local stock | Fine: no stock at the head office before step 7 |
| `InvoiceAPI` (from POS tickets) | Refused | Fine: no ticket at the head office |
| `DynamicsNavRestClient`, `DynamicsNavConnector`, `DynamicsNavConfig` (`erp.dynamicsnav.enabled`) | The NAV connector instead of the no-op one | Needed for the imports |
| Selling services, franchise code | Not reached: no cashier session or cashier login on a head office (task 1.1), franchise flags refused | Unchanged |

**Jobs on a head office** (`HeadOfficeErpJobs.NOT_ON_HEAD_OFFICE`, never run, never offered): `EXPORT_CUSTOMERS`, `EXPORT_TICKETS`, `EXPORT_RETURNS`, `EXPORT_SESSIONS` (a head office has no ticket, return or session, and a customer created there must not reach the ERP) and `IMPORT_SALES_PRICES_AND_DISCOUNTS` (filtered on the responsibility center of one location, it would give one store's prices as if they were the network's; a head office does not sell, and each ERP store imports its own prices). Offered: `IMPORT_ITEM_FAMILIES`, `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`, `IMPORT_ITEM_BARCODES`, `IMPORT_LOCATIONS` (needed to choose the reference location), `SYNC_ERP_DELETIONS`, and `IMPORT_CUSTOMERS` (import only, disabled as seeded: the head office Customers page can show the ERP customers; nothing goes back to the ERP).

**ERP reference location** (`ErpReferenceLocationService`, `ErpReferenceLocationAPI`, `@ConditionalOnHeadOfficeErp`: head office with `application.standalone=false`; 404 elsewhere). The import needs `DEFAULT_LOCATION` and, for the price filter, the location marked default; choosing the reference location is the store's "set as default" (`LocationService.setAsDefault`), without the store's Locations page. `RESPONSIBILITY_CENTER` (general setup) is read only by the exports, which never run here: not written.

| Request | Answer |
|---|---|
| `GET /admin/headoffice/erp/reference-location` | `{locationCode, name, responsibilityCenter, locations: [{locationCode, name, responsibilityCenter}]}`: the current choice (`DEFAULT_LOCATION`, null when none; name and responsibility center null when that code is not among the imported locations) and the imported locations by code |
| `PUT /admin/headoffice/erp/reference-location` | Body `{"locationCode": "MAG01"}` (trimmed, any case): 200 with the view above; 400 `{"error":"locationCode is required"}` or `{"error":"Unknown ERP location 'X': choose one of the locations imported from the ERP (job IMPORT_LOCATIONS)"}` |

Order at a new head office with an ERP: enable and run `IMPORT_LOCATIONS`, choose the reference location, then enable the item imports (families, sub-families, items, barcodes).

**Pages** (permissions now, routes and menu with the frontend prompt; seeded on every head office ADMIN like the others, the frontend shows the pages only when `GET /config` says `standalone: false`):

| Page | Permission (= route name `admin-headoffice-...`) | API |
|---|---|---|
| ERP jobs (store page as a twin) | `read:admin-headoffice-erp-jobs` | `admin/erp/jobs` (guarded as above) |
| ERP communications log (store page as a twin) | `read:admin-headoffice-erp-communications` | `admin/erp/communications` |
| ERP reference location (new page) | `read:admin-headoffice-erp-reference-location` | `admin/headoffice/erp/reference-location` |

**NAV**: only the test instance of `application-dynamics-test.properties` may be called from this PC, and only with GET; never the production NAV of the dev and prod profiles. Reaching the test instance needs the VPN (closing prompt of step 3).

### Start the pair on this PC (test level L2)

| Instance | Backend | Frontend |
|---|---|---|
| Store | any store profile (e.g. `standalone-dev`), port 444 | `npm run serve` (`.env.development.local` → 444) |
| Head office | profile `headoffice-dev`, port 888 (set in the profile) | `npm run serve:headoffice` → http://localhost:8081 (`.env.headoffice` → 888) |

- In STS, duplicate the backend run configuration and set its profile to `headoffice-dev` (`--spring.profiles.active=headoffice-dev`); the active profile in `application.properties` is overridden.
- 888 and 8081 are also the franchise customer's ports: the franchise pair and the head office pair do not run at the same time.
- To make the store call the head office: "Connect a store" above.

### New head office database: manual steps
1. In SQL Server, create the empty database: `CREATE DATABASE pos_headoffice`. Hibernate creates the tables (`ddl-auto=update`); it does not create the database.
2. Start the backend with `headoffice-dev`. `AppVersionGuard` skips the check on an empty `APP_VERSION`; `ZZDataInitializer` seeds the data above. No `db/<version>/update.sql` script is needed on a new database.
3. Start `npm run serve:headoffice` and log in as `admin` (default password set in `ZZDataInitializer.initUsers`).
4. Upload the license on the License page: until then every API except login, `/config`, `/company-info` and the license pages answers 402. The license is bound to the machine, not to the database, so this PC's license file works.
5. Check `GET http://localhost:888/zsretail/api/config`: `"nodeType":"HEAD_OFFICE"`, all owners `LOCAL`, `"salesUpstreams":[]`.

### Tests (L1)
- `ApplicationModeOwnershipTest`: head office rows (standalone and ERP flags), the startup checks, `isHeadOffice()`. Task 1.4: a store with `headoffice.url` and a key is accepted on the 4 profiles; refused on a head office, without a key, with an interval below 1 or not a number; without the URL the other `headoffice.*` keys are not checked. Task 1.5: `headoffice.offline-after-seconds` on a head office (below 1 or not a number refused, not checked on a store); `isHeadOfficeLinked()`.
- `AppConfigAPITest`: `headoffice-dev` row, old `/config` fields unchanged. Task 1.5: `headOfficeLinked` true only with a non-blank `headoffice.url`, last in the key order.
- `CashierSessionHeadOfficeTest`: `openSession` and a new-session `save()` refused on a head office; existing session saved; the 4 store profiles open sessions as today.
- `JWTAuthenticationFilterTest`: cashier refused on a head office; admin and a user without AppRole accepted; a cashier login on the 4 store profiles answers exactly as before.
- `ZZDataInitializerUsersTest`: head office seeds `admin` only; the 4 store profiles seed admin, responsible and cashier. The "empty user table" guard in `init()` is not covered.
- `ZZDataInitializerRolesTest`: covers the 4 store profiles and the head office.
  - New head office database: ADMIN gets today's permissions plus the 23 head office ones (17, plus tickets, sessions and returns in task 2.5, plus the three ERP pages in task 3.4), each role saved once. `HEAD_OFFICE_ADMIN_PERMISSIONS` equals the test's copy of the frontend list, without `read:admin-headoffice`.
  - Existing head office ADMIN: it receives the missing permissions once and keeps its others (also `read:admin-headoffice`); the next start saves nothing; RESPONSIBLE and POS_USER are untouched.
  - Stores: the 4 profiles seed exactly today's roles. With and without `headoffice.url`, an existing role is never saved or changed.
  - Task 1.5: with `headoffice.url`, ADMIN also gets `read:admin-holink-status` on the 4 profiles; a head office never does.
- `StoreServiceTest`: key generated and stored only as a hash; key check (good, bad, inactive store, unknown code, missing key) and its reason from `check` (an inactive store with a wrong key is `WRONG_KEY`); regenerate; code trimmed, uppercase, unique and final; `lastContact`, `appVersion` and the hash never written by the client (service and JSON); delete refused after a contact.
- `OnHeadOfficeConditionTest`: `StoreService`, `StoreAPI`, `StoreApiKeyFilter`, `HeadOfficePingAPI` and `HeadOfficeHeartbeatAPI` are registered on a head office (any case and spacing of `node.type`) and not on a store; an unknown value fails like at startup. The `/ho/**` chain is registered on both, with an optional filter, a head-office-only servlet registration and order 0. Uses a bare bean registry, no context started.
- `StoreApiKeyFilterTest`: good key (store principal, authority `HO_STORE`); wrong key, missing headers (none, one, blank: no store lookup), unknown store, inactive store: the same 401 and body, one WARN line with reason, code, remote address and path, never the key; control characters kept out of the log; paths outside `/ho/**` untouched even with a valid key; a user JWT alone refused, and dropped when a valid store key comes with it. Real `StoreService` over an in-memory repository, log captured with a Logback `ListAppender`.
- `HeadOfficePingAPITest`: code and server time format, the store not changed, the JSON has only `storeCode` and `serverTime`.
- `HeadOfficeHeartbeatAPITest` (task 1.4): `lastContact` and `appVersion` written by id through the two-column update; hash, code, name, kind, active and `updatedAt` unchanged; the detached principal (changed in memory) never saved; `lastContact` and `serverTime` the same instant; version trimmed, blank, empty, null or no body gives null, longer than 255 cut. Real `StoreService` over an in-memory repository.
- `OnHeadOfficeLinkConditionTest` (task 1.4): no URL, empty or blank URL gives false, a URL gives true; `HeadOfficeClient`, `HeadOfficeLinkStatus`, `HeartbeatJob`, `HeadOfficeLinkAPI` (1.5), `LinkJobScheduler`, `LinkJobService` and `LinkExchangeLog` (2.6) registered only with a URL and all carry the annotation. Bare bean registry.
- `HeadOfficeClientTest` (task 1.4): every line of the state table (200; 401; 402; 403, 404, 500, 503, 204, 302; HTML, broken JSON, empty body; connection refused, unknown host, timeout; `DEFAULT_LOCATION` null, empty, blank and unreadable with no call); both headers and the body on every call, the key trimmed; the store code read at each call; trailing slashes; the 5 s / 10 s timeouts of the own `RestTemplate`. Transport: Spring's `MockRestServiceServer` on a plain `RestTemplate`, so its own error handling is exercised.
- `HeartbeatJobTest` (task 1.4; was `HeadOfficeHeartbeatSchedulerTest`, a job since 2.6): code, first delay 15 s, default frequency; `PENDING` at start; a failure keeps the last success and its head office time; one INFO line and one exchange log row per state change (4 rows for 7 heartbeats: `SUCCESS` or `ERROR` with `<state>: <message>`, `UP`, 0 records), DEBUG and no row otherwise; run result `SUCCESS` `ONLINE` or `ERROR` with the state.
- `StoreStatusTest` (task 1.5): INACTIVE (also with a recent contact), NEVER, ONLINE up to and including the threshold, OFFLINE 1 ms after it, a contact in the future ONLINE, another threshold applied; the list item JSON is the store's JSON plus `status` and `secondsSinceContact`, never the hash; read by id.
- `HeadOfficeLinkAPITest` (task 1.5): `GET status` has exactly the 8 fields and never the key (also after a refusal); `POST check` before the thread is started makes no call; after it, the heartbeat runs on `ho-link-1` and the answer is the new state (ONLINE, then REFUSED with the last success kept); `DEFAULT_LOCATION` empty gives NOT_CONFIGURED and a null store code.
- `ApplicationModeOwnershipTest` (task 2.1): `headoffice.sales-push.from-date` accepted as `yyyy-MM-dd` (trimmed) or blank, refused otherwise with the key in the message, not checked without `headoffice.url`.
- `OnHeadOfficeSalesPushConditionTest` (task 2.1): false without the URL, and with the URL when the upstreams do not include the head office (standalone and ERP flags without `sales.upstream`, `ERP`, empty, franchise admin); true with an explicit `HEAD_OFFICE` (any case, alone or with `ERP`) and on franchise customer flags (derived); the sales copy beans registered only then, all carrying the annotation. Bare bean registry.
- `SalesCopyFinderTest` (task 2.1): the finished statuses of each type; a new finished ticket gives one `PENDING` row (number, date, no attempt); a ticket changed after it was sent is `PENDING` again (attempts and error reset, accepted hash kept, same row); parked and cancelled tickets are not found, a parked ticket completed later is; from-date ignores the day before and keeps the day itself, for every type; without it the whole history; a cycle with nothing new reads no document and touches no tracking row; the 30 s settle delay; a sent ticket cancelled later is found again; an `ERROR` row that changes is `PENDING` with attempts 0, a `PENDING` one is not rewritten; 1001 tickets read 500 per cycle, each once, with equal change times across page boundaries; returns and sessions (an `OPENED` session is read but not tracked); one failing type is reported, the others are searched and its cursor does not move. The store documents are an in-memory list read with the rules of the JPQL query; the query itself is checked at L2.
- `SalesCopyMapperTest` (task 2.2): every field of the ticket copy (header, promotion, customer, cashier, session, loyalty, invoice), lines and payments in store order whatever the input order, no ERP field; a walk-in ticket (no customer, member, promotion, cashier, lines or payments) and a member with only a first name; the return copy with its voucher (not its later use) and a simple return; the session copy with cashier, verifier and count lines (cash without a payment method), and a `CLOSED` session; in every copy no field named `id` or `...Id` and none of the entity ids; JSON dates as ISO strings, read back equal, a `storeCode` or `id` in the body ignored even by a strict mapper.
- `SalesCopyReceiverTest` (task 2.3): a repeated push creates one row with its lines and payments once; a changed ticket replaces its row's header, lines and payments (same row, first reception kept); two stores with the same sales number keep two rows and a push replaces only the sender's; in one batch, a missing number, a line without item code, an empty document, a payment without method and a missing date are each rejected with their reason, in order, while the others are saved, one transaction per document; a document refused by the database is rejected with the cause and the next ones are saved; the row references the store by id, never the principal object; empty and null batches; the reason cut to 500 characters; returns and sessions (repeat, replace, two stores, `TERMINATED` replacing `CLOSED` with its count lines). In-memory repositories and a counting transaction stub.
- `SalesCopyFinderTest` (step 2, item 1): a sent session reopened later (`OPENED`, closing date cleared) is found again; a ticket cancelled before it was finished is read at each change but never tracked.
- `SalesCopyRoundTripTest` (step 2, item 1): the real search and push talk through `MockRestServiceServer` to the real `SalesCopyReceiver`, all over in-memory tables. A ticket sent as `COMPLETED`, then cancelled, is sent again and the head office row becomes `CANCELLED` (one row, lines replaced); a parked ticket cancelled before being finished is never tracked nor sent; the next cycle sends nothing.
- `HeadOfficeSalesAPITest` (task 2.3): the three endpoints answer `{"results":[{documentNumber, accepted, message}]}`, one per document.
- `OnHeadOfficeConditionTest` (task 2.3): `SalesCopyReceiver` and `HeadOfficeSalesAPI` exist only on a head office; task 2.5: `ConsolidatedSalesService` and `ConsolidatedSalesAPI` too.
- `ConsolidatedSalesServiceTest` (task 2.5): ticket filters by store (unknown store: nothing), dates (a whole day, or from a time), number (contains, any case), status (`all` = any), session; newest first; store code and name on every row; paging (defaults, page 2 of 25, answer keys and order, size bounds, page below 0 refused); parsing (absent filters become non-null bounds, end of day at 23:59:59.9999999, bad date refused with its name); ticket row and detail with the store's field names, counts, lines and payments in order, 404; session totals from its own store only (finished tickets, simple and voucher returns), the store page's differences, count lines; return row with the returned ticket of the same store or only its number, voucher, lines; store options; home cards (finished tickets of today only, completed returns of today, zeros when empty). In-memory lists; the stubs apply the rules of the JPQL queries, which were translated with Hibernate during the task and are checked at L2.
- `ZZDataInitializerRolesTest` (task 2.5): the head office list now has 20 permissions (tickets, sessions, returns added).
- `QueryParameterBindingTest` (step 2): every JPQL `@Query` of `HoTicketRepository`, `HoReturnRepository`, `HoSessionRepository`, `LinkExchangeRepository` and `SalesCopyRepository` is parsed by Hibernate (SQL Server dialect, no database) and a value of each declared parameter type is checked against the type Hibernate infers, with the validator JPA applies at `setParameter`. Added after L2 found `(:storeId = 0 or ...)` typing the parameter Integer (the list answered 500 for a store filter); the queries use `0L`.
- `SalesPushServiceTest` (task 2.4): retry after a rejection (`ERROR` with the reason and one attempt, then `SENT` with two attempts and the accepted hash at the next cycle, then no request); head office unreachable, 401, 402, 503 and an HTML answer leave every tracking row exactly as it was (pending, rejected and changed rows), stop the cycle after one request and do not speed up the next one; one rejected document in a batch does not stop the others, and a document missing from the answer is `ERROR`; a copy equal to the accepted one is `SENT` without a request, a changed one is sent; a deleted document and a read failure are `ERROR` with an attempt while the rest is sent; batches of `batch-size`, oldest first, rejected after never tried, 2 per type per cycle with the types in turn, a rejected document not resent in the same cycle, a full batch speeding up the next cycle until caught up; rejected-only batches do not; the 20 s cycle bound; the hash (equal after a JSON round trip, different when a line changes); the counts. Real `HeadOfficeClient` over `MockRestServiceServer`, in-memory `hol_` tables.
- `SalesPushJobTest` (task 2.4; was `SalesPushSchedulerTest`, a job since 2.6): code, first delay 20 s, default frequency from the settings; a cycle that throws gives a WARN line and an `ERROR` run, no exception, no exchange row. The timing moved to `LinkJobSchedulerTest`.
- `HeadOfficeClientTest` (task 2.4): the push POSTs a JSON array (dates as ISO strings) to `/ho/sales/tickets`, `/returns`, `/sessions` with both headers and reads one result per document; 401, 402, 500, connection refused, an HTML answer, `{"results":null}` and an empty `DEFAULT_LOCATION` (no request) are not delivered and give no result. The heartbeat cases are unchanged on the shared `post` method.
- `HeadOfficeLinkAPITest` (task 2.4): the 11 fields in order; counts null without the push; with it the count per status (0 when none), and null when the count fails while the link fields are still answered.
- `ApplicationModeOwnershipTest` (task 2.4): an explicit `HEAD_OFFICE` upstream (alone or with `ERP`, any case) without `headoffice.url` is refused on standalone and ERP flags; `ERP`, empty, or with the URL accepted; the franchise customer profile (derived `HEAD_OFFICE`) still starts without the URL; a head office keeps its own message; `batch-size` 1 to 1000 and `interval-seconds` at least 1, not checked without the URL. Two step-0 tests that set `sales.upstream=HEAD_OFFICE` now also set the URL and key.
- `OnHeadOfficeSalesPushConditionTest` (task 2.4): `SalesPushService` and `SalesPushJob` added to the sales copy beans.
- `LinkJobSchedulerTest` (task 2.6): first run at the job's first delay, then its default frequency after the end of the previous run; a frequency saved from the page is used by the next run (and saved in `hol_job`; `null` back to the default; the other job unchanged); a run with more to do brings the next one 5 s later, then the frequency again; each run recorded (start, result, message, duration) in memory and `hol_job`; the log purged once at the first run, not at the second; a job that throws is an `ERROR` run with a WARN line and the usual next run; start: one thread `ho-link-1`, no run at once, one INFO line with the jobs; run now refused before the start, then on `ho-link-1` and recorded; reschedule after a change (next run one new frequency from now); views in order with the limits; jobs found by code without regard to case.
- `LinkJobServiceTest` (task 2.6): default frequency, a saved one read back at start, `null` resets; limits 10 s to 86,400 s (nothing saved outside); database down: defaults used, a run remembered in memory without throwing, a change refused, the table read once it can be.
- `LinkExchangeLogTest` (step 5): a failure repeated 5 cycles is one row (records kept), a new reason one more, another job its own episode; a run that works writes one `SUCCESS` row (0 records, the direction of the failure), then nothing; a later failure is written again; a batch row ends the episode without an extra row; a `WARNING` run counts as working. `SharedLoyaltyRoundTripTest` (step 5): head office stopped for 3 cycles of push and pull: one `ERROR` row each; back: their batch rows; stopped again: written again.
- `LinkExchangeLogTest` (task 2.6): a row's fields, the error cut to 1000, a write failure swallowed; purge of rows older than 30 days at the first call, not again within a day, a failure swallowed; the log page by job, result (`all`), dates (a day, from a time), newest first, paged, keys in order; a bad result or date refused.
- `SalesPushServiceTest` (task 2.6): with the push as a job over an in-memory log: a cycle with nothing to send writes no row (`nothing to send`); one row per batch that sent something (2 batches: records, `WARNING` with `T-2: <reason>`, then `SUCCESS`); not delivered gives one `ERROR` row with the state and message, a failed search one `ERROR` row with 0 records; a log that cannot be written does not break the push (documents `SENT`, run `SUCCESS`).
- `HeadOfficeLinkAPITest` (task 2.6): `GET jobs` (only the heartbeat without the push, fields in order); `PUT jobs/{code}/interval` saves (status shows it), `null` resets, 400 below 10 or not a number (nothing changed), 404 for a job this store does not have; `POST jobs/{code}/run` runs on `ho-link-1` and answers the job after the run (one exchange row on `PENDING -> ONLINE`), 404 unknown; `GET log` answers the page, 400 on a bad result or date.
- `ApplicationModeOwnershipTest` (task 2.6): `headoffice.log-retention-days` below 1 or not a whole number refused, `1` and ` 90 ` accepted, not checked without the URL.
- `ApplicationModeOwnershipTest` (task 3.1): an explicit `ownership.promotions=HEAD_OFFICE` (any case) without `headoffice.url` (absent or blank) refused on standalone and ERP flags; `LOCAL` without, `HEAD_OFFICE` with the URL accepted; the 4 profiles unchanged; a head office keeps its message; `headoffice.pull.interval-seconds` checked only with the URL. `explicitOwnerOverridesOneDomain` now sets the URL.
- `OnHeadOfficePullConditionTest` (task 3.1): no pull without the URL, with every domain local (standalone, ERP), with sales copies only, on a head office; pull with the URL and promotions owned by the head office (any case); `isOwnedByHeadOffice` per domain; `CopiesDownPuller` and `CopiesDownJob` registered only then and carry the annotation. Bare bean registry.
- `OnHeadOfficeConditionTest` (task 3.1): `CopiesDownFeed` and `HeadOfficeDownAPI` exist only on a head office.
- `CopiesDownFeedTest` (task 3.1): the cursor is the change number (blank first, sent back, a clock or a negative value refused, an absent cursor starts again); a change committed during a pull comes with the next one; a change numbered above the committed number is not read until it is committed; targets (every store, also a store created later; a list; a store outside the list receives nothing, not even a removal; an edit for another store brings nothing); targets changed (taken off: removal; added: record; back to all; from all to a list: removal for the others); deletions; pages (limit, `more`, cursor of the last code, last page at the horizon); a cursor above the horizon; limits and unknown domains; one change row per (code, store); the startup backfill. In-memory tables that apply the JPQL rules.
- `CopiesDownPullerTest` (task 3.1): the cursor saved exactly as received (`41`, then an opaque `v2:opaque/+=`) and read back unchanged by the head office (strict encoding); the same page applied twice changes nothing, also after a cursor that could not be saved; 401, 402, 503, connection refused, HTML, another domain and no cursor change nothing (no apply, cursor unchanged, one `ERROR` row) while the retries still run; `more` pulls the next page in the same cycle, 10 pages then the next cycle soon; a page that cannot be applied leaves the cursor; handler problems give `WARNING` with the first problem; local records set inactive give one row with how many; the job's code, first delay, frequency, no handler, a cycle that throws. Real `HeadOfficeClient` over `MockRestServiceServer`.
- `QueryParameterBindingTest` (task 3.1): `HoDownChangeRepository` and `HoDownSequenceRepository` added.
- `PromotionAPIGuardTest` (task 3.2): promotions owned by the head office: create, edit, deactivate and delete answer 409 for a local, a `LOCAL` and a `HEAD_OFFICE` promotion, nothing written, reads allowed; promotions local (rule fix): a promotion that came from the head office is edited, deactivated, written by a create with its id, locked once used and deleted like a local one, its origin kept; local promotions as before (create, edit, deactivate, usage lock, delete refusal, delete), the origin never read from JSON and kept on update; `isPromotionsOwnedByHeadOffice` only with the URL and the owner.
- `HoPromotionServiceTest` (task 3.3): real `PromotionService`, `HoPromotionService` and `CopiesDownFeed` over in-memory tables. Every store by default (and a store created later), an edit and a deactivation reach them; a list reaches only its stores and a store outside it receives nothing, also when the promotion is created with its list; targets changed (removal for the store taken off, record for the store added, same targets record nothing); deleted (removal for its stores, nothing for the others, target rows gone); code changed (old code removed, new one sent); the payload by codes (sorted group items, benefit item, no field named `id` or `...Id`, no item id); target requests (empty list, no body, unknown store, unknown promotion refused, nothing written; get with the stores sorted, list, create without targets = every store); usage count from the consolidated tickets and lines, the lock follows it; the startup backfill with an existing list.
- `PromotionDownHandlerTest` (task 3.3): copies built from head office promotions (other ids) through JSON. Every scope (ITEM, ITEM_FAMILY, ITEM_SUBFAMILY, ITEM_GROUP, ALL_ITEMS, CART, a cross-product benefit item) resolved to the store's records with every field and `origin=HEAD_OFFICE`; the same copy twice writes nothing, a changed one updates the same row; the normalisation kept (stray targets cleared) and the usage lock skipped; a local code not saved and reported; removal (unused deleted, used deactivated, local and unknown codes left alone, already inactive unchanged, targeted again updated and active); missing targets not saved and reported, a partial group saved with its items; local promotions set inactive once; unreadable records reported while the next ones are applied.
- `OnHeadOfficeConditionTest`, `OnHeadOfficePullConditionTest` (task 3.3): `HoPromotionService` and `HoPromotionTargetAPI` on a head office only; `PromotionDownHandler` only with promotions owned by the head office (another domain owned: the job exists, not this handler).
- `QueryParameterBindingTest` (task 3.3): `PromotionRepository` added (its JPQL queries; the native `countUsages` is skipped) and the two count queries of `HoTicketRepository`.
- `PromotionAllItemsScopeTest`: unchanged and green (its `PromotionService` has no head office hooks).
- `PromotionDownHandlerTest` (task 3.5): an item missing gives `WAITING` with the reason, a retry with the same outcome writes nothing, the item added: the next retry applies it with the store's item, an applied record is not retried; family, sub-family and benefit item missing give `WAITING`; a promotion the store has, now pointing to a missing item, becomes inactive (rest unchanged), the same answer again writes nothing, and it is active again once the item exists; ITEM_GROUP with no item `WAITING`, with some `APPLIED` and the missing codes as information; a code clash `ERROR` retried and applied once the local promotion is gone; the same answer twice writes no tracking row; a removal deletes the rows; the records of this cycle's pull are not retried in the same cycle.
- `DownRecordLogTest` (task 3.5): a row written only when something changed; reception time moves only with a new copy, status time only with a new status; a retry keeps the copy; texts cut to 1000; rows to retry by code; counts with every status; removal; a bad status refused.
- `HeadOfficeLinkAPITest` (task 3.5): `received` is the 12th and last field, null without a pull; with it the counts per domain; `GET received/{domain}` in order (`ERROR`, `WAITING`, `APPLIED`, by code) with its fields, the status filter, 400 for a bad status, 404 for a domain not pulled and on a store without pull.
- `OnHeadOfficePullConditionTest`, `QueryParameterBindingTest` (task 3.5): `DownRecordLog` among the pull beans; `DownRecordRepository` added.
- `HeadOfficeHeartbeatAPITest` (task 3.6): owners and upstreams saved in the same update as the contact, read leniently (any case and spaces, an owner the domain does not allow and an unreadable value give null, an unknown domain key and unknown upstreams dropped, upstreams in enum order once); the JSON `ownership` and `salesUpstreams`, the columns hidden, nothing read back from a client; an empty list is `[]`; a heartbeat without the fields (older store, no body) makes the store unknown, also after a report; round trip from the real store client (`MockRestServiceServer`): the body carries what `GET /config` gives, a client without the mode sends the version only, byte for byte as before.
- `ConsolidatedSalesServiceTest`, `HoPromotionServiceTest` (task 3.6): `ownership` last in the store options; in the targets view, a store owning its promotions is accepted and shown, a store without a report is null.
- `HeadOfficeErpTest` (task 3.4): at each start the exports and the price import are disabled with no next run (the two exports seeded enabled, one enabled by hand later), imports untouched, the next start writes nothing, a database without ERP jobs is fine; through Spring AOP on test subclasses of the ERP runner and controller: the runner refuses each of the five jobs with a warning and runs the others; `GET admin/erp/jobs` leaves them out; run, enable, update and statistics of one of them answer 404 without reaching the controller; the others and an unknown id as before.
- `ErpReferenceLocationServiceTest` (task 3.4): none chosen (imported locations by code, blank setting = none); chosen by code in any case through the store's set-as-default, the view with name and responsibility center; blank and unknown codes refused, nothing changed; a `DEFAULT_LOCATION` not imported shows its code only.
- `HeadOfficeErpProfileTest` (task 3.4): the real `application-headoffice-dynamics-dev.properties` starts (head office, owners ERP, ERP, LOCAL, LOCAL, ERP, sales nowhere), same database and port as `headoffice-dev`, NAV settings equal to `dynamics-test` (the test instance), never the URL or host of `dynamics-dev` or `dynamics-prod`; the reference location beans only there; the export guard on both head office profiles, never on `dynamics-dev` or `standalone-dev`.
- `ZZDataInitializerRolesTest` (task 3.4): the head office list has 23 permissions (the three ERP pages added).
- `HoLoyaltyRegisterTest` (step 5): the member as held now, an alias card answering its surviving member, unknown and blank cards 404; an adjustment from a store refused without `canAdjustPoints` (nothing written), applied with it as an `ADJUSTED` row by `STORE:RS01 (cashier1)` with the reason, sent to every store, never below zero; delta 0, no reason, unknown card refused.
- `HoLoyaltyReportServiceTest` (step 5): an overspend listed once (the movement sent twice), newest first, with store code and name, card, member name, sale or return number, points asked and missing; the count and points; filters by store, text (name, sale number, return number), dates (from, a day), bad date and page refused, unknown store empty. `OnHeadOfficeConditionTest`, `QueryParameterBindingTest`, `ZZDataInitializerRolesTest`, `StoreServiceTest`, `HeadOfficeHeartbeatAPITest`: the report beans, its two queries, the 24th permission, `redeemRequiresOnline` (false by default, set and cleared, in the heartbeat answer as the 5th key).
- `HoLoyaltyRegisterTest` (step 4, head office side): cards `LYL-HO-000001` with their own sequence (a higher one followed, today's `LYL-000007` and a store's card ignored); the phone across the network at the head office (a store's card holds it); copies down to every store (the program with sorted tiers, then the member with function code and balance, no id); a program closed by a new one (old code removed, new one sent); the startup backfill (members and the active program, not a closed one); members up: new phone `CREATED` with the store's card, balance 0, `STORE:RS01`, the store's enrol date, known to another store; known phone `MERGED` (alias, surviving card and balance, no member row for the card; sent again: same answer, one alias); known card `EXISTS` with nothing written; rejected members with their reasons while the next is saved; movements: applied twice changes nothing (one ledger row, `already applied`), the same key from another store is another movement; a movement for an alias card goes to the surviving member; never below zero with the overspend in the row and the description; in order, each on its own (unknown card and unknown type rejected, the others applied); rights: a manual adjustment refused without `canAdjustPoints`, points given back after a return accepted, accepted with the right; an applied movement sends the member again with its balance; phone check (active card first, none, blank); member edit refused without `canEditMembers`, applied and sent with it, deactivation; phone unique across the network (409 text), 7 digits refused, unknown card, edit through an alias card. Real `LoyaltyService`, `HoLoyaltyService`, `HoLoyaltyReceiver` and `CopiesDownFeed` over in-memory tables (`support/InMemoryLoyalty`, `InMemoryDownTables`).
- Enrol switch (2026-10-04): `SharedLoyaltyRoundTripTest.strictEnrol` (head office stopped: 503 with the message and nothing created, earning and search go on; back: the enrol works; a 7-digit phone gets the 400 first), `LoyaltyAPINetworkTest` (the 503 body `{error}`), `StoreServiceTest` (off by default, set, kept when absent), `HeadOfficeHeartbeatAPITest` (6 keys, null read as false).
- `SharedLoyaltyRoundTripTest` (step 5): fresh balance online (the head office's 300 + our 20 not sent = 320, fresh, `refreshedAt`, the card fresh) and offline (this store's copy, fresh false with the reason, `canRedeem` true on a lenient store and false on a strict one; a card not sent yet: the unknown reason); strict store: spending refused with the message and nothing written, earning still works, allowed after a refresh, still at exactly 2 minutes, refused one second later, a refresh while offline does not help; lenient store (setting unknown, then false): spending with the head office stopped; adjustment from the store: 403 with the head office text without the right, with it applied there by `STORE:RS01 (cashier1)` and saved here with our movement not sent (160), no movement written here, card fresh, 503 when unreachable, equal after push and pull; returns: a sale spending 200 and earning 100, a partial then a full return: the head office balance and both totals equal the store's after each push, 300 at the end, no overspend, the six movements in order. Heartbeat setting kept through a failure.
- `LoyaltyAPINetworkTest` (step 5): an adjustment with the network bean goes through it (its 403 reaches the caller), delta 0 refused before the call.
- `SharedLoyaltyRoundTripTest` (step 4, store side end to end): the real store (`LoyaltyService` with `StoreLoyaltyHooks`, `StoreLoyaltyNetwork`, `LoyaltyPushService`, `CopiesDownPuller` with `LoyaltyDownHandler`, the real `HeadOfficeClient`) talks through `MockRestServiceServer` to the real head office (`HoLoyaltyReceiver`, `HoLoyaltyService`, `CopiesDownFeed`), each over its own in-memory tables with different ids; the head office can be stopped (connection refused) or lose an answer after applying it. Enrol online with a new phone (checked there, `LYL-RS01-000001` from `rs01`, sent by the job, created there; the next card `000002`); online with a known phone (today's message naming the network card, that member saved here with the store's own function, found by the search, no card created); offline (created here, a switched-off local card does not block the number, a second card for it refused here; the push while down changes nothing and writes an `ERROR` row; sent once back); upload then merge (local card deactivated, surviving card here at 300 + 100, our movement applied there to the surviving member in the same cycle, 400 after the pull); merge saved before our movement is applied (an answer lost: counted twice until sent again, applied once there, right after the next pull); a movement sent twice applied once (members first); a member rejected there: its movements wait, the others go; balance never backwards (another store's 30 + our 120 pulled before our push = 150; unchanged by the push; 150 after the pull; earned total too); never below zero (spent here and elsewhere: 0 there with an overspend of 80, 0 here); local members and program switched off at the first pull (kept, one `WARNING` row with 3, none at the next pull, their movements never sent, the head office program the active one); the program received is the only active one and earning uses it, a new program at the head office replaces it; member change refused without the right (403 with the head office text), applied there and here with it, deactivation; refusals when unreachable (503), for a local card (409), for a card not sent yet (409), for the phone of another network card (409 with the text); link page counts and lists (errors first, paging, bad kind or status refused); heartbeat rights kept through failures and older answers; the card sequence follows a higher card received, no store code 409.
- `LoyaltyAPINetworkTest` (step 4): with the network bean, program create, update, delete, deactivate and the manual adjustment answer 409 with nothing written; member edit, toggle and link pass the network refusal and its status; without it (loyalty `LOCAL`), `LYL-000001` with no origin, the program created and the adjustment applied as before.
- `OnHeadOfficePullConditionTest` (step 4): the 7 store loyalty beans exist only with the URL and `ownership.loyalty=HEAD_OFFICE` (each annotated with `LOYALTY`), the pull beans then too, not the promotions handler; none with loyalty local, without the URL, with promotions only, on a head office.
- `HeadOfficeLinkAPITest` (step 4): `loyalty` is the 13th and last field, null without shared loyalty. `QueryParameterBindingTest`: `LoyaltyMemberCopyRepository` and `LoyaltyMovementCopyRepository` JPQL bound.
- `LoyaltyCardNumbersTest` (step 4): prefixes, next number (other prefixes, today's format and a prefix that starts like another one ignored; past 999999), the LIKE escape; `LoyaltyLedger` signs, the zero floor and overspend, the totals.
- `ApplicationModeOwnershipTest` (step 4): an explicit `ownership.loyalty=HEAD_OFFICE` without the URL refused (standalone and ERP flags, blank URL), accepted with it; the 4 profiles keep loyalty `LOCAL`; `isLoyaltyOwnedByHeadOffice()`; a head office keeps its own message; `headoffice.loyalty-push.interval-seconds` checked only with the URL.
- `StoreServiceTest` (step 4): rights false by default, set on create and update, kept by a `PUT` without them, in the JSON; the code `HO` refused.
- `HeadOfficeHeartbeatAPITest` (step 4): the answer carries the rights (null row: false), read by an older store as the ping fields; `recordsContactById` now expects the four keys (intended change of the answer).
- `OnHeadOfficeConditionTest`, `QueryParameterBindingTest` (step 4): `HoLoyaltyService`, `HoLoyaltyReceiver`, `HeadOfficeLoyaltyAPI` on a head office only; `LoyaltyMemberRepository` and `LoyaltyProgramRepository` JPQL bound.
- `HoCatalogueServiceTest` (step 6): backfill of families, sub-families, items and barcodes for every store, by codes, one number each; no stock, cost, franchise, ERP or image field and no id; `TAX_STAMP` and its barcodes never sent; an item's save sends it and its barcodes, a barcode of an inactive item sent inactive; price per store (no list, a list with the item, a list without it, an empty list); a pack's active components sorted; code change refused for an item, family, sub-family, a barcode value change removes the old value, a code over 90 refused; a deleted item removed with its barcodes and its list lines; a data import of 1,203 codes recorded in chunks, blank and too long skipped; the next start adds nothing. `support/InMemoryCatalogue` holds the catalogue tables of one installation.
- `HoPriceListServiceTest` (step 6): a line created, changed, deleted reaches the stores on the list only with their price (same price: nothing); a list without a store sends nothing; a store's list change sends the old and new list's items to that store only; a used list cannot be deactivated or deleted, unused: deleted with its lines; code rules; lines all or none.
- `CopiesDownFeedTest` (step 6): the chunked backfill of 1,234 promotions (every store, lists, a store created later) and 777 loyalty records gives exactly the rows and numbers of one `recordChange` per record; the next start adds nothing; every code once in order through pages of 100.
- `StoreServiceTest`, `HeadOfficeHeartbeatAPITest`, `ZZDataInitializerRolesTest`, `QueryParameterBindingTest` (step 6): the two rights (false by default, kept when absent, in the heartbeat answer as the 7th and 8th keys), the list ignored by the generic `PUT` and refused without price lists; the 25th permission; `HoPriceListLineRepository`, `StoreRepository`, the item, family, sub-family, barcode and sales price repositories bound.
- `OnHeadOfficeCatalogueConditionTest` (step 6): store catalogue beans only on a standalone store with the URL and an explicit `HEAD_OFFICE`; never on a franchise customer, with or without the URL (derived `HEAD_OFFICE`, the job without a catalogue handler as before); the startup refusals (no URL, franchise customer, franchise admin, ERP); head office catalogue and price lists only on a head office without an ERP.
- `CatalogueRoundTripTest` (step 6): the real head office feed to the real store handler and writer: created (codes resolved, origin, price, no stock); a local item of the same code taken over (stock, costs, image kept; other local items untouched); deactivated and deleted give inactive, never deleted, a local code untouched; a missing family waits and applies by itself; own price kept across a pull with the head office price beside, given back with one `WARNING` row when the right goes off; a barcode moved from a local item and an old `item.barcode` field cleared, one `WARNING` row, once; unchanged copies write nothing; price list; pack components.
- `StoreCatalogueGuardTest` (step 6): head office records consult-only; purchase right off (never received) and on (head office item in a purchase line refused, head office codes); rights saved and kept after a restart and through an older answer; own price rules; imports and sales prices; the status block and the startup count.
- `ItemAPICatalogueGuardTest` (step 6): the real `ItemAPI` with the guard (409 on edit, delete and pack flag of a head office item; create needs the right and a free code; own price endpoints) and without it (as before: own price 404, every item edited, own price and head office price kept on update).
- `HeartbeatJobTest` (step 6): the rights of an ONLINE answer saved, kept through a failure and an older answer. `AppConfigAPITest`: `catalogueFromHeadOffice` last, true only with the setting, false for a franchise customer with the URL. `HeadOfficeLinkAPITest`: `catalogue` is the 14th and last field. `ApplicationModeOwnershipTest`, `OnHeadOfficePullConditionTest`: an explicit catalogue `HEAD_OFFICE` is now checked (intended change).
- `ItemStockAdjustmentTest` (step 7A, decision 6): `adjust-stock` changes the quantity as before (one update) and writes one `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` movement with the reason; refusals (delta 0 or missing, unknown item, ERP mode) write nothing.
- `HeadOfficeWarehouseTest` (task 7A.1): a purchase at a head office raises its stock with a `PURCHASE_RECEPTION` movement and the last costs, a stock adjustment writes its movement, neither records a CATALOGUE change; a head office with an ERP keeps its 403s (purchases, history, vendors, purchase invoices).
- `HoDeliveryServiceTest` (task 7A.2): drafts (lines numbered, snapshots, no number, no stock, nothing for the stores), every draft rule with its message, a store reporting another supply owner (409) or none (accepted), edit and delete of a draft and 409 once sent; validation (`BL-000001`, `SENT`, stock out once with one `DELIVERY_OUT` per line, one change row for its store only, a second validation 409 with nothing moved, the next number); stock not sufficient (409 listing each short item, all or nothing, no number used); `ALLOW_NEGATIVE_STOCK`; the copy by codes for its store only (C gets nothing, also when it asks for the code); the startup backfill; no CATALOGUE change; the list filters and paging. `support/InMemoryStock` (stock updates with the rules of the native queries, real `StockService` and `StockMovementService`), `headoffice/service/InMemoryDeliveries`.
- `SupplyRoundTripTest` (tasks 7A.3, 7A.4): the real head office and the real store B through `MockRestServiceServer` (`support/InMemoryReceivedDeliveries`): a BL reaches B only, lines resolved; B confirms 48 of 50 and B002 as sent (stock up once, two `DELIVERY_IN`), sent up, `RECEIVED` there with the quantities, the store's note and user, −2 shown, the head office stock unchanged by the reception; a second confirmation 409 with nothing moved; head office stopped (stock up at once, the confirmation `PENDING` with no attempt, one failure row for two cycles; back: `RECEIVED`); an answer lost after it was applied (sent again, accepted, nothing changes); an item not in B (its line waits, the confirmation goes up, the retry reports it, then its stock goes in once); a store's own item with the same code never used; more than sent (+2); reception rules; the head office receiver's rules (another store, unknown, no number, lines not matching, quantity missing, a line missing, same again accepted, other quantities rejected); the link counts and the list.
- `SupplyRoundTripTest` (task 7A.5): stock up: every item the first time (a null stock as 0, own items flagged, the store clock), then only the item that changed, nothing when nothing changed, a deleted item removed there and its row here; head office stopped: nothing marked, sent once back; the head office page (its items without the tax stamp, its stock and each store's, the stores with their last push, one store, search, an unknown store 400) and the stores' own items. `support/InMemoryNetworkStock`.
- `HoSupplyPriceServiceTest` (step 7B, part 1): PRICE_LIST mode (the supply list line, else the base price, else none, to the millime); PERCENT_OFF mode (the selling list line or base price minus the percentage, 409 without a percentage); base supply prices set, changed, deleted, all or none, the page without the tax stamp or services, never a change for a store; price list kinds (created, listed by kind, final, a supply line records nothing, assignment by kind both ways, a used supply list cannot be deleted); the store invoicing settings (create, update, kept when absent, blank cleared, limits); the lines of a supply list show the base supply price (null when none), a selling list the selling price. `support/InMemoryCatalogue` has `ho_item_supply_price`.
- `HoSupplyInvoiceServiceTest` (step 7B, part 2): grouped rhythm (preview writing nothing; two received BLs on the confirmed quantities at the supply price with each item's VAT, a line received 0 left out, totals to the millime, `FHO-2026-000001`, BLs `INVOICED`, buyer and seller copied, the `INV:` record for its store only, no id); every refusal with nothing written; a preview with an item without a supply price (its lines in place by BL, flagged, without a price, totals of the priced lines only, no flag in a priced line's JSON); rhythm `PER_BL` (invoiced at the confirmation; a missing price keeps the BL received with its reason and the confirmation accepted; then invoiced by hand; a store not invoiced untouched); the tax stamp off and on (`SUPPLY_INVOICE_TAX_STAMP_MILLIMES`, 1000 by default then 600, VAT 0; the till stamp setting not read); nothing received (409, also with the stamp and in the preview; left out of `/to-invoice`; `PER_BL` no invoice and no note); invoice dates (future and before the last invoice refused, the preview too); percentage mode; paid and unpaid, list filters, balances; one sequence per year. `headoffice/service/InMemoryInvoices`.
- `SupplyInvoiceRoundTripTest` (step 7B, part 3): the real head office and store B over the copies down: a per-BL invoice arrives once as a purchase invoice (vendor `HEAD_OFFICE` created once with the seller, origin `HEAD_OFFICE`, totals and lines copied, notes with the BL), the cost of the head office item (last costs and `costPrice`), the BL numbered, pulled again from the start: nothing new; a second invoice with the tax stamp (the store's `TAX_STAMP` item, no cost); an item not here or only the store's own: a line without item, own cost kept, the tracking information; the head office vendor consult-only through `VendorAPI` (create with its code, update, delete, taking its code: 409; another vendor goes on). `OnHeadOfficeSupplyConditionTest`: `SupplyInvoiceWriter` and `SupplyVendorGuard` among the supply beans.
- `OnHeadOfficeSupplyConditionTest` (step 7A): the five store beans only on a standalone store with the URL, the catalogue and an explicit supply from the head office; never with supply `LOCAL` or `ERP`, the catalogue alone, loyalty alone, the 4 profiles, a head office; never on a franchise customer, with or without the URL (its `FranchiseSupplyReceptionService` still registered); the startup refusals (no URL, franchise customer, franchise admin, ERP, no catalogue from the head office, a bad `headoffice.supply-push.interval-seconds` with the URL only); the receiver only on a head office without an ERP.
- `OnHeadOfficeCatalogueConditionTest`, `QueryParameterBindingTest`, `ZZDataInitializerRolesTest`, `AppConfigAPITest`, `HeadOfficeLinkAPITest` (step 7A): `HoDeliveryService`, `HoDeliveryAPI`, `HoNetworkStockService` and `HoNetworkStockAPI` on a head office without an ERP only; `HoDeliveryRepository`, `HoNumberSequenceRepository`, `ReceivedDeliveryRepository`, `StockCopyRepository`, `HoStoreStockRepository` bound (a `Boolean` sample added); 34 head office permissions, and a store whose goods come from the head office gets `read:admin-holink-deliveries` on a new database and at the next start of an existing one (only that one, saved once, then nothing); `/config` `supplyFromHeadOffice` last, true only with the setting, false for a franchise customer with the URL; the link status `supply` is the 15th and last field, null without the setting.
- Not covered by L1 (checked at L2 on the pair): the JPQL against SQL Server, the transaction timeouts, the real timer, the head office endpoints through the `/ho/**` chain. The JPQL and the entity mappings were translated with Hibernate (SQL Server 2012 dialect, no database) during the tasks.
- Frontend (task 1.5): eslint on the changed files; a Node script (not committed) for the route guard with and without the link, the `appConfig` mutation, getter and fetch (true, false, absent, failure), "x min ago" and the status badges, the wiring of the five points and the 75 i18n keys in en, fr and ar; a build with the eslint plugin skipped (the production build stops on four `console` statements that were already there before task 1.5, in `Home.vue`, `Login.vue` and `store/app-config/index.js`).
- Not covered by L1 (needs a started context): the chain wiring itself (store installation answers 401, a JWT is not read on `/ho/**`, other paths unchanged). Checked by the L2 table under "Store API". Also the real timer (first heartbeat after 15 s), timeouts on a real network and the bulk update on SQL Server: L2 table under "Connect a store".
- Frontend: no test runner; the guard, the home helper, the menu filter, the Network group and the Roles page filter are checked with a Node script during the task, and by L2.
- Frontend (task 1.6): eslint on the changed files and `npm run build` (the eslint plugin on), both clean. A Node script (not committed, 274 checks) loads the real router, with `.vue` files and the JWT module stubbed:
  - **Store:** the new router against the router of the previous commit. For 4 users × 16 configurations (standalone, pricing group, head office link, license) × 70 targets (4,480 navigations), the outcome is the same everywhere except the head office paths. `/headoffice/...` now goes home (a cashier then goes on to the POS) instead of the 404 page, and `/admin/headoffice/stores` is now the 404 page. Store route definitions and store menu entries are unchanged.
  - **Head office:** each store route goes to its twin or home, with params, query and hash kept. Common pages stay. Each head office route opens. Without the permission the user gets not-authorized. License blocked: the license screen, with the company information twin still allowed. Not standalone: data import goes home. No cashier session check.
  - **Menu:** every route has one link, with the route's permission, in both menu shapes. The search entries, quick links and Roles groups are checked, and the frontend permission list equals the backend one.
  - **Also:** the i18n keys in en, fr and ar; the layout from `nodeType`, including a `/config` failure; the popup mixin (messages, skipped cases, listener removed); the wiring, with `LayoutVertical.vue` unchanged.

**L2 checks (task 1.6)** (the pair on this PC):

| Case | Head office (8081) | Store (8080) |
|---|---|---|
| Log in as admin | Menu on top: Home, Network, Catalogue, Customers & loyalty, Settings; home quick links to head office pages | Vertical menu exactly as before; no Network group |
| Each menu entry | Opens the page under `/headoffice/...` | — |
| Type a store URL, e.g. `/admin/customers?x=1`, `/pos/items`, `/admin/statistics` | `/headoffice/customers?x=1`; head office home; head office home | Store pages as before |
| Type `/headoffice/items` | Items | Store home |
| Arabic | Menu mirrored, dropdowns open to the left | As before |
| Window below 1200 px | Burger opens the head office menu on the side | As before |
| Search bar | Only head office pages | As before |
| Roles page | Only the head office permissions, five groups | As before, no head office permission |
| An API error (e.g. save a duplicate) | Error popup shown | As before |
