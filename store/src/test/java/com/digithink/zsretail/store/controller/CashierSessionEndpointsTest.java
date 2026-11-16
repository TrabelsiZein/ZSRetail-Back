package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.SessionCloseTicketDTO;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.digithink.zsretail.store.service.SessionReportService;

/**
 * 3.2.0: the endpoints of /cashier-session over HTTP (standalone MockMvc over the real CashierSessionAPI, so the
 * mappings themselves are checked, the overridden generic ones included). The eight generic endpoints of
 * _BaseController answer 403 and touch no session; the cashier's till keeps its endpoints (open, current, the ticket
 * count and methods, close, their own ticket Z); /history and /{id}/details need read:admin-sessions-history.
 */
class CashierSessionEndpointsTest {

	private static final String SESSIONS = CashierSessionAPI.SESSIONS_PERMISSION;

	private final UserAccount cashier = user(3L, "cashier");
	private final UserAccount responsible = user(4L, "resp");
	private UserAccount logged;
	private final CashierSession session = new CashierSession();
	/** What reached the service: the generic CRUD must never appear here. */
	private final List<String> touched = new ArrayList<>();
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		session.setId(7L);
		session.setSessionNumber("SESSION7");
		session.setStatus(SessionStatus.OPENED);
		session.setCashier(cashier);
		session.setOpeningCash(100.0);

