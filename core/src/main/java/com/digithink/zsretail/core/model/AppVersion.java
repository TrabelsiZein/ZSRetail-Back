package com.digithink.zsretail.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton table — always exactly one row: the version of the WAR that last started on this database (ZZDataInitializer).
 * Until 3.1.2 it had to match app.version or the start was refused (AppVersionGuard). From 4.0.0 the schema's version is
 * Flyway's (flyway_schema_history); this row decides only whether an existing database can be taken over
 * (db/migration/common/beforeBaseline.sql: at 3.1.2 only), and makes an older WAR refuse a database a newer one ran on.
 */
@Entity
@Table(name = "APP_VERSION")
@Data
@NoArgsConstructor
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String version;

    public AppVersion(String version) {
        this.version = version;
    }
}
