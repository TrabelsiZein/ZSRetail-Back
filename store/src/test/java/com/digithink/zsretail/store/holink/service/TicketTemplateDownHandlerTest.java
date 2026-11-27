package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.store.holink.dto.DownApplyResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 3.5.0, ticket templates step 5: the store side of the copies down TICKET_TEMPLATES (the real service, the table in
 * memory): active only while the head office manages the templates; a record saved HEAD_OFFICE (the same again
 * unchanged), a removal deletes the row received, a document this version does not know waiting; the head office's
 * JSON (its dates) read as sent.
 */
class TicketTemplateDownHandlerTest {

	final Map<String, TicketTemplate> rows = new HashMap<>();
	final TicketTemplatesManaged managed = mock(TicketTemplatesManaged.class);
	TicketTemplateService service;
	TicketTemplateDownHandler handler;
	final ObjectMapper headOfficeMapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@BeforeEach
	void setUp() {
		TicketTemplateRepository repository = mock(TicketTemplateRepository.class);
		when(repository.findByDocumentType(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
		when(repository.save(any(TicketTemplate.class))).thenAnswer(call -> {
			TicketTemplate row = call.getArgument(0);
			rows.put(row.getDocumentType(), row);
			return row;
		});
		doAnswer(call -> rows.remove(((TicketTemplate) call.getArgument(0)).getDocumentType())).when(repository)
				.delete(any(TicketTemplate.class));
		CompanyInformationService company = mock(CompanyInformationService.class);
		when(company.get()).thenReturn(new CompanyInformationDTO());
		service = new TicketTemplateService(repository, company, mock(GeneralSetupService.class),
				new AdminEvents(new InMemoryEventLog().eventLog()));
		handler = new TicketTemplateDownHandler(service, managed);
	}

	private JsonNode copyOf(TicketDocumentType type, String spacing) {
		TicketTemplateDTO t = service.defaults(type);
		t.getLayout().put("spacing", spacing);
		t.setOrigin(TicketTemplateService.HEAD_OFFICE);
		t.setUpdatedAt(java.time.LocalDateTime.of(2026, 11, 27, 18, 0));
		t.setUpdatedBy("admin-siege");
		return headOfficeMapper.valueToTree(t);
	}

	@Test
	@DisplayName("Active while managed only; the domain")
	void active() {
		assertEquals(DataDomain.TICKET_TEMPLATES, handler.getDomain());
		when(managed.managedByHeadOffice()).thenReturn(false);
		assertFalse(handler.active());
		when(managed.managedByHeadOffice()).thenReturn(true);
		assertTrue(handler.active());
	}

	@Test
	@DisplayName("A record saved HEAD_OFFICE, the same again unchanged, a removal deletes it, an unknown document waiting")
	void apply() {
		DownApplyResult first = handler.apply(Arrays.asList(copyOf(TicketDocumentType.SALE, "COMPACT"),
				copyOf(TicketDocumentType.SESSION_REPORT, "NORMAL")), Collections.emptyList());
		assertEquals(2, first.getApplied());
		assertEquals(TicketTemplateService.HEAD_OFFICE, rows.get("SALE").getOrigin());
		assertEquals("COMPACT", service.saved(TicketDocumentType.SALE).get().getLayout().get("spacing"));
		assertEquals("NORMAL", service.saved(TicketDocumentType.SESSION_REPORT).get().getLayout().get("spacing"));

		DownApplyResult again = handler.apply(Arrays.asList(copyOf(TicketDocumentType.SALE, "COMPACT")), Collections.emptyList());
		assertEquals(0, again.getApplied());
		assertEquals(1, again.getUnchanged());

		JsonNode later = headOfficeMapper.createObjectNode().put("documentType", "MENU_BOARD");
		DownApplyResult mixed = handler.apply(Arrays.asList(later), Arrays.asList("SALE", "RETURN_VOUCHER"));
		assertEquals(1, mixed.getWaiting(), "a document of a later head office waits");
		assertEquals(1, mixed.getRemoved(), "the SALE row received deleted; no RETURN_VOUCHER row: nothing");
		assertFalse(rows.containsKey("SALE"));
		assertTrue(rows.containsKey("SESSION_REPORT"));
	}
}