		CashierSessionService sessions = new CashierSessionService() {
			// the generic CRUD (_BaseService): recorded, so a closed endpoint that reached it fails the test
			@Override
			public List<CashierSession> findAll() {
				touched.add("findAll");
				return Arrays.asList(session);
			}

			@Override
			public Optional<CashierSession> findById(Long id) {
				touched.add("findById");
				return id == 7L ? Optional.of(session) : Optional.empty();
			}

			@Override
			public List<CashierSession> findByField(String fieldName, String operation, Object value) {
				touched.add("findByField");
				return Arrays.asList(session);
			}

			@Override
			public CashierSession save(CashierSession entity) {
				touched.add("save");
				return entity;
			}

			@Override
			public void deleteById(Long id) {
				touched.add("deleteById");
			}

			@Override
			public long count() {
				touched.add("count");
				return 1L;
			}

			// the session's own endpoints
			@Override
			public Optional<CashierSession> getCurrentOpenSession(UserAccount user) {
				return session.getStatus() == SessionStatus.OPENED && user.getId().equals(cashier.getId())
						? Optional.of(session)
						: Optional.empty();
			}

			@Override
			public CashierSession openSession(UserAccount user, Double openingCash, String remoteAddress) {
				session.setStatus(SessionStatus.OPENED);
				session.setOpeningCash(openingCash);
				return session;
			}

			@Override
			public long countSessionTickets(CashierSession s) {
				return 3L;
			}

			@Override
			public List<PaymentMethod> sessionPaymentMethods(CashierSession s) {
				return new ArrayList<>();
			}

			@Override
			public CashierSession closeSessionWithCashCount(Long sessionId, CloseSessionRequestDTO request) {
				session.setStatus(SessionStatus.CLOSED);
				return session;
			}

			@Override
			public SessionCloseTicketDTO getSessionCloseTicketData(Long sessionId) {
				return new SessionCloseTicketDTO();
			}

			@Override
			public Map<String, Object> getSessionHistory(String search, String dateFrom, String dateTo, String status,
					String syncStatus, String cashierId, String minTotalSales, String maxTotalSales,
					String minSalesCount, String maxSalesCount, String differenceSign, String posMachineId, int page,
					int size, boolean amountsVisible) {
				Map<String, Object> page0 = new HashMap<>();
				page0.put("content", new ArrayList<>());
				page0.put("totalElements", 0L);
				return page0;
			}

			@Override
			public Map<String, Object> getSessionDetails(Long sessionId) {
				SessionDashboardDTO dto = new SessionDashboardDTO();
				dto.setId(sessionId);
				dto.setStatus(SessionStatus.TERMINATED);
				dto.setTotalSalesAmount(50.0);
				Map<String, Object> details = new HashMap<>();
				details.put("session", dto);
				details.put("totalSalesAmount", 50.0);
				return details;
			}
		};
		CashierSessionAPI api = new CashierSessionAPI();
		set(api, _BaseController.class, "service", sessions);
		set(api, CashierSessionAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return logged;
			}
		});
		set(api, CashierSessionAPI.class, "sessionReportService", new SessionReportService() {
			@Override
			public SessionReportTicketDTO build(Long sessionId) {
				SessionReportTicketDTO ticket = new SessionReportTicketDTO();
				ticket.setSessionId(sessionId);
				ticket.setType("Z");
				return ticket;
			}
		});
		mvc = MockMvcBuilders.standaloneSetup(api).build();
	}

	@Test
	@DisplayName("The eight generic endpoints answer 403 to anyone (even an administrator with every session right) and"
			+ " never reach the service: no session read, created, changed or deleted")
	void genericEndpointsClosed() throws Exception {
		login(responsible, SESSIONS, "read:view-session-amounts", "read:verify-session", "read:day-journal");
		String body = "{\"id\":7,\"sessionNumber\":\"SESSION7\",\"status\":\"TERMINATED\",\"realCash\":0}";
		RequestBuilder[] generic = {
				get("/cashier-session"),
				get("/cashier-session/7"),
				get("/cashier-session/findByField").param("fieldName", "status").param("operation", "equals")
						.param("value", "OPENED"),
				post("/cashier-session").contentType(MediaType.APPLICATION_JSON).content(body),
				put("/cashier-session/7").contentType(MediaType.APPLICATION_JSON).content(body),
				delete("/cashier-session/7"),
				get("/cashier-session/7/exists"),
				get("/cashier-session/count") };
		for (RequestBuilder request : generic) {
			MockHttpServletResponse answer = mvc.perform(request).andReturn().getResponse();
			assertEquals(403, answer.getStatus(), answer.getContentAsString());
			assertTrue(answer.getContentAsString().contains("is closed"), answer.getContentAsString());
		}
		assertEquals(new ArrayList<>(), touched, "the generic CRUD was reached");
		assertEquals(SessionStatus.OPENED, session.getStatus(), "the session unchanged");
	}

	@Test
	@DisplayName("The cashier's till keeps everything: open, current, the ticket count and methods, close, their own"
			+ " ticket Z; /history and /details refused to the cashier")
	void cashierTillFlow() throws Exception {
		login(cashier, "read:cashier-interface");
		session.setStatus(SessionStatus.TERMINATED); // no open session yet
		assertEquals(200, call(post("/cashier-session/open").contentType(MediaType.APPLICATION_JSON)
				.content("{\"openingCash\":100}")).getStatus());
		MockHttpServletResponse current = call(get("/cashier-session/current"));
		assertEquals(200, current.getStatus());
		assertTrue(current.getContentAsString().contains("SESSION7"), current.getContentAsString());
		assertTrue(call(get("/cashier-session/current/ticket-count")).getContentAsString().contains("\"ticketCount\":3"));
		assertEquals(200, call(get("/cashier-session/current/payment-methods")).getStatus());
		MockHttpServletResponse closed = call(post("/cashier-session/close").contentType(MediaType.APPLICATION_JSON)
				.content("{\"notes\":\"fin\"}"));
		assertEquals(200, closed.getStatus(), closed.getContentAsString());
		assertEquals(SessionStatus.CLOSED, session.getStatus());
		MockHttpServletResponse ticketZ = call(get("/cashier-session/7/report-ticket"));
		assertEquals(200, ticketZ.getStatus(), "the till's ticket Z of their own session: " + ticketZ.getContentAsString());
		assertTrue(ticketZ.getContentAsString().contains("\"type\":\"Z\""), ticketZ.getContentAsString());

		assertEquals(403, call(get("/cashier-session/history")).getStatus());
		assertEquals(403, call(get("/cashier-session/7/details")).getStatus());
	}

	@Test
	@DisplayName("/history and /{id}/details: 403 without read:admin-sessions-history (even with the amounts' right),"
			+ " 200 with it")
	void sessionsRight() throws Exception {
		login(responsible, "read:view-session-amounts", "read:day-journal");
		MockHttpServletResponse history = call(get("/cashier-session/history"));
		assertEquals(403, history.getStatus());
		assertTrue(history.getContentAsString().contains(SESSIONS), history.getContentAsString());
		assertEquals(403, call(get("/cashier-session/7/details")).getStatus());

		login(responsible, SESSIONS);
		assertEquals(200, call(get("/cashier-session/history")).getStatus());
		MockHttpServletResponse details = call(get("/cashier-session/7/details"));
		assertEquals(200, details.getStatus());
		assertTrue(details.getContentAsString().contains("\"totalSalesAmount\":50.0"), "a TERMINATED session whole: " + details.getContentAsString());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
	}

	private void login(UserAccount user, String... permissions) {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		user.setAppRole(role);
		logged = user;
	}

	private static UserAccount user(Long id, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(name);
		return user;
	}

	private static void set(Object target, Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}
}
