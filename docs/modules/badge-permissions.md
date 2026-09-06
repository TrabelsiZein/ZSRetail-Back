# Badge-Based Permission System - Implementation Summary

## Overview

A comprehensive badge-based permission system has been implemented to control access to sensitive POS features. Users (cashiers and responsibles) can be assigned badges with specific permissions. When attempting restricted actions, users must scan a badge (their own or a responsible's) to gain access. All badge scans are logged for audit purposes.

---

## What Was Implemented

### 1. Backend Implementation

#### 1.1 BadgePermission Enum
**File**: `src/main/java/com/digithink/pos/model/enumeration/BadgePermission.java`
- Created enum with 5 permission types:
  - `CONSULT_CUSTOMER_LIST` - Access customer list
  - `OPEN_TICKET_HISTORY` - Access ticket history
  - `MAKE_RETURN` - Process product returns
  - `CLOSE_SESSION` - Close cashier session
  - `VERIFY_SESSION` - Verify cashier session

#### 1.2 UserAccount Entity Updates
**File**: `src/main/java/com/digithink/pos/model/UserAccount.java`
- Added badge-related fields:
  - `badgeCode` (String, unique, nullable) - Unique identifier for the badge
  - `badgePermissions` (String, nullable) - Comma-separated list of permissions
  - `badgeExpirationDate` (LocalDateTime, nullable) - When badge expires
  - `badgeRevoked` (Boolean) - Whether badge is revoked
  - `badgeRevokedAt` (LocalDateTime, nullable) - When badge was revoked
  - `badgeRevokedBy` (UserAccount, nullable) - Who revoked the badge
  - `badgeRevokeReason` (String, nullable) - Reason for revocation

#### 1.3 BadgeScanLog Entity
**File**: `src/main/java/com/digithink/pos/model/BadgeScanLog.java`
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
**File**: `src/main/java/com/digithink/pos/model/BadgeScanRateLimit.java`
- New entity for rate limiting:
  - `badgeCode` - Badge being rate limited
  - `windowStart` - Start of rate limit window
  - `scanCount` - Number of scans in current window
  - `maxScansPerWindow` - Maximum allowed scans
  - `windowDurationMinutes` - Window duration

#### 1.5 BadgeService
**File**: `src/main/java/com/digithink/pos/service/BadgeService.java`
- Core business logic for badge operations:
  - `findUserByBadgeCode()` - Find user by badge code
  - `hasPermission()` - Check if user has specific permission
  - `checkBadgePermission()` - Validate badge permission
  - `scanBadge()` - Scan badge and log attempt
  - `isBadgeExpired()` - Check if badge is expired
  - `isBadgeRevoked()` - Check if badge is revoked
  - Rate limiting logic to prevent abuse

#### 1.6 BadgeAPI Controller
**File**: `src/main/java/com/digithink/pos/controller/BadgeAPI.java`
- REST endpoints:
  - `GET /badge/by-badge/{badgeCode}` - Find user by badge code
  - `POST /badge/check-permission` - Check badge permission (no logging)
  - `POST /badge/scan` - Scan badge and log attempt
  - `GET /badge/scan-history` - Get scan history (admin only)
  - `GET /badge/{badgeCode}/scan-history` - Get history for specific badge
  - `GET /badge/scan-statistics` - Get scan statistics (admin only)

#### 1.7 UserAccountAPI Updates
**File**: `src/main/java/com/digithink/pos/controller/UserAccountAPI.java`
- Added endpoints:
  - `GET /user-account/current` - Get current authenticated user
  - Badge management integrated into user update/create endpoints

#### 1.8 GeneralSetup Configuration
**File**: `src/main/java/com/digithink/pos/service/ZZDataInitializer.java`
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

**File**: `src/main/resources/db/migration/001_add_badge_system.sql`

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
- **ADMIN role**: Can manage all badges

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
   - Execute `src/main/resources/db/migration/001_add_badge_system.sql`
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

