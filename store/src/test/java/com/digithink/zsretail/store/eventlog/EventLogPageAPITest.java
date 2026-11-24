package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.repository.PosMachineRepository;

/**
 * 3.4.0, event log step 6: GET /event-log, /excel, /catalogue, /missing: 404 while the module is off, 403 without
 * read:admin-event-log (the reader never asked), 400 for filters that do not parse, else the answers.
 */
class EventLogPageAPITest {

	private final EventLog eventLog = mock(EventLog.class);
	private final EventLogReader reader = mock(EventLogReader.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final PosMachineRepository machines = mock(PosMachineRepository.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		EventLogPageAPI api = new EventLogPageAPI();
		ReflectionTestUtils.setField(api, "eventLog", eventLog);
		ReflectionTestUtils.setField(api, "reader", reader);
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		ReflectionTestUtils.setField(api, "users", users);
		ReflectionTestUtils.setField(api, "machines", machines);
		mvc = MockMvcBuilders.standaloneSetup(api).build();
		when(eventLog.isOn()).thenReturn(true);
		when(current.currentUserHasPermission("read:admin-event-log")).thenReturn(true);
		when(reader.page(any(), anyInt(), anyInt())).thenReturn(new EventLogPage());
		when(reader.all(any(), anyInt())).thenReturn(Collections.emptyList());
		when(reader.missing()).thenReturn(Collections.emptyList());
	}

	private MockHttpServletResponse call(String url) throws Exception {
		MockHttpServletResponse answer = mvc.perform(get(url)).andReturn().getResponse();
		answer.setCharacterEncoding("UTF-8");
		return answer;
	}

	private static final String[] CALLS = { "/event-log?from=2026-11-18&to=2026-11-24",
			"/event-log/excel?from=2026-11-18&to=2026-11-24", "/event-log/catalogue", "/event-log/missing" };

	@Test
	@DisplayName("The module off: 404 on every call, the reader never asked")
	void offIs404() throws Exception {
		when(eventLog.isOn()).thenReturn(false);
		for (String url : CALLS) {
			MockHttpServletResponse answer = call(url);
			assertEquals(404, answer.getStatus(), url);
			assertTrue(answer.getContentAsString().contains("eventlog.enabled"), url);
		}
		verifyNoInteractions(reader);
	}

	@Test
	@DisplayName("Without read:admin-event-log: 403 on every call, the right named, the reader never asked")
	void withoutTheRight() throws Exception {
		when(current.currentUserHasPermission("read:admin-event-log")).thenReturn(false);
		for (String url : CALLS) {
			MockHttpServletResponse answer = call(url);
			assertEquals(403, answer.getStatus(), url);
			assertEquals("{\"error\":\"Permission required: read:admin-event-log\"}", answer.getContentAsString(), url);
		}
		verifyNoInteractions(reader);
	}

	@Test
	@DisplayName("With the right: the page (JSON), the Excel file, the catalogue (every user, the disabled ones too, the"
			+ " machines), the missing numbers; 400 without a period or with a bad one")
	void withTheRight() throws Exception {
		UserAccount gone = new UserAccount();
		gone.setId(4L);
		gone.setUsername("ancien");
		gone.setFullName("Ancien Caissier");
		gone.setActive(false);
		when(users.findAll()).thenReturn(Arrays.asList(gone));
		PosMachine caisse = new PosMachine();
		caisse.setId(1L);
		caisse.setName("Caisse 1");
		when(machines.findAll()).thenReturn(Arrays.asList(caisse));

		MockHttpServletResponse page = call("/event-log?from=2026-11-18&to=2026-11-24&importances=20,30&page=0");
		assertEquals(200, page.getStatus());
		assertTrue(page.getContentType().startsWith("application/json"), page.getContentType());
		assertTrue(page.getContentAsString().contains("\"counts\""), page.getContentAsString());

		MockHttpServletResponse excel = call("/event-log/excel?from=2026-11-18&to=2026-11-24");
		assertEquals(200, excel.getStatus());
		assertTrue(excel.getHeader("Content-Disposition").contains("journal-des-evenements-2026-11-18-2026-11-24.xlsx"));
		assertTrue(excel.getContentType().startsWith("application/vnd.openxmlformats"), excel.getContentType());

		String catalogue = call("/event-log/catalogue").getContentAsString();
		assertTrue(catalogue.contains("{\"code\":\"CART_LINE_ADDED\",\"subjectType\":\"CART\",\"importance\":10,"
				+ "\"label\":\"Ligne ajoutée\"}"), catalogue);
		assertTrue(catalogue.contains("{\"id\":4,\"login\":\"ancien\",\"name\":\"Ancien Caissier\",\"active\":false}"),
				catalogue);
		assertTrue(catalogue.contains("{\"id\":1,\"name\":\"Caisse 1\"}"), catalogue);
		assertTrue(catalogue.contains("\"subjects\":[\"CART\""), catalogue);

		assertEquals(200, call("/event-log/missing").getStatus());

		assertEquals(400, call("/event-log").getStatus(), "no period");
		assertEquals(400, call("/event-log?from=2026-11-24&to=2026-11-18").getStatus(), "to before from");
		assertEquals(400, call("/event-log?from=2026-11-18&to=2026-11-24&importances=ALERT").getStatus());
		assertEquals(400, call("/event-log/excel?from=x&to=2026-11-24").getStatus());
	}
}
