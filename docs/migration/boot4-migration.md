# Spring Boot 4 migration

Branch `feature/boot4-migration`, from `release/3.1.2`. Product version 4.0.0. Plan approved by Zein on 2026-11-08.

**Status: step 1.0 done. Next: step 1.1.** Each step starts only on Zein's go.

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

Taken by Zein on 2026-11-08, on the inventory and plan of the same day.

| # | Decision | Step |
|---|---|---|
| 1 | Build on Boot 4.0.x as planned. A final step 2.9 bumps to the latest 4.1.x before any deployment. **The first deployment is on 4.1.** | 2.9 |
| 2 | Every customer gets JDK 25 and Tomcat 11 on its upgrade night, so the current JDK and Tomcat per site do not matter. Every tracked connection string and every customer config example gets `encrypt=true;trustServerCertificate=true`. A startup check logs a clear WARN when SQL Server is below 2014. | 1.3, 1.4 |
| 3 | The head office and the stores of one customer upgrade together, the same night. No mixed-version tests; one line in the deploy guide. | 2.8 |
| 4 | `ddl-auto=validate` in every tracked config, store and head office. Schema changes ship only through `db/<version>` scripts. Steps 1.8 and 2.2 produce, with `hibernate.hbm2ddl.scripts.action=update`, the catch-up SQL Hibernate would have run: a reviewable script per database type, applied by hand on the upgrade night. Developer-local throwaway databases may keep `update`, documented. Enum CHECK constraints: their generation is disabled, so new tables behave like the existing ones. | 1.8, 2.2 |
| 5 | NTLM on HttpClient 5, registered explicitly in **one shared helper in erp-api**, used by all four connectors. Connect and read timeouts move to the connection manager with today's values: the scheduler-hang fix of 2026-10-22 must keep working. NTLM POST is proved live on ZEN's test NAV (`commandesVente`; orders were posted there on 2026-10-28), no fake server. | 1.7, 2.7 |
| 6 | Startup tests are allowed, against a throwaway local SQL Server database named `zsretail_migtest` **only**, created and dropped by the tests. Never Store A, never a customer database, never an existing local database. Its connection details are in a test-only properties file. When it is unreachable the startup tests are skipped with a clear message, not failed. | 1.2 |
| 7, 8 | Removed: springdoc, springfox, spring-security-oauth2, jasperreports (with iText, castor, jfreechart), firebase-admin, jsoup, modelmapper, commons-io 1.4, starter-webflux, reactor-core, starter-mail, starter-thymeleaf (and the `/thymeleaf` entries in `SecurityConfig` and `LicenseFilter`), jackson-dataformat-xml. The lone `@NotBlank` on `ErpSyncJob` is deleted; starter-validation is not added. Nothing is planned for Firebase or Jasper. | 1.1 |
| 9 | Base branch `feature/boot4-migration` from `release/3.1.2`. Product version 4.0.0. Customer fixes continue on `release/3.1.2` and are merged into this branch at the end of each phase. A freeze applies only during step 1.7. | all |
| 10 | The 2.7.18 internal stop is approved. Build on JDK 25 with `--release 21`. Lombok goes in the pom at step 1.5, so `-Dlombok.version` disappears. | 1.4, 1.5, 2.6 |
| 11 | The `spring.jackson.use-jackson2-defaults` decisions are taken at step 2.4. | 2.4 |
| 12 | The charset of the licence fingerprint is covered by a test at step 2.6. | 2.6 |

## Steps

File counts are estimates from grep counts, not from a trial. Total: about 350 to 400 distinct files, well over half of
them one-line edits.

### Phase 1: to the Boot 3.5 compile checkpoint, never deployed

| # | Step | Boot | Files |
|---|---|---|---|
| 1.0 | Record the real baseline: suite per module, WAR jar lists, build command, Java and Maven versions. No code change. | 2.3.9 | 0 |
| 1.1 | Remove the unused list (decision 7, 8). Compare the WAR jars with the baseline. Pin the `Accept` header in the connector tests. | 2.3.9 | ~8 |
| 1.2 | Safety nets: startup tests of both applications on `zsretail_migtest`, a status matrix on the real security chains, golden JSON of key DTOs and entities. | 2.3.9 | 8–12 new |
| 1.3 | Edits the current API allows: `fromUriString`, `getStatusCode().value()`, company info fix, dead annotations. SQL Server version WARN. | 2.3.9 | ~14 |
| 1.4 | Boot 2.7.18: config-data tests, naming strategy, H2 2.1, logging keys, bean cycles, connection strings. | 2.7.18 | 40–50 |
| 1.5 | Java 21: `release=21`, Lombok in the pom, processor paths, `proc=full`. | 2.7.18 | ~8 |
| 1.6 | `SecurityFilterChain` and lambda DSL on Security 5.8. ERROR and FORWARD dispatches permitted. | 2.7.18 | ~6 |
| 1.7 | **Freeze.** Boot 3.5.16: jakarta rename, Hibernate 6.6, Security 6.5, HttpClient 5 with the erp-api helper and the timeouts, dialect keys, `WarLauncher`. | 3.5.16 | ~210 |
| 1.8 | Behaviour the tests expose (HQL, casts, null parameters). `ddl-auto=validate`, catch-up SQL per database type, enum CHECK constraints. | 3.5.16 | 25–45 |
| 1.9 | Remove every deprecated call, run the properties migrator once, tag the checkpoint. Merge `release/3.1.2`. | 3.5.16 | 10–20 |

