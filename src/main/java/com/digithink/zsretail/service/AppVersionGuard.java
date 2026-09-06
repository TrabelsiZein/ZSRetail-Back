package com.digithink.zsretail.service;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.repository.AppVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Startup guard: compares app.version (from pom.xml) to the version stored in
 * APP_VERSION table. If they differ the application stops immediately.
 *
 * Fresh install: APP_VERSION is empty (Hibernate just created it) → guard skips,
 * ZZDataInitializer seeds the version row on the same startup.
 *
 * Upgrade workflow: run db/X.Y.Z/update.sql first, then deploy the new binary.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class AppVersionGuard {

    private final AppVersionRepository appVersionRepository;

    @Value("${app.version:unknown}")
    private String appVersion;

    @PostConstruct
    public void checkVersion() {
        var rows = appVersionRepository.findAll();

        if (rows.isEmpty()) {
            // Fresh install — ZZDataInitializer will seed the version row
            log.info("APP_VERSION is empty — fresh install detected, skipping version check");
            return;
        }

        String dbVersion = rows.get(0).getVersion();

        if (!appVersion.equals(dbVersion)) {
            log.error("═══════════════════════════════════════════════════════");
            log.error("  VERSION MISMATCH — APPLICATION WILL NOT START");
            log.error("  App version : {}", appVersion);
            log.error("  DB  version : {}", dbVersion);
            log.error("  → Run db/{}/update.sql against the database first.", appVersion);
            log.error("═══════════════════════════════════════════════════════");
            throw new IllegalStateException(
                "Version mismatch: app=" + appVersion + " db=" + dbVersion +
                ". Run db/" + appVersion + "/update.sql first."
            );
        }

        log.info("Version check passed — running v{}", appVersion);
    }
}
