package com.digithink.zsretail.core.tickettemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.core.tickettemplate.controller.TicketTemplateAPI;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;

/**
 * 3.5.0, ticket templates step 2: GET /ticket-templates for any logged user (the till prints with it); the save and the
 * reset behind write:admin-ticket-templates (the read right alone is refused, 403 with its code); a refusal answered
 * with its status and {error, code, params}.
 */
class TicketTemplateAPITest {

	private final TicketTemplateService service = mock(TicketTemplateService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final TicketTemplateAPI api = new TicketTemplateAPI(service, current,
			TestModes.of(new MockEnvironment().withProperty("warranty.enabled", "true")));

	private void holds(String... keys) {
		Set<String> held = new HashSet<>(Arrays.asList(keys));
		when(current.currentUserHasPermission(anyString())).thenAnswer(call -> held.contains(call.getArgument(0)));
		when(current.getCurrentUserName()).thenReturn("admin");
	}

	@Test
	@DisplayName("Reading: no right asked")
	void readingIsOpen() {
		when(service.page()).thenReturn(Collections.singletonMap("documents", Collections.emptyList()));
		assertEquals(Collections.singletonMap("documents", Collections.emptyList()), api.page());
		verify(current, never()).currentUserHasPermission(anyString());
	}

	@Test
	@DisplayName("Saving and resetting: refused without the write right (403, TICKET_TEMPLATE_FORBIDDEN), nothing done;"
			+ " done with it, by the logged user")
	void writesNeedTheRight() {
		holds("read:admin-ticket-templates");
		TicketTemplateException save = assertThrows(TicketTemplateException.class,
				() -> api.save("SALE", new TicketTemplateDTO()));
		assertEquals(403, save.getStatus());
		assertEquals("TICKET_TEMPLATE_FORBIDDEN", save.getCode());
		assertEquals("write:admin-ticket-templates", save.getParams().get("permission"));
		assertEquals(403, assertThrows(TicketTemplateException.class, () -> api.reset("SALE")).getStatus());
		verify(service, never()).save(anyString(), any(), anyString());
		verify(service, never()).reset(anyString());

		holds("write:admin-ticket-templates");
		TicketTemplateDTO sent = new TicketTemplateDTO();
		api.save("SALE", sent);
		verify(service).save("SALE", sent, "admin");
		assertEquals(204, api.reset("SALE").getStatusCodeValue());
		verify(service).reset("SALE");
	}

	@Test
	@DisplayName("3.5.0: warranties off (no line, blank or false): GET leaves WARRANTY out (the others listed, nothing"
			+ " deleted), PUT and DELETE of WARRANTY answer 404 {error} as /warranty, the service never called; on: listed"
			+ " and saved")
	void warrantyFollowsItsSwitch() {
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("documents", Arrays.asList(document("SALE"), document("WARRANTY"), document("TOMBOLA_SLIP")));
		page.put("formatVersion", 2);
		when(service.page()).thenReturn(page);
		holds("write:admin-ticket-templates");
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(),
				new MockEnvironment().withProperty("warranty.enabled", ""),
				new MockEnvironment().withProperty("warranty.enabled", "false") }) {
			TicketTemplateAPI off = new TicketTemplateAPI(service, current, TestModes.of(env));
			assertEquals(Arrays.asList("SALE", "TOMBOLA_SLIP"), types(off.page()));
			assertEquals(2, off.page().get("formatVersion"));
			for (ResponseEntity<?> answer : Arrays.asList(off.save("WARRANTY", new TicketTemplateDTO()), off.save("warranty", null),
					off.reset("WARRANTY"))) {
				assertEquals(404, answer.getStatusCodeValue());
				assertEquals(Collections.singletonMap("error", ApplicationModeService.WARRANTY_OFF_MESSAGE), answer.getBody());
			}
			off.save("SALE", new TicketTemplateDTO());
		}
		verify(service, never()).save(eq("WARRANTY"), any(), anyString());
		verify(service, never()).reset(anyString());
		assertEquals(3, ((List<?>) page.get("documents")).size(), "the service's answer untouched");

		assertEquals(Arrays.asList("SALE", "WARRANTY", "TOMBOLA_SLIP"), types(api.page()));
		assertEquals(200, api.save("WARRANTY", new TicketTemplateDTO()).getStatusCodeValue());
		verify(service).save(eq("WARRANTY"), any(), eq("admin"));
	}

	@Test
	@DisplayName("Step 5: a head office always lists and saves the warranty ticket (some of its stores have warranties);"
			+ " a store managed by its head office: a save refused with 403 TICKET_TEMPLATE_MANAGED")
	void headOfficeAndManaged() {
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("documents", Arrays.asList(document("SALE"), document("WARRANTY")));
		when(service.page()).thenReturn(page);
		holds("write:admin-ticket-templates");
		TicketTemplateAPI headOffice = new TicketTemplateAPI(service, current,
				TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")));
		assertEquals(Arrays.asList("SALE", "WARRANTY"), types(headOffice.page()));
		assertEquals(200, headOffice.save("WARRANTY", new TicketTemplateDTO()).getStatusCodeValue());

		when(service.save(eq("SALE"), any(), anyString())).thenThrow(new TicketTemplateException(403, "TICKET_TEMPLATE_MANAGED",
				"The head office manages the ticket templates of this store", "type", "SALE"));
		TicketTemplateException managed = assertThrows(TicketTemplateException.class, () -> api.save("SALE", new TicketTemplateDTO()));
		assertEquals(403, api.refused(managed).getStatusCodeValue());
		assertEquals("TICKET_TEMPLATE_MANAGED", api.refused(managed).getBody().get("code"));
	}

	private static Map<String, Object> document(String type) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("type", type);
		d.put("available", true);
		return d;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> types(Map<String, Object> page) {
		return ((List<Map<String, Object>>) page.get("documents")).stream().map(d -> d.get("type")).collect(Collectors.toList());
	}

	@Test
	@DisplayName("A refusal: its status and {error, code, params}")
	void refusalBody() {
		TicketTemplateException locked = TicketTemplateException.refused("TICKET_TEMPLATE_LOCKED", "locked", "section",
				"PAYMENTS", "element", null);
		assertEquals(400, api.refused(locked).getStatusCodeValue());
		assertEquals("TICKET_TEMPLATE_LOCKED", api.refused(locked).getBody().get("code"));
		assertEquals("PAYMENTS",
				((java.util.Map<?, ?>) api.refused(locked).getBody().get("params")).get("section"));
	}
}
