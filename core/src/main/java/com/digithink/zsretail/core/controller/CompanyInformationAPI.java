package com.digithink.zsretail.core.controller;

import java.util.Collections;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Manages the singleton company information record.
 *
 * GET  /company-info — public (used by frontend on startup for receipts/invoices)
 * PUT  /company-info — behind the page's permission: {@value #WRITE_COMPANY} on a store, {@value #HEAD_OFFICE_COMPANY} on a
 * head office (the one key of its page)
 *
 * No POST or DELETE endpoints: the record always exists (seeded by ZZDataInitializer).
 */
@RestController
@RequestMapping("company-info")
@RequiredArgsConstructor
@Log4j2
public class CompanyInformationAPI {

    static final String WRITE_COMPANY = "write:admin-company-information";
    static final String HEAD_OFFICE_COMPANY = "read:admin-headoffice-company-information";

    private final CompanyInformationService service;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationModeService applicationModeService;

    /**
     * Returns the company information.
     * Public — allowed without authentication so the frontend can load it on startup.
     */
    @GetMapping
    public ResponseEntity<CompanyInformationDTO> get() {
        return ResponseEntity.ok(service.get());
    }

    /**
     * Updates the company information: 403 without the page's permission. The path is public for its GET, so an anonymous
     * call gets here too and is refused the same way.
     */
    @PutMapping
    public ResponseEntity<?> update(@RequestBody CompanyInformationDTO dto) {
        String key = applicationModeService.isHeadOffice() ? HEAD_OFFICE_COMPANY : WRITE_COMPANY;
        if (!currentUserProvider.currentUserHasPermission(key)) {
            log.warn("CompanyInformationAPI::update refused without the right {}", key);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Collections.singletonMap("error", "Saving the company information needs the right " + key + "."));
        }
        try {
            String updatedBy = currentUserProvider.getCurrentUser().getUsername();
            return ResponseEntity.ok(service.update(dto, updatedBy));
        } catch (Exception e) {
            log.error("CompanyInformationAPI::update error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update company information");
        }
    }
}
