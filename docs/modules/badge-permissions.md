# Badge-Based Permission System - Implementation Summary

## Till rights on the role (3.1.0, step C)

The rule of today. The sections after it (2.2.2, S, B) tell what came before; where they disagree, this one holds.

- **A till right belongs to the role**: five role permissions, the `key()` of `BadgePermission`:
  `till:consult-customer-list`, `till:make-return`, `till:apply-line-discount`, `till:apply-total-discount`,
  `till:close-session-with-discrepancy`. `GET /app-roles/permissions` lists them after the page permissions (the Roles
  page shows them in a "Till" block from `tillActions`); they reach the till with the login's `permissions`, as the page
  permissions do (CASL `till` / `make-return`, ...), so a role edited takes effect at the user's next login.
- **A badge is a credential only**: code, expiry, revoked (and its owner active, step S). Scanned, it opens an action
  when its owner's role holds the right (`BadgeService.roleHolds`), else `BADGE_NO_ACCESS`. No per-user list any more:
  `UserAccount.badgePermissions`, `UserAccountDTO.badgePermissions`, the list in `PUT /user-account/{id}` and
  `BadgeService.hasPermission` / `parseBadgePermissions` / `initializeBadgePermissionsForRole` are gone.
  **Deprecated**: the column `user_account.badge_permissions` stays one release for a rollback, read once by the
  migration below and never written; it is dropped in the next version.
- **`ALWAYS_SHOW_BADGE_SCAN_POPUP` is removed**: no longer created, its row deleted at the first start of a store (after
  the migration read it), no reader left (back or front).
- **A new store database** gives the five rights to every role (ADMIN, RESPONSIBLE, POS_USER: `ZZDataInitializer`
  `TILL_PERMISSIONS`); a store restricts them on the Roles page. A head office has no till: no right, no migration.
- **A store of before: the migration, once** (`TillRightsMigration`, at the first start, after the roles exist; marker
  `TILL_RIGHTS_MIGRATED`, a read-only General Setup row holding the date and the matrix). A role gets a right only when
  every active user of the role passes that action today without a badge, so nothing changes at the till on the day of
  the upgrade:
  - an admin (`UserAccount.role` ADMIN, the 2.2.2 rule) passes the five, whatever the setting or his card;
  - anyone else passes nothing while `ALWAYS_SHOW_BADGE_SCAN_POPUP` is on;
  - else the actions of his card's list (exact names, trimmed) when his own badge is valid (a code, not revoked, not
    expired), never the close with a difference (the server took it from an admin or a badge only).

  The role named ADMIN gets the five; a role without an active user gets none; rights are only added (a role created
  at this start keeps its seed). The role × right matrix is logged once at INFO ("Till rights given to the roles from
  the users' badges (ALWAYS_SHOW_BADGE_SCAN_POPUP was on|off): {ADMIN=..., RESPONSIBLE=...}"). The script does not
  delete the setting: the first start must read it. Matrix of the local copies (2026-10-18, read only):

  | Role | `pos_db_prod` (Store A, EMTOP, setting on) | the other store copies |
  |---|---|---|
  | ADMIN | the five | the five |
  | RESPONSIBLE | none | consult-customer-list, make-return, apply-line-discount, apply-total-discount |
  | CONTROLE (EMTOP) | none | — |
  | POS_USER | none | none |

- **Every pass is logged** in `badge_scan_log`, `granted_by`: `BADGE` (a scan, success or the refusal reason, as
  before; the session is the scanning user's open session, read by the server, the till's `sessionId` is no longer
  trusted) or `ROLE` (a pass on the logged user's own right: no badge code, the owner is the user himself, his open
  session; `POST /badge/pass {action}` logs it, 403 `{error: TILL_RIGHT_REQUIRED, action}` when the role does not hold
  the action, and the till then asks for a badge). `scanned_badge_code` is nullable. `GET /badge/scan-history` filters
  `grantedBy` (ROLE, BADGE; none: both) and its rows give `grantedBy`; `/badge/{code}/scan-history` and the statistics
  count the scans (BADGE) only.
- **Closing with a cash difference**: the logged user whose role holds `till:close-session-with-discrepancy` confirms
  the difference shown (`discrepancyConfirmed`, the first request still gets the `CASH_DISCREPANCY` warning), whatever
  the role's name; anyone else brings a badge whose owner's role holds it (`badgeCode`, `badgePermission`).
  `CurrentUserProvider.currentUserIsAdmin` is gone (it was its only reader).
- Schema: `badge_scan_log.granted_by` (varchar(10), null; the rows of before set to BADGE) and `scanned_badge_code`
  nullable, by `db/3.1.0/update.sql` and at each start by `TillSchemaUpgrade` (a database already at 3.1.0).
- Tests: `TillRightsMigrationTest` (the rule; the run on H2: marker, head office, add-only, setting row, a database
  without the old columns), `CashierSessionDiscrepancyRightTest` (the close on the role's right and by a badge),
  `ZZDataInitializerRolesTest` (the seed), `BadgeServiceInactiveOwnerTest`.

## Rules of 2.2.2 (before step C)

- **Five actions** need a badge permission (`BadgePermission`): `CONSULT_CUSTOMER_LIST` (customer list: till, payment,
  the list itself), `MAKE_RETURN` (the Returns button of the till and the return screen), `APPLY_LINE_DISCOUNT` (till),
  `APPLY_TOTAL_DISCOUNT` (payment), `CLOSE_SESSION_WITH_DISCREPANCY` (closing a session with a cash difference).
- **The till's rule, one place** (since step B, 3.1.0: `src/services/till-auth-rules.js` `decide()`, asked through
  `tillAuth.requireTillAction` by every screen; before: `badgeService.isBadgeScanRequired`): an admin never gets the
  popup; otherwise always when `ALWAYS_SHOW_BADGE_SCAN_POPUP` is on, else when the user's own badge (present, not
  revoked, not expired) does not hold the permission; any error asks for the badge. A grant is good for one gesture
  (every opening of a discount dialog asks; the Returns and Customers buttons hand their grant to the next screen, which
  asks once more only when opened by a typed URL or a refresh).
