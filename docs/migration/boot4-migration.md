# Spring Boot 4 migration

Branch `feature/boot4-migration`, from `release/3.1.2`. Product version 4.0.0. Plan approved by Zein on 2026-11-08.

**Status: step 1.1 done. Next: step 1.2.** Each step starts only on Zein's go.

| | Today | Target |
|---|---|---|
| Spring Boot | 2.3.9 | 4.0.x, then the latest 4.1.x (the first deployment is on 4.1) |
| Stops inside the branch, never deployed | | 2.7.18, then 3.5.16 (the compile checkpoint), then 4.0.x |
| Compile | JDK 21, target 11, no `--release` | JDK 25, `--release 21` |
| Runtime | not confirmed per site | JDK 25, external Tomcat 11, WAR |
| Stack | Spring 5.2, Hibernate 5.4, Jackson 2.11, Security 5.3 | Spring 7, Hibernate 7, Jackson 3, Security 7, Jakarta EE 11 |

## Working rules

- Every step compiles and the full suite is green before the next one. The two exceptions are stated in the tables.
- A step with skipped startup tests is **not green**. They skip when `zsretail_migtest` is unreachable, and the
  `MSSQLSERVER` service on the dev machine is Manual, so a stopped service would otherwise pass unnoticed. The progress
  log says that they ran.
- Before every `mvn`: no `javaw` of ours running on `target/classes`. During a suite: no edit of any file in the repo
  (Eclipse rebuilds with its own JDK and the build then fails on the class version).
- The progress log below is updated at the end of every step, with the commit hashes.
- `spring-boot-properties-migrator`, the classic starters and `spring-boot-jackson2` are migration aids only: none of
  them stays.

## Decisions

Taken by Zein on 2026-11-08, on the inventory and plan of the same day. Rows 4 and 13 to 15 are his second answers.

**The schema rule, from 4.0.0: a schema change = one V script next to the Java change; never edit an applied script.**

| # | Decision | Step |
|---|---|---|
| 1 | Build on Boot 4.0.x as planned. A final step 2.9 bumps to the latest 4.1.x before any deployment. **The first deployment is on 4.1.** | 2.9 |
| 2 | Every customer gets JDK 25 and Tomcat 11 on its upgrade night, so the current JDK and Tomcat per site do not matter. Every tracked connection string and every customer config example gets `encrypt=true;trustServerCertificate=true`. A startup check logs a clear WARN when SQL Server is below 2014. | 1.3, 1.4 |
| 3 | The head office and the stores of one customer upgrade together, the same night. No mixed-version tests; one line in the deploy guide. | 2.8 |
| 4 | `ddl-auto=validate` in every tracked config, store and head office. Schema changes ship only through V scripts (decision 13). Steps 1.8 and 2.2 produce, with `hibernate.hbm2ddl.scripts.action=update`, the catch-up SQL Hibernate would have run, reviewed, per database type: it becomes the `V4.0.0.x` scripts. Developer-local throwaway databases may keep `update`, documented. Enum CHECK constraints stay out through the review of the baseline and catch-up scripts, confirmed at 1.8. | 1.8, 2.2 |
| 5 | NTLM on HttpClient 5, registered explicitly in **one shared helper in erp-api**, used by all four connectors. Connect and read timeouts move to the connection manager with today's values: the scheduler-hang fix of 2026-10-22 must keep working. NTLM POST is proved live on ZEN's test NAV (`commandesVente`; orders were posted there on 2026-10-28), no fake server. | 1.7, 2.7 |
| 6 | Startup tests are allowed, against a throwaway local SQL Server database named `zsretail_migtest` **only**, created and dropped by the tests. Never Store A, never a customer database, never an existing local database. Its connection details are in a test-only properties file. When it is unreachable the startup tests are skipped with a clear message, not failed. | 1.2 |
| 7, 8 | Removed: springdoc, springfox, spring-security-oauth2, jasperreports (with iText, castor, jfreechart), firebase-admin, jsoup, modelmapper, commons-io 1.4, starter-webflux, reactor-core, starter-mail, starter-thymeleaf (and the `/thymeleaf` entries in `SecurityConfig` and `LicenseFilter`), jackson-dataformat-xml. The lone `@NotBlank` on `ErpSyncJob` is deleted; starter-validation is not added. Nothing is planned for Firebase or Jasper. | 1.1 |
| 9 | Base branch `feature/boot4-migration` from `release/3.1.2`. Product version 4.0.0. Customer fixes continue on `release/3.1.2` and are merged into this branch at the end of each phase. A freeze applies only during step 1.7. | all |
| 10 | The 2.7.18 internal stop is approved. Build on JDK 25 with `--release 21`. Lombok goes in the pom at step 1.5, so `-Dlombok.version` disappears. | 1.4, 1.5, 2.6 |
| 11 | The `spring.jackson.use-jackson2-defaults` decisions are taken at step 2.4. | 2.4 |
| 12 | The charset of the licence fingerprint is covered by a test at step 2.6. | 2.6 |
| 13 | **Flyway**, added at step 1.8. `db/migration/V<version>__<description>.sql` in core resources, one script per change. `V4.0.0__baseline.sql` is the full schema, generated per database type at 1.8 (`scripts.action=create`, reviewed), frozen forever. The 4.0.0 catch-up deltas are separate `V4.0.0.x` scripts. `baseline-on-migrate`: an existing 3.1.2 database marks the baseline as applied on its first start. Flyway replaces the manual `update.sql` run and `AppVersionGuard`; the guard stays only for what Flyway cannot check. The `db/<version>` folders stay as history, untouched. | 1.8 |
| 14 | ZEN's test NAV (SRV-NAV) allows GET and POST. EMTOP's test NAV is GET only. Production NAV instances are never called, by tests or by Claude. In the root `CLAUDE.md`. | all |
| 15 | The pom says 4.0.0 from step 1.1, with the CHANGELOG section opened there. | 1.1 |

