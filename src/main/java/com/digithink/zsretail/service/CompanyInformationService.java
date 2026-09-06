package com.digithink.zsretail.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.CompanyInformationDTO;
import com.digithink.zsretail.model.CompanyInformation;
import com.digithink.zsretail.repository.CompanyInformationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Manages the single CompanyInformation record (id=1).
 * Only GET and UPDATE operations are exposed — no create/delete.
 */
@Service
@RequiredArgsConstructor
public class CompanyInformationService {

    private static final Long SINGLETON_ID = 1L;

    private final CompanyInformationRepository repository;

    /**
     * Returns the singleton company information record.
     * Guaranteed to exist (seeded by ZZDataInitializer).
     */
    public CompanyInformationDTO get() {
        CompanyInformation entity = repository.findById(SINGLETON_ID)
                .orElseGet(this::createDefaultRecord);
        return toDTO(entity);
    }

    /**
     * Updates the singleton record. Always operates on id=1 regardless of any
     * id that might be present in the payload.
     */
    @Transactional
    public CompanyInformationDTO update(CompanyInformationDTO dto, String updatedBy) {
        CompanyInformation entity = repository.findById(SINGLETON_ID)
                .orElseGet(this::createDefaultRecord);

        entity.setCompanyName(dto.getCompanyName());
        entity.setLogoBase64(dto.getLogoBase64());
        entity.setMatriculeFiscal(dto.getMatriculeFiscal());
        entity.setAddress(dto.getAddress());
        entity.setCity(dto.getCity());
        entity.setPostalCode(dto.getPostalCode());
        entity.setCountry(dto.getCountry());
        entity.setPhone(dto.getPhone());
        entity.setFax(dto.getFax());
        entity.setEmail(dto.getEmail());
        entity.setWebsite(dto.getWebsite());
        entity.setBankName(dto.getBankName());
        entity.setBankAccount(dto.getBankAccount());
        entity.setRib(dto.getRib());
        entity.setInvoiceFooterNote(dto.getInvoiceFooterNote());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(updatedBy);

        return toDTO(repository.save(entity));
    }

    /**
     * Ensures the singleton record exists. Called by ZZDataInitializer on startup.
     */
    @Transactional
    public void ensureExists() {
        if (!repository.existsById(SINGLETON_ID)) {
            createDefaultRecord();
        }
    }

    private CompanyInformation createDefaultRecord() {
        CompanyInformation entity = new CompanyInformation();
        entity.setId(SINGLETON_ID);
        entity.setCompanyName("");
        entity.setCreatedBy("System");
        entity.setUpdatedBy("System");
        entity.setActive(true);
        return repository.save(entity);
    }

    private CompanyInformationDTO toDTO(CompanyInformation e) {
        CompanyInformationDTO dto = new CompanyInformationDTO();
        dto.setCompanyName(e.getCompanyName());
        dto.setLogoBase64(e.getLogoBase64());
        dto.setMatriculeFiscal(e.getMatriculeFiscal());
        dto.setAddress(e.getAddress());
        dto.setCity(e.getCity());
        dto.setPostalCode(e.getPostalCode());
        dto.setCountry(e.getCountry());
        dto.setPhone(e.getPhone());
        dto.setFax(e.getFax());
        dto.setEmail(e.getEmail());
        dto.setWebsite(e.getWebsite());
        dto.setBankName(e.getBankName());
        dto.setBankAccount(e.getBankAccount());
        dto.setRib(e.getRib());
        dto.setInvoiceFooterNote(e.getInvoiceFooterNote());
        return dto;
    }
}
