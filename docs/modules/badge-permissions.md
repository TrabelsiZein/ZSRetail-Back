# Till rights and badges

The design of 3.1.0 (till authorisation, steps S to E). A till right belongs to the role; a badge is a credential that
lends its owner's role rights at the till; the server checks the rights on the requests that do the actions, and logs
every pass. The history of the steps is at the end.

## The five actions

`BadgePermission` (core) is the one registry: the constant name is what the database stores and what the till sends,
`key()` the role permission, `checkedOnRequest()` whether one request does the action (step D). `GET /config` serves
it as `tillActions: [{code, key, checkedOnRequest}]` (empty on a head office, which has no till); the frontend keeps no
list of its own (labels: i18n `pos.badge.permissions.<code lower case>`).

| Code | Role permission | Where | Checked on the request |
|---|---|---|---|
| `CONSULT_CUSTOMER_LIST` | `till:consult-customer-list` | the till's Customers button, the payment page's customer chip, the customer list | no |
| `MAKE_RETURN` | `till:make-return` | the till's Returns button and the return screen | `process-return` |
| `APPLY_LINE_DISCOUNT` | `till:apply-line-discount` | the line discount dialog (till) | each manual line discount of a sale or pending request |
| `APPLY_TOTAL_DISCOUNT` | `till:apply-total-discount` | the total discount dialog (payment page) | the manual header discount of a sale or pending request |
| `CLOSE_SESSION_WITH_DISCREPANCY` | `till:close-session-with-discrepancy` | closing a session whose counted cash differs from the expected cash | the close |

## The rights are the role's

- A role holds a right when its permissions (`app_role_permission`) hold the key. The Roles page shows them in the
  "Actions de caisse" block (from `tillActions`); `GET /app-roles/permissions` lists them after the page permissions.
- They reach the till with the login's `permissions`, as the page permissions (CASL ability `till` on
  `<key without "till:">`). A role edited takes effect at the user's next login; until then the server, which reads
  the role on each request, has the last word (a 403 asks for a badge).
- A new store database gives the five rights to every role (ADMIN, RESPONSIBLE, POS_USER: `ZZDataInitializer`
  `TILL_PERMISSIONS`); a store restricts them on the Roles page. A head office has none.
- **Administrator** (step E): the user of the system role named `ADMIN` (`core.security.SystemRoles`; the frontend's
  `isAdminUser` in `@/auth/utils`). A custom role is never one, whatever its permissions. The administrator has no
  till exception: he passes because his role holds the five rights. He alone gives or removes the ADMIN role, and opens
  the ERP admin pages.

## The badge is a credential

- A user's badge: `badge_code` (unique when not null, `ux_user_account_badge_code`), an expiry, revoked or not; the
  Users page keeps the code, Generate, the expiry and Revoke / Reactivate. No list per user (step C).
- Scanned at the till (`POST /badge/scan {badgeCode, permission}`), it opens the action when, in this order: the code is
  known (`BADGE_NOT_EXISTS`), not revoked (`BADGE_REVOKED`), not expired (`BADGE_EXPIRED`), its owner active
  (`BADGE_OWNER_INACTIVE`), and its owner's role holds the right (`BADGE_NO_ACCESS`).
- For an action checked on its request, a successful scan answers a **grant** (below); for the customer list none.

## At the till (frontend)

- One helper, `src/services/tillAuth.js` `requireTillAction(action, {sessionId, reuse, forceScan, reason})`, resolves
  with an Authorisation `{action, grantedBy: ROLE | BADGE, badgeOwnerId, badgeOwnerName, badgeCode, grantToken, at}`
  or rejects `TillAuthCancelled`. The decision is the pure `decide()` of `till-auth-rules.js`: the role holds the
  right (the login's abilities) → ROLE, no popup; else the one popup `TillAuthPopup.vue` (mounted once in the layout).
- A pass on the role's right of the customer list is logged by `POST /badge/pass {action}` (403
  `TILL_RIGHT_REQUIRED` when the role lost the right: the popup); for the other four the request logs it.
- A grant is good for one gesture: every opening of a discount dialog asks; the Returns and Customers buttons hand
  their authorisation to the next screen in memory (30 s), never in the URL.
- The grant goes with the request: a line discount's on its cart line (`tillGrant`), the total discount's in the order
  summary, the return's and the close's with them. `withTillRights(send, attach)` sends a request again after a 403
  `TILL_RIGHT_REQUIRED`, once a new badge is scanned (the popup says the server asked); cancelled, nothing is done and
  the screen stays as it is (the table grid too).
