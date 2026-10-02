# Head Office Module

**Status**: in progress. Task 1.1 done: installation type, `headoffice-dev` profile, guards. Task 1.2 done: stores list and API keys. Target model and steps: `docs/roadmap/head-office-design.md` and `docs/roadmap/head-office-plan.md`.

### Overview
- Two installation types, same WAR: a **store** sells; a **head office** manages several stores and never sells (no cashier session, no ticket).
- Set by `node.type=STORE` (default) or `node.type=HEAD_OFFICE`. Not a user role: `ADMIN`, `RESPONSIBLE`, `POS_USER` and the role tables are unchanged.
- A head office runs as its own instance with its own database, possibly on the same machine as a store.

### Installation type
- Backend: `ApplicationModeService.getNodeType()` and `isHeadOffice()`, resolved at startup by `config/NodeOwnership.java` (see `docs/deployment-modes.md`, "Ownership model").
- Head office only beans: `@ConditionalOnHeadOffice` (`config/OnHeadOfficeCondition.java`) reads `node.type` with the same parsing as the startup (`NodeOwnership.nodeTypeOf`: trimmed, case-insensitive, `STORE` when absent). On a store such a bean is not created, so its endpoints answer 404.
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
| `lastContact`, `appVersion` | Null until the first contact (written from task 1.3 on). Read-only in JSON, never taken from the client |
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

**API key**: generated by the server with `SecureRandom`, 32 bytes, URL-safe Base64 without padding (43 characters). Returned only by the creation and by regenerate-key, stored only as its SHA-256 hash. The page shows it once, with a copy button; it goes in the store's settings (`headoffice.api-key`, task 1.4). A lost key is replaced with regenerate-key.

**Key check for task 1.3**: `StoreService.authenticate(code, key)` returns the active store with that code (trimmed, case-insensitive) whose hash matches the key, compared with `MessageDigest.isEqual` (constant time). An unknown code is compared against a fixed hash too, so the answer time does not tell which codes exist.

**Frontend**: page `src/views/admin/headoffice/StoresManagement.vue`, route `admin-headoffice-stores` (`/admin/headoffice/stores`, `meta.resource` checked by CASL), menu group **Network** → **Stores**.
- Columns: code, name, kind, status, last contact, version. Actions: create, edit (code read-only), deactivate / activate, regenerate key (with confirmation), delete (shown only while `lastContact` is empty).
- After create or regenerate, a dialog shows the key once, with a copy button (Clipboard API on HTTPS or localhost, otherwise copy from the selected field: plain HTTP on the LAN has no Clipboard API) and the warning that it cannot be shown again.
- On a store: the route is redirected to `home`, the Network group is hidden, and the Roles page does not list the two permissions (`HEAD_OFFICE_ONLY_ROUTES`, `HEAD_OFFICE_ONLY_PERMISSIONS` in `src/navigation/head-office.js`). On a store the Roles page and the menu are as before.
- Permissions: ADMIN only, by default. **A head office database created before 1.2** keeps its ADMIN role as it was, so the Network menu does not appear: tick "Réseau (menu)" and "Magasins du réseau" for the role on the Roles page, or recreate the database. No startup top-up.

### Convention for the head office code
- **Same data, same page.** Pages that edit data the head office owns (items, promotions, loyalty, customers, users, settings) are the existing pages, never copies.
- **Different data, new page.** What exists only on a head office (stores list, tickets / sessions / returns of the stores, shipments, the `/ho/**` endpoints) is new code in its own folder:
  - backend: feature package `com.digithink.zsretail.headoffice`, with the same sub-packages as `erp/` (`controller`, `service`, `model`, `repository`, `dto`, `scheduler`);
  - frontend: `src/views/admin/headoffice/` (admin pages of a feature live under `views/admin/<feature>`, like `views/admin/franchise`), routes `admin-headoffice-*` under `/admin/headoffice/...`.
- Existing store pages are not modified to serve the head office. First content: the stores list (task 1.2).

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

### New head office database: manual steps
1. In SQL Server, create the empty database: `CREATE DATABASE pos_headoffice`. Hibernate creates the tables (`ddl-auto=update`); it does not create the database.
2. Start the backend with `headoffice-dev`. `AppVersionGuard` skips the check on an empty `APP_VERSION`; `ZZDataInitializer` seeds the data above. No `db/<version>/update.sql` script is needed on a new database.
3. Start `npm run serve:headoffice` and log in as `admin` (default password set in `ZZDataInitializer.initUsers`).
4. Upload the license on the License page: until then every API except login, `/config`, `/company-info` and the license pages answers 402. The license is bound to the machine, not to the database, so this PC's license file works.
5. Check `GET http://localhost:888/zsretail/api/config`: `"nodeType":"HEAD_OFFICE"`, all owners `LOCAL`, `"salesUpstreams":[]`.

### Tests (L1)
- `ApplicationModeOwnershipTest`: head office rows (standalone and ERP flags), the startup checks, `isHeadOffice()`.
- `AppConfigAPITest`: `headoffice-dev` row, old `/config` fields unchanged.
- `CashierSessionHeadOfficeTest`: `openSession` and a new-session `save()` refused on a head office; existing session saved; the 4 store profiles open sessions as today.
- `JWTAuthenticationFilterTest`: cashier refused on a head office; admin and a user without AppRole accepted; a cashier login on the 4 store profiles answers exactly as before.
- `ZZDataInitializerUsersTest`: head office seeds `admin` only; the 4 store profiles seed admin, responsible and cashier. The "empty user table" guard in `init()` is not covered.
- `ZZDataInitializerRolesTest`: head office ADMIN gets the two Network permissions; the 4 store profiles seed exactly today's roles; an existing role is not changed.
- `StoreServiceTest`: key generated and stored only as a hash; key check (good, bad, inactive store, unknown code, missing key); regenerate; code trimmed, uppercase, unique and final; `lastContact`, `appVersion` and the hash never written by the client (service and JSON); delete refused after a contact.
- `OnHeadOfficeConditionTest`: `StoreService` and `StoreAPI` are registered on a head office (any case and spacing of `node.type`) and not on a store; an unknown value fails like at startup. Uses a bare bean registry, no context started.
- Frontend: no test runner; the guard, the home helper, the menu filter, the Network group and the Roles page filter are checked with a Node script during the task, and by L2.
