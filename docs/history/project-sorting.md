# Project sorting (code cleanup steps 5 to 7, history)

> History of the code cleanup that made the projects. What they are now: `docs/projects.md`.

Target: Maven projects, one version, two applications.

| Project | Package root (step 6) | What | Uses |
|---|---|---|---|
| erp-api | `com.digithink.zsretail.erp.api` | the ERP plug: `erp/api`, `erp/api/dto` (since step 6.2; were `erp/spi`, `erp/dto`) | nothing of ours |
| core | `com.digithink.zsretail.core` | what both applications use: catalogue, promotions and loyalty definitions, customers, users, roles, settings, stock, purchases, vendors, login, licence, the ERP import and jobs, `core/erp/engine`, the link messages, and the selling entities (loyalty transactions and stock movements point at them) | erp-api |
| store | `com.digithink.zsretail.store` | POS, sessions, tickets, payments, returns, customer invoices, inventory counts, analytics, the sales exports to the ERP, the link to the head office (`store/holink`) | core, erp-api |
| headoffice | `com.digithink.zsretail.headoffice` | stores, points de stock, tickets received, BLs, supply invoices, the `/ho/**` API | core, erp-api |
| emtop | `com.digithink.zsretail.connector.emtop` | `erp/dynamicsnav` until step 6.3 | erp-api |
| happyness | `com.digithink.zsretail.connector.happyness` | `erp/navpospages` until step 6.3 | erp-api |

Rule to sort a class: used by both applications, core; only by a store, store; only by a head office, headoffice.
The sorting of every class and the rules are in `ProjectSortingGuardTest` (`projectOf`): by package, with the store
classes of the common packages listed, the link messages of `headoffice/dto` and the `/ho/**` chain going to core, and
the two ERP admin views of `erp/dto` going to core (`core/erp/dto`). What still breaks a rule is its allow-list, each line with its reason
and the step 5 commit that removes it; empty since step 5.8d.

Step 5 unties the knots without moving classes; step 6 moves each class once, straight into its project. The connector
names become the customers' (`erp.connector=emtop | happyness | none`); the keys `erp.dynamicsnav.*`,
`erp.navpospages.*` and the two old enabled lines keep working.

## The Maven projects (step 6)

The parent `pom.xml` at the root of the repository (`com.digithink:zsretail-parent`, packaging `pom`, parent
`spring-boot-starter-parent` 2.3.9) lists the projects and gives them one version, the product version. Each project
is a folder with its `pom.xml`; the version of each `com.digithink` artifact is managed by the parent, so a project
names its dependencies without versions.

| Folder | Artifact | Depends on | Package root |
|---|---|---|---|
| `erp-api` | `zsretail-erp-api` | nothing of ours | `com.digithink.zsretail.erp.api` |
| `connectors/happyness` | `zsretail-connector-happyness` | erp-api | `com.digithink.zsretail.connector.happyness` |
| `connectors/emtop` | `zsretail-connector-emtop` | erp-api | `com.digithink.zsretail.connector.emtop` |
| `core` | `zsretail-core` | erp-api | `com.digithink.zsretail.core` |
| `headoffice` | `zsretail-headoffice` (the head office WAR) | core | `com.digithink.zsretail.headoffice` |
| `store` | `zsretail-store` (the store WAR) | core | `com.digithink.zsretail.store` |
| `network-tests` | `zsretail-network-tests` (tests only, never deployed) | store, headoffice | tests |
| `app` | `zsretail-app` | everything, until it is empty | the classes not moved yet |

The dependencies make the rules true: a project that does not depend on another cannot compile a class that uses it.
`ProjectSortingGuardTest` stays, for the rules Maven does not see (a class sorted into the wrong project).

Step by step, each commit with the full suite green:
- 6.1: the parent and the empty projects; the code, its `pom.xml` and the Eclipse files move to `app/` unchanged
  (`app/target/zsretailws.war`, the same classes, resources and libraries as before). The guards and `Installations`
  find the repository from any project folder (`support/Repo`) and scan every project's `src/main/java`.
- 6.2: erp-api, 47 classes (`erp/spi` grew to 22 at step 5.8; `erp/dto` gives 25, its two admin views stay for core),
  package `com.digithink.zsretail.erp.api` (and `.dto`); app depends on it.