- Closing with a difference: the first close answers `CASH_DISCREPANCY` with the amounts; the warning offers "Confirm
  and close" to a role holding the right (`discrepancyConfirmed`), "Force close" (a badge, `tillGrant`) to anyone else.
- `tools/till-auth-check.mjs`: the table of the rule (role right × badge state × action, the roles of day one) and
  the 403 as the screens read it.

## On the server (store)

- **The grant** (`till_grant`, `TillGrantService`): 32 random bytes (base64url, 43 characters) for the till; the table
  keeps the SHA-256 only. One action, the scanning user's open session, used once (a conditional update: two requests
  cannot both use it), good until used, until the session is no longer the open one, or `TILL_GRANT_MAX_MINUTES` after
  the scan (General Setup, stores, **240** by default; not a positive number: 240). The row keeps who scanned, the
  owner, the badge code, `consumed_at` and `consumed_by_ref` ("SALE ...", "PENDING ...", "RETURN of ...", "CLOSE ...").
- **The check** (`TillRightsGuard`, inside the request's transaction, before anything is written), per action of the
  request: the logged user's role holds the right → it passes, logged once per request (ROLE); else a valid grant
  came → it passes, the grant is used, the pass logged (BADGE); else **403** `{error: TILL_RIGHT_REQUIRED, action,
  reason, lineIndex?}` and nothing of the request is kept. `reason`: `NO_GRANT`, `GRANT_UNKNOWN`, `GRANT_USED`,
  `GRANT_EXPIRED`, `GRANT_OTHER_SESSION`, `GRANT_OTHER_ACTION`.
- **Which discounts**: every discount (a percentage or an amount above 0.001), per line (`SaleLineDTO.tillGrant`) and
  for the header (`ProcessSaleRequestDTO.tillGrant`), on `process-sale`, `save-pending`, `update-pending/{id}`,
  `complete-pending/{id}`, unless the server verifies its source: `PROMOTION` with its promotion (`promotionId`,
  existing and active; the header's: its cart promotion); `SALES_PRICE` or `SALES_DISCOUNT` when the price list
  (`PricingService`, as `POST /price-calculate`) gives the item, for the request's customer (else the stored ticket's,
  else the default customer, as the price calculation), a discount at least the line's percentage. A source missing,
  unknown or not verified counts as `MANUAL`. The split payment of a table ticket takes its lines from the stored ticket.
- **Pending tickets keep their sources**: `save-pending` and `update-pending/{id}` store each line's `discount_source`
  and promotion, and the header's, as a completed sale does (the columns of `sales_line` and `sales_header`); the till
  sends them again when it recalls the ticket (the pending list and the table grid). A ticket parked before has none:
  its discounts count as `MANUAL`, and pass as long as they are not above what it held (below).
- **A pending ticket keeps what it held**: saved again or completed, a discount not above the stored one (same item;
  not a larger percentage, or not a larger amount) passes: it was authorised, and logged, when stored (a ticket parked
  before the upgrade too). A new ticket is credited nothing.
- **Closing with a difference** (`CashierSessionService.checkCashDiscrepancy`, `ENABLE_CASH_DISCREPANCY_CHECK`, a
  difference above 0.01): a confirmation from a role holding the right (ROLE), or a grant (BADGE); neither: 400
  `CASH_DISCREPANCY` (the warning); a confirmation without the right or a grant not valid: 403.
- The head office is unchanged: no till, no grant; its copies of the sales are built from the entities.

## The log

- `badge_scan_log`, one row per pass and per refused scan: `granted_by` `ROLE` (a pass on the logged user's own role
  right: no badge code, the owner is the user himself) or `BADGE` (a scan); the action, who, the owner, the session
  (the server's, never the till's), success or the refusal reason.
- A successful scan of an action checked on its request writes no row; its pass is logged when the request uses the
  grant (a scan whose action never happens leaves no pass). The customer list's scan is its pass.
- The Badge scan history page (`read:admin-badge-scan-history`, else 403): flat rows (`BadgeScanLogDTO`, never a badge
  code), filters by badge code, user, action, success, `grantedBy` and dates; an "Accordé par" column; CSV export. The
  statistics (`/badge/scan-statistics`, the tiles Total / Accordés / Refusés / taux) count every row of the period, ROLE
  and BADGE, as the list shows them.

## Upgrade (day one)

Nothing changes for anyone on the day of the upgrade until an administrator edits a role:

- **Step C, once per store** (`TillRightsMigration`, marker `TILL_RIGHTS_MIGRATED`, a read-only General Setup row with
  the date and the matrix): a role gets a right when every active user of the role passed it before without a badge:
  an admin (the 2.2.2 enum ADMIN) the five; nobody else while `ALWAYS_SHOW_BADGE_SCAN_POPUP` was on; else the actions
  of his card's list with a valid badge, never the close with a difference. The role named ADMIN gets the five; rights
  are only added; the matrix is logged at INFO. The setting is read once, then its row deleted. Matrix of the local
  copies (2026-10-18): Store A (EMTOP copy, setting on) ADMIN the five, RESPONSIBLE / POS_USER / CONTROLE none; the
  other store copies ADMIN the five, RESPONSIBLE consult-customer-list, make-return, apply-line-discount,
  apply-total-discount, POS_USER none.
- **Step E**: a user of before without an AppRole gets the role named as his enum value (`TillSchemaUpgrade`, also by
  the script); `user_account.role` becomes nullable (its type kept) and is no longer read but by the one migration.
- **Schema** (`db/3.1.0/update.sql`, and at each start `TillSchemaUpgrade` for a database already at 3.1.0):
  `badge_scan_log.granted_by` (the rows of before: BADGE), `scanned_badge_code` nullable, `till_grant` (a store only),
  `user_account.role` nullable. **Deprecated, dropped in the next version**: `user_account.badge_permissions` (the card
  lists, read once by the migration) and `user_account.role` (the enum).

## Settings

| General Setup | Default | Meaning |
|---|---|---|
| `ENABLE_CASH_DISCREPANCY_CHECK` | false (absent: on) | check the counted cash at the close |
| `TILL_GRANT_MAX_MINUTES` | 240 | how long a badge scan's grant stays good when no request used it (a store only) |
| `TILL_RIGHTS_MIGRATED` | (read only) | written once by the step C migration |
| ~~`ALWAYS_SHOW_BADGE_SCAN_POPUP`~~ | | retired at step C (read once, then deleted) |

## Tests

- Back end: `TillRightsMigrationTest` (the rule; the run on H2), `TillRightsGuardTest` (the matrix of the requests:
  role, grants per line and header, used, expired, other action, other session, unknown, a race, the discounts that
  need nothing, the pending credit, returns, the grant's issue and lifetime), `CashierSessionDiscrepancyRightTest`
  (the close), `BadgeServiceInactiveOwnerTest`, `UserRoleChangeRuleTest` (the administrator is the system role),
  `ZZDataInitializerRolesTest` (the seed); helper `store.support.InMemoryTillRights` (the real guard over in-memory
  tables; `allowAll()` for the tests of something else).
- Frontend: `tools/till-auth-check.mjs`.

## Known limits

- A promotion is verified by its existence and activity, not by its rules (scope, dates, quantities): a client naming
  an active promotion on a line it does not cover passes unchecked. A price list discount is verified for the item
  and the customer.
- The till's abilities date from the login: a right removed since passes the till's own decision, the server refuses
  it (403, then a badge); a right added since shows the popup until the next login.
- The router checks a page's permission only where its route declares one (`meta.resource`, at the end of the guard);
  most store routes do not (the menu hides them; the server does not check page roles: `Apps/ZSRetail-Back/CLAUDE.md`,
  Conventions).

## History

- **2.2.2**: an admin (the enum `UserAccount.role` ADMIN) never scanned; others scanned unless their own badge's list
  held the action; `ALWAYS_SHOW_BADGE_SCAN_POPUP` made everyone but an admin scan.
- **Step S**: removed the unlogged badge lookups and the `/user` controller; the scan history behind its page
  permission, flat rows; a deactivated owner's badge refused; badge codes unique, generated with `SecureRandom`.
- **Step B**: the registry on `GET /config`; one helper and one popup at the till; the four till screens went from 8
  popups, 7 copied checks, 23 flags and 37 methods to one popup, one helper call per action, 5 flags and 7 methods.
- **Step C**: the rights on the role; the badge a credential; the migration; ROLE passes logged.
- **Step D**: the server checks the rights on the requests; the grants.
- **Step E**: the enum Role retired; the administrator is the system role ADMIN.