- **Closing with a difference is decided by the server**: `CashierSessionService.checkCashDiscrepancy` (with
  `ENABLE_CASH_DISCREPANCY_CHECK` on, absent = on, a difference above 0.01) answers 400 `CASH_DISCREPANCY` unless a
  badge holding `CLOSE_SESSION_WITH_DISCREPANCY` comes with the request; the till then shows the difference popup, then
  the badge popup (`requireTillAction(..., {ownRightAccepted: false})`), and sends the close again with the badge code
  and action of the authorisation (`badgeCode`, `badgePermission`). It does not read `ALWAYS_SHOW_BADGE_SCAN_POPUP`.
- **Admin, never asked for a badge (2.2.2, every installation)**: on the five actions the till checks nothing and shows no
  popup, even with `ALWAYS_SHOW_BADGE_SCAN_POPUP` on. Closing with a difference: an admin still gets the difference
  popup (a typing mistake in the counted cash never closes silently), with "Check the counted amounts..." and the button
  "Confirm and close" instead of the badge (the wording follows `tillAuth.passesWithoutScan`; the helper grants
  `ROLE` without a popup); the till sends the close again with `discrepancyConfirmed: true`, which the
  server accepts from an admin only (from anyone else it means nothing), with the log line "Session closure with
  discrepancy authorized by the admin role: session ..., user ..., expected ..., counted ..., no badge". No badge scan
  is logged for an admin (none happens).
  Cashiers, responsibles and custom roles: unchanged (same checks, same popups, same scan history).
- **Who is an admin** (one definition per side): the logged user's role `UserAccount.role` is `ADMIN`. Server:
  `CurrentUserProvider.currentUserIsAdmin()`; till: `till-auth-rules.isAdminUser(user)` on
  `GET /user-account/current` (`role`). The role is kept in step with the built-in role ADMIN by
  `UserAccountService.applyAppRole`; a custom role maps to POS_USER or RESPONSIBLE, never ADMIN.
- Tests: `CashierSessionAdminBadgeTest` (admin without a badge passes; cashier, responsible, custom role refused as in
  2.2.1; a valid badge passes, an unknown or insufficient one refused as in 2.2.1).

## Security (3.1.0, step S)

- **Removed endpoints** (no caller in ZSRetail-Front): the whole `/user` controller (`UseAccountAPI`: generic CRUD on
  the raw `UserAccount`, password hash included, and `PUT /user/{id}/role`, which set the role enum with no I-21 check);
  the badge lookups `GET /badge/by-badge/{code}`, `POST /badge/check-permission`, `GET /user-account/by-badge/{code}`,
  `GET` and `PUT /user-account/{id}/badge` (no log, any logged user). Users and badges are edited through
  `PUT /user-account/{id}` and the `/user-account/{id}/badge/generate|revoke|reactivate` actions only.
