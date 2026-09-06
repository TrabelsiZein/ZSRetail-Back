package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Stores uploaded license files.
 * Each row represents one uploaded license; only one row has current=true at a time.
 * Rows are never deleted — they form the full license history.
 */
@Entity
@Table(name = "license_record")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class LicenseRecord extends _BaseEntity {

    /** Company name extracted from the license data. */
    @Column(name = "company_name")
    private String companyName;

    /** Machine installation ID extracted from the license and validated against runtime fingerprint. */
    @Column(name = "installation_id")
    private String installationId;

    /** License issue date extracted from the license data. */
    @Column(name = "issued_at")
    private LocalDate issuedAt;

    /** License expiry date extracted from the license data. */
    @Column(name = "expires_at")
    private LocalDate expiresAt;

    /** Full raw JSON of the uploaded license file (data + signature). */
    @Column(name = "raw_json", columnDefinition = "NVARCHAR(MAX)")
    private String rawJson;

    /** When this license was uploaded. */
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    /** Username of the admin who uploaded this license. */
    @Column(name = "uploaded_by")
    private String uploadedBy;

    /**
     * True for the currently active license.
     * When a new license is uploaded, this flag is set to false on all previous rows
     * and true only on the new row.
     */
    @Column(name = "current_license")
    private boolean currentLicense;
}
