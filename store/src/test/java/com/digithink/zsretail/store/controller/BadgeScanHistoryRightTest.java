package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.BadgeScanLogService;

/**
 * 3.4.0, event log step 7: the old page "Journal de scan badge" stays where the event log is off, its route now behind
 * read:admin-badge-scan-history (ZSRetail-Front); its API already was (step S, 3.1.0): 403 without the right, the history
 * never read; with it, the page of rows.
 */
class BadgeScanHistoryRightTest {

	private final BadgeScanLogService history = mock(BadgeScanLogService.class);
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		BadgeAPI api = new BadgeAPI();
		ReflectionTestUtils.setField(api, "badgeScanLogService", history);
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		mvc = MockMvcBuilders.standaloneSetup(api).build();
		when(history.getScanHistoryPaginated(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
				.thenReturn(Page.empty());
	}

	private MockHttpServletResponse call() throws Exception {
		return mvc.perform(get("/badge/scan-history")).andReturn().getResponse();
	}

	@Test
	@DisplayName("Without read:admin-badge-scan-history: 403, the history never read")
	void withoutTheRight() throws Exception {
		when(current.currentUserHasPermission("read:admin-badge-scan-history")).thenReturn(false);
		MockHttpServletResponse answer = call();
		assertEquals(403, answer.getStatus());
		assertTrue(answer.getContentAsString().contains("read:admin-badge-scan-history"), answer.getContentAsString());
		verifyNoInteractions(history);
	}

	@Test
	@DisplayName("With it: the rows")
	void withTheRight() throws Exception {
		when(current.currentUserHasPermission("read:admin-badge-scan-history")).thenReturn(true);
		assertEquals(200, call().getStatus());
	}
}
