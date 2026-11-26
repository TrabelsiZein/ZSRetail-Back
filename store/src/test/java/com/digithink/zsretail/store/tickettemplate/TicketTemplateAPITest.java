package com.digithink.zsretail.store.tickettemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;

/**
 * 3.5.0, ticket templates step 2: GET /ticket-templates for any logged user (the till prints with it); the save and the
 * reset behind write:admin-ticket-templates (the read right alone is refused, 403 with its code); a refusal answered
 * with its status and {error, code, params}.
 */
class TicketTemplateAPITest {

	private final TicketTemplateService service = mock(TicketTemplateService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final TicketTemplateAPI api = new TicketTemplateAPI(service, current);

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
