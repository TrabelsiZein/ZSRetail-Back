package com.digithink.zsretail.core.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.CompanyInformation;
import com.digithink.zsretail.core.repository.CompanyInformationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Manages the single CompanyInformation record: the first row, whatever id IDENTITY gave it (1 on every database so far).
 * Only GET and UPDATE operations are exposed — no create/delete.
 */
@Service
@RequiredArgsConstructor
public class CompanyInformationService {

    private final CompanyInformationRepository repository;

    /**
     * Returns the singleton company information record.
     * Guaranteed to exist (seeded by ZZDataInitializer).
     */
    public CompanyInformationDTO get() {
        CompanyInformation entity = repository.findFirstByOrderByIdAsc()
                .orElseGet(this::createDefaultRecord);
        return toDTO(entity);
    }

    /**
     * Updates the singleton record, regardless of any id that might be present in the payload.
     */
    @Transactional
    public CompanyInformationDTO update(CompanyInformationDTO dto, String updatedBy) {
        CompanyInformation entity = repository.findFirstByOrderByIdAsc()
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
        entity.setReceiptPrintAddress(dto.getReceiptPrintAddress());
        entity.setReceiptFooterText(dto.getReceiptFooterText());
        entity.setReceiptThankYouText(dto.getReceiptThankYouText());
        entity.setReceiptShowThankYou(dto.getReceiptShowThankYou());
        entity.setReceiptShowSoftwareLabel(dto.getReceiptShowSoftwareLabel());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(updatedBy);

        return toDTO(repository.save(entity));
    }

    /**
     * Ensures the singleton record exists. Called by ZZDataInitializer on startup.
     */
    @Transactional
    public void ensureExists() {
        if (repository.count() == 0) {
            createDefaultRecord();
        }
    }

    /**
     * No id set: IDENTITY gives it. An id with no row was silently inserted by Hibernate 5 under a generated id anyway, and
     * Hibernate 6.6 refuses it (OptimisticLockException), so a new database would not start.
     */
    private CompanyInformation createDefaultRecord() {
        CompanyInformation entity = new CompanyInformation();
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
        dto.setReceiptPrintAddress(e.getReceiptPrintAddress());
        dto.setReceiptFooterText(e.getReceiptFooterText());
        dto.setReceiptThankYouText(e.getReceiptThankYouText());
        dto.setReceiptShowThankYou(e.getReceiptShowThankYou());
        dto.setReceiptShowSoftwareLabel(e.getReceiptShowSoftwareLabel());
        return dto;
    }
}