## Steps

File counts are estimates from grep counts, not from a trial. Total: about 350 to 400 distinct files, well over half of
them one-line edits.

### Phase 1: to the Boot 3.5 compile checkpoint, never deployed

| # | Step | Boot | Files |
|---|---|---|---|
| 1.0 | Record the real baseline: suite per module, WAR jar lists, build command, Java and Maven versions. No code change. | 2.3.9 | 0 |
| 1.1 | Remove the unused list (decision 7, 8). Version 4.0.0 and its CHANGELOG section. Compare the WAR jars with the baseline. Pin the `Accept` header in the connector tests. | 2.3.9 | 26 |
| 1.2 | Safety nets: startup tests of both applications on `zsretail_migtest`, a status matrix on the real security chains, golden JSON of key DTOs and entities. | 2.3.9 | 8–12 new |
| 1.3 | Edits the current API allows: `fromUriString`, `getStatusCode().value()`, company info fix, dead annotations. SQL Server version WARN. | 2.3.9 | ~14 |
| 1.4 | Boot 2.7.18: config-data tests, naming strategy, H2 2.1, logging keys, bean cycles, connection strings. | 2.7.18 | 40–50 |
| 1.5 | Java 21: `release=21`, Lombok in the pom, processor paths, `proc=full`. | 2.7.18 | ~8 |
| 1.6 | `SecurityFilterChain` and lambda DSL on Security 5.8. ERROR and FORWARD dispatches permitted. | 2.7.18 | ~6 |
| 1.7 | **Freeze.** Boot 3.5.16: jakarta rename, Hibernate 6.6, Security 6.5, HttpClient 5 with the erp-api helper and the timeouts, dialect keys, `WarLauncher`. | 3.5.16 | ~210 |
| 1.8 | Behaviour the tests expose (HQL, casts, null parameters). Flyway: baseline per database type, catch-up `V4.0.0.x` scripts, `baseline-on-migrate`, `AppVersionGuard` out. `ddl-auto=validate`. Enum CHECK constraints confirmed. | 3.5.16 | 30–50 |
| 1.9 | Remove every deprecated call, run the properties migrator once, tag the checkpoint. Merge `release/3.1.2`. | 3.5.16 | 10–20 |

Exception to "green at each step": 1.7 is two commits (mechanical rename, then fixes) and compiles only after both.

### Phase 2: to Boot 4

