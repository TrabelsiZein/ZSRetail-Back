package com.digithink.zsretail.store.posmachine.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.cashmovement.controller.CashMovementAPI;
import com.digithink.zsretail.store.controller.CashierSessionAPI;
import com.digithink.zsretail.store.controller.ReturnHeaderAPI;
import com.digithink.zsretail.store.controller.SalesHeaderAPI;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.support.InMemoryRfid;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.2.0, POS machines step 2, part B, rules 3 and 4: the selling guard (PosMachineSellingGuard) in front of the sales and
 * returns endpoints. The endpoint guard: every mapped method of the two controllers but the reads is classed, restricted
 * or not. The rule: the drawer of the cashier refuses nothing; the drawer of the POS machine refuses selling, holding and
 * returning from another address than the session's machine (its current address), never a session without a machine,
 * never the closing or the verification (CashierSessionAPI, outside the guard's paths).
 */
class PosMachineSellingGuardTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private UserAccount loggedUser;
	private final PosMachineSellingGuard guard = new PosMachineSellingGuard(rfid.sessionMachineService,
			new CurrentUserProvider() {
				@Override
				public UserAccount getCurrentUser() {
					return loggedUser;
				}
			});

	private static HandlerMethod handler(Class<?> controller, String name) {
		Method method = Arrays.stream(controller.getMethods()).filter(m -> m.getName().equals(name) && !m.isBridge())
				.findFirst().orElseThrow(() -> new AssertionError(controller.getSimpleName() + "." + name));
		try {
			return new HandlerMethod(controller.getDeclaredConstructor().newInstance(), method);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The guard's answer to this method from this address: null when it lets the request go, else the response. */
	private MockHttpServletResponse call(Class<?> controller, String name, String address) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sales-header/" + name);
		request.setRemoteAddr(address);
		MockHttpServletResponse response = new MockHttpServletResponse();
		return guard.preHandle(request, response, handler(controller, name)) ? null : response;
	}

	private static UserAccount user(String name) {
		UserAccount user = new UserAccount();
		user.setUsername(name);
		user.setFullName(name);
		return user;
	}

	/** The mapped methods of a controller that are not reads (any HTTP method but GET), by name. */
	private static Set<String> writeMappings(Class<?> controller) {
		Set<String> names = new TreeSet<>();
		for (Method method : controller.getMethods()) {
			RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
			if (method.isBridge() || mapping == null) {
				continue;
			}
			List<RequestMethod> methods = Arrays.asList(mapping.method());
			if (methods.isEmpty() || !methods.stream().allMatch(m -> m == RequestMethod.GET)) {
				names.add(method.getName());
			}
		}
		return names;
	}

	@Test
	@DisplayName("Endpoint guard: every write endpoint of SalesHeaderAPI and ReturnHeaderAPI is restricted or not, never both,"
			+ " and every name classed is an endpoint")
	void everyEndpointClassed() {
		for (Class<?> controller : Arrays.asList(SalesHeaderAPI.class, ReturnHeaderAPI.class, CashMovementAPI.class)) {
			Set<String> mapped = writeMappings(controller);
			Set<String> restricted = PosMachineSellingGuard.RESTRICTED.get(controller);
			Set<String> notRestricted = PosMachineSellingGuard.NOT_RESTRICTED.get(controller);
			Set<String> both = new HashSet<>(restricted);
			both.retainAll(notRestricted);
			assertTrue(both.isEmpty(), controller.getSimpleName() + " classed twice: " + both);
			Set<String> classed = new TreeSet<>(restricted);
			classed.addAll(notRestricted);
			Set<String> unclassed = new TreeSet<>(mapped);
			unclassed.removeAll(classed);
			assertTrue(unclassed.isEmpty(), controller.getSimpleName() + ": a new endpoint, class it in"
					+ " PosMachineSellingGuard (RESTRICTED or NOT_RESTRICTED): " + unclassed);
			Set<String> gone = new TreeSet<>(classed);
			gone.removeAll(mapped);
			assertTrue(gone.isEmpty(), controller.getSimpleName() + ": classed but not an endpoint: " + gone);
		}
		assertEquals(new TreeSet<>(Arrays.asList("cancelPendingSale", "completePendingSale", "processSale",
				"savePendingSale", "splitAndPay", "transferTable", "updatePendingSale")),
				new TreeSet<>(PosMachineSellingGuard.RESTRICTED.get(SalesHeaderAPI.class)));
		assertEquals(new HashSet<>(Arrays.asList("processReturn")),
				PosMachineSellingGuard.RESTRICTED.get(ReturnHeaderAPI.class));
	}

	@Test
	@DisplayName("Drawer of the cashier: nothing refused, from any address (a session on another machine included)")
	void cashierDrawer() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		loggedUser = user("amira");
		rfid.session(1L, loggedUser, SessionStatus.OPENED, caisse1);
		for (String name : PosMachineSellingGuard.RESTRICTED.get(SalesHeaderAPI.class)) {
			assertEquals(null, call(SalesHeaderAPI.class, name, "192.168.1.12"), name);
		}
		assertEquals(null, call(ReturnHeaderAPI.class, "processReturn", "192.168.1.12"));
	}

	@Test
	@DisplayName("Drawer of the POS machine: from the session's machine (any form of its address) every action goes through")
	void sameMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		loggedUser = user("amira");
		rfid.session(1L, loggedUser, SessionStatus.OPENED, caisse1);
		for (String name : PosMachineSellingGuard.RESTRICTED.get(SalesHeaderAPI.class)) {
			assertEquals(null, call(SalesHeaderAPI.class, name, "::ffff:192.168.1.11"), name);
		}
		assertEquals(null, call(ReturnHeaderAPI.class, "processReturn", "192.168.1.11"));
	}

	@Test
	@DisplayName("Drawer of the POS machine: from another address, sell, hold and return answer 409 OTHER_POS_MACHINE with"
			+ " the session's machine; the after-sale endpoints go through")
	@SuppressWarnings("unchecked")
	void otherMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.machine("Caisse 2", "192.168.1.12", null);
		loggedUser = user("amira");
		rfid.session(1L, loggedUser, SessionStatus.OPENED, caisse1);
		List<String> refused = new ArrayList<>();
		for (String name : PosMachineSellingGuard.RESTRICTED.get(SalesHeaderAPI.class)) {
			MockHttpServletResponse response = call(SalesHeaderAPI.class, name, "192.168.1.12");
			if (response != null) {
				refused.add(name);
				assertEquals(409, response.getStatus());
				Map<String, Object> body = new ObjectMapper().readValue(response.getContentAsString(), Map.class);
				assertEquals("OTHER_POS_MACHINE", body.get("code"));
				assertEquals("OTHER_POS_MACHINE", body.get("reason"));
				assertEquals("Caisse 1", body.get("posMachineName"));
				assertEquals("Caisse 1", ((Map<String, Object>) body.get("params")).get("name"));
				assertEquals("Your session is open on the POS machine 'Caisse 1': selling, putting a sale on hold, returns"
						+ " and cash movements are only possible there. Go back to 'Caisse 1', or close the session.", body.get("error"));
			}
		}
		assertEquals(PosMachineSellingGuard.RESTRICTED.get(SalesHeaderAPI.class).size(), refused.size(), "all refused");
		assertEquals(409, call(ReturnHeaderAPI.class, "processReturn", "192.168.1.99").getStatus(), "an unregistered PC too");
		// 3.2.0, step 5: a cash movement, as a sale: the same 409 from another PC, passed from the session's machine
		assertEquals(409, call(CashMovementAPI.class, "record", "192.168.1.12").getStatus(), "a cash movement");
		assertEquals(null, call(CashMovementAPI.class, "record", "192.168.1.11"), "from the session's POS machine");
		for (String name : PosMachineSellingGuard.NOT_RESTRICTED.get(SalesHeaderAPI.class)) {
			assertEquals(null, call(SalesHeaderAPI.class, name, "192.168.1.12"), name);
		}
	}

	@Test
	@DisplayName("Drawer of the POS machine: the machine's address corrected, the session sells from the new one only")
	void addressCorrected() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		loggedUser = user("amira");
		rfid.session(1L, loggedUser, SessionStatus.OPENED, caisse1);
		caisse1.setIpAddress("192.168.1.21");
		rfid.machineService.save(caisse1); // the address can be corrected while its session is open
		assertEquals(null, call(SalesHeaderAPI.class, "processSale", "192.168.1.21"));
		assertEquals(409, call(SalesHeaderAPI.class, "processSale", "192.168.1.11").getStatus());
	}

	@Test
	@DisplayName("Drawer of the POS machine: a session without a machine, a user without a session, an admin: as anyone")
	void neverRestricted() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		loggedUser = user("sami");
		rfid.session(2L, loggedUser, SessionStatus.OPENED, null); // opened before the choice
		assertEquals(null, call(SalesHeaderAPI.class, "processSale", "192.168.1.12"), "session without a machine");
		loggedUser = user("nour");
		assertEquals(null, call(SalesHeaderAPI.class, "processSale", "192.168.1.12"), "no session: the endpoint says it");
		UserAccount admin = user("admin"); // the admin selling through the POS button follows the same rules (B5)
		rfid.session(3L, admin, SessionStatus.OPENED, caisse1);
		loggedUser = admin;
		assertEquals(409, call(SalesHeaderAPI.class, "processSale", "192.168.1.12").getStatus());
		assertEquals(null, call(SalesHeaderAPI.class, "processSale", "192.168.1.11"));
	}

	@Test
	@DisplayName("Rule 4: closing and verifying are never behind the guard (CashierSessionAPI is outside its paths and"
			+ " none of its methods is restricted), from any PC, the cashier's own close included")
	void closingNeverRestricted() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		loggedUser = user("amira");
		rfid.session(1L, loggedUser, SessionStatus.OPENED, caisse1);
		for (String name : Arrays.asList("closeSession", "closeSessionById", "verifySession")) {
			assertFalse(PosMachineSellingGuard.restricted(handler(CashierSessionAPI.class, name)), name);
		}
		InterceptorRegistryReader reader = new InterceptorRegistryReader();
		new PosMachineWebConfig(guard).addInterceptors(reader);
		List<Object> interceptors = reader.read();
		assertEquals(1, interceptors.size());
		MappedInterceptor mapped = (MappedInterceptor) interceptors.get(0);
		assertTrue(mapped.getInterceptor() == guard);
		AntPathMatcher paths = new AntPathMatcher();
		assertTrue(mapped.matches("/sales-header/process-sale", paths));
		assertTrue(mapped.matches("/sales-header/12/table-number", paths));
		assertTrue(mapped.matches("/return-header/process-return", paths));
		for (String path : Arrays.asList("/cashier-session/close", "/cashier-session/7/close", "/cashier-session/verify",
				"/cashier-session/open", "/cashier-session/current/pos-machine")) {
			assertFalse(mapped.matches(path, paths), path);
		}
	}

	/** The registry's interceptors (a protected read). */
	static class InterceptorRegistryReader extends InterceptorRegistry {
		List<Object> read() {
			return getInterceptors();
		}
	}
}
