package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnLineRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.controller.BadgeAPI;
import com.digithink.zsretail.store.controller.CashierSessionAPI;
import com.digithink.zsretail.store.controller.ReturnHeaderAPI;
import com.digithink.zsretail.store.controller.SalesHeaderAPI;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.store.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.store.dto.SessionCloseTicketDTO;
import com.digithink.zsretail.store.support.InMemoryTillRights;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Till authorisation, end to end over HTTP (steps C to E): the real BadgeAPI, SalesHeaderAPI, ReturnHeaderAPI and
 * CashierSessionAPI under Spring MVC (standalone MockMvc: the real mappings, JSON binding and answers), over the real
 * BadgeService, TillGrantService and TillRightsGuard on in-memory tables (InMemoryTillRights). The sale, the return
 * and the close services stop after the rights (the rest of each has its own tests): the sale and the return check
 * the request with the guard, the close runs the real CashierSessionService.checkCashDiscrepancy (120 expected, 115
 * counted). No SQL Server test profile exists: the database is in memory.
 *
 * The matrix: a cashier whose role holds each right, or not; a badge scan, then the request with its grant; the grant
 * used again, expired, of another action, of another session; the administrator; the close by ROLE and by BADGE; the
 * rows of badge_scan_log.
 */
class TillRightsEndToEndTest {

	private static final BadgePermission CUSTOMERS = BadgePermission.CONSULT_CUSTOMER_LIST;
	private static final BadgePermission RETURN = BadgePermission.MAKE_RETURN;
	private static final BadgePermission LINE = BadgePermission.APPLY_LINE_DISCOUNT;
	private static final BadgePermission TOTAL = BadgePermission.APPLY_TOTAL_DISCOUNT;
	private static final BadgePermission CLOSE = BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY;

	private final ObjectMapper json = new ObjectMapper();
	private final InMemoryTillRights rights = new InMemoryTillRights();
	private final Map<String, UserAccount> badges = new HashMap<>();
	private final Map<Long, CashierSession> openSessions = new HashMap<>(); // by user id
	private UserAccount logged;
	private MockMvc mvc;

	private final AppRole adminRole = role("ADMIN", BadgePermission.values());
	private final UserAccount admin = user(1, "admin", adminRole);
	private final UserAccount responsible = user(2, "responsible", role("RESPONSIBLE", BadgePermission.values()));
	private final UserAccount cashier = user(3, "cashier", role("POS_USER"));
	private final UserAccount otherCashier = user(4, "cashier2", role("POS_USER"));

	private static AppRole role(String name, BadgePermission... rights) {
		AppRole role = new AppRole(name, name, "POS_USER".equals(name));
		role.setPermissions(new HashSet<>(Arrays.asList("read:cashier-interface")));
		for (BadgePermission right : rights) {
			role.getPermissions().add(right.key());
		}
		return role;
	}