| # | Step | Boot | Files |
|---|---|---|---|
| 2.1 | Boot 4.0.x with the classic starters and `spring-boot-jackson2`, so Jackson stays on 2: Spring 7, Security 7, Hibernate 7.2, JUnit 6, Tomcat 11 in tests, post-processor move, explicit `starter-aspectj`, JPA 3.2 criteria types. | 4.0.x | 30–50 |
| 2.2 | Hibernate 7 behaviour. Catch-up SQL produced again, per database type, compared with the `V4.0.0.x` scripts. | 4.0.x | 5–20 |
| 2.3 | Jackson 3, main and tests: mappers through builders, the 4 deserializers, the exceptions. Guard test banning the old imports. `spring-boot-jackson2` removed. | 4.0.x | ~100 |
| 2.4 | Compare the golden JSON and decide which Jackson 2 defaults stay (decision 11). | 4.0.x | 1–5 |
| 2.5 | Classic starters out, explicit starters in, `spring-boot-starter-flyway` among them. | 4.0.x | ~15 |
| 2.6 | Compile on JDK 25 with `--release 21`, suite on JDK 25, Eclipse launches, devenv. Licence fingerprint charset test (decision 12). | 4.0.x | ~8 |
| 2.7 | Real proof, only when Zein asks: Tomcat 11, JDK 25, rehearsal databases, L2 scripts, NTLM GET and POST on ZEN's test NAV, RFID, licence, front. | 4.0.x | 0 |
| 2.8 | Upgrade path: old lines refused at startup, deploy guide (with the one line of decision 3), docs, changelogs, release notes. Merge `release/3.1.2`. | 4.0.x | ~15 |
| 2.9 | Bump to the latest 4.1.x and run the proof of 2.7 again on it: 4.1 is what gets deployed. | 4.1.x | 5–15 |

Exception to "green at each step": 2.3 is two commits (main, then tests) that pass one gate together, because
`@JsonDeserialize` under the new package is not read by a Jackson 2 test mapper.

## What each step must not forget

**1.2**
- Both applications start for real, startup T-SQL included (`OBJECT_ID` and `sys.*`: 18 uses in 7 files), with no ERP
  connector, no head office link and the jobs off. Today no test starts a context: 0 `@SpringBootTest`, MockMvc only
  standalone (5). Anything that only fails at startup is invisible otherwise: bean cycles (396 `@Autowired` in 140 files),
  188 `@Query` validated at startup, aspects that stop applying (`HeadOfficeErpGuard`).
- The test-only properties file holds no real password. Passwords are read from the machine files at run time, never
  copied.
- Only `zsretail_migtest` is created and dropped; no statement acts on the whole instance.
- The security matrix drives the real chains: anonymous, JWT, store key, licence missing, on `/ho/**` and the rest.
- The golden JSON includes a null into a primitive, the four `@JsonFormat` patterns and error bodies.

**1.3**
- `fromHttpUrl`: 23 uses in 5 files, gone in Spring 7. `getRawStatusCode` 15, `getStatusCodeValue` 5.
- `CompanyInformationService.java:83` saves a new entity with `id=1` and no row. Hibernate 6.6 throws
  `OptimisticLockException` there for a generated id, where Hibernate 5 inserted, so a fresh database would break.
  Still to read: `PromotionDownHandler:387`, `PosMachineAPI:47`, `RfidReaderAPI:56`.
- `@EnableGlobalMethodSecurity` guards nothing (0 `@PreAuthorize`). `@Bean Principal getPrincipal()` is dead.

**1.4**
- 19 of the 20 tracked `spring.datasource.url` lines lack `encrypt` and `trustServerCertificate`. The driver goes to 10.2
  at this step. `configs/local` is Zein's, upgraded by hand.
- `logging.file.{max-size,max-history,total-size-cap,clean-history-on-start}` silently stop working: move to
  `logging.logback.rollingpolicy.*`, or 200 MB and 90 days fall back to Boot's defaults.
- `ConfigFileApplicationListener` is gone (6 test files). `ConfigurationSources.java:47` parses a source name that changes.
- `SpringPhysicalNamingStrategy` and `SpringImplicitNamingStrategy`: 18 test imports. H2 2.x: 40 test files.
- Bean cycles are forbidden from here. The parent's `spring-milestones` repository and `maven-jar-plugin.version` go.
- `network-tests/src/test/resources/config/*.txt` are records: re-record only when the difference is the intended one.

