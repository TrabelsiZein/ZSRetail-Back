# ZSRetail-Back — Backend Guide

Spring Boot backend for ZS Retail. See `../../CLAUDE.md` for the project-wide map and
the development contract; this file covers backend specifics only.

## Stack

- Java 11 · Spring Boot 2.3.9 · packaged as **WAR** (deployed to Tomcat 9)
- Spring Security + JWT · JPA/Hibernate · SQL Server
- Maven, `com.digithink:pos` (version is the product version, currently 1.11.x)
- springdoc-openapi UI for API docs
- Context path `/pos/api`, port `444`

## Layout — `src/main/java/com/digithink/pos/`

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
- `application.properties` + one file per profile:
  `standalone-dev|prod`, `dynamics-dev|test|prod`, `franchise-admin`, `franchise-customer`
- `db/<product-version>/` — SQL migration scripts grouped by release (e.g. `db/1.11.0/`)
- `license/` — public key material for offline licensing

## Conventions

- **Generic CRUD first.** New entities extend `_BaseEntity` and reuse
  `_BaseRepository` / `_BaseService` / `_BaseController`. Read `docs/generics.md` before
  hand-writing a controller.
- **Controllers are named `<Entity>API.java`**, not `*Controller`.
- **DB changes ship as a numbered SQL script** under `src/main/resources/db/<next version>/`
  and are reflected in the matching module doc. Never edit a released script.
- **Role enforcement** happens in the router, the navigation, *and* the API
  (`@PreAuthorize`). All three must agree: `ADMIN`, `RESPONSIBLE`, `POS_USER`.
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
