package com.digithink.zsretail.core.communication.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.LicenseRecord;
import com.digithink.zsretail.core.repository.LicenseRecordRepository;
import com.digithink.zsretail.core.service.CompanyInformationService;

/**
 * 3.3.0, communication step 7: the installation's names a message may need, read when it is made. The company's name
 * (Settings, Company information) and, for the empty-field guard ({@link CommunicationTemplates#withFallbacks}), the
 * licence holder's name: the last stand-in when neither the company's nor the store's name is known. Null when empty.
 */
@Component
public class CommunicationNames {

	private final CompanyInformationService companyInformation;
	private final LicenseRecordRepository licences;

	public CommunicationNames(CompanyInformationService companyInformation, LicenseRecordRepository licences) {
		this.companyInformation = companyInformation;
		this.licences = licences;
	}

	public String companyName() {
		CompanyInformationDTO company = companyInformation.get();
		return company == null ? null : blankToNull(company.getCompanyName());
	}

	public String licenceName() {
		return licences.findByCurrentLicenseTrue().map(LicenseRecord::getCompanyName).map(CommunicationNames::blankToNull)
				.orElse(null);
	}

	private static String blankToNull(String text) {
		return text == null || text.trim().isEmpty() ? null : text.trim();
	}
}
