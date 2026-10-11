# Projects

The backend is several Maven projects under the parent `pom.xml` (`com.digithink:zsretail-parent`), one version for
all of them, the product version. Two of them are the applications, each its WAR (`deploy-note.md`). How it came to
be: `history/project-sorting.md` (code cleanup steps 5 to 7).

| Folder | Artifact | Package root | Uses (of ours) | What |
|---|---|---|---|---|
| `erp-api` | `zsretail-erp-api` | `com.digithink.zsretail.erp.api` | nothing | the ERP plug: what a connector gives (`ErpConnector`, `ErpCatalogueSource`...), what it may ask of the application (`ErpStoreLocation`, `ErpInvoiceStart`...), the neutral `Erp*DTO`, `ErpConnectorDescriptor` |
| `connectors/<name>` | `zsretail-connector-<name>` | `com.digithink.zsretail.connector.<name>` | erp-api | one per customer: `emtop` (EMTOP's Dynamics NAV, keys `erp.emtop.*`), `happyness` (Happyness's POS pages, keys `erp.happyness.*`) |
| `connectors` | `zsretail-connectors` (pom) | — | the connectors | their list (modules) and the one dependency the applications take |
| `core` | `zsretail-core` | `com.digithink.zsretail.core` | erp-api | what both applications use: catalogue, customers, loyalty and promotions, users and roles, settings, stock and purchases, the selling entities, the ERP jobs and catalogue engine, the link messages and the `/ho/**` chain (`core.link`), the configuration, `application.properties`, `db/`, `license/` |
| `store` | `zsretail-store` (WAR, `StoreApplication`) | `com.digithink.zsretail.store` | core; the connectors at runtime | the POS: sessions, tickets, payments, returns, customer invoices, inventory counts, analytics, the sales exports to the ERP, the link to the head office (`store.holink`); `application-store.properties` |
| `headoffice` | `zsretail-headoffice` (WAR, `HeadOfficeApplication`) | `com.digithink.zsretail.headoffice` | core; the connectors at runtime | stores, points de stock, the tickets received, BLs, supply invoices, the `/ho/**` API; `application-headoffice.properties` |
| `network-tests` | `zsretail-network-tests` | tests only | store, headoffice (their `classes` jars), the connectors | the tests that need both applications |

## Where a class goes

Used by both applications: core. Only by a store: store. Only by a head office: headoffice. A connector's classes:
its project. A class lives under its project's package root, with the sub-packages of before (`core.service`,
`store.holink.service`, ...). A connector is never named by core or an application: they ask `ErpAbilities` what the
connector in effect can do (`modules/erp-sync.md`, "How to add a connector").

## What keeps the rules

- **Maven**: core cannot see an application; store and headoffice cannot see each other; a connector sees only erp-api;
  the applications and core's tests take the connectors at `runtime` / `test` scope, so a main class that names one does
  not compile.
- **The guards** (`network-tests`), which find the projects and connectors by their folders and descriptors:
  - `ProjectSortingGuardTest`: what each project may use, every class in the project of its package root, each
    connector project declaring one descriptor named as its folder;
  - `ConnectorNamesGuardTest`: no class outside the connectors names a connector's package or key prefix;
  - `ConnectorDependenciesGuardTest`: a connector uses erp-api only;
  - `AspectPointcutsGuardTest`: every pointcut matches a method;
  - `ClassNamesInStringsGuardTest`: every full class name in a string (JPQL, pointcuts) names a class that exists.
  - `OldKeysGuardTest` (code cleanup step 8): no old configuration key (`erp.dynamicsnav`, `erp.navpospages`, declared by
    the connectors' descriptors only) in main code, resources, `configs/`, `eclipse/` or `devenv/`, and no type line
    (`spring.profiles.active`, `node.type`) in `configs/`, `eclipse/` or `devenv/` (`configs/local` and the released SQL
    scripts apart).
- **The proofs**: `ConfigurationKeysProofTest` (every key of every file of `configs/` resolves as recorded) and
  `InstallationBeansProofTest` (the beans each installation question decides, in every configuration, as recorded).

## Tests

Each project tests its own classes in its `src/test/java`; `network-tests` holds what needs both applications or core
with a connector (the round trips, the proofs, the guards, the connector tests). Test helpers: `core.support`,
`store.support`, `headoffice.support` (each project publishes its helpers as a test-jar), `support` in network-tests.
Commands: `../CLAUDE.md`.
