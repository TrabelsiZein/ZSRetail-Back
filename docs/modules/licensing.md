# On-Prem Licensing

**Status**: ✅ Implemented and active

### Goal
- Prevent POS usage without a valid license in on-prem deployments (offline-capable).
- Bind each license to a specific machine to reduce license sharing/cloning risk.

### Current Model
- License file is a **signed JSON** (RSA signature, verified in backend).
- Required `data` fields:
  - `company`
  - `installationId` (machine fingerprint hash)
  - `issuedAt`
  - `expiresAt`
- `signature` contains `SHA256withRSA` signature over compact `data` JSON.

### Security Design
- Backend uses embedded **public key** (`core/src/main/resources/license/public_key.pem`) for verification.
- Private key is kept outside app deployment and used only in generator tool.
- Validation is backend-side only (never trusted on frontend).
- `appId` was removed from active logic/config/generator/frontend; machine binding now relies on `installationId`.

### Machine Fingerprint
- New service: `MachineFingerprintService`
- Fingerprint inputs (Windows):
  - `MachineGuid` from registry
  - `C:` volume serial
- Final `installationId` = `SHA-256(MachineGuid + "|" + VolumeSerial)` (uppercase hex).
- Fingerprint is computed at startup and cached in memory.

### Validation Rules
- License is valid only if:
  1. Signature is valid.
  2. `installationId` in license equals runtime machine fingerprint.
  3. `expiresAt` not passed.
- Statuses used by backend/frontend:
  - `MISSING`
  - `WARNING` (<= 14 days left)
  - `VALID`
  - `EXPIRED`
- If license is missing/expired/invalid-machine-binding, app is blocked except allowed paths.

### Backend Components Added/Updated
- **New**
  - `service/MachineFingerprintService.java`
  - `db/migration/016_add_license_installation_id.sql`
- **Updated**
  - `service/LicenseService.java` (machine-bound verification + status checks)
  - `controller/LicenseAPI.java` (`/license/status` returns both machine and licensed IDs)
  - `model/LicenseRecord.java` (stores `installationId`; legacy `appId` removed from active use)
  - `core/security/LicenseFilter.java` bypass list includes `/admin/license` so upload works when license is missing.

### Frontend UX
- The **Licence** page (2.3.0; before, a card of the Company Information page), in the Settings menu: store route
  `admin-license` (`/admin/license`, permission `read:admin-license`), head office twin `admin-headoffice-license`
  (`/headoffice/settings/license`, `read:admin-headoffice-license`), one component `views/admin/License.vue`, the same API:
  - Current status
  - **This Machine Installation ID** (with copy button)
  - **Licensed Installation ID** (shown only when different/missing to avoid duplication)
  - Upload area for `license.json`
  - License history
- Warning banner appears globally when license is close to expiry; a click opens the Licence page.
- Dedicated `/admin/license-expired` page blocks normal navigation when invalid. Its upload button opens the Licence page,
  the only page besides login and itself that a blocked licence lets through (`licenseBypassRoutes` in `router/index.js`,
  the head office twin by `meta.twinOf`). The page still needs its permission: without it (a session opened before the
  upgrade, or a role without it) the button is replaced by a line asking to log in again.
- **The permission at the upgrade**: a store's ADMIN role receives `read:admin-license` at every start when it lacks it
  (`ZZDataInitializer.LICENSE_ADMIN_PERMISSIONS`, `addMissingPermissions`), a head office's ADMIN role
  `read:admin-headoffice-license` with its other head office pages (`HEAD_OFFICE_ADMIN_PERMISSIONS`): a database of before
  (EMTOP, the Happyness head office and stores) gets it at its first 2.3.0 start, no SQL script, nothing to tick. An admin
  logged in before the upgrade logs in again to receive it (the abilities are read at login).

### Important Fixes During Implementation
- i18n issue fixed: license keys correctly placed under `admin.license` in `en/fr/ar`.
- French locale encoding corruption (`SociÃ©tÃ©`) was repaired and JSON revalidated.
- Layout transition warning fixed by wrapping banner + router view in a single root node in vertical layout.

### License Generator (Developer-side)
- File: `ZSRetail-Back/LicenseGenerator/LicenseGenerator.java`
- New usage:
  - `java LicenseGenerator.java "<company>" "<installationId>" "<expiresAt>"`
- Example:
  - `java LicenseGenerator.java "Client ABC" "A1B2...F9" "2030-12-31"`
- Output: `license.json` to send customer for upload.

### Operational Notes
- Do **not** rotate RSA keys unless private key compromise is suspected (rotation invalidates existing licenses).
- For each customer machine:
  1. Read/copy machine installation ID from the Licence page.
  2. Generate signed license with that ID.
  3. Upload license on the Licence page.
