# ZS Retail Documentation

Reference documentation for the ZS Retail POS suite (backend + frontend). It lives in
the `POS_Back` repo so it is versioned alongside the code, but it describes **both**
applications.

Split out of the former single `AI_CONTEXT_POS.md` so each topic can be read on its own.

## Orientation

| File | Covers |
|---|---|
| `overview.md` | Domains implemented, roles, high-level architecture |
| `backend.md` | Backend behaviour notes and recent changes |
| `frontend.md` | Frontend behaviour notes and recent changes |
| `deployment-modes.md` | Standalone vs Dynamics NAV vs franchise |
| `generics.md` | `_BaseEntity` / `_BaseService` / `_BaseController` CRUD scaffolding |
| `ui-design-system.md` | Touch-screen POS layout rules (Payment, ItemSelection) |
| `admin-pages.md` | Inventory of admin screens |
| `printing-barcodes.md` | Receipts, return vouchers, CODE128 |
| `development-contract.md` | Working agreement for AI-assisted development |

## Modules — `modules/`

`promotion` · `discounts` · `pricing` · `loyalty` · `franchise` · `licensing` ·
`erp-sync` · `reporting` · `global-search` · `i18n` · `data-import` · `customers` ·
`company-information` · `general-setup` · `table-management` · `badge-permissions`

## Specs and plans

- `specs/` — client-facing specifications (`DOC_FIDELITE_POS`, `DOC_PROMOTION_POS`, `.md` + `.docx`)
- `roadmap/standalone-tasks.md` — standalone backlog

## Rule

When a module changes materially, update its file here **in the same commit**. These
files are what the next Claude session reads instead of re-scanning the codebase.
