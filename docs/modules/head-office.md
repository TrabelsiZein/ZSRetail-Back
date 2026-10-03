# Head Office Module

**Status**: in progress. Task 1.1 done: installation type, `headoffice-dev` profile, guards. Task 1.2 done: stores list and API keys. Task 1.3 done: store key filter on `/ho/**`, `GET /ho/ping`. Task 1.4 done: the store's heartbeat to the head office (`POST /ho/heartbeat`, head office link on the store). Target model and steps: `docs/roadmap/head-office-design.md` and `docs/roadmap/head-office-plan.md`.

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

An explicit owner `ERP` or `LOCAL` is accepted (for example a head office that imports items from the ERP, decision D2).

### Guards (task 1.1)
Backend:
- **No cashier session.** `CashierSessionService.openSession` and `save()` of a **new** session throw `IllegalStateException("This installation is a head office: cashier sessions cannot be opened.")`. `POST /cashier-session/open` answers 400 with that plain string (shown by `OpenSession.vue`); the generic `POST /cashier-session` answers 500 with the same string. Existing sessions are saved as before.
- Every selling endpoint that needs an open session (`process-sale`, `save-pending`, `complete-pending`, `cancel-pending`, `process-return`) is therefore refused too, with today's "No open cashier session found".
- **No cashier login.** `JWTAuthenticationFilter.successfulAuthentication`: when `isHeadOffice()` and the user's `AppRole.isPosRole` is true (the flag the login response sends as `isPosRole`), the answer is 403 `{"code":403,"msg":"This installation is a head office: cashier accounts cannot sign in here."}` and no token. `Login.vue` shows `msg`. On a store the check is not evaluated.
- Known gap, out of task 1.1: the generic CRUD endpoints (`POST`/`PUT` on `/sales-header`, `/payment`, `/sales-line`, `/return-header`, `/return-voucher`) are not guarded, on a head office as on a store.

Frontend (one constant: `src/navigation/head-office.js`, `HEAD_OFFICE_HIDDEN_ROUTES`):
- The six POS routes carry `meta.pos: true`.
- Router guard (`src/router/index.js`), before the cashier session check: on a head office, a POS route or a hidden page redirects to `home`. A head office never calls `/cashier-session/current`.
- `getHomeRouteForLoggedInUser` (`src/auth/utils.js`) returns `home` on a head office; `Login.vue` uses it instead of sending cashier roles straight to the POS.
- The menu (`VerticalNavMenu.vue`), the search bar (`SearchBar.vue`) and the home quick links (`Home.vue`) leave out the hidden pages.

### Hidden pages on a head office
Menu entry hidden and URL redirected to `home`:

| Group | Pages (route names) |
|---|---|
| Replaced in step 2 by new head office pages fed by the consolidated copies, with a store filter and a store column | Tickets history (`tickets-history`), Sessions history (`admin-sessions-history`), Returns (`admin-returns`) |
| Waiting for the head office dashboards | Statistics (`admin-statistics`), Reports: sales, sessions, promotions (`admin-report-sales`, `admin-report-sessions`, `admin-report-promotions`) |
| Not relevant on a head office | Locations (`admin-locations`: a head office shows stores, never locations), Sales invoices (`admin-invoices`), Payment methods (`admin-payment-methods`), Badge scan log (`admin-badge-scan-history`), Session dashboard (`admin-sessions`, `responsible-sessions`, not in the menu) |

Everything else stays: items, families, prices, promotions, customers, loyalty, purchases, stock reports, users, roles, settings.

### Seed on a new database
- `ZZDataInitializer.initUsers` (runs only when the user table is empty): on a head office only the `admin` account is created; the default responsible and cashier accounts are not. The three roles are still created. On a store the seed is unchanged. Existing users are never deleted.
- Everything else is seeded as on a standalone store (payment methods, General Setup keys, passenger customer, tax stamp item, company info, `APP_VERSION`).
- Roles (`ensureDefaultRoles`): on a head office the ADMIN role is created with today's permissions plus `read:admin-headoffice` and `read:admin-headoffice-stores` (the Network menu). On a store the three roles are created exactly as before. A role that already exists is never changed.

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
| `GET /`, `GET /{id}`, `GET /count`, `GET /{id}/exists` | Generic `_BaseController` reads; no hash in the JSON |
| `GET /findByField` | Generic search; 400 on `apiKeyHash` |
| `POST /` `{code, name, kind, active}` | 201 `{store, apiKey}`. 400 when code or name is missing, 409 when the code exists. `lastContact`, `appVersion` and any hash sent are ignored |
| `PUT /{id}` | Applies `name`, `kind`, `active` (each when sent). 400 when the code differs from the stored one |
| `POST /{id}/regenerate-key` | 200 `{store, apiKey}`; the old key stops working at once |
| `DELETE /{id}` | 204 before the first contact; 409 "This store has already contacted the head office: deactivate it instead." afterwards |

