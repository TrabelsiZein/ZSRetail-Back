# Company Information

**Status**: ✅ Complete

**Overview:**
- Dedicated `company_information` DB table (singleton — always exactly one row, `id=1`) storing all company/store profile data used on printed documents.
- Replaces the previously hardcoded `receiptBranding.js` static file.
- Admin-only PUT endpoint; public GET endpoint (loaded at startup alongside `GET /config`).

### Database — New Table

**`company_information`** (singleton, id=1 always):
- `company_name`, `logo_base64` (TEXT/LOB — base64 data-URL), `matricule_fiscal`
- `address`, `city`, `postal_code`, `country`
- `phone`, `fax`, `email`, `website`
- `bank_name`, `bank_account`, `rib`
- `invoice_footer_note`
- Standard audit fields from `_BaseEntity`

### Backend — New Files
- **`model/CompanyInformation.java`** — Entity extending `_BaseEntity`
- **`dto/CompanyInformationDTO.java`** — flat DTO (all fields as Strings)
- **`repository/CompanyInformationRepository.java`** — standard JpaRepository
- **`service/CompanyInformationService.java`** — `get()`, `update()`, `ensureExists()`. Always operates on `id=1`; `ensureExists()` called by `ZZDataInitializer` on startup.
- **`controller/CompanyInformationAPI.java`** — `GET /company-info` (public) + `PUT /company-info` (ADMIN only, `@PreAuthorize("hasRole('ADMIN')")`)
- **`db/migration/013_add_company_information.sql`** — creates table + inserts default row

### Backend — Modified Files
- **`SecurityConfig.java`** — Added `/company-info` to `permitAll()` list
- **`ZZDataInitializer.java`** — Injects `CompanyInformationService`, calls `companyInformationService.ensureExists()` on startup

### Frontend — New Files
- **`src/views/admin/CompanyInformation.vue`** — Admin page with 6 sections (Identity, Logo, Address, Contact, Banking, Documents). Logo upload: drag & drop or click, 2 MB limit, stores as base64. Save button calls `PUT /company-info` and refreshes the Vuex store. Reset button reverts to last loaded state.

### Frontend — Modified Files
- **`src/store/app-config/index.js`** — Added `companyInfo` state object, `UPDATE_COMPANY_INFO` mutation, `companyInfo` getter, `fetchCompanyInfo` action (`GET /company-info`)
- **`src/main.js`** — Calls `store.dispatch('appConfig/fetchCompanyInfo')` at startup (after `fetchAppConfig`)
- **`src/components/ReceiptTemplate.vue`** — Replaced `receiptBranding.js` import with `mapGetters({ companyInfo: 'appConfig/companyInfo' })`. Computed properties `storeName`, `storeLogoUrl`, `receiptAddress`, `receiptPhone`, `receiptEmail`, `receiptMatriculeFiscal`, `receiptFooterNote` now read from store. Header shows address/phone/email/MF below company name. Footer company info block is now dynamic (was hardcoded). Removed `receiptBranding.js` dependency entirely.
- **`src/navigation/vertical/index.js`** — Added `admin.companyInformationMenu` entry (`BriefcaseIcon`) under Administration group
- **`src/router/index.js`** — Added route `/admin/company-information` (ADMIN only)
- **`src/views/Login.vue`** — Added `admin-company-information` read+write abilities to ADMIN role
- **`src/libs/i18n/locales/en.json` / `fr.json` / `ar.json`** — Added `admin.companyInformationMenu` and `admin.companyInformation.*` keys (title, subtitle, save, sections, all field labels/placeholders/hints, success/error messages)

### Key Design Decisions
- **Single-row enforcement**: Service always uses `SINGLETON_ID = 1L`; no POST/DELETE endpoints; row seeded on first startup
- **Logo as base64**: Stored as a `TEXT`/`@Lob` column; no filesystem or separate image endpoint needed; works directly as `<img :src="...">` in Vue
- **Public GET**: Loaded without auth at startup so company info is available even before login
- **Immediate store refresh after save**: After a successful PUT, `fetchCompanyInfo` is dispatched so all open print templates see updated data without page reload
