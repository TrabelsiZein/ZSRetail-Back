package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;

/**
 * 3.5.0, ticket templates step 6: TICKET_RENDERER, TICKET_SHOW_LOYALTY_BALANCE and TICKET_SHOW_LOYALTY_EARNED stay in
 * an installation of before one release (source data, carried into the ticket templates) but are never shown (the
 * General setup page reads GET /general-setup, findAll) nor read (findByCode, findValueByCode).
 */
class GeneralSetupRetiredCodesTest {

	static GeneralSetup row(String code, String value) {
		GeneralSetup setup = new GeneralSetup();
		setup.setCode(code);
		setup.setValeur(value);
		return setup;
	}

	@Test
	@DisplayName("The three retired rows: not in the list, not found by code; the others as before")
	void retiredRowsHidden() throws Exception {
		List<GeneralSetup> rows = new ArrayList<>(Arrays.asList(row("TICKET_RENDERER", "OLD"),
				row("TICKET_SHOW_LOYALTY_BALANCE", "false"), row("TICKET_SHOW_LOYALTY_EARNED", "true"),
				row("TOMBOLA_ENABLED", "true"), row("CASHIER_CLOSING_TICKET", "Z")));
		GeneralSetupRepository repository = mock(GeneralSetupRepository.class);
		when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(rows);
		when(repository.findByCode(anyString())).thenAnswer(
				call -> rows.stream().filter(r -> r.getCode().equals(call.getArgument(0))).findFirst());
		GeneralSetupService service = new GeneralSetupService();
		Field field = GeneralSetupService.class.getDeclaredField("generalSetupRepository");
		field.setAccessible(true);
		field.set(service, repository);

		assertEquals(Arrays.asList("TOMBOLA_ENABLED", "CASHIER_CLOSING_TICKET"),
				service.findAll().stream().map(GeneralSetup::getCode).collect(Collectors.toList()));
		for (String code : GeneralSetupService.RETIRED_CODES) {
			assertNull(service.findByCode(code), code);
			assertNull(service.findValueByCode(code), code);
		}
		assertEquals("true", service.findValueByCode("TOMBOLA_ENABLED"));
		assertEquals(Optional.of("Z"), Optional.ofNullable(service.findByCode("CASHIER_CLOSING_TICKET")).map(GeneralSetup::getValeur));
	}
}
