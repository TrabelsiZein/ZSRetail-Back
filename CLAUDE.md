# ZSRetail-Back — Backend Guide

Spring Boot backend for ZS Retail. See `../../CLAUDE.md` for the project-wide map and
the development contract; this file covers backend specifics only.

## Stack

- Java 11 · Spring Boot 2.3.9 · packaged as **WAR** (deployed to Tomcat 9)
- Spring Security + JWT · JPA/Hibernate · SQL Server
- Maven, `com.digithink:zsretail` (version is the product version, currently 1.11.x)
- springdoc-openapi UI for API docs
- Context path `/zsretail/api`, port `444`

## Layout — `src/main/java/com/digithink/zsretail/`

```
POSMainApp.java      entry point
config/              Spring configuration
security/            JWT, SecurityConfig, ACL
model/               JPA entities (+ enumeration/)
repository/          Spring Data repositories
service/             business logic (incl. ZZDataInitializer bootstrap)
controller/          REST APIs (*API.java)
dto/                 request/response DTOs
erp/                 ERP abstraction layer (Dynamics NAV adapters)
analytics/           reporting & analytics queries
exception/  utils/
```

`src/main/resources/`
- Three files (configuration step C1): `application.properties` (common keys, default `spring.profiles.active=store`),
  `application-store.properties` and `application-headoffice.properties` (the two types: "what this installation is"
  and "where it runs" with the dev values; the database password from `ZSRETAIL_DB_PASSWORD`)
- An optional outside file per installation (usually where it runs; it may name the type and change owners), named by
  `-Dzsretail.machine-file=<path>` or found as
  `${catalina.base}/conf/zsretail/<context name>.properties`; without one the type file applies. Any active profile
  other than exactly `store` or `headoffice` is refused. Outside files of this repo: `deploy/` (model, dev machines,
  customer values). See `docs/deployment-modes.md`.
- `db/<product-version>/` — SQL migration scripts grouped by release (e.g. `db/1.11.0/`)
- `license/` — public key material for offline licensing

## Conventions

- **Generic CRUD first.** New entities extend `_BaseEntity` and reuse
  `_BaseRepository` / `_BaseService` / `_BaseController`. Read `docs/generics.md` before
  hand-writing a controller.
- **Controllers are named `<Entity>API.java`**, not `*Controller`.
- **DB changes ship as a numbered SQL script** under `src/main/resources/db/<next version>/`
  and are reflected in the matching module doc. Never edit a released script.
- **Role enforcement** is in the frontend only: the menu shows entries by CASL permission, and
  the route guard checks only routes with `meta.resource` (today `/admin/roles`). The API does
  not check roles: there is no `@PreAuthorize`, and the JWT carries no authorities.
- **Money and VAT**: totals are computed VAT-inclusive first, then reduced backwards to
  the excluding-VAT amounts. See `docs/modules/discounts.md`.
- New public endpoints must be added to `SecurityConfig` `permitAll()` explicitly.

## Commands

```bash
mvn -q -DskipTests compile        # fast syntax/compile check
mvn -q -DskipTests package        # build the WAR
mvn test
```

Offline build (no network): add `-o`.

## Never commit

`private_key.pem` / `*.pem` (licensing), `/uploads/`, `/target/`.
`LicenseGenerator/` is developer-side tooling — read `docs/modules/licensing.md` before
touching it.

## Documentation

All ZS Retail documentation lives in `docs/` in this repo — index in `../../CLAUDE.md`.
Keep the relevant `docs/modules/*.md` updated in the same commit as the code change.
