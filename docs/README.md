# ZS Retail Documentation

Reference documentation for the ZS Retail POS suite (backend + frontend). It lives in
the `ZSRetail-Back` repo so it is versioned alongside the code, but it describes **both**
repositories.

## Orientation

| File | Covers |
|---|---|
| `overview.md` | Domains implemented, roles, high-level architecture |
| `projects.md` | The Maven projects (erp-api, the connectors, core, store, headoffice, network-tests), where a class goes, the guards |
| `deploy-note.md` | The two applications and their WARs: what is built, how each is delivered and deployed as `zsretailws` |
| `deployment-modes.md` | With or without an ERP, ownership settings, the two types, the configuration files and the outside file (`configs/`) |
| `backend.md` | Backend behaviour notes and recent changes |
| `frontend.md` | Frontend behaviour notes and recent changes |
| `generics.md` | `_BaseEntity` / `_BaseService` / `_BaseController` CRUD scaffolding |
| `ui-design-system.md` | Touch-screen POS layout rules (Payment, ItemSelection) |
| `admin-pages.md` | Inventory of admin screens |
| `printing-barcodes.md` | Receipts, return vouchers, CODE128 |
| `development-contract.md` | Working agreement for AI-assisted development |

## Modules — `modules/`

`promotion` · `discounts` · `pricing` · `loyalty` · `franchise` · `head-office` · `licensing` ·
`erp-sync` (the ERP jobs, the connectors, how to add one) · `reporting` · `global-search` · `i18n` · `data-import` ·
`customers` · `company-information` · `general-setup` · `table-management` · `badge-permissions` · `inventory-count` ·
`decimal-quantities` · `rfid` (3.1.0: the store's RFID readers and POS machines)

## Specs and plans

- `specs/` — client-facing specifications (`DOC_FIDELITE_POS`, `DOC_PROMOTION_POS`, `.md` + `.docx`)
- `roadmap/head-office-design.md` — the store / head office model (ownership table, the three movements)
- `roadmap/regression-checklist.md` — the scenarios replayed by hand before a merge, per installation
- `roadmap/standalone-tasks.md` — standalone backlog
- `roadmap/2.3-families-by-stock-point.md` — 2.3 analysis, not built

## History — `history/`

What past steps did, kept for reference; not a description of the code of today.
- `history/head-office-plan.md` — the head office plan, steps 0 to 9 (all done)
- `history/project-sorting.md` — the code cleanup that made the projects (steps 5 to 7)

## Rule

When a module changes materially, update its file here **in the same commit**. These
files are what the next Claude session reads instead of re-scanning the codebase.