**API key**: generated by the server with `SecureRandom`, 32 bytes, URL-safe Base64 without padding (43 characters). Returned only by the creation and by regenerate-key, stored only as its SHA-256 hash. The page shows it once, with a copy button; it goes in the store's settings (`headoffice.api-key`, see "Connect a store"). A lost key is replaced with regenerate-key.

**Key check**: `StoreService.check(code, key)` returns a `KeyCheck`: the outcome `ACCEPTED`, `UNKNOWN_STORE`, `WRONG_KEY` or `INACTIVE_STORE`, and the store when accepted. The code is trimmed and compared without regard to case; the key's hash is compared with `MessageDigest.isEqual` (constant time). An unknown code is compared against a fixed hash too, so the answer time does not tell which codes exist. An inactive store is reported as such only when its key is right (otherwise `WRONG_KEY`). `authenticate(code, key)` keeps its contract (the accepted store, or empty) and uses `check`.

**Frontend**: page `src/views/admin/headoffice/StoresManagement.vue`, route `admin-headoffice-stores` (`/admin/headoffice/stores`, `meta.resource` checked by CASL), menu group **Network** → **Stores**.
- Columns: code, name, kind, status, last contact, version. Actions: create, edit (code read-only), deactivate / activate, regenerate key (with confirmation), delete (shown only while `lastContact` is empty).
- After create or regenerate, a dialog shows the key once, with a copy button (Clipboard API on HTTPS or localhost, otherwise copy from the selected field: plain HTTP on the LAN has no Clipboard API) and the warning that it cannot be shown again.
- On a store: the route is redirected to `home`, the Network group is hidden, and the Roles page does not list the two permissions (`HEAD_OFFICE_ONLY_ROUTES`, `HEAD_OFFICE_ONLY_PERMISSIONS` in `src/navigation/head-office.js`). On a store the Roles page and the menu are as before.
- Permissions: ADMIN only, by default. **A head office database created before 1.2** keeps its ADMIN role as it was, so the Network menu does not appear: tick "Réseau (menu)" and "Magasins du réseau" for the role on the Roles page, or recreate the database. No startup top-up.

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

**Status**: `HeadOfficeLinkStatus`, in memory only (no table; `PENDING` again after a restart): state, last attempt and last success (store clock), last message, head office `serverTime` of the last success. A failure keeps the last success. Not exposed yet: task 1.5 adds the endpoint, the "Head office link" status card and online/offline on the Stores page.

**Thread**: `HeadOfficeHeartbeatScheduler` starts on `ApplicationReadyEvent`: first heartbeat 15 s later, then every interval (fixed delay, counted from the end of the previous call). It runs on its own thread `ho-link-1`, not with `@Scheduled`: Spring Boot's default scheduler has one thread (`scheduling-1`) shared by `ErpSyncScheduler` and `FranchiseSalesPushScheduler`, so a call blocked up to 15 s would delay them, and a long ERP job would hold the heartbeat back. The thread pool is deliberately not a bean: a `TaskScheduler` bean would replace Spring Boot's default one and move the existing jobs onto it. Only this thread calls the head office; no request, sale or session waits for it.

**Log** (store): one INFO line at start (`Head office link: heartbeat to <url> every <n> s`), one INFO line per state change (e.g. `Head office link: ONLINE -> OFFLINE (head office unreachable (ConnectException: Connection refused))`), DEBUG while the state stays the same. The key is never written in a log line.

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

### Convention for the head office code
- **Same data, same page.** Pages that edit data the head office owns (items, promotions, loyalty, customers, users, settings) are the existing pages, never copies.
- **Different data, new page.** What exists only on a head office (stores list, tickets / sessions / returns of the stores, shipments, the `/ho/**` endpoints) is new code in its own folder:
  - backend: feature package `com.digithink.zsretail.headoffice`, with the same sub-packages as `erp/` (`controller`, `service`, `model`, `repository`, `dto`, `scheduler`);
  - frontend: `src/views/admin/headoffice/` (admin pages of a feature live under `views/admin/<feature>`, like `views/admin/franchise`), routes `admin-headoffice-*` under `/admin/headoffice/...`.
