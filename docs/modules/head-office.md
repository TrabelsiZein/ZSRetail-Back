# Head Office Module

**Status**: in progress. Task 1.1 done: installation type, `headoffice-dev` profile, guards. Task 1.2 done: stores list and API keys. Task 1.3 done: store key filter on `/ho/**`, `GET /ho/ping`. Task 1.4 done: the store's heartbeat to the head office (`POST /ho/heartbeat`, head office link on the store). Task 1.5 done: computed status on the Stores page, "Head office link" page on the store. Task 1.6 done: separate head office routes and menu, horizontal layout on a head office. Target model and steps: `docs/roadmap/head-office-design.md` and `docs/roadmap/head-office-plan.md`.

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

The home page is the store's `Home.vue`. Its sales cards show zero on a head office until step 2; its quick links come from the head office menu.

**Menu** (`src/navigation/headoffice/index.js`): Home · Network · Catalogue · Customers & loyalty · Settings.
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

**Thread**: `HeadOfficeHeartbeatScheduler` starts on `ApplicationReadyEvent`: first heartbeat 15 s later, then every interval (fixed delay, counted from the end of the previous call). It runs on its own thread `ho-link-1`, not with `@Scheduled`: Spring Boot's default scheduler has one thread (`scheduling-1`) shared by `ErpSyncScheduler` and `FranchiseSalesPushScheduler`, so a call blocked up to 15 s would delay them, and a long ERP job would hold the heartbeat back. The thread pool is deliberately not a bean: a `TaskScheduler` bean would replace Spring Boot's default one and move the existing jobs onto it. Only this thread calls the head office; no request, sale or session waits for it.

**Log** (store): one INFO line at start (`Head office link: heartbeat to <url> every <n> s`), one INFO line per state change (e.g. `Head office link: ONLINE -> OFFLINE (head office unreachable (ConnectException: Connection refused))`), DEBUG while the state stays the same. The key is never written in a log line.

### Head office link page: the store side (task 1.5)
**API**: `HeadOfficeLinkAPI`, `/admin/holink` (JWT, like the other admin APIs), `@ConditionalOnHeadOfficeLink`: without `headoffice.url` (and on a head office) these URLs answer 404. Nothing in the selling path calls them.

| Request | Answer (`HeadOfficeLinkStatusDTO`) |
|---|---|
| `GET /status` | `{state, message, lastAttempt, lastSuccess, serverTime, headOfficeUrl, storeCode, intervalSeconds}`: the in-memory status, the URL without trailing slashes, `DEFAULT_LOCATION` read now (null when empty or unreadable), the interval. Never the key |
| `POST /check` | Runs one heartbeat now and answers the status after it. The heartbeat runs on `ho-link-1` like every heartbeat (`HeadOfficeHeartbeatScheduler.checkNow`), queued behind one in progress, waited for up to 30 s; when the wait ends first, or before the heartbeat thread is started, the current status is answered and no call is made from the request thread |

**`GET /config`** gets `headOfficeLinked` (task 1.5), last field: true when `headoffice.url` is set (`ApplicationModeService.isHeadOfficeLinked()`, same check as the condition). The frontend store keeps it as `appConfig/isHeadOfficeLinked` (default false, also when `/config` fails).

**Frontend**: page `src/views/admin/holink/HeadOfficeLinkStatus.vue`, route `admin-holink-status` (`/admin/holink/status`, `meta.resource` checked by CASL), menu **Settings** → **Head office link** (last entry).
- Shows the state as a badge with a translated label for each of the six states (PENDING grey, ONLINE green, OFFLINE and REFUSED red, ERROR and NOT_CONFIGURED orange), the backend message as a detail line (English, as sent), last success, last attempt, the head office URL, the store code (or "not set" when `DEFAULT_LOCATION` is empty), and a **Check now** button (disabled while the check runs). A failed `DEFAULT_LOCATION` read is `ERROR` with its own message, not a separate state.
- Exists only when `headOfficeLinked` is true: otherwise the route is redirected to `home`, the menu entry is hidden and the Roles page does not list the permission (`HEAD_OFFICE_LINK_ROUTES`, `HEAD_OFFICE_LINK_PERMISSIONS` in `src/navigation/head-office.js`). A head office is never linked.

**Permission** `read:admin-holink-status` ("Lien siège", Roles page group "Paramètres & Outils"), ADMIN only by default. Seeded (`ZZDataInitializer.HEAD_OFFICE_LINK_ADMIN_PERMISSIONS`) when the ADMIN role is created on a store that has `headoffice.url`; the 4 profiles without the URL seed exactly the roles of before. **A store database whose ADMIN role already exists** (every existing install, and a store linked after its first start) does not get it: on that store, once `headoffice.url` is set, open the Roles page, tick "Lien siège" for ADMIN, save, then log out and in (abilities are built at login). No startup top-up.