**1.5**
- JDK 23 and later skip annotation processors unless told. `-Dlombok.version` also leaves `devenv/build.ps1:13` and both
  `CLAUDE.md`. 15 `Stream.toList()` calls become legal; they compile today only because there is no `--release`.

**1.6**
- `SecurityConfig.java:48`, `HeadOfficeApiSecurityConfig.java:43`. Authorization applies to every dispatch type from
  Security 6 and `/error` is not permitted: without ERROR and FORWARD, a real error is expected to come back as 401 or 403.
- CORS stays the hand-written headers of `JWTAuthorizationFilter`.

**1.7**
- Rename only `javax.persistence`, `servlet`, `annotation`, `validation`: 176 files plus 57 inline lines. Never
  `javax.imageio`, `javax.net.ssl` or `libs/hopeland` (`javax.usb`).
- The helper: `DynamicsNavConfig`, `NavPosPagesConfig`, `ParafendriConfig`, `ZenConfig`, and `ZenProductsClient` (no auth).
  HttpClient 5's default registry has Basic, Digest and Bearer only; NTLM has been deprecated since 5.3. erp-api takes
  httpclient5 and spring-web: check `ProjectSortingGuardTest` and `ConnectorDependenciesGuardTest`.
- ZEN's test NAV answered GET with the username alone (`domain\user` gave 401, 2026-10-27): keep that working.
- `HeadOfficeClient.java:194` has its own factory, and `:600` classifies a read timeout by `SocketTimeoutException`.
  `NavPosPagesReadRetryTest` and `RetryingCatalogueSource` depend on the exception classes: retest.
- `SQLServer2012Dialect`: 14 config files and `QueryParameterBindingTest`. `BasicBinder` logger: 5 files.
  `devenv/start.ps1:26` names the old `WarLauncher` package. `getOne`: 3 in `SalesCopyReceiver`.
- Internal Hibernate API in `QueryParameterBindingTest` and `ItemJsonProxyTest`.

**1.8, 2.2**
- Flyway on Boot 3.5 is `flyway-core` plus `flyway-sqlserver` (11.7.2 in the 3.5.14 BOM): `spring-boot-starter-flyway` exists
  only from Boot 4, so the starter comes at 2.5. The startup tests go through Flyway from here: an empty
  `zsretail_migtest`, baseline, `validate`. They prove the new-install path.
- Casts that may break at run time: `(Long)` 19, `(Double)` 11, `(Integer)` 8, `(BigDecimal)` 10 on `Object[]` rows.
  `(Number)` 106 is safe. `:param is null`: 49 in 17 files, to run on real SQL Server.
- Hibernate 7's `update` alters existing columns when a type or length differs (`StandardTableMigrator`,
  `SQLServerDialect.supportsAlterColumnType()` is true), with no `NULL` or `NOT NULL` clause. Read every `ALTER COLUMN` of
  the catch-up SQL. Hibernate 5 never touched existing columns, so drift has had years to build up.
- Under `validate`, the startup T-SQL runs after the check: `TillSchemaUpgrade`, `UserBadgeCodeIndex`,
  `StoreErpCustomerIndex`, `StoreOrderDraftIndex`, `JdbcCatalogueState`.
- `devenv/rehearsal.ps1` and `setup-stores.ps1` create databases: they keep `update` by `-Set`. Documented with `configs/local`.

**2.1**
- `EnvironmentPostProcessor` moves to `org.springframework.boot` (3 classes, `spring.factories`, tests).
- `session.saveOrUpdate` 1 and `session.delete` 2 in tests are gone. `CashierSessionService`, `ReturnHeaderAPI`,
  `CustomerService`, `VendorService` use the criteria types that change in JPA 3.2.
- `starter-aspectj` explicitly: without it `HeadOfficeErpGuard` could silently stop applying. The startup test must prove it.

**2.3**
- **With Jackson 2 still on the classpath (java-jwt keeps it), a `@JsonDeserialize` under its old import is silently
  ignored by Jackson 3**, which would disable the refusal of 1.5 on 9 whole-quantity fields. Hence the guard test.
