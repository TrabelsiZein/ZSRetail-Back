package com.digithink.zsretail.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.LicenseRecord;
import com.digithink.zsretail.model.enumeration.LicenseStatus;
import com.digithink.zsretail.repository.LicenseRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Core licensing service.
 * Loads the RSA public key from classpath, verifies uploaded license files,
 * and reports the current license status.
 *
 * License file format (JSON):
 * {
 *   "data": {
 *     "company": "ABC Store",
 *     "installationId": "SHA256_MACHINE_FINGERPRINT",
 *     "issuedAt": "2026-01-01",
 *     "expiresAt": "2027-01-01"
 *   },
 *   "signature": "BASE64_RSA_SHA256_SIGNATURE"
 * }
 *
 * The signature covers the compact JSON of the "data" object.
 */
@Service
@RequiredArgsConstructor
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    private static final int WARNING_DAYS = 14;

    private final LicenseRecordRepository licenseRepo;
    private final ObjectMapper objectMapper;
    private final MachineFingerprintService machineFingerprintService;

    private PublicKey publicKey;

    @PostConstruct
    private void loadPublicKey() {
        try (InputStream is = getClass().getResourceAsStream("/license/public_key.pem")) {
            if (is == null) {
                log.error("public_key.pem not found in classpath at /license/public_key.pem");
                return;
            }
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            log.info("License public key loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load license public key: {}", e.getMessage());
        }
    }

    // ─── Status ──────────────────────────────────────────────────────────────

    public LicenseStatus getStatus() {
        Optional<LicenseRecord> current = licenseRepo.findByCurrentLicenseTrue();
        if (current.isEmpty()) return LicenseStatus.MISSING;
        LicenseRecord lic = current.get();
        if (!isBoundToCurrentMachine(lic)) return LicenseStatus.EXPIRED;
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), lic.getExpiresAt());
        if (daysLeft < 0)  return LicenseStatus.EXPIRED;
        if (daysLeft <= WARNING_DAYS) return LicenseStatus.WARNING;
        return LicenseStatus.VALID;
    }

    public long getDaysUntilExpiry() {
        return licenseRepo.findByCurrentLicenseTrue()
                .map(l -> isBoundToCurrentMachine(l) ? ChronoUnit.DAYS.between(LocalDate.now(), l.getExpiresAt()) : -1L)
                .orElse(-1L);
    }

    public Optional<LicenseRecord> getCurrentLicense() {
        return licenseRepo.findByCurrentLicenseTrue();
    }

    public List<LicenseRecord> getLicenseHistory() {
        return licenseRepo.findAllByOrderByUploadedAtDesc();
    }

    // ─── Upload ───────────────────────────────────────────────────────────────

    /**
     * Parses and validates a license JSON string, then persists it as the new current license.
     * Throws IllegalArgumentException with a user-facing message if invalid.
     */
    public LicenseRecord uploadLicense(String licenseJson) {
        try {
            JsonNode root = objectMapper.readTree(licenseJson);
            JsonNode data = root.get("data");
            String signatureB64 = root.path("signature").asText();

            if (data == null || signatureB64.isBlank()) {
                throw new IllegalArgumentException("Invalid license format: missing 'data' or 'signature'");
            }

            // Verify RSA signature
            if (publicKey == null) {
                throw new IllegalStateException("License public key not loaded — contact support");
            }
            String dataJson = objectMapper.writeValueAsString(data);
            if (!verifySignature(dataJson, signatureB64)) {
                throw new IllegalArgumentException("License signature is invalid — this license was not issued by a trusted source");
            }

            // Validate installationId against machine fingerprint (runtime source of truth)
            String licenseInstallationId = data.path("installationId").asText();
            if (licenseInstallationId == null || licenseInstallationId.isBlank()) {
                throw new IllegalArgumentException("License is missing installationId");
            }
            String machineInstallationId = machineFingerprintService.getInstallationId();
            if (!machineInstallationId.equalsIgnoreCase(licenseInstallationId)) {
                throw new IllegalArgumentException("License is not valid for this machine (installationId mismatch)");
            }

            // Parse fields
            String company  = data.path("company").asText();
            LocalDate issued  = LocalDate.parse(data.path("issuedAt").asText());
            LocalDate expires = LocalDate.parse(data.path("expiresAt").asText());

            // Persist
            String uploadedBy = getCurrentUsername();
            licenseRepo.clearCurrentLicense();

            LicenseRecord record = new LicenseRecord();
            record.setCompanyName(company);
            record.setInstallationId(licenseInstallationId);
            record.setIssuedAt(issued);
            record.setExpiresAt(expires);
            record.setRawJson(licenseJson);
            record.setUploadedAt(LocalDateTime.now());
            record.setUploadedBy(uploadedBy);
            record.setCurrentLicense(true);
            record.setCreatedBy(uploadedBy);

            LicenseRecord saved = licenseRepo.save(record);
            log.info("License uploaded: company='{}', installationId='{}', expires={}, by={}",
                    company, mask(licenseInstallationId), expires, uploadedBy);
            return saved;

        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing license upload: {}", e.getMessage());
            throw new IllegalArgumentException("Failed to process license file: " + e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean verifySignature(String dataJson, String signatureB64) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update(dataJson.getBytes(StandardCharsets.UTF_8));
        byte[] sigBytes = Base64.getDecoder().decode(signatureB64);
        return sig.verify(sigBytes);
    }

    private String getCurrentUsername() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return (auth != null && auth.getName() != null) ? auth.getName() : "system";
        } catch (Exception e) {
            return "system";
        }
    }

    private String mask(String id) {
        if (id == null || id.length() < 12) return "N/A";
        return id.substring(0, 6) + "..." + id.substring(id.length() - 6);
    }

    private boolean isBoundToCurrentMachine(LicenseRecord record) {
        String storedInstallationId = record.getInstallationId();
        if (storedInstallationId == null || storedInstallationId.isBlank()) {
            return false;
        }
        String machineInstallationId = machineFingerprintService.getInstallationId();
        return machineInstallationId.equalsIgnoreCase(storedInstallationId);
    }
}
