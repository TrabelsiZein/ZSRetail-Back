package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.SessionCloseTicketDTO;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.2.0, POS machines step 3 (item 4): POST /cashier-session/close has one path. Before, a body without cashCountLines
 * (the old {actualCash, notes}, or the till's {notes} when its closing window has no row) went through a closing method
 * that called the transactional one from inside the service, outside its transaction: the row lock answered 500 "no
 * transaction is in progress" (since 1.8.0). Now every body builds the same request and goes to
 * closeSessionWithCashCount; the till's body is read as before. Standalone MockMvc over the real CashierSessionAPI; the
 * service records the request it receives (the closing itself has its own tests).
 */
class CloseSessionBodyTest {

	private final ObjectMapper json = new ObjectMapper();
	private final List<CloseSessionRequestDTO> received = new ArrayList<>();
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		UserAccount cashier = new UserAccount();
		cashier.setId(3L);
		cashier.setUsername("cashier");
		CashierSession open = new CashierSession();
		open.setId(7L);
		open.setSessionNumber("SESSION7");
		open.setStatus(SessionStatus.OPENED);

		CashierSessionService sessions = new CashierSessionService() {
			@Override
			public Optional<CashierSession> getCurrentOpenSession(UserAccount user) {
				return Optional.of(open);
			}

			@Override
			public CashierSession closeSessionWithCashCount(Long sessionId, CloseSessionRequestDTO request) {
				assertEquals(7L, sessionId);
				received.add(request);
				open.setStatus(SessionStatus.CLOSED);
				return open;
			}

			@Override
			public SessionCloseTicketDTO getSessionCloseTicketData(Long sessionId) {
				return new SessionCloseTicketDTO();
			}
		};
		CashierSessionAPI api = new CashierSessionAPI();
		set(api, _BaseController.class, "service", sessions);
		set(api, CashierSessionAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return cashier;
			}
		});
		mvc = MockMvcBuilders.standaloneSetup(api).build();
	}

	private static void set(Object target, Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private MockHttpServletResponse close(Object body) throws Exception {
		return mvc.perform(post("/cashier-session/close").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
	}

	@Test
	@DisplayName("The body {notes} alone closes through the transactional closing, with no counted line (was 500)")
	void notesOnly() throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("notes", "fin de journée");

		MockHttpServletResponse answer = close(body);
		assertEquals(200, answer.getStatus(), answer.getContentAsString());
		assertTrue(answer.getContentAsString().contains("sessionCloseTicket"));
		assertEquals(1, received.size());
		CloseSessionRequestDTO request = received.get(0);
		assertEquals("fin de journée", request.getNotes());
		assertNull(request.getActualCash());
		assertNotNull(request.getCashCountLines());
		assertTrue(request.getCashCountLines().isEmpty());
		assertNull(request.getTillGrant());
		assertFalse(request.isDiscrepancyConfirmed());
	}

	@Test
	@DisplayName("The old body {actualCash, notes} closes the same way, its amount kept (number or text)")
	void oldBody() throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("actualCash", 50);
		body.put("notes", "ancien format");
		assertEquals(200, close(body).getStatus());
		body.put("actualCash", "75.5");
		assertEquals(200, close(body).getStatus());

		assertEquals(50.0, received.get(0).getActualCash());
		assertEquals("ancien format", received.get(0).getNotes());
		assertTrue(received.get(0).getCashCountLines().isEmpty());
		assertEquals(75.5, received.get(1).getActualCash());
	}

	@Test
	@DisplayName("The till's body (counted lines, badge grant, confirmed difference) is read as before")
	void tillBody() throws Exception {
		Map<String, Object> cash = new HashMap<>();
		cash.put("denominationValue", 115.0);
		cash.put("quantity", 1);
		Map<String, Object> card = new HashMap<>();
		card.put("denominationValue", 40.5);
		card.put("quantity", 1);
		card.put("paymentMethodId", 2);
		card.put("referenceNumber", "1234");
		card.put("notes", "TPE");
		Map<String, Object> body = new HashMap<>();
		body.put("notes", "clôture");
		body.put("cashCountLines", Arrays.asList(cash, card));
		body.put("tillGrant", "grant-1");
		body.put("discrepancyConfirmed", true);

		assertEquals(200, close(body).getStatus());
		CloseSessionRequestDTO request = received.get(0);
		assertEquals("clôture", request.getNotes());
		assertNull(request.getActualCash());
		assertEquals("grant-1", request.getTillGrant());
		assertTrue(request.isDiscrepancyConfirmed());
		assertEquals(2, request.getCashCountLines().size());
		CloseSessionRequestDTO.CashCountLineDTO first = request.getCashCountLines().get(0);
		assertEquals(115.0, first.getDenominationValue());
		assertEquals(Integer.valueOf(1), first.getQuantity());
		assertNull(first.getPaymentMethodId());
		CloseSessionRequestDTO.CashCountLineDTO second = request.getCashCountLines().get(1);
		assertEquals(Long.valueOf(2), second.getPaymentMethodId());
		assertEquals("1234", second.getReferenceNumber());
		assertEquals("TPE", second.getNotes());

		// a quantity with decimals is still refused naming the field (2.2.1)
		cash.put("quantity", 1.5);
		MockHttpServletResponse refused = close(body);
		assertEquals(400, refused.getStatus());
		assertTrue(refused.getContentAsString().contains("cashCountLines.quantity"), refused.getContentAsString());
	}

	@Test
	@DisplayName("Every public closing method of the service runs in its own transaction (no closing outside one)")
	void closingMethodsTransactional() {
		for (Method method : CashierSessionService.class.getDeclaredMethods()) {
			if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("close")) {
				assertNotNull(method.getAnnotation(Transactional.class), method.getName() + " is not @Transactional");
			}
		}
	}
}
