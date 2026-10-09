package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesInvoiceSettingCheck;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.repository.GeneralSetupChangeLogRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.GeneralSetupValueCheck;

/** Release 2.2: the General Setup "Read ERP invoices after number": empty or an invoice number, anything else refused. */
class NavPosPagesInvoiceSettingCheckTest {

	private final NavPosPagesInvoiceSettingCheck check = new NavPosPagesInvoiceSettingCheck(new NavPosPagesProperties());

	@Test
	@DisplayName("Empty, blank or an invoice number pass; other settings are not looked at")
	void accepted() {
		check.check("ERP_INVOICES_READ_AFTER", null);
		check.check("ERP_INVOICES_READ_AFTER", "");
		check.check("ERP_INVOICES_READ_AFTER", "  ");
		check.check("ERP_INVOICES_READ_AFTER", "FVV26000000123");
		check.check("ERP_INVOICES_READ_AFTER", " FVV25000000001 ");
		check.check("DEFAULT_LOCATION", "anything");
	}

	@Test
	@DisplayName("Anything else is refused with a clear message")
	void refused() {
		for (String wrong : new String[] { "FVV26", "123", "FA26000000123", "FVV2600000012X" }) {
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> check.check("ERP_INVOICES_READ_AFTER", wrong), wrong);
			assertEquals("'" + wrong + "' is not an invoice number of the ERP: write the number after which the invoices"
					+ " are read, e.g. FVV26000000123 (FVV, the year in 2 digits, then digits), or leave it empty to read"
					+ " every invoice of the page.", error.getMessage());
		}
	}

	@Test
	@DisplayName("General Setup: a refused value is not saved (the API answers 400 with the message)")
	void notSaved() {
		GeneralSetupRepository repository = mock(GeneralSetupRepository.class);
		GeneralSetup setting = new GeneralSetup();
		setting.setCode("ERP_INVOICES_READ_AFTER");
		setting.setValeur("");
		when(repository.findById(7L)).thenReturn(Optional.of(setting));
		GeneralSetupService service = new GeneralSetupService();
		ReflectionTestUtils.setField(service, "generalSetupRepository", repository);
		ReflectionTestUtils.setField(service, "generalSetupChangeLogRepository", mock(GeneralSetupChangeLogRepository.class));
		ReflectionTestUtils.setField(service, "valueChecks", Collections.<GeneralSetupValueCheck>singletonList(check));

		GeneralSetup wrong = new GeneralSetup();
		wrong.setValeur("FVV26");
		assertThrows(IllegalStateException.class, () -> service.updateFromAdmin(7L, wrong, null));
		assertEquals("", setting.getValeur());
		verify(repository, never()).save(any());
	}
}
