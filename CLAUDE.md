# ZSRetail-Back — Backend Guide

Spring Boot backend for ZS Retail. See `../../CLAUDE.md` for the project-wide map and
the development contract; this file covers backend specifics only.

## Stack

- Java 11 · Spring Boot 2.3.9 · two applications, each packaged as a **WAR** (deployed to Tomcat 9)
- Spring Security + JWT · JPA/Hibernate · SQL Server
- Maven, several projects under one parent `com.digithink:zsretail-parent` (the `pom.xml` of this folder), one
  version for all of them: the product version, currently 2.2.2
- springdoc-openapi UI for API docs
- Context path `/zsretail/api`, port `444`

## Maven projects (code cleanup step 6)

Each folder below is a project with its `pom.xml`; the parent lists them. What goes where and the rules between them:
`docs/projects.md`; the two WARs: `docs/deploy-note.md`.

| Folder | Artifact | Package root | Uses (of ours) |
|---|---|---|---|
| `erp-api` | `zsretail-erp-api` | `com.digithink.zsretail.erp.api` | nothing |
| `connectors` | `zsretail-connectors` (pom) | the list of the connector projects, the one dependency of the WARs | |
| `connectors/happyness` | `zsretail-connector-happyness` | `com.digithink.zsretail.connector.happyness` | erp-api |
| `connectors/emtop` | `zsretail-connector-emtop` | `com.digithink.zsretail.connector.emtop` | erp-api |
| `core` | `zsretail-core` | `com.digithink.zsretail.core` | erp-api |
| `store` | `zsretail-store` (WAR, main class `StoreApplication`) | `com.digithink.zsretail.store` | core; the connectors at runtime |
| `headoffice` | `zsretail-headoffice` (WAR, main class `HeadOfficeApplication`) | `com.digithink.zsretail.headoffice` | core; the connectors at runtime |
| `network-tests` | `zsretail-network-tests` (tests only) | tests | store, headoffice (their `classes` jars), the connectors |

A connector is taken at `runtime` scope: it is in the WAR, but a class of an application that names one does not compile.
A class of a project lives under the project's package root (`ProjectSortingGuardTest`).

## Layout

The sub-packages of before stay under each root: `core.model`, `core.service`, `core.controller`, `store.service`,
`store.holink` (the store's link to its head office), `headoffice.service`, ...

```
core/src/main/java/com/digithink/zsretail/core/
  config/  security/  model/ (+ enumeration/)  repository/  service/ (incl. ZZDataInitializer)  controller/
  dto/ (+ report/)  erp/ (jobs, engine, admin)  link/ (the store / head office messages, the /ho/** chain)
  exception/  utils/
store/src/main/java/com/digithink/zsretail/store/
  StoreApplication  controller/ service/ model/ repository/ dto/ (sales, sessions, payments, returns, invoices)
  holink/  analytics/  inventory/  erp/ (the sales exports to the ERP)
headoffice/src/main/java/com/digithink/zsretail/headoffice/
  HeadOfficeApplication  controller/ service/ model/ repository/ dto/ security/ enumeration/
```

`core/src/main/resources/` (in both WARs, inside the core jar)
- `application.properties`: the common keys of both applications
- `db/<product-version>/` — SQL migration scripts grouped by release (e.g. `db/1.11.0/`)
- `license/` — public key material for offline licensing (`public_key.pem`, gitignored, kept on the machine)
- `META-INF/spring.factories`: the configuration's post-processors

The two type files (configuration step C1, "what this installation is"; the database password from
`ZSRETAIL_DB_PASSWORD`): `store/src/main/resources/application-store.properties` and
`headoffice/src/main/resources/application-headoffice.properties`. The application is the type (step 6.5): an
installation names no type. An optional outside file per installation (usually where it runs; it may change owners),
named by `-Dzsretail.machine-file=<path>` or found as `${catalina.base}/conf/zsretail/<context name>.properties`;
without one the type file applies. An old outside file naming the other type is refused. Outside files of this repo:
`configs/` (model, dev machines, customer values). See `docs/deployment-modes.md`.

## Conventions

- **Generic CRUD first.** New entities extend `_BaseEntity` and reuse
  `_BaseRepository` / `_BaseService` / `_BaseController`. Read `docs/generics.md` before
  hand-writing a controller.
- **Controllers are named `<Entity>API.java`**, not `*Controller`.
- **A new class goes to its project**: used by both applications, core; only by a store, store; only by a head office,
  headoffice; under that project's package root.
- **DB changes ship as a numbered SQL script** under `core/src/main/resources/db/<next version>/`
  and are reflected in the matching module doc. Never edit a released script. A statement on a table of one
  application only is guarded (`IF OBJECT_ID(...)`): a store database has no head office table, and the reverse.
- **Role enforcement** is in the frontend only: the menu shows entries by CASL permission, and
  the route guard checks only routes with `meta.resource` (today `/admin/roles`). The API does
  not check roles: there is no `@PreAuthorize`, and the JWT carries no authorities.
- **Money and VAT**: totals are computed VAT-inclusive first, then reduced backwards to
  the excluding-VAT amounts. See `docs/modules/discounts.md`.
- New public endpoints must be added to `SecurityConfig` `permitAll()` explicitly.

## Tests (code cleanup step 6.7)

Each project tests its own classes in its `src/test/java`, with what it sees: core's tests need only core, a store test
only core and the store, a head office test only core and the head office. `network-tests` holds what needs both
applications or a connector with core: the store / head office round trips, the configuration and installation proofs
(`ConfigurationKeysProofTest`, `InstallationBeansProofTest`, their records in `network-tests/src/test/resources/config/`),
the guards over every project (`ProjectSortingGuardTest`, `ConnectorNamesGuardTest`, `ConnectorDependenciesGuardTest`,
`AspectPointcutsGuardTest`, `ClassNamesInStringsGuardTest`), the connector tests (they read the deploy files through
`Installations`, which needs both type files). Test helpers: `core.support` (`Repo`, `InMemoryLoyalty`, `TestModes`...),
`store.support`, `headoffice.support` (each project publishes its helpers as a test-jar), `support` in network-tests
(`Installations`, `Starts`, `ClassRenames`).

## Commands

Always from this folder (the parent): Maven builds the projects in dependency order.

```bash
mvn -q -DskipTests compile        # fast syntax/compile check
mvn -q -DskipTests package        # build the two WARs: store/target/zsretail-store-<v>.war, headoffice/target/zsretail-headoffice-<v>.war
mvn test                          # every project; reports in <project>/target/surefire-reports
mvn test -pl store -am            # one project (here store) with what it depends on (core's tests run too)
mvn test -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false   # one test class (the other projects have none)
```

Offline build (no network): add `-o`. The full command of this machine is in `../../CLAUDE.md`.

## Never commit

`private_key.pem` / `*.pem` (licensing), `/uploads/`, `target/` (of every project).
`LicenseGenerator/` is developer-side tooling — read `docs/modules/licensing.md` before
touching it.

## Documentation

All ZS Retail documentation lives in `docs/` in this repo — index in `docs/README.md` (and `../../CLAUDE.md`); what past
steps did is in `docs/history/`.
Keep the relevant `docs/modules/*.md` updated in the same commit as the code change.