	private static UserAccount user(long id, String username, AppRole role) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(username);
		user.setActive(true);
		user.setAppRole(role);
		return user;
	}

	private static CashierSession session(long id) {
		CashierSession session = new CashierSession();
		session.setId(id);
		session.setSessionNumber("SESSION" + id);
		session.setStatus(SessionStatus.OPENED);
		return session;
	}

	@BeforeEach
	void setUp() throws Exception {
		for (UserAccount user : Arrays.asList(admin, responsible, cashier, otherCashier)) {
			user.setBadgeCode("B-" + user.getUsername());
			user.setBadgeExpirationDate(LocalDateTime.now().plusYears(1));
			user.setBadgeRevoked(false);
			badges.put(user.getBadgeCode(), user);
		}
		openSessions.put(admin.getId(), session(6));
		openSessions.put(cashier.getId(), session(7));
		openSessions.put(otherCashier.getId(), session(8));

		CurrentUserProvider currentUser = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return logged;
			}

			@Override
			public String getCurrentUserName() {
				return logged.getUsername();
			}
		};
		BadgeService badgeService = rights.badges;
		set(badgeService, BadgeService.class, "userAccountRepository", proxy(UserAccountRepository.class,
				(m, a) -> "findByBadgeCode".equals(m) ? Optional.ofNullable(badges.get(a[0])) : UNHANDLED));
		set(badgeService, BadgeService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class,
				(m, a) -> "findByCashierAndStatus".equals(m)
						? Optional.ofNullable(openSessions.get(((UserAccount) a[0]).getId())) : UNHANDLED));
		GeneralSetupRepository setup = proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			GeneralSetup on = new GeneralSetup();
			on.setCode((String) a[0]);
			on.setValeur("true");
			return "ENABLE_CASH_DISCREPANCY_CHECK".equals(a[0]) ? Optional.of(on) : Optional.empty();
		});

		BadgeAPI badgeAPI = new BadgeAPI();
		set(badgeAPI, BadgeAPI.class, "badgeService", badgeService);
		set(badgeAPI, BadgeAPI.class, "currentUserProvider", currentUser);
		set(badgeAPI, BadgeAPI.class, "tillGrantService", rights.grantService);

		// The sale: the guard on the request, then a ticket (the rest of processCompleteSale has its own tests)
		SalesHeaderService sales = new SalesHeaderService() {
			@Override
			public SalesHeader processCompleteSale(ProcessSaleRequestDTO request, UserAccount user) {
				CashierSession open = openSessions.get(user.getId());
				rights.guard.checkSale(request, user, open, null, "SALE T-" + open.getId());
				SalesHeader ticket = new SalesHeader();
				ticket.setId(100L);
				ticket.setSalesNumber("T-" + open.getId());
				return ticket;
			}
		};
		SalesHeaderAPI salesAPI = new SalesHeaderAPI();
		set(salesAPI, _BaseController.class, "service", sales);
		set(salesAPI, SalesHeaderAPI.class, "currentUserProvider", currentUser);
		set(salesAPI, SalesHeaderAPI.class, "salesLineRepository",
				proxy(SalesLineRepository.class, (m, a) -> "findBySalesHeader".equals(m) ? new ArrayList<>() : UNHANDLED));
		set(salesAPI, SalesHeaderAPI.class, "paymentRepository",
				proxy(PaymentRepository.class, (m, a) -> "findBySalesHeader".equals(m) ? new ArrayList<>() : UNHANDLED));
		set(salesAPI, SalesHeaderAPI.class, "generalSetupRepository", setup);

		// The return: the guard on the request, then a return (the rest of processReturn has its own tests)
		ReturnHeaderService returns = new ReturnHeaderService() {
			@Override
			public ReturnHeader processReturn(ProcessReturnRequestDTO request, UserAccount user) {
				rights.guard.checkReturn(request, user, openSessions.get(user.getId()),
						"RETURN of " + request.getTicketNumber());
				ReturnHeader done = new ReturnHeader();
				done.setId(200L);
				done.setReturnNumber("R-1");
				return done;
			}
		};
		ReturnHeaderAPI returnAPI = new ReturnHeaderAPI();
		set(returnAPI, _BaseController.class, "service", returns);
		set(returnAPI, ReturnHeaderAPI.class, "currentUserProvider", currentUser);
		set(returnAPI, ReturnHeaderAPI.class, "returnLineRepository",
				proxy(ReturnLineRepository.class, (m, a) -> "findByReturnHeader".equals(m) ? new ArrayList<>() : UNHANDLED));

		// The close: the real check of the counted cash (120 expected, 115 counted) with the real guard
		CashierSessionService sessions = new CashierSessionService() {
			@Override
			public Optional<CashierSession> getCurrentOpenSession(UserAccount user) {
				return Optional.ofNullable(openSessions.get(user.getId()));
			}

			@Override
			public CashierSession closeSessionWithCashCount(Long sessionId, CloseSessionRequestDTO request) {
				CashierSession open = openSessions.get(logged.getId());
				checkCashDiscrepancy(open, 120.0, 115.0, request);
				return open;
			}

			@Override
			public SessionCloseTicketDTO getSessionCloseTicketData(Long sessionId) {
				return new SessionCloseTicketDTO();
			}
		};
		set(sessions, CashierSessionService.class, "generalSetupRepository", setup);
		set(sessions, CashierSessionService.class, "tillRightsGuard", rights.guard);
		set(sessions, _BaseService.class, "currentUserProvider", currentUser);
		CashierSessionAPI sessionAPI = new CashierSessionAPI();
		set(sessionAPI, _BaseController.class, "service", sessions);
		set(sessionAPI, CashierSessionAPI.class, "currentUserProvider", currentUser);

		mvc = MockMvcBuilders.standaloneSetup(badgeAPI, salesAPI, returnAPI, sessionAPI).build();
	}

	// ─── HTTP ──────────────────────────────────────────────────────

	private MockHttpServletResponse send(String path, Object body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body))).andReturn().getResponse();
	}

	private JsonNode body(MockHttpServletResponse response) throws Exception {
		return json.readTree(response.getContentAsString());
	}

	/** POST /badge/scan by the logged user: the grant's token, or null. */
	private String scan(UserAccount owner, BadgePermission action) throws Exception {
		Map<String, Object> request = new HashMap<>();
		request.put("badgeCode", owner.getBadgeCode());
		request.put("permission", action.name());
		request.put("sessionId", 999); // never trusted
		JsonNode answer = body(send("/badge/scan", request));
		return answer.path("grant").isMissingNode() || answer.path("grant").isNull() ? null
				: answer.path("grant").path("token").asText();
	}

	private Map<String, Object> saleWithLineDiscount(String grant) {
		Map<String, Object> line = new HashMap<>();
		line.put("itemId", 10);
		line.put("quantity", 1);
		line.put("discountSource", "MANUAL");
		line.put("discountPercentage", 10.0);
		line.put("discountAmount", 1.5);
		line.put("tillGrant", grant);
		return sale(Arrays.asList(line));
	}

	private Map<String, Object> saleWithTotalDiscount(String grant) {
		Map<String, Object> line = new HashMap<>();
		line.put("itemId", 10);
		line.put("quantity", 1);
		Map<String, Object> sale = sale(Arrays.asList(line));
		sale.put("discountSource", "MANUAL");
		sale.put("discountAmount", 2.0);
		sale.put("tillGrant", grant);
		return sale;
	}

	private static Map<String, Object> sale(List<Map<String, Object>> lines) {
		Map<String, Object> payment = new HashMap<>();
		payment.put("paymentMethodId", 1);
		payment.put("amount", 13.5);
		Map<String, Object> sale = new HashMap<>();
		sale.put("lines", lines);
		sale.put("payments", Arrays.asList(payment));
		return sale;
	}

	private static Map<String, Object> returnRequest(String grant) {
		Map<String, Object> line = new HashMap<>();
		line.put("salesLineId", 5);
		line.put("quantity", 1);
		Map<String, Object> request = new HashMap<>();
		request.put("ticketNumber", "T-1");
		request.put("returnType", "RETURN_VOUCHER");
		request.put("returnLines", Arrays.asList(line));
		request.put("tillGrant", grant);
		return request;
	}

	private static Map<String, Object> close(boolean confirmed, String grant) {
		Map<String, Object> count = new HashMap<>();
		count.put("denominationValue", 115.0);
		count.put("quantity", 1);
		Map<String, Object> request = new HashMap<>();
		request.put("cashCountLines", Arrays.asList(count));
		request.put("discrepancyConfirmed", confirmed);
		if (grant != null) {
			request.put("tillGrant", grant);
		}
		return request;
	}

	/** The request of each action checked on its request, with this grant. */
	private MockHttpServletResponse request(BadgePermission action, String grant) throws Exception {
		switch (action) {
			case MAKE_RETURN: return send("/return-header/process-return", returnRequest(grant));
			case APPLY_LINE_DISCOUNT: return send("/sales-header/process-sale", saleWithLineDiscount(grant));
			case APPLY_TOTAL_DISCOUNT: return send("/sales-header/process-sale", saleWithTotalDiscount(grant));
			case CLOSE_SESSION_WITH_DISCREPANCY: return send("/cashier-session/close", close(grant == null, grant));
			default: throw new IllegalArgumentException(action.name());
		}
	}

	private void assertRefused(MockHttpServletResponse response, BadgePermission action, String reason) throws Exception {
		assertEquals(403, response.getStatus(), response.getContentAsString());
		JsonNode answer = body(response);
		assertEquals("TILL_RIGHT_REQUIRED", answer.path("error").asText());
		assertEquals(action.name(), answer.path("action").asText());
		assertEquals(reason, answer.path("reason").asText());
	}

	private BadgeScanLog lastRow() {
		return rights.log.get(rights.log.size() - 1);
	}

	private static final BadgePermission[] CHECKED = { RETURN, LINE, TOTAL, CLOSE };

	// ─── The matrix ────────────────────────────────────────────────

	@Test
	@DisplayName("A cashier whose role holds each right: every request passes without a badge, one ROLE row each, no grant")
	void cashierWithEachRight() throws Exception {
		for (BadgePermission action : CHECKED) {
			cashier.setAppRole(role("POS_USER", action));
			logged = cashier;
			MockHttpServletResponse response = request(action, null);
			assertEquals(200, response.getStatus(), action + " " + response.getContentAsString());
			BadgeScanLog row = lastRow();
			assertEquals(GrantedBy.ROLE, row.getGrantedBy(), action.name());
			assertEquals(action, row.getFunctionality());
			assertSame(cashier, row.getScannedBy());
			assertSame(cashier, row.getScannedUser());
			assertNull(row.getScannedBadgeCode());
			assertEquals(7L, row.getSessionId());
		}
		assertEquals(4, rights.log.size());
		assertTrue(rights.grants.isEmpty());
	}

	@Test
	@DisplayName("A cashier whose role lacks each right: 403 TILL_RIGHT_REQUIRED NO_GRANT (the line's index for a line); the close without a confirmation shows the difference (400)")
	void cashierWithoutTheRights() throws Exception {
		logged = cashier;
		for (BadgePermission action : new BadgePermission[] { RETURN, LINE, TOTAL }) {
			assertRefused(request(action, null), action, "NO_GRANT");
		}
		assertEquals(0, body(request(LINE, null)).path("lineIndex").asInt());
		MockHttpServletResponse warning = send("/cashier-session/close", close(false, null));
		assertEquals(400, warning.getStatus());
		assertEquals("CASH_DISCREPANCY", body(warning).path("error").asText());
		assertRefused(send("/cashier-session/close", close(true, null)), CLOSE, "NO_GRANT");
		assertTrue(rights.log.isEmpty());
	}

	@Test
	@DisplayName("A responsible's badge scanned, then the request with its grant: passes once per action, BADGE rows with the owner; the scan itself writes no row")
	void scanThenRequest() throws Exception {
		logged = cashier;
		for (BadgePermission action : CHECKED) {
			String token = scan(responsible, action);
			assertNotNull(token, action.name());
			int rows = rights.log.size();
			MockHttpServletResponse response = request(action, token);
			assertEquals(200, response.getStatus(), action + " " + response.getContentAsString());
			assertEquals(rows + 1, rights.log.size(), "the pass is logged once, at the request");
			BadgeScanLog row = lastRow();
			assertEquals(GrantedBy.BADGE, row.getGrantedBy());
			assertEquals(action, row.getFunctionality());
			assertSame(cashier, row.getScannedBy());
			assertSame(responsible, row.getScannedUser());
			assertEquals("B-responsible", row.getScannedBadgeCode());
			assertEquals(7L, row.getSessionId(), "the server's session, not the till's 999");
			assertNotNull(rights.last().getConsumedAt());
		}
		assertEquals(4, rights.log.size());
	}

	@Test
	@DisplayName("A grant used again, expired, of another action, of another session: 403 with its reason, nothing logged")
	void grantsThatDoNotCount() throws Exception {
		logged = cashier;
		String token = scan(responsible, LINE);
		assertEquals(200, request(LINE, token).getStatus());
		assertRefused(request(LINE, token), LINE, "GRANT_USED");

		String expired = scan(responsible, TOTAL);
		rights.last().setExpiresAt(LocalDateTime.now().minusSeconds(1));
		assertRefused(request(TOTAL, expired), TOTAL, "GRANT_EXPIRED");

		String returnGrant = scan(responsible, RETURN);
		assertRefused(request(LINE, returnGrant), LINE, "GRANT_OTHER_ACTION");

		logged = otherCashier; // session 8: the grant was scanned in the cashier's session 7
		assertRefused(request(RETURN, returnGrant), RETURN, "GRANT_OTHER_SESSION");

		assertRefused(request(RETURN, "forged-token"), RETURN, "GRANT_UNKNOWN");
		assertEquals(1, rights.log.size(), "only the one pass");
	}

	@Test
	@DisplayName("The administrator: every request passes on his role's rights without a badge; an ADMIN role without a right is refused like any role (no exception for the name)")
	void administrator() throws Exception {
		logged = admin;
		for (BadgePermission action : CHECKED) {
			assertEquals(200, request(action, null).getStatus(), action.name());
			assertEquals(GrantedBy.ROLE, lastRow().getGrantedBy());
			assertEquals(6L, lastRow().getSessionId());
		}
		adminRole.getPermissions().remove(LINE.key());
		assertRefused(request(LINE, null), LINE, "NO_GRANT");
	}

	@Test
	@DisplayName("The close with a difference: by ROLE (confirmed) and by BADGE (a grant), the warning first, a confirmation without the right refused")
	void closeByRoleAndByBadge() throws Exception {
		logged = cashier;
		assertEquals("CASH_DISCREPANCY", body(send("/cashier-session/close", close(false, null))).path("error").asText());
		String token = scan(responsible, CLOSE);
		MockHttpServletResponse byBadge = send("/cashier-session/close", close(false, token));
		assertEquals(200, byBadge.getStatus(), byBadge.getContentAsString());
		assertEquals(GrantedBy.BADGE, lastRow().getGrantedBy());
		assertEquals("CLOSE SESSION7", rights.last().getConsumedByRef());

		cashier.setAppRole(role("POS_USER", CLOSE));
		MockHttpServletResponse byRole = send("/cashier-session/close", close(true, null));
		assertEquals(200, byRole.getStatus(), byRole.getContentAsString());
		assertEquals(GrantedBy.ROLE, lastRow().getGrantedBy());
		assertEquals(CLOSE, lastRow().getFunctionality());
		assertEquals(2, rights.log.size());
	}

	@Test
	@DisplayName("The scan: a badge whose owner's role lacks the right is refused and logged, no grant; the customer list's scan is logged at once and gives no grant; POST /badge/pass logs a ROLE pass or answers 403")
	void scanAndPass() throws Exception {
		logged = cashier;
		Map<String, Object> scanRequest = new HashMap<>();
		scanRequest.put("badgeCode", otherCashier.getBadgeCode());
		scanRequest.put("permission", LINE.name());
		MockHttpServletResponse refused = send("/badge/scan", scanRequest);
		assertEquals(400, refused.getStatus());
		assertEquals("BADGE_NO_ACCESS", body(refused).path("failureReason").asText());
		assertTrue(body(refused).path("grant").isMissingNode());
		assertEquals(1, rights.log.size());
		assertEquals(false, lastRow().getSuccess());
		assertEquals(GrantedBy.BADGE, lastRow().getGrantedBy());

		assertNull(scan(responsible, CUSTOMERS), "no request to check the customer list");
		assertEquals(2, rights.log.size());
		assertEquals(true, lastRow().getSuccess());
		assertTrue(rights.grants.isEmpty());

		Map<String, Object> pass = new HashMap<>();
		pass.put("action", CUSTOMERS.name());
		MockHttpServletResponse noRight = send("/badge/pass", pass);
		assertEquals(403, noRight.getStatus());
		assertEquals("TILL_RIGHT_REQUIRED", body(noRight).path("error").asText());
		cashier.setAppRole(role("POS_USER", CUSTOMERS));
		assertEquals(200, send("/badge/pass", pass).getStatus());
		assertEquals(GrantedBy.ROLE, lastRow().getGrantedBy());
		assertEquals(7L, lastRow().getSessionId());
	}

	private static void set(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
