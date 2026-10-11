# Project sorting (code cleanup steps 5 and 6)

Target: Maven projects, one version, two applications.

| Project | Package root (step 6) | What | Uses |
|---|---|---|---|
| erp-api | `com.digithink.zsretail.erp.api` | the ERP plug: `erp/api`, `erp/api/dto` (since step 6.2; were `erp/spi`, `erp/dto`) | nothing of ours |
| core | `com.digithink.zsretail.core` | what both applications use: catalogue, promotions and loyalty definitions, customers, users, roles, settings, stock, purchases, vendors, login, licence, the ERP import and jobs, `core/erp/engine`, the link messages, and the selling entities (loyalty transactions and stock movements point at them) | erp-api |
| store | `com.digithink.zsretail.store` | POS, sessions, tickets, payments, returns, customer invoices, inventory counts, analytics, the sales exports to the ERP, the link to the head office (`holink`) | core, erp-api |
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
| `connector-happyness` | `zsretail-connector-happyness` | erp-api | `com.digithink.zsretail.connector.happyness` |
| `connector-emtop` | `zsretail-connector-emtop` | erp-api | `com.digithink.zsretail.connector.emtop` |
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
- 6.5: headoffice and its WAR. 6.6: store and its WAR; `app` is gone. 6.7: the network tests, the dev
  scripts.