Exception to "green at each step": 1.7 is two commits (mechanical rename, then fixes) and compiles only after both.

### Phase 2: to Boot 4

| # | Step | Boot | Files |
|---|---|---|---|
| 2.1 | Boot 4.0.x with the classic starters and `spring-boot-jackson2`, so Jackson stays on 2: Spring 7, Security 7, Hibernate 7.2, JUnit 6, Tomcat 11 in tests, post-processor move, explicit `starter-aspectj`, JPA 3.2 criteria types. | 4.0.x | 30–50 |
| 2.2 | Hibernate 7 behaviour. Catch-up SQL produced again, per database type. | 4.0.x | 5–20 |
| 2.3 | Jackson 3, main and tests: mappers through builders, the 4 deserializers, the exceptions. Guard test banning the old imports. `spring-boot-jackson2` removed. | 4.0.x | ~100 |
| 2.4 | Compare the golden JSON and decide which Jackson 2 defaults stay (decision 11). | 4.0.x | 1–5 |
| 2.5 | Classic starters out, explicit starters in. | 4.0.x | ~15 |
| 2.6 | Compile on JDK 25 with `--release 21`, suite on JDK 25, Eclipse launches, devenv. Licence fingerprint charset test (decision 12). | 4.0.x | ~8 |
| 2.7 | Real proof, only when Zein asks: Tomcat 11, JDK 25, rehearsal databases, L2 scripts, NTLM GET and POST on ZEN's test NAV, RFID, licence, front. | 4.0.x | 0 |
| 2.8 | Upgrade path: old lines refused at startup, deploy guide (with the one line of decision 3), docs, changelogs, `db/4.0.0`. Merge `release/3.1.2`. | 4.0.x | ~15 |
| 2.9 | Bump to the latest 4.1.x and run the proof of 2.7 again on it: 4.1 is what gets deployed. | 4.1.x | 5–15 |

Exception to "green at each step": 2.3 is two commits (main, then tests) that pass one gate together, because
`@JsonDeserialize` under the new package is not read by a Jackson 2 test mapper.

## What each step must not forget

**1.1**
- All in `core/pom.xml`. Removing springdoc also deletes `core.config.OpenApiConfig` and the `/v3/api-docs`, `/swagger-ui`
  entries of `SecurityConfig` and `LicenseFilter`.
- Removing jackson-dataformat-xml changes the default `Accept` of a `RestTemplate`. See open point 3.
- A removed library may have leaked something the code compiles against: compile, then compare the jar lists.

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
| 1 | **New installations under `validate`.** `deployment-modes.md:199` and `machine-model.properties:35` say Hibernate creates the tables on an empty database, and `db/` has no full-schema script. Decision 4 covers upgrades and developers, not a new customer. Proposal: the same mechanism with `scripts.action=create` gives a full script per database type, kept in `db/4.0.0`. | Zein, before 1.8 |
| 2 | **Enum CHECK setting.** Decision 4 names a Hibernate setting; none was found (Hibernate forum, one search): the maintainers point to disabling hbm2ddl, and a user reports a dialect override. Under `validate` Hibernate creates nothing in production, so the constraints can only reach a site through the catch-up SQL, where review can strip them. To read in the 6.6 and 7.2 sources. | 1.8 |
| 3 | **`Accept` after jackson-dataformat-xml.** A connector test with `MockRestServiceServer` can prove what we send, not what NAV answers. NAV's real answer needs a live GET: at 2.7, or earlier if Zein opens the VPN. | 1.1, 2.7 |
| 4 | **Source of the catch-up SQL.** It reads the metadata of a database with the old schema, and decision 6 keeps startup tests off existing local databases. Candidates: a throwaway database built by the 3.1.2 WAR, or a restored customer copy. A script per type generated from one database may not fit a customer whose schema drifted: each `ALTER` has to tolerate that. | 1.8 |
| 5 | **This machine for 2.6 and 2.7.** JDK 25 and Tomcat 11 are not installed (JDK 21 and 23; Tomcat 9). Eclipse is `jee-2024-12`, to be checked against JDK 25. | 2.6 |
| 6 | **The rule on NAV.** The root `CLAUDE.md` still says the test instance at 192.168.10.166, GET only. Decision 5 allows POST on ZEN's test NAV, which is another instance (`erp.zen.base-url` of `configs/local/test_zen_*`). 192.168.10.156 stays forbidden. | Zein, before 2.7 |
| 7 | **When the pom says 4.0.0.** Proposal: at 1.1, the first commit that needs a CHANGELOG section. `db/4.0.0` starts when first needed and closes at 2.8. `configuration-keys.txt` holds `app.version`, so the record changes with it. | Zein, at 1.1 |

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
| 1.0 | 2026-11-08 | tested `53c8996`, no code change; recorded in the commit that adds this file | 1,884 tests, 0 failures, 0 errors, 3 skipped. Both WARs built. |