- `new ObjectMapper()`: 22 in main, 68 in tests. They get Jackson 3's defaults whatever `use-jackson2-defaults` says.
- `WholeQuantityDeserializer` (`NumberDeserializers`, `JsonStreamContext`, extends `JsonMappingException`),
  `GroupItemsDeserializer`, and `withExactBigDecimals` in `NavPosInvoiceRow` and `ZenInvoiceRow`, which money depends on.
- A shared mapper factory cannot live in core for the connectors: a connector sees only erp-api.

**2.4**
- `FAIL_ON_NULL_FOR_PRIMITIVES` turns on: 173 primitive fields in `dto` packages, 115 `@RequestBody` parameters.
  `FAIL_ON_UNKNOWN_PROPERTIES` turns off for plain mappers. Checked, low risk: no field changes name under the new bean
  naming, no enum overrides `toString`.

**2.6**
- `MachineFingerprintService.java:171` reads `vol` with the default charset, which becomes UTF-8. A regex and a WMI
  fallback protect it, but a changed fingerprint would lock a site.

## Open points

| # | Point | To settle |
|---|---|---|
| 1 | **Flyway's SQL Server floor.** Flyway's open-source `SQLServerDatabase` calls `ensureDatabaseNotOlderThanOtherwiseRecommendUpgradeToFlywayEdition("14.0", Tier.PREMIUM, ...)` (read on its main branch, to check on the version Boot manages). 14.0 is SQL Server 2017, while decision 2's WARN is at 2014: a site on 2014 or 2016 would be refused at startup. Needs the SQL Server version per customer, or an upgrade on the night, or a paid Flyway. | Zein, before 1.8 |
| 2 | **Where the baselines live.** Decision 13 puts the scripts in core resources, and both WARs carry core. Flyway refuses two scripts of one version in its locations, so the store and head office `V4.0.0__baseline.sql` cannot sit side by side. Proposal: `db/migration/store` and `db/migration/headoffice` for what differs, `db/migration/common` for the rest, `spring.flyway.locations` in each type file. | 1.8 |
| 3 | **Deltas on a new database.** On an empty database Flyway runs the baseline, then the `V4.0.0.x` scripts too, and the baseline already has their result. Each delta must be a guarded no-op there. | 1.8 |
| 4 | **`AppVersionGuard`.** Answer to decision 13: it can go, except for one check Flyway cannot make, that an existing database really is at 3.1.2 when `baseline-on-migrate` marks it 4.0.0. A `V4.0.0.x` script that fails when `APP_VERSION` is not 3.1.2 does that in SQL, with no Java guard. A database newer than the WAR is Flyway's if `spring.flyway.ignore-migration-patterns` is emptied: its default `*:future` ignores newer scripts. To check at 1.8: who else reads the `APP_VERSION` row, and who writes the release notes of `update.sql`. **Until 1.8 the guard stays**, so this branch stops on any 3.1.2 database and names a `db/4.0.0/update.sql` that does not exist. | 1.8 |
| 5 | **Source of the catch-up SQL.** It reads the metadata of a database with the old schema, and decision 6 keeps startup tests off existing local databases. A restored copy of a customer backup Zein provides is acceptable, read-only. A script generated from one database may not fit a customer whose schema drifted: each delta has to tolerate that. Proposal due at 1.8. | 1.8 |
| 6 | **Enum CHECK constraints.** No setting found that disables them (Hibernate forum, one search). Under `validate` Hibernate creates nothing in production, so they can only arrive through the baseline and catch-up scripts, where review keeps them out. Confirmed at 1.8. | 1.8 |
| 7 | **`Accept`.** `ConnectorAcceptHeaderTest` pins what we send: it failed on the old classpath with `application/xml, text/xml, application/json, application/*+xml, application/*+json` on all four connectors, and passes on the new one. A mock cannot show what NAV answers: live GET at 2.7, or earlier when Zein opens the VPN. | 2.7 |
| 8 | **POI's jars.** 45 jars of the store WAR come with POI 5.0.0's compile dependencies (batik, pdfbox, xmlsec, bouncycastle...), and our code imports none of them: only POI's `usermodel`. Removing the commons-io 1.4 pin exposed the older versions they ask for: commons-io 1.3.1, woodstox 5.2.1, stax2 4.2. To settle when POI is upgraded. | 1.4 |
| 9 | **This machine for 2.6 and 2.7.** JDK 25 and Tomcat 11 are not installed (JDK 21 and 23; Tomcat 9). Eclipse is `jee-2024-12`, to be checked against JDK 25. Zein is told what to install at 2.6. | 2.6 |

