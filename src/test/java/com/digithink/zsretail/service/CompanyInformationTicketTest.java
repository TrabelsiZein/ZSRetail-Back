package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.CompanyInformationDTO;
import com.digithink.zsretail.model.CompanyInformation;
import com.digithink.zsretail.repository.CompanyInformationRepository;

/**
 * 2.2.2, step 5: the five sale-ticket fields of Company Information are stored and read back as sent; left null they stay
 * null (the ticket of 2.2.1). Plain JUnit over an in-memory repository.
 */
class CompanyInformationTicketTest {

	private final CompanyInformation row = new CompanyInformation();

	private CompanyInformationService service() {
		row.setId(1L);
		CompanyInformationRepository repository = (CompanyInformationRepository) Proxy.newProxyInstance(
				CompanyInformationRepository.class.getClassLoader(), new Class<?>[] { CompanyInformationRepository.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "findById": return Optional.of(row);
						case "save": return args[0];
						default: throw new UnsupportedOperationException(method.getName());
					}
				});
		return new CompanyInformationService(repository);
	}

	@Test
	@DisplayName("Null everywhere (nobody opened the page, or saved it untouched): null kept, as 2.2.1")
	void nullKept() {
		CompanyInformationService service = service();
		CompanyInformationDTO dto = service.get();
		assertNull(dto.getReceiptPrintAddress());
		assertNull(dto.getReceiptFooterText());
		assertNull(dto.getReceiptThankYouText());
		assertNull(dto.getReceiptShowThankYou());
		assertNull(dto.getReceiptShowSoftwareLabel());
		dto.setInvoiceFooterNote("Note");
		CompanyInformationDTO saved = service.update(dto, "admin");
		assertNull(saved.getReceiptShowThankYou());
		assertNull(row.getReceiptShowSoftwareLabel());
		assertEquals("Note", saved.getInvoiceFooterNote());
	}

	@Test
	@DisplayName("The five fields stored and read back, the footer's line breaks kept")
	void stored() {
		CompanyInformationService service = service();
		CompanyInformationDTO dto = service.get();
		dto.setReceiptPrintAddress(true);
		dto.setReceiptFooterText("Ligne 1\nLigne 2\nLigne 3");
		dto.setReceiptThankYouText("Bonne journée !");
		dto.setReceiptShowThankYou(false);
		dto.setReceiptShowSoftwareLabel(false);
		service.update(dto, "admin");
		CompanyInformationDTO read = service.get();
		assertEquals(true, read.getReceiptPrintAddress());
		assertEquals("Ligne 1\nLigne 2\nLigne 3", read.getReceiptFooterText());
		assertEquals("Bonne journée !", read.getReceiptThankYouText());
		assertEquals(false, read.getReceiptShowThankYou());
		assertEquals(false, read.getReceiptShowSoftwareLabel());
	}
}
