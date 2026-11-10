package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per release note entry.
 * type: NEW | FIX | IMPROVE
 */
@Entity
@Table(name = "APP_RELEASE_NOTES")
@Data
@NoArgsConstructor
public class AppReleaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Column(name = "released_at")
    private LocalDateTime releasedAt = LocalDateTime.now();

    public AppReleaseNote(String version, String type, String description) {
        this.version = version;
        this.type = type;
        this.description = description;
    }
}
