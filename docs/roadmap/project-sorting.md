# Project sorting (code cleanup steps 5 and 6)

Target: Maven projects, one version, two applications.

| Project | Package root (step 6) | What | Uses |
|---|---|---|---|
| erp-api | `com.digithink.zsretail.erp.api` | the ERP plug: today `erp/spi`, `erp/dto` | nothing of ours |
| core | `com.digithink.zsretail.core` | what both applications use: catalogue, promotions and loyalty definitions, customers, users, roles, settings, stock, purchases, vendors, login, licence, the ERP import and jobs, `erp/engine`, the link messages, and the selling entities (loyalty transactions and stock movements point at them) | erp-api |
| store | `com.digithink.zsretail.store` | POS, sessions, tickets, payments, returns, customer invoices, inventory counts, analytics, the sales exports to the ERP, the link to the head office (`holink`) | core, erp-api |
| headoffice | `com.digithink.zsretail.headoffice` | stores, points de stock, tickets received, BLs, supply invoices, the `/ho/**` API | core, erp-api |
| emtop | `com.digithink.zsretail.connector.emtop` | today `erp/dynamicsnav` | erp-api |
| happyness | `com.digithink.zsretail.connector.happyness` | today `erp/navpospages` | erp-api |

Rule to sort a class: used by both applications, core; only by a store, store; only by a head office, headoffice.
The sorting of every class and the rules are in `ProjectSortingGuardTest` (`projectOf`): by package, with the store
classes of the common packages listed, the link messages of `headoffice/dto` and the `/ho/**` chain going to core, and
the two ERP admin views of `erp/dto` going to core. What still breaks a rule is its allow-list, each line with its reason
and the step 5 commit that removes it.

Step 5 unties the knots without moving classes; step 6 moves each class once, straight into its project. The connector
names become the customers' (`erp.connector=emtop | happyness | none`); the keys `erp.dynamicsnav.*`,
`erp.navpospages.*` and the two old enabled lines keep working.