- 6.3: the connectors, each in its project with its final package (`connector.happyness`, `connector.emtop`; their
  sub-packages unchanged), depending on erp-api and libraries only: a connector class that names a class of the
  application does not compile. app takes them at `runtime` scope: they are in the WAR and on the tests' classpath,
  but a main class of app that names a connector does not compile either. Their tests stay in app for now (they use
  its fixtures), in the connector's package.
- 6.4: core, 311 classes, each with its sub-package under `com.digithink.zsretail.core` (`config` to `core.config`,
  `erp.engine` to `core.erp.engine`...); the link messages of `headoffice.dto` and the `/ho/**` chain go to `core.link`.
  With them `application.properties`, `db/`, `license/` and `spring.factories`. core carries the libraries of the
  application before the projects (both WARs keep the same); the test libraries are the parent's. Every class moved
  in step 6 is listed, old name then new, in `class-renames.txt` (tests); the proof of step 4 reads it, so its record
  is unchanged. New guards: a class lives in the project of its package root (`ProjectSortingGuardTest`), and every
  full class name in a string (JPQL, pointcuts) names a class that exists (`ClassNamesInStringsGuardTest`).
- 6.5: headoffice, 117 classes (names unchanged) and two new ones: `HeadOfficeApplication` and `HeadOfficeQuantityColumns`;
  `application-headoffice.properties`; a WAR, `zsretail-headoffice-<version>.war` (its classes also as a `classes` jar, for the
  tests that need both applications). The application is the type (`core/config/ZsRetailApplication`: its profile, and
  `spring.profiles.active` and `zsretail.application.type` as default properties); `application.properties` no longer names
  `store`; a file or an option naming the other type stops the start (`InstallationTypeEnvironmentPostProcessor`). Each
  application checks its own quantity columns (`QuantityColumns`). The proofs: `ConfigurationKeysProofTest` (each
  configuration in the application of its type, every key as recorded) and `InstallationBeansProofTest` (the beans of each
  configuration among its application's), records unchanged. `devenv` builds both WARs and starts each instance with the
  application of its type.
- 6.6: store, 164 classes under `com.digithink.zsretail.store` (`holink` to `store.holink`), with
  `application-store.properties`; `POSMainApp` becomes `StoreApplication`; a WAR, `zsretail-store-<version>.war` (and its
  `classes` jar). `app` is gone: its tests are in network-tests (both applications' classes) until 6.7 sorts them. The
  sorting guard needs no list any more: a class's project is its package root. A stray `Payment.vue` committed at
  `src/views/pos/` in 7401b17 (moved to `app/src/views` at 6.1) is back at its old place, outside every project.
- 6.7: the tests of each project in its project (core 28 classes, store 33, headoffice 30, network-tests 61): a test goes to
  the smallest project that sees every class it uses, with the helpers it uses; a test moved into core takes core's root,
  a test of core's classes that needs an application keeps its package there. The helpers: `core.support`, `store.support`,
  `headoffice.support` (each project a test-jar), `support` in network-tests. `InMemoryLoyalty` lost the head office's
  alias and movement tables to `headoffice.support.InMemoryHoLoyalty`, so core's and the store's tests need no head office
  class; `InMemoryCatalogue` and `InMemoryStock` still mix core and head office fakes (in headoffice.support), so the store
  tests that use them run in network-tests. The shared Eclipse launch files `eclipse/dev-*.launch` and
  `eclipse/rehearsal-*.launch` start the application of each instance. CLAUDE.md, README, the docs.

## Step 7: a connector declares itself

The connector projects are in `connectors/` (`connectors/emtop`, `connectors/happyness`); `connectors/pom.xml`
(`zsretail-connectors`, packaging pom) lists them as modules and as dependencies, and each WAR depends on it alone
(runtime scope). Each connector declares itself to erp-api with an `ErpConnectorDescriptor` in its
`META-INF/spring.factories`: its name (a value of `erp.connector`), its abilities, its key prefix, its old enabled line
when it has one, what the startup summary shows. erp-api and core name no customer: `ErpConnectors.names()`,
`ErpAbilities` and the translation of `erp.connector` read the descriptors found. The guards find the connectors by
their folders and descriptors. A new connector: its folder and two lines in `connectors/pom.xml` (erp-sync.md, "How to
add a connector").
