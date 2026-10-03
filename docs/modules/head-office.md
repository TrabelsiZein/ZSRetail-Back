# Head Office Module

**Status**: in progress. Task 1.1 done: installation type, `headoffice-dev` profile, guards. Task 1.2 done: stores list and API keys. Task 1.3 done: store key filter on `/ho/**`, `GET /ho/ping`. Task 1.4 done: the store's heartbeat to the head office (`POST /ho/heartbeat`, head office link on the store). Task 1.5 done: computed status on the Stores page, "Head office link" page on the store. Task 1.6 done: separate head office routes and menu, horizontal layout on a head office. Step 2 in progress: task 2.1 done (the store's tracking table and the search for documents to send, see "Sales copies"); task 2.2 done (the copies of a ticket, a return and a session closing); task 2.3 done (consolidation tables and `POST /ho/sales/*` on the head office); task 2.4 done (the store's push job with retry, counts on `GET admin/holink/status`). Task 2.5 backend done (consolidated sales API, home cards, page permissions; see "Consolidated sales API"; its pages to come); task 2.6 backend done (jobs with editable frequency and run now, exchange log; see "Head office link: jobs and exchange log"); the pages of 2.5 and 2.6 come with the frontend session. Step 3 in progress: task 3.1 done (the copies down mechanism, see "Copies down"); task 3.2 done (`origin` on `promotion` and the write guards, see `docs/modules/promotion.md`). Target model and steps: `docs/roadmap/head-office-design.md` and `docs/roadmap/head-office-plan.md`.

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
| Settings | Company information and license | `admin-headoffice-company-information` | `/headoffice/settings/company-information` | `admin-company-information` |
| Settings | General setup | `admin-headoffice-general-setup` | `/headoffice/settings/general-setup` | `admin-general-setup` |
| Settings | Users | `admin-headoffice-users` | `/headoffice/settings/users` | `admin-users` |
| Settings | Roles | `admin-headoffice-roles` | `/headoffice/settings/roles` | `admin-roles` |
| Settings | Data import (standalone only) | `admin-headoffice-data-import` | `/headoffice/settings/data-import` | `admin-data-import` |

Each page is one route: details and edits are dialogs on the page. Every other store page is absent until its step adds it: print labels, sales prices and discounts, warranty, purchases, vendors, reports, ERP and franchise pages, and the selling pages hidden in task 1.1.

The home page is the store's `Home.vue`. On a head office its sales cards read `GET admin/headoffice/dashboard/today` (task 2.5, the copies of all stores, see "Consolidated sales API") and show Today's sales and Today's returns only, each half a row: open sessions and pending tickets are always 0 there. A store still reads `GET admin/dashboard/today` and shows its four cards. Its quick links come from the head office menu: Stores, Tickets history, Sessions, Items, Promotions, Customers, Loyalty members, Users.

The three Sales pages are described under "Consolidated sales API", "Head office pages".

**Menu** (`src/navigation/headoffice/index.js`): Home · Network · Sales · Catalogue · Customers & loyalty · Settings. The Sales links use the store menu's titles (Sales history, Sessions, Returns).
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
| `lastContact`, `appVersion` | Null until the first heartbeat. Written only by `POST /ho/heartbeat` (task 1.4): head office clock; version trimmed, blank gives null, cut to 255 characters (`Store.APP_VERSION_LENGTH`). `/ho/ping` does not write them. Read-only in JSON, never taken from the admin API |
| `apiKeyHash` | SHA-256 (hex) of the API key. Never serialized (`@JsonIgnore`), never in `toString`, never logged |

**API**: `StoreAPI`, `/admin/headoffice/stores` (JWT, like the other admin APIs; outside `/ho/**`). The service and the API carry `@ConditionalOnHeadOffice`: on a store these URLs answer 404.

| Request | Answer |
|---|---|
| `GET /`, `GET /{id}` | The store's JSON as before (no hash) plus `status` and `secondsSinceContact` (task 1.5, see "Status" below); `GET /{id}` 404 when unknown |
| `GET /count`, `GET /{id}/exists` | Generic `_BaseController` reads |
| `GET /findByField` | Generic search; 400 on `apiKeyHash` |
| `POST /` `{code, name, kind, active}` | 201 `{store, apiKey}`. 400 when code or name is missing, 409 when the code exists. `lastContact`, `appVersion` and any hash sent are ignored |
| `PUT /{id}` | Applies `name`, `kind`, `active` (each when sent). 400 when the code differs from the stored one |
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

**`POST /ho/heartbeat`** (`HeadOfficeHeartbeatAPI`, head office only, task 1.4): body `{"appVersion":"1.12.0"}` (`HeadOfficeHeartbeatDTO`; a missing body counts as no version). Sets `lastContact` (head office clock) and `appVersion` on the calling store through `StoreService.recordContact`, by id with a two-column update (`StoreRepository.updateContact`): the principal is never saved, and `apiKeyHash`, `code`, `name`, `kind`, `active` and `updatedAt` are not touched. Answers like `/ho/ping`: `{"storeCode":"RS01","serverTime":"..."}`, the same instant as `lastContact`. The store side is described under "Head office link".

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

**Thread**: since task 2.6 the heartbeat is the job `HEARTBEAT` (`holink/scheduler/HeartbeatJob`), run by `LinkJobScheduler` (see "Head office link: jobs and exchange log"): first heartbeat 15 s after the start, then every frequency (saved from the page, or `headoffice.heartbeat-interval-seconds`), counted from the end of the previous call. It runs on its own thread `ho-link-1`, not with `@Scheduled`: Spring Boot's default scheduler has one thread (`scheduling-1`) shared by `ErpSyncScheduler` and `FranchiseSalesPushScheduler`, so a call blocked up to 15 s would delay them, and a long ERP job would hold the heartbeat back. The thread pool is deliberately not a bean: a `TaskScheduler` bean would replace Spring Boot's default one and move the existing jobs onto it. Only this thread calls the head office; no request, sale or session waits for it. Every job of the link (the sales push since task 2.4) runs on the same thread, so they never run at the same time.

**Log** (store): one INFO line at start for all the jobs (`Head office link: jobs on ho-link-1: HEARTBEAT every 60 s, SALES_PUSH every 60 s`), one INFO line per state change (e.g. `Head office link: ONLINE -> OFFLINE (head office unreachable (ConnectException: Connection refused))`), DEBUG while the state stays the same. The key is never written in a log line.

### Head office link page: the store side (task 1.5)
**API**: `HeadOfficeLinkAPI`, `/admin/holink` (JWT, like the other admin APIs), `@ConditionalOnHeadOfficeLink`: without `headoffice.url` (and on a head office) these URLs answer 404. Nothing in the selling path calls them.

| Request | Answer (`HeadOfficeLinkStatusDTO`) |
|---|---|
| `GET /status` | `{state, message, lastAttempt, lastSuccess, serverTime, headOfficeUrl, storeCode, intervalSeconds, pendingCount, sentCount, errorCount}`: the in-memory status, the URL without trailing slashes, `DEFAULT_LOCATION` read now (null when empty or unreadable), the interval. Never the key. Task 2.4: the three counts of `hol_sales_copy` by status (0 for a status without rows); null when the store does not copy its sales to the head office (no push job), or when the count cannot be read (the status is still answered) |
| `POST /check` | Runs one heartbeat now and answers the status after it: the "Run now" of the `HEARTBEAT` job (`LinkJobScheduler.runNow`), on `ho-link-1`, queued behind a run in progress, waited for up to 30 s; when the wait ends first, or before the thread is started, the current status is answered and no call is made from the request thread |
| `GET /jobs`, `PUT /jobs/{code}/interval`, `POST /jobs/{code}/run`, `GET /log` | Task 2.6, see "Head office link: jobs and exchange log". `intervalSeconds` in `GET /status` is the heartbeat frequency in force (saved or default) |

**`GET /config`** gets `headOfficeLinked` (task 1.5), last field: true when `headoffice.url` is set (`ApplicationModeService.isHeadOfficeLinked()`, same check as the condition). The frontend store keeps it as `appConfig/isHeadOfficeLinked` (default false, also when `/config` fails).

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

**When it runs** (store): only with `headoffice.url` set and the domain owned by the head office (`ownership.<domain>=HEAD_OFFICE`). The job and the puller carry `@ConditionalOnHeadOfficePull` (URL set and at least one domain owned by the head office, `NodeOwnership.isHeadOfficePullSet`); a domain's handler carries `@ConditionalOnHeadOfficeOwned(<domain>)` (`NodeOwnership.isOwnedByHeadOffice`). A store without `headoffice.url`, or with every domain local, has none of these beans. An explicit `ownership.promotions=HEAD_OFFICE` without `headoffice.url` stops the startup: `Missing value for property headoffice.url: required when ownership.promotions is HEAD_OFFICE ('<value>')`. A head office keeps its own message for an owner `HEAD_OFFICE`. Known case: a franchise customer (catalogue and supply derived `HEAD_OFFICE`) that also sets `headoffice.url` gets the job without a handler ("no domain to pull") until step 6.

**Head office side** (package `headoffice`, `@ConditionalOnHeadOffice`):

| Table (entity) | Content |
|---|---|
| `ho_down_sequence` (`HoDownSequence`) | One row per domain: `last_version`, the domain's last change number |
| `ho_down_change` (`HoDownChange`) | One row per (`domain`, `record_code`, `store_id`), `store_id` null = every store (also stores created later); `change_version` = the number of the record's last change for that store. Unique `uk_ho_down_change`, index `ix_ho_down_change_version` (`domain`, `change_version`) |

- `CopiesDownFeed.recordChange(domain, code, stores)`, called inside the writer's transaction (`Propagation.MANDATORY`): increments the domain's number (`update ... set last_version = last_version + 1`, the row stays locked until the commit, so one domain's numbers are given in commit order), then moves the rows of the stores concerned to it. A change of targets passes the old and the new stores together (`StoreTargets.union`): a store taken off gets the code as removed.
- "Changed" covers created, edited, activated or deactivated, deleted, and targets changed: each is one `recordChange`.
- **Cursor**: the change number, made from the head office data only; never a clock. A pull reads the domain's committed number first (the horizon), then the codes changed for the store (its rows and the every-store rows) after the cursor and up to the horizon, oldest first. A change committed during the pull has a higher number and comes with the next pull. A cursor above the horizon (head office database restored) starts again from 0.
- Startup (`ApplicationReadyEvent`): each served domain gets its sequence row, and each existing record without a change row gets one with its targets (backfill: promotions created before step 3 reach every store).
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
  - New head office database: ADMIN gets today's permissions plus the 20 head office ones (17, plus tickets, sessions and returns in task 2.5), each role saved once. `HEAD_OFFICE_ADMIN_PERMISSIONS` equals the test's copy of the frontend list, without `read:admin-headoffice`.
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
- `PromotionAPIGuardTest` (task 3.2): promotions owned by the head office: create, edit, deactivate and delete answer 409 for a local, a `LOCAL` and a `HEAD_OFFICE` promotion, nothing written, reads allowed; promotions local: a head office promotion cannot be edited, deleted or overwritten by a create with its id; local promotions as before (create, edit, deactivate, usage lock, delete refusal, delete), the origin never read from JSON and kept on update; `isPromotionsOwnedByHeadOffice` only with the URL and the owner.
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
