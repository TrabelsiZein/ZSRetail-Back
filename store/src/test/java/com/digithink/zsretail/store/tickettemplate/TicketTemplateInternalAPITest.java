package com.digithink.zsretail.store.tickettemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO.SectionDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;

/**
 * 3.5.0, ticket templates step 4: the internal tickets (the session and store tickets X / Z, the closing ticket, the two
 * slips) through the API with the real service (the repository in memory): available, a layout only (width, size,
 * spacing, the rows at zero hidden; default today's), no blocks (a save that sends some is refused,
 * TICKET_TEMPLATE_NO_BLOCKS), the customer tickets' blank lines and separators refused, a save and a reset each an event.
 */
class TicketTemplateInternalAPITest {

	static final List<String> INTERNAL = Arrays.asList("SESSION_REPORT", "STORE_REPORT", "CLOSING_DECLARED",
			"CASH_MOVEMENT_SLIP", "CLOSING_WITHDRAWAL_SLIP");

	final Map<String, TicketTemplate> rows = new HashMap<>();
	final InMemoryEventLog log = new InMemoryEventLog();
	TicketTemplateAPI api;

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
		GeneralSetupService setup = mock(GeneralSetupService.class);
		CurrentUserProvider current = mock(CurrentUserProvider.class);
		when(current.currentUserHasPermission(anyString())).thenReturn(true);
		when(current.getCurrentUserName()).thenReturn("admin");
		TicketTemplateService service = new TicketTemplateService(repository, company, setup, new AdminEvents(log.eventLog()));
		api = new TicketTemplateAPI(service, current, TestModes.of(new MockEnvironment().withProperty("warranty.enabled", "true")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> document(String type) {
		return ((List<Map<String, Object>>) api.page().get("documents")).stream().filter(d -> type.equals(d.get("type")))
				.findFirst().get();
	}

	private static TicketTemplateDTO layout(Object... pairs) {
		TicketTemplateDTO t = new TicketTemplateDTO();
		Map<String, Object> layout = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			layout.put((String) pairs[i], pairs[i + 1]);
		}
		t.setLayout(layout);
		return t;
	}

	@Test
	@DisplayName("Each internal ticket: available, internal, no blocks, its default today's (80 mm, normal, airy, every row printed), no row")
	void defaults() {
		for (String type : INTERNAL) {
			Map<String, Object> d = document(type);
			assertEquals(true, d.get("available"), type);
			assertEquals(true, d.get("internal"), type);
			assertTrue(((List<?>) d.get("sections")).isEmpty(), type);
			assertNull(d.get("template"), type);
			TicketTemplateDTO defaults = (TicketTemplateDTO) d.get("defaults");
			Map<String, Object> expected = new LinkedHashMap<>();
			expected.put("paperWidth", 80);
			expected.put("fontSize", "NORMAL");
			expected.put("spacing", "AIRY");
			expected.put("hideZeroRows", false);
			assertEquals(expected, defaults.getLayout(), type);
			assertTrue(defaults.getSections().isEmpty(), type);
		}
	}

	@Test
	@DisplayName("Saved: 58 mm, small, compact, the rows at zero hidden; read back the same; an event in French; reset deletes the row")
	void saveAndReset() {
		for (String type : INTERNAL) {
			ResponseEntity<?> saved = api.save(type, layout("paperWidth", 58, "fontSize", "SMALL", "spacing", "COMPACT",
					"hideZeroRows", true));
			assertEquals(200, saved.getStatusCodeValue(), type);
			TicketTemplateDTO body = (TicketTemplateDTO) saved.getBody();
			assertEquals(true, body.getLayout().get("hideZeroRows"), type);
			assertEquals(58, body.getLayout().get("paperWidth"), type);
			assertTrue(body.getSections().isEmpty(), type);
			assertTrue(rows.containsKey(type), type);
			assertEquals(true, ((TicketTemplateDTO) document(type).get("template")).getLayout().get("hideZeroRows"), type);
			String details = log.rows.get(log.rows.size() - 1).getDetails();
			assertTrue(details.contains("Lignes à zéro masquées") && details.contains("Papier 58 mm"), details);
			assertFalse(details.contains("Ordre") || details.contains("Séparateurs"), details);
			assertEquals(204, api.reset(type).getStatusCodeValue(), type);
			assertFalse(rows.containsKey(type), type);
		}
	}

	@Test
	@DisplayName("Refused, nothing saved: blocks sent (TICKET_TEMPLATE_NO_BLOCKS), the customer tickets' blank lines or separators, a wrong switch")
	void refusals() {
		for (String type : INTERNAL) {
			TicketTemplateDTO withBlocks = layout("paperWidth", 80);
			withBlocks.getSections().add(new SectionDTO("HEADER", true, null));
			assertEquals("TICKET_TEMPLATE_NO_BLOCKS", refusal(type, withBlocks));
			assertEquals("TICKET_TEMPLATE_UNKNOWN_OPTION", refusal(type, layout("feedLines", 2)));
			assertEquals("TICKET_TEMPLATE_UNKNOWN_OPTION", refusal(type, layout("separator", "DASHES")));
			assertEquals("TICKET_TEMPLATE_OPTION", refusal(type, layout("hideZeroRows", "yes")));
			assertEquals("TICKET_TEMPLATE_OPTION", refusal(type, layout("paperWidth", 70)));
			assertTrue(rows.isEmpty(), type);
		}
	}

	private String refusal(String type, TicketTemplateDTO sent) {
		TicketTemplateException e = assertThrows(TicketTemplateException.class, () -> api.save(type, sent));
		assertEquals(400, api.refused(e).getStatusCodeValue());
		return e.getCode();
	}
}
