# ZSRetail-Back — Backend Guide

Spring Boot backend for ZS Retail. See `../../CLAUDE.md` for the project-wide map and
the development contract; this file covers backend specifics only.

## Stack

- Java 11 · Spring Boot 2.3.9 · packaged as **WAR** (deployed to Tomcat 9)
- Spring Security + JWT · JPA/Hibernate · SQL Server
- Maven, several projects under one parent `com.digithink:zsretail-parent` (the `pom.xml` of this folder), one
  version for all of them: the product version, currently 2.2.2
- springdoc-openapi UI for API docs
- Context path `/zsretail/api`, port `444`

## Maven projects (code cleanup step 6)

Each folder below is a project with its `pom.xml`; the parent lists them. The projects, what goes where and the rules
between them: `docs/roadmap/project-sorting.md`.

| Folder | Artifact | Uses | State |
|---|---|---|---|
| `erp-api` | `zsretail-erp-api` | nothing of ours | since step 6.2: `com.digithink.zsretail.erp.api` (and `.dto`), 47 classes |
| `connector-happyness` | `zsretail-connector-happyness` | erp-api | since step 6.3: `com.digithink.zsretail.connector.happyness`, 19 classes |
| `connector-emtop` | `zsretail-connector-emtop` | erp-api | since step 6.3: `com.digithink.zsretail.connector.emtop`, 22 classes |
| `core` | `zsretail-core` | erp-api | since step 6.4: `com.digithink.zsretail.core` (link messages and `/ho/**` chain in `core.link`), 311 classes, `application.properties`, `db/`, `license/`, `spring.factories` |
| `headoffice` | `zsretail-headoffice` (WAR) | core; the connectors at runtime | since step 6.5: `com.digithink.zsretail.headoffice`, 119 classes, main class `HeadOfficeApplication`, `application-headoffice.properties`; `target/zsretail-headoffice-<version>.war` |
| `store` | `zsretail-store` | core | empty until step 6.6 (then the store WAR) |
| `app` | `zsretail-app` (WAR) | core; the connectors at runtime scope (in the WAR, never named by a main class); the head office's classes for its tests only | the application before the projects: `app/target/zsretailws.war`, gone at step 6.6 |
| `network-tests` | `zsretail-network-tests` | store, headoffice | tests only, empty until step 6.7 |

## Layout

A project's classes are under its package root, with the sub-packages of before (`core.model`, `core.service`,
`core.controller`, ...). Until step 6.6 the store's classes and the head office's (until 6.5) are still in `app`,
under their old packages, with `POSMainApp`.

```
core/src/main/java/com/digithink/zsretail/core/
  config/  security/  model/ (+ enumeration/)  repository/  service/ (incl. ZZDataInitializer)  controller/
  dto/ (+ report/)  erp/ (jobs, engine, admin)  link/ (the store / head office messages, the /ho/** chain)
  exception/  utils/
```

`core/src/main/resources/`
- `application.properties`: the common keys of both applications (default `spring.profiles.active=store`)
- `db/<product-version>/` — SQL migration scripts grouped by release (e.g. `db/1.11.0/`)
- `license/` — public key material for offline licensing (`public_key.pem`, gitignored, kept on the machine)
- `META-INF/spring.factories`: the configuration's post-processors

The two type files (configuration step C1, "what this installation is"; the database password from
`ZSRETAIL_DB_PASSWORD`): `app/src/main/resources/application-store.properties` and, since step 6.5,
`headoffice/src/main/resources/application-headoffice.properties`. The application is the type (step 6.5). An optional outside file per installation (usually where it runs; it may name the type and
change owners), named by `-Dzsretail.machine-file=<path>` or found as
`${catalina.base}/conf/zsretail/<context name>.properties`; without one the type file applies. Any active profile
other than exactly `store` or `headoffice` is refused. Outside files of this repo: `deploy/` (model, dev machines,
customer values). See `docs/deployment-modes.md`.

## Conventions

- **Generic CRUD first.** New entities extend `_BaseEntity` and reuse
  `_BaseRepository` / `_BaseService` / `_BaseController`. Read `docs/generics.md` before
  hand-writing a controller.
- **Controllers are named `<Entity>API.java`**, not `*Controller`.
- **DB changes ship as a numbered SQL script** under `core/src/main/resources/db/<next version>/`
  and are reflected in the matching module doc. Never edit a released script.
- **Role enforcement** is in the frontend only: the menu shows entries by CASL permission, and
  the route guard checks only routes with `meta.resource` (today `/admin/roles`). The API does
  not check roles: there is no `@PreAuthorize`, and the JWT carries no authorities.
- **Money and VAT**: totals are computed VAT-inclusive first, then reduced backwards to
  the excluding-VAT amounts. See `docs/modules/discounts.md`.
- New public endpoints must be added to `SecurityConfig` `permitAll()` explicitly.

## Commands

Always from this folder (the parent): Maven builds the projects in dependency order.

```bash
mvn -q -DskipTests compile        # fast syntax/compile check
mvn -q -DskipTests package        # build the two WARs (docs/deploy-note.md)
mvn test                          # reports in <project>/target/surefire-reports
mvn test -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false   # one test class (the other projects have none)
```

Offline build (no network): add `-o`. The full command of this machine is in `../../CLAUDE.md`.

## Never commit

`private_key.pem` / `*.pem` (licensing), `/uploads/`, `target/` (of every project).
`LicenseGenerator/` is developer-side tooling — read `docs/modules/licensing.md` before
touching it.

## Documentation

All ZS Retail documentation lives in `docs/` in this repo — index in `../../CLAUDE.md`.
Keep the relevant `docs/modules/*.md` updated in the same commit as the code change.