- **Scan history reads** (`GET /badge/scan-history`, `/badge/{code}/scan-history`, `/badge/scan-statistics`): 403
  unless the logged user's role holds `read:admin-badge-scan-history`, the page's permission
  (`CurrentUserProvider.currentUserHasPermission`; ADMIN and RESPONSIBLE by default). The rows are `BadgeScanLogDTO`:
  `id`, `timestamp`, `scannedByUsername`/`FullName`, `scannedUserUsername`/`FullName` (the owner), `functionality`,
  `success`, `failureReason`, `sessionId`; never the badge code nor a `UserAccount`. The statistics give `failedScans`
  and no longer the most scanned badge codes. The `badgeCode` filter still searches by an exact code.
- **A deactivated user's badge opens nothing** (`BadgeService.isBadgeOwnerInactive`: `active` false, null read as
  active): a scan is refused `BADGE_OWNER_INACTIVE` and logged like the other refusals (after revoked and expired, before
  the permission); the close with a cash difference (`findUserByBadgeCode`) refuses it too ("Badge validation failed:
  BADGE_NOT_EXISTS", as an unknown badge). The till shows its generic "unexpected error" text for this reason until the
  front maps it. Test: `BadgeServiceInactiveOwnerTest`.
- **Badge codes are unique** in the database: filtered unique index `ux_user_account_badge_code`
  (`WHERE badge_code IS NOT NULL`), by `db/3.1.0/update.sql` and, on a new database, by `UserBadgeCodeIndex` at the
  start (Hibernate cannot create a filtered index; `unique = true` on the column would refuse a second user without a
  badge). `UserAccountAPI` still refuses a code taken, with its message. **Generated codes** keep their form (up to 10
  letters of the username, "-", 4 digits) with the digits from `SecureRandom`. **A new database's responsible** gets a
  generated code, logged once at the first start ("First start: the badge code of the user 'responsible' is ..."),
  no longer `RESP-BADGE-001`.

## Till authorisation (3.1.0, step B)

The till rights move to the role at step C (`till:*` permissions); step B prepares the ground without changing what
the user sees:

- **The registry, once**: `BadgePermission` (its constant names stay what the database stores and what the till sends
  to `POST /badge/scan`) gains `key()`, the role permission key (`till:consult-customer-list`, ...);
  `TillActions.all()` lists `{code, key}`; `GET /config` serves it as `tillActions` (empty on a head office, which has
  no till; read as empty by a frontend on an older backend). The frontend builds its badge lists, labels (i18n
  `pos.badge.permissions.<code lower case>`, 3 locales) and filters from it: no list of the five in the front
  (`tillAuth.js`, Users page, badge popup, scan history filter).
- **One helper, one popup (front)**: `src/services/tillAuth.js` `requireTillAction(action, {sessionId, scope,
  reuse, ownRightAccepted})` → `Promise<Authorisation {action, grantedBy: 'ROLE'|'BADGE', badgeOwnerId,
  badgeOwnerName, badgeCode, grantToken: null, at}>`, rejected (`TillAuthCancelled`) on cancel or a route change; the
  decision is the pure `decide()` of `till-auth-rules.js` (today's rule: admin never asked, `ALWAYS_SHOW_BADGE_SCAN_POPUP`,
  else the user's own valid badge); `TillAuthPopup.vue` mounted once in `src/layouts/full/LayoutFull.vue` and driven by
  the helper. A grant is good for one gesture; a caller hands it to the next screen in memory only (`consumeGrant`,
  30 s), never in the URL. `tools/till-auth-check.mjs` proves `decide()` equals the old `isBadgeScanRequired` (its
  copy) on 250 rows: 200 for the actions, 10 with the user unknown, 40 for the discrepancy close.
- **Done (B2-B5 + cleanup)**: every till screen asks through the helper: `CustomerList` and its two callers (B2; the
  `?returnTo=` query no longer skips the check, it is the Back button's target), the Returns button and `ReturnProducts`
  (B3; one scan covers the whole return, the second ask at Process return is gone), the line and total discount dialogs
  (B4; no sticky scan), the discrepancy close (B5; `ownRightAccepted: false`, the server still deciding;
  `passesWithoutScan` words the warning). `BadgeScanPopup.vue`, `badgeService.isBadgeScanRequired`,
  `BADGE_PERMISSIONS`, `isAdminUser` / `isCurrentUserAdmin` and the front's lists of the five are gone; `badgeService`
  keeps `scanBadge` and `getFailureReasonMessage`. Before/after on the four screens: 8 popup instances → 0 (one in the
  layout), 7 copied checks → 0, 23 flags → 4 (`showDiscrepancyPopup`, `discrepancyData`, `pendingCloseSessionData`,
  `closeAuthorisation`, plus `closePassesOnOwnRight` for the wording), 37 methods → 7.

The sections below are the original implementation summary.

## Overview

A comprehensive badge-based permission system has been implemented to control access to sensitive POS features. Users (cashiers and responsibles) can be assigned badges with specific permissions. When attempting restricted actions, users must scan a badge (their own or a responsible's) to gain access. All badge scans are logged for audit purposes.

---

## What Was Implemented

### 1. Backend Implementation

#### 1.1 BadgePermission Enum
**File**: `core/src/main/java/com/digithink/zsretail/core/model/enumeration/BadgePermission.java`
- Enum with 5 permission types (as of 2.2.2):
  - `CONSULT_CUSTOMER_LIST` - Access customer list
  - `MAKE_RETURN` - Process product returns
  - `APPLY_LINE_DISCOUNT` - Discount on a cart line
  - `APPLY_TOTAL_DISCOUNT` - Discount on the ticket total
  - `CLOSE_SESSION_WITH_DISCREPANCY` - Close a cashier session with a cash difference

#### 1.2 UserAccount Entity Updates
**File**: `core/src/main/java/com/digithink/zsretail/core/model/UserAccount.java`
- Added badge-related fields:
  - `badgeCode` (String, unique, nullable) - Unique identifier for the badge
  - `badgePermissions` (String, nullable) - Comma-separated list of permissions
  - `badgeExpirationDate` (LocalDateTime, nullable) - When badge expires
  - `badgeRevoked` (Boolean) - Whether badge is revoked
  - `badgeRevokedAt` (LocalDateTime, nullable) - When badge was revoked
  - `badgeRevokedBy` (UserAccount, nullable) - Who revoked the badge
  - `badgeRevokeReason` (String, nullable) - Reason for revocation

#### 1.3 BadgeScanLog Entity
**File**: `core/src/main/java/com/digithink/zsretail/core/model/BadgeScanLog.java`
- New entity to track all badge scan attempts:
  - `scannedBy` - User who performed the scan
  - `scannedBadgeCode` - Badge code that was scanned
  - `scannedUser` - Owner of the scanned badge
  - `functionality` - Which permission was requested
  - `success` - Whether scan was successful
  - `failureReason` - Reason if scan failed
  - `sessionId` - Cashier session ID (if applicable)
  - `timestamp` - When scan occurred
  - `scanType` - BARCODE or QR_CODE

#### 1.4 BadgeScanRateLimit Entity
**File**: not in the code (no such class has ever been committed; planned as `core/model/BadgeScanRateLimit.java`)
- New entity for rate limiting:
  - `badgeCode` - Badge being rate limited
  - `windowStart` - Start of rate limit window
  - `scanCount` - Number of scans in current window
  - `maxScansPerWindow` - Maximum allowed scans
  - `windowDurationMinutes` - Window duration

#### 1.5 BadgeService
**File**: `core/src/main/java/com/digithink/zsretail/core/service/BadgeService.java`
- Core business logic for badge operations:
  - `findUserByBadgeCode()` - Find user by badge code
  - `hasPermission()` - Check if user has specific permission
  - `checkBadgePermission()` - Validate badge permission
  - `scanBadge()` - Scan badge and log attempt
  - `isBadgeExpired()` - Check if badge is expired
  - `isBadgeRevoked()` - Check if badge is revoked
  - Rate limiting logic to prevent abuse

#### 1.6 BadgeAPI Controller
**File**: `store/src/main/java/com/digithink/zsretail/store/controller/BadgeAPI.java`
- REST endpoints:
  - `POST /badge/scan` - Scan badge and log attempt (3.1.0: `by-badge` and `check-permission` removed)
  - `GET /badge/scan-history` - Get scan history (3.1.0: `read:admin-badge-scan-history`, rows `BadgeScanLogDTO`)
  - `GET /badge/{badgeCode}/scan-history` - Get history for specific badge (same rule)
  - `GET /badge/scan-statistics` - Get scan statistics (same rule)

#### 1.7 UserAccountAPI Updates
**File**: `core/src/main/java/com/digithink/zsretail/core/controller/UserAccountAPI.java`
- Added endpoints:
  - `GET /user-account/current` - Get current authenticated user
  - Badge management integrated into user update/create endpoints

#### 1.8 GeneralSetup Configuration
**File**: `core/src/main/java/com/digithink/zsretail/core/service/ZZDataInitializer.java`
- Added configuration entries:
  - `BADGE_RATE_LIMIT_ATTEMPTS` - Max failed attempts (default: 5)
  - `BADGE_RATE_LIMIT_WINDOW_HOURS` - Rate limit window (default: 1 hour)
  - `ALWAYS_SHOW_BADGE_SCAN_POPUP` - Always show popup (default: false)
  - `BADGE_QR_CODE_ENABLED` - Enable QR code scanning (default: true)

---

### 2. Frontend Implementation

#### 2.1 BadgeScanPopup Component
**File**: `src/components/pos/BadgeScanPopup.vue`
- Reusable modal component for badge scanning:
  - Barcode/QR code input field
  - Auto-detection of scan type
  - Success/error feedback
  - Supports both barcode and QR code scanning
  - Props: `show`, `requiredPermission`, `sessionId`
  - Events: `badge-scanned`, `close`

#### 2.2 BadgeService (Frontend)
**File**: `src/services/badgeService.js`
- Frontend service for badge operations:
  - `checkCurrentUserPermission()` - Check if current user has permission
  - `getAlwaysShowBadgeScan()` - Get configuration setting
  - `scanBadge()` - Scan badge and validate
  - `checkBadgePermission()` - Check permission without logging
  - `isQRCodeEnabled()` - Check if QR code is enabled
  - `detectScanType()` - Auto-detect barcode vs QR code

#### 2.3 UserManagement Updates
**File**: `src/views/admin/UserManagement.vue`
- Enhanced user management with badge features:
  - Badge code input field with auto-generation button
  - Badge expiration date picker
  - Badge permissions multi-select
  - Badge status display (Active/Expired/Revoked)
  - Revoke badge functionality
  - Auto-assign all permissions to RESPONSIBLE role

#### 2.4 BadgeScanHistory Page
**File**: `src/views/admin/BadgeScanHistory.vue`
- Admin dashboard for badge scan audit:
  - Statistics dashboard (total scans, success rate, etc.)
  - Filterable table with:
    - Badge code filter
    - User filter
    - Permission filter
    - Date range filter
    - Success/failure filter
    - Scan type filter
  - Export to CSV functionality
  - Pagination support

#### 2.5 POS Feature Integration

**CustomerManagement.vue**
- Integrated badge scanning for `CONSULT_CUSTOMER_LIST` permission
- Shows badge scan popup before displaying customer list if required

**ReturnProducts.vue**
- Integrated badge scanning for `MAKE_RETURN` permission
- Shows badge scan popup before processing returns if required

**ItemSelection.vue**
- Integrated badge scanning for `CLOSE_SESSION` permission
- Shows badge scan popup before closing session if required

---

### 3. Translations

**Files**: 
- `src/libs/i18n/locales/en.json`
- `src/libs/i18n/locales/fr.json`
- `src/libs/i18n/locales/ar.json`

- Added comprehensive translations for:
  - Badge management UI
  - Badge scan popup
  - Badge scan history
  - Permission labels
  - Error messages
  - Status messages

---

### 4. Database Migration

**File**: `core/src/main/resources/db/migration/001_add_badge_system.sql`

The migration script adds:

1. **user_account table columns:**
   - `badge_code` (NVARCHAR(50), unique, nullable)
   - `badge_permissions` (NVARCHAR(500), nullable)
   - `badge_expiration_date` (DATETIME2, nullable)
   - `badge_revoked` (BIT, default 0)
   - `badge_revoked_at` (DATETIME2, nullable)
   - `badge_revoked_by_id` (BIGINT, foreign key to user_account)
   - `badge_revoke_reason` (NVARCHAR(500), nullable)

2. **badge_scan_log table:**
   - Complete audit log table with all scan attempts
   - Foreign keys to user_account
   - Indexes for performance

3. **badge_scan_rate_limit table:**
   - Rate limiting tracking
   - Unique index on badge_code
   - Index on window_start

4. **GeneralSetup entries:**
   - Configuration values for badge system

---

## How It Works

### Permission Check Flow

1. User attempts to access a restricted feature (e.g., customer list)
   - 2.2.2: the user's role is ADMIN → allow access directly, nothing else is checked
2. System checks `ALWAYS_SHOW_BADGE_SCAN_POPUP` configuration
3. If `false`:
   - Check if current user has the required badge permission
   - If yes → Allow access directly
   - If no → Show badge scan popup
4. If `true`:
   - Always show badge scan popup
5. User scans badge (barcode or QR code)
6. System validates:
   - Badge exists
   - Badge is not expired
   - Badge is not revoked
   - Badge has required permission
   - Rate limit not exceeded
7. If valid → Allow access + Log success
8. If invalid → Show error + Log failure

### Badge Assignment

- **RESPONSIBLE role**: Automatically gets all badge permissions when created
- **POS_USER role**: No badge by default (must be assigned by admin)
- **ADMIN role**: Can manage all badges; never asked for a badge at the till (2.2.2)

### Rate Limiting

- Prevents brute-force badge scanning
- Configurable attempts per time window
- Default: 5 failed attempts per hour
- Successful scans reset the counter

---

## Testing Checklist

Before deploying, test:

- [ ] Database migration runs successfully
- [ ] Admin can assign badge codes to users
- [ ] Admin can set badge permissions
- [ ] Badge code auto-generation works
- [ ] Badge scan popup appears when required
- [ ] Badge scanning validates correctly
- [ ] Customer list requires badge scan (if no permission)
- [ ] Return products requires badge scan (if no permission)
- [ ] Close session requires badge scan (if no permission)
- [ ] Badge scan logs are created
- [ ] BadgeScanHistory page displays correctly
- [ ] Rate limiting works
- [ ] Expired badges are rejected
- [ ] Revoked badges are rejected
- [ ] Translations work in all languages

---

## Files Modified/Created

### Backend Files Created:
- `BadgePermission.java` (enum)
- `BadgeScanLog.java` (entity)
- `BadgeScanRateLimit.java` (entity)
- `BadgeService.java` (service)
- `BadgeAPI.java` (controller)
- `BadgeScanLogService.java` (service)
- `BadgeScanRateLimitService.java` (service)
- `BadgeScanLogRepository.java` (repository)
- `BadgeScanRateLimitRepository.java` (repository)

### Backend Files Modified:
- `UserAccount.java` (added badge fields)
- `UserAccountRepository.java` (added findByBadgeCode)
- `UserAccountService.java` (added badge methods)
- `UserAccountAPI.java` (added /current endpoint, badge management)
- `ZZDataInitializer.java` (added GeneralSetup entries)

### Frontend Files Created:
- `BadgeScanPopup.vue` (component)
- `BadgeScanHistory.vue` (admin page)
- `badgeService.js` (service)

### Frontend Files Modified:
- `UserManagement.vue` (added badge management)
- `CustomerManagement.vue` (integrated badge scanning)
- `ReturnProducts.vue` (integrated badge scanning)
- `ItemSelection.vue` (integrated badge scanning for close session)
- `en.json`, `fr.json`, `ar.json` (added translations)

### Database:
- `001_add_badge_system.sql` (migration script)

---

## Next Steps

1. **Run Database Migration**
   - Execute `core/src/main/resources/db/migration/001_add_badge_system.sql`
   - Update database name in script if needed

2. **Build and Test**
   - Build backend project
   - Build frontend project
   - Test all badge features

3. **Assign Badges**
   - Assign badges to RESPONSIBLE users
   - Assign badges to POS_USER users as needed
   - Configure permissions appropriately

4. **Monitor**
   - Check BadgeScanHistory regularly
   - Review failed scan attempts
   - Adjust rate limits if needed

---

## Configuration

Key configuration values in GeneralSetup:

- **BADGE_RATE_LIMIT_ATTEMPTS**: Maximum failed attempts (default: 5)
- **BADGE_RATE_LIMIT_WINDOW_HOURS**: Time window for rate limiting (default: 1)
- **ALWAYS_SHOW_BADGE_SCAN_POPUP**: Always show popup (default: false)
- **BADGE_QR_CODE_ENABLED**: Enable QR code scanning (default: true)

These can be modified in the GeneralSetup Management admin page.

---

## Support

For issues or questions:
1. Check BadgeScanHistory for failed scan attempts
2. Verify badge permissions in User Management
3. Check GeneralSetup configuration
4. Review application logs for errors

