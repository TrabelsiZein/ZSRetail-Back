package com.digithink.zsretail.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digithink.zsretail.model.LicenseRecord;
import com.digithink.zsretail.model.enumeration.LicenseStatus;
import com.digithink.zsretail.service.LicenseService;
import com.digithink.zsretail.service.MachineFingerprintService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * License management endpoints.
 *
 * GET  /license/status  — authenticated, returns current license status
 * POST /admin/license/upload — ADMIN only, upload a new license.json file
 */
@RestController
@RequiredArgsConstructor
public class LicenseAPI {

    private final LicenseService licenseService;
    private final MachineFingerprintService machineFingerprintService;

    /**
     * Returns the current license status.
     * Called by the frontend after login to determine if license warning/block applies.
     * Accessible to any authenticated user.
     */
    @GetMapping("/license/status")
    public ResponseEntity<LicenseStatusDTO> getStatus() {
        LicenseStatus status = licenseService.getStatus();
        long daysLeft = licenseService.getDaysUntilExpiry();

        LicenseRecord current = licenseService.getCurrentLicense().orElse(null);
        List<LicenseRecord> history = licenseService.getLicenseHistory();

        LicenseStatusDTO dto = new LicenseStatusDTO();
        dto.setStatus(status.name());
        dto.setDaysUntilExpiry(daysLeft);
        dto.setMachineInstallationId(machineFingerprintService.getInstallationId());
        if (current != null) {
            dto.setCompany(current.getCompanyName());
            dto.setInstallationId(current.getInstallationId());
            dto.setIssuedAt(current.getIssuedAt());
            dto.setExpiresAt(current.getExpiresAt());
            dto.setUploadedAt(current.getUploadedAt() != null ? current.getUploadedAt().toString() : null);
            dto.setUploadedBy(current.getUploadedBy());
        }
        dto.setHistory(history.stream().map(LicenseHistoryDTO::from).collect(Collectors.toList()));
        return ResponseEntity.ok(dto);
    }

    /**
     * Upload a new license.json file.
     * Replaces the current license; keeps history.
     * Restricted to ADMIN role.
     */
    @PostMapping("/admin/license/upload")
    public ResponseEntity<?> uploadLicense(@RequestParam("file") MultipartFile file) {
        try {
            String json = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            LicenseRecord saved = licenseService.uploadLicense(json);
            LicenseHistoryDTO result = LicenseHistoryDTO.from(saved);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse("Failed to upload license: " + e.getMessage()));
        }
    }

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    public static class LicenseStatusDTO {
        private String status;
        private long daysUntilExpiry;
        private String company;
        private String installationId;
        private String machineInstallationId;
        private LocalDate issuedAt;
        private LocalDate expiresAt;
        private String uploadedAt;
        private String uploadedBy;
        private List<LicenseHistoryDTO> history;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseHistoryDTO {
        private Long id;
        private String company;
        private String installationId;
        private LocalDate issuedAt;
        private LocalDate expiresAt;
        private String uploadedAt;
        private String uploadedBy;
        private boolean currentLicense;

        public static LicenseHistoryDTO from(LicenseRecord r) {
            return new LicenseHistoryDTO(
                r.getId(),
                r.getCompanyName(),
                r.getInstallationId(),
                r.getIssuedAt(),
                r.getExpiresAt(),
                r.getUploadedAt() != null ? r.getUploadedAt().toString() : null,
                r.getUploadedBy(),
                r.isCurrentLicense()
            );
        }
    }

    @Data
    @AllArgsConstructor
    public static class ErrorResponse {
        private String message;
    }
}