- Existing store pages are not modified to serve the head office. First content: the stores list (task 1.2).
- **Store side of the link.** Code that runs on a store and calls the head office lives in `com.digithink.zsretail.holink`, never in `headoffice`; its beans carry `@ConditionalOnHeadOfficeLink` (task 1.4).

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
- `ApplicationModeOwnershipTest`: head office rows (standalone and ERP flags), the startup checks, `isHeadOffice()`. Task 1.4: a store with `headoffice.url` and a key is accepted on the 4 profiles; refused on a head office, without a key, with an interval below 1 or not a number; without the URL the other `headoffice.*` keys are not checked.
- `AppConfigAPITest`: `headoffice-dev` row, old `/config` fields unchanged.
- `CashierSessionHeadOfficeTest`: `openSession` and a new-session `save()` refused on a head office; existing session saved; the 4 store profiles open sessions as today.
- `JWTAuthenticationFilterTest`: cashier refused on a head office; admin and a user without AppRole accepted; a cashier login on the 4 store profiles answers exactly as before.
- `ZZDataInitializerUsersTest`: head office seeds `admin` only; the 4 store profiles seed admin, responsible and cashier. The "empty user table" guard in `init()` is not covered.
- `ZZDataInitializerRolesTest`: head office ADMIN gets the two Network permissions; the 4 store profiles seed exactly today's roles; an existing role is not changed.
- `StoreServiceTest`: key generated and stored only as a hash; key check (good, bad, inactive store, unknown code, missing key) and its reason from `check` (an inactive store with a wrong key is `WRONG_KEY`); regenerate; code trimmed, uppercase, unique and final; `lastContact`, `appVersion` and the hash never written by the client (service and JSON); delete refused after a contact.
- `OnHeadOfficeConditionTest`: `StoreService`, `StoreAPI`, `StoreApiKeyFilter`, `HeadOfficePingAPI` and `HeadOfficeHeartbeatAPI` are registered on a head office (any case and spacing of `node.type`) and not on a store; an unknown value fails like at startup. The `/ho/**` chain is registered on both, with an optional filter, a head-office-only servlet registration and order 0. Uses a bare bean registry, no context started.
- `StoreApiKeyFilterTest`: good key (store principal, authority `HO_STORE`); wrong key, missing headers (none, one, blank: no store lookup), unknown store, inactive store: the same 401 and body, one WARN line with reason, code, remote address and path, never the key; control characters kept out of the log; paths outside `/ho/**` untouched even with a valid key; a user JWT alone refused, and dropped when a valid store key comes with it. Real `StoreService` over an in-memory repository, log captured with a Logback `ListAppender`.
- `HeadOfficePingAPITest`: code and server time format, the store not changed, the JSON has only `storeCode` and `serverTime`.
- `HeadOfficeHeartbeatAPITest` (task 1.4): `lastContact` and `appVersion` written by id through the two-column update; hash, code, name, kind, active and `updatedAt` unchanged; the detached principal (changed in memory) never saved; `lastContact` and `serverTime` the same instant; version trimmed, blank, empty, null or no body gives null, longer than 255 cut. Real `StoreService` over an in-memory repository.
- `OnHeadOfficeLinkConditionTest` (task 1.4): no URL, empty or blank URL gives false, a URL gives true; `HeadOfficeClient`, `HeadOfficeLinkStatus` and `HeadOfficeHeartbeatScheduler` registered only with a URL and all carry the annotation. Bare bean registry.
- `HeadOfficeClientTest` (task 1.4): every line of the state table (200; 401; 402; 403, 404, 500, 503, 204, 302; HTML, broken JSON, empty body; connection refused, unknown host, timeout; `DEFAULT_LOCATION` null, empty, blank and unreadable with no call); both headers and the body on every call, the key trimmed; the store code read at each call; trailing slashes; the 5 s / 10 s timeouts of the own `RestTemplate`. Transport: Spring's `MockRestServiceServer` on a plain `RestTemplate`, so its own error handling is exercised.
- `HeadOfficeHeartbeatSchedulerTest` (task 1.4): `PENDING` at start; a failure keeps the last success and its head office time; one INFO line per state change, DEBUG otherwise; `start` creates the single thread `ho-link-1` and makes no call at once.
- Not covered by L1 (needs a started context): the chain wiring itself (store installation answers 401, a JWT is not read on `/ho/**`, other paths unchanged). Checked by the L2 table under "Store API". Also the real timer (first heartbeat after 15 s), timeouts on a real network and the bulk update on SQL Server: L2 table under "Connect a store".
- Frontend: no test runner; the guard, the home helper, the menu filter, the Network group and the Roles page filter are checked with a Node script during the task, and by L2.
