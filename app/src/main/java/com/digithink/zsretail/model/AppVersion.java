package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton table — always exactly one row.
 * Stores the current DB schema version. Must match app.version (pom.xml)
 * or the application refuses to start (AppVersionGuard).
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