## Baseline (step 1.0)

Run on 2026-11-08 on `53c8996` (`feature/boot4-migration`, the same commit as `release/3.1.2`), in the working tree. Eclipse
was open with no application running. No instance was started.

**Result: BUILD SUCCESS, 1,884 tests, 0 failures, 0 errors, 3 skipped**, in 6 min 32 s.

| Project | Test classes | Tests | Failures | Errors | Skipped | Maven time |
|---|---|---|---|---|---|---|
| erp-api | 0 | 0 | | | | 8.8 s |
| connectors/emtop | 0 | 0 | | | | 10.4 s |
| connectors/happyness | 0 | 0 | | | | 9.1 s |
| connectors/parafendri | 14 | 91 | 0 | 0 | 1 | 14.0 s |
| connectors/zen | 8 | 81 | 0 | 0 | 0 | 14.1 s |
| core | 79 | 446 | 0 | 0 | 0 | 1 min 43 s |
| headoffice | 45 | 307 | 0 | 0 | 0 | 1 min 24 s |
| store | 88 | 603 | 0 | 0 | 1 | 1 min 16 s |
| network-tests | 60 | 356 | 0 | 0 | 1 | 1 min 10 s |
| **Total** | **294** | **1,884** | **0** | **0** | **3** | **6 min 32 s** |

The 3 skipped are whole classes, each counted as one test, switched on by a system property for a live or hardware run:
`ParafendriLiveConnectionTest` (`parafendri.live`), `HopelandRfidReaderManualTest` (`rfid.reader`),
`NavPosPagesLiveReadTest` (`navpospages.live`). No startup test exists yet: the rule on skipped startup tests starts at 1.2.

Counts come from the surefire XML reports. They replace the 1,475 of the root `CLAUDE.md` (2026-10-27).

**Build command** (Git Bash, from `Apps/ZSRetail-Back`):

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" mvn -gs "C:\Users\DELL\.m2\zsretail-settings.xml" -Dlombok.version=1.18.36 clean test --fail-at-end
```

The WARs, the same way with `-DskipTests clean package`.

**Versions**

| | |
|---|---|
| Java | Oracle JDK 21.0.10 (build 21.0.10+8-LTS-217) |
| Maven | 3.9.0, with `-gs` replacing the machine's global settings |
| Lombok | 1.18.36 by the flag (the pom's managed 1.18.18 does not work on JDK 21) |
| OS | Windows 11, locale en_US, platform encoding UTF-8 |

**WARs**, jar lists in `baseline/`, sorted, one `WEB-INF/...` path per line, ready to compare at each step:

| WAR | Bytes | `WEB-INF/lib` | `WEB-INF/lib-provided` | List |
|---|---|---|---|---|
| `zsretail-store-3.1.2.war` | 172,611,746 | 263 | 4 | [store-war-jars.txt](baseline/store-war-jars.txt) |
| `zsretail-headoffice-3.1.2.war` | 170,322,174 | 262 | 4 | [headoffice-war-jars.txt](baseline/headoffice-war-jars.txt) |

The only difference between the lists is `rfid-reader-api-2.11-zs1.jar`, in the store WAR alone.

## Progress log

| Step | Date | Back commits | Result |
|---|---|---|---|
| 1.0 | 2026-11-08 | tested `53c8996`, no code change; recorded in `f15fb36` | 1,884 tests, 0 failures, 0 errors, 3 skipped. Both WARs built. |
| 1.1 | 2026-11-08 | `0387307` | 1,888 tests (the 1,884 and 4 new), 0 failures, 0 errors, the same 3 skipped. Store WAR 263 to 130 jars, 173 to 91 MB; head office WAR 262 to 128, 170 to 88 MB ([store diff](diffs/step-1.1-store-war-jars.diff), [head office diff](diffs/step-1.1-headoffice-war-jars.diff)). `commons-lang3` declared by the store. No instance started. |
