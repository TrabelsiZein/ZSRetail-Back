package com.digithink.pos.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.pos.dto.CompanyInformationDTO;
import com.digithink.pos.security.CurrentUserProvider;
import com.digithink.pos.service.CompanyInformationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Manages the singleton company information record.
 *
 * GET  /company-info — public (used by frontend on startup for receipts/invoices)
 * PUT  /company-info — admin only (role checked manually via CurrentUserProvider)
 *
 * No POST or DELETE endpoints: the record always exists (seeded by ZZDataInitializer).
 */
@RestController
@RequestMapping("company-info")
@RequiredArgsConstructor
@Log4j2
public class CompanyInformationAPI {

    private final CompanyInformationService service;
    private final CurrentUserProvider currentUserProvider;

    /**
     * Returns the company information.
     * Public — allowed without authentication so the frontend can load it on startup.
     */
    @GetMapping
    public ResponseEntity<CompanyInformationDTO> get() {
        return ResponseEntity.ok(service.get());
    }

    /**
     * Updates the company information. Admin only.
     */
    @PutMapping
    public ResponseEntity<?> update(@RequestBody CompanyInformationDTO dto) {
        try {
            String updatedBy = currentUserProvider.getCurrentUser().getUsername();
            return ResponseEntity.ok(service.update(dto, updatedBy));
        } catch (Exception e) {
            log.error("CompanyInformationAPI::update error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update company information");
        }
    }
}