### Connect a store
1. At the head office, **Network → Stores**, create the store with **code = the store's `DEFAULT_LOCATION`** (General Setup of the store). For an ERP store that is its NAV location code.
2. Copy the key from the dialog: it is shown once (a lost key is replaced with regenerate-key).
3. In the store's properties file (the profile it runs with), set `headoffice.url` (the head office base URL, with `/zsretail/api`) and `headoffice.api-key` (the key). In `application-standalone-dev.properties` and `application-dynamics-dev.properties`: uncomment the two `headoffice.*` lines at the end and replace `PASTE_KEY_HERE` with the key.
4. Restart the store. About 15 s after the start its log shows `Head office link: PENDING -> ONLINE`; the head office Stores page shows the store's last contact and version.

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
- **Different data, new page.** What exists only on a head office (stores list, tickets / sessions / returns of the stores, shipments, the `/ho/**` endpoints) is new code in its own folder:
  - backend: feature package `com.digithink.zsretail.headoffice`, with the same sub-packages as `erp/` (`controller`, `service`, `model`, `repository`, `dto`, `scheduler`);
  - frontend: `src/views/admin/headoffice/` (admin pages of a feature live under `views/admin/<feature>`, like `views/admin/franchise`), routes `admin-headoffice-*` under `/headoffice/...` in `src/router/headoffice-routes.js` (task 1.6, "Add a page to the head office").
- Existing store pages are not modified to serve the head office: a shared page gets a head office route with `meta.twinOf`. First content: the stores list (task 1.2).
- **Store side of the link.** Code that runs on a store and calls the head office lives in `com.digithink.zsretail.holink`, never in `headoffice`; its beans carry `@ConditionalOnHeadOfficeLink` (task 1.4). Its pages live in `src/views/admin/holink/`, routes `admin-holink-*` (task 1.5).

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
  - New head office database: ADMIN gets today's permissions plus the 17 head office ones, each role saved once. `HEAD_OFFICE_ADMIN_PERMISSIONS` equals the test's copy of the frontend list, without `read:admin-headoffice`.
  - Existing head office ADMIN: it receives the missing permissions once and keeps its others (also `read:admin-headoffice`); the next start saves nothing; RESPONSIBLE and POS_USER are untouched.
  - Stores: the 4 profiles seed exactly today's roles. With and without `headoffice.url`, an existing role is never saved or changed.
  - Task 1.5: with `headoffice.url`, ADMIN also gets `read:admin-holink-status` on the 4 profiles; a head office never does.
- `StoreServiceTest`: key generated and stored only as a hash; key check (good, bad, inactive store, unknown code, missing key) and its reason from `check` (an inactive store with a wrong key is `WRONG_KEY`); regenerate; code trimmed, uppercase, unique and final; `lastContact`, `appVersion` and the hash never written by the client (service and JSON); delete refused after a contact.
- `OnHeadOfficeConditionTest`: `StoreService`, `StoreAPI`, `StoreApiKeyFilter`, `HeadOfficePingAPI` and `HeadOfficeHeartbeatAPI` are registered on a head office (any case and spacing of `node.type`) and not on a store; an unknown value fails like at startup. The `/ho/**` chain is registered on both, with an optional filter, a head-office-only servlet registration and order 0. Uses a bare bean registry, no context started.
- `StoreApiKeyFilterTest`: good key (store principal, authority `HO_STORE`); wrong key, missing headers (none, one, blank: no store lookup), unknown store, inactive store: the same 401 and body, one WARN line with reason, code, remote address and path, never the key; control characters kept out of the log; paths outside `/ho/**` untouched even with a valid key; a user JWT alone refused, and dropped when a valid store key comes with it. Real `StoreService` over an in-memory repository, log captured with a Logback `ListAppender`.
- `HeadOfficePingAPITest`: code and server time format, the store not changed, the JSON has only `storeCode` and `serverTime`.
- `HeadOfficeHeartbeatAPITest` (task 1.4): `lastContact` and `appVersion` written by id through the two-column update; hash, code, name, kind, active and `updatedAt` unchanged; the detached principal (changed in memory) never saved; `lastContact` and `serverTime` the same instant; version trimmed, blank, empty, null or no body gives null, longer than 255 cut. Real `StoreService` over an in-memory repository.
- `OnHeadOfficeLinkConditionTest` (task 1.4): no URL, empty or blank URL gives false, a URL gives true; `HeadOfficeClient`, `HeadOfficeLinkStatus`, `HeadOfficeHeartbeatScheduler` and `HeadOfficeLinkAPI` (1.5) registered only with a URL and all carry the annotation. Bare bean registry.
- `HeadOfficeClientTest` (task 1.4): every line of the state table (200; 401; 402; 403, 404, 500, 503, 204, 302; HTML, broken JSON, empty body; connection refused, unknown host, timeout; `DEFAULT_LOCATION` null, empty, blank and unreadable with no call); both headers and the body on every call, the key trimmed; the store code read at each call; trailing slashes; the 5 s / 10 s timeouts of the own `RestTemplate`. Transport: Spring's `MockRestServiceServer` on a plain `RestTemplate`, so its own error handling is exercised.
- `HeadOfficeHeartbeatSchedulerTest` (task 1.4): `PENDING` at start; a failure keeps the last success and its head office time; one INFO line per state change, DEBUG otherwise; `start` creates the single thread `ho-link-1` and makes no call at once.
- `StoreStatusTest` (task 1.5): INACTIVE (also with a recent contact), NEVER, ONLINE up to and including the threshold, OFFLINE 1 ms after it, a contact in the future ONLINE, another threshold applied; the list item JSON is the store's JSON plus `status` and `secondsSinceContact`, never the hash; read by id.
- `HeadOfficeLinkAPITest` (task 1.5): `GET status` has exactly the 8 fields and never the key (also after a refusal); `POST check` before the thread is started makes no call; after it, the heartbeat runs on `ho-link-1` and the answer is the new state (ONLINE, then REFUSED with the last success kept); `DEFAULT_LOCATION` empty gives NOT_CONFIGURED and a null store code.
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
