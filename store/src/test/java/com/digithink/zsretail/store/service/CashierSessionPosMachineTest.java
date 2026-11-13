package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.DrawerRuleException;
import com.digithink.zsretail.store.posmachine.dto.TillPosMachine;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * 3.2.0, POS machines step 2, part A: a session keeps the POS machine registered at the address of its opening request
 * (CashierSessionService.openSession); none from a PC that is not registered. Part B: the drawer of the cashier refuses
 * nothing; the drawer of the POS machine opens only on a registered, active machine (rule 1), one open session per
 * machine (rule 2). Plain JUnit, the machines and the links in memory (InMemoryRfid), the sessions in a stub repository.
 */
class CashierSessionPosMachineTest {

	final InMemoryRfid rfid = new InMemoryRfid();
	final List<CashierSession> sessions = new ArrayList<>();

	/** The store's session service over the stubs; the sessions saved get their id. */
	CashierSessionService service() throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", new MockEnvironment());
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		CashierSessionRepository repository = (CashierSessionRepository) Proxy.newProxyInstance(
				CashierSessionRepository.class.getClassLoader(), new Class<?>[] { CashierSessionRepository.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
					case "findByCashierAndStatus":
						return sessions.stream().filter(s -> s.getCashier() == args[0] && s.getStatus() == args[1])
								.findFirst();
					case "countByOpenedAtGreaterThanEqual":
						return (long) sessions.size();
					case "countByStatus":
						return sessions.stream().filter(s -> s.getStatus() == args[0]).count();
					case "save": {
						CashierSession session = (CashierSession) args[0];
						if (session.getId() == null) {
							session.setId(sessions.size() + 1L);
							sessions.add(session);
						}
						return session;
					}
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					case "toString":
						return "sessions";
					default:
						throw new UnsupportedOperationException(method.getName());
					}
				});
		CashierSessionService service = new CashierSessionService();
		inject(service, CashierSessionService.class, "cashierSessionRepository", repository);
		inject(service, CashierSessionService.class, "applicationModeService", mode);
		inject(service, CashierSessionService.class, "sessionPosMachineService", rfid.sessionMachineService);
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "tester";
			}
		});
		return service;
	}

	static UserAccount cashier(String name) {
		UserAccount cashier = new UserAccount();
		cashier.setUsername(name);
		cashier.setFullName(name);
		return cashier;
	}

	static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	@DisplayName("Opened from a registered machine: the session keeps it (any form of its address)")
	void fromRegisteredMachine() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSession session = service().openSession(cashier("amira"), 100.0, "::ffff:192.168.1.11");
		assertSame(caisse1, rfid.sessionMachineService.machineOf(session.getId()).get());
		assertEquals("Caisse 1", rfid.sessionMachineService.nameOf(session.getId()));
		assertEquals(SessionStatus.OPENED, session.getStatus());
	}

	@Test
	@DisplayName("Opened from an address that is not registered (or not an address): opened as today, without a machine")
	void fromUnregisteredAddress() throws Exception {
		rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSessionService service = service();
		CashierSession session = service.openSession(cashier("amira"), 100.0, "192.168.1.99");
		assertFalse(rfid.sessionMachineService.machineOf(session.getId()).isPresent());
		assertNull(rfid.sessionMachineService.nameOf(session.getId()));
		CashierSession odd = service.openSession(cashier("sami"), 50.0, "not-an-address");
		assertFalse(rfid.sessionMachineService.machineOf(odd.getId()).isPresent());
		assertTrue(rfid.sessionMachines.isEmpty());
	}

	@Test
	@DisplayName("Opened from an inactive machine (drawer of the cashier): the session keeps it, as information")
	void fromInactiveMachine() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		caisse1.setActive(false);
		CashierSession session = service().openSession(cashier("amira"), 100.0, "192.168.1.11");
		assertSame(caisse1, rfid.sessionMachineService.machineOf(session.getId()).get());
	}

	// ─── Part B: who the drawer belongs to ───────────────────────────────────

	@Test
	@DisplayName("Drawer of the cashier (the default): nothing refused, from an unregistered PC, an inactive machine, two"
			+ " sessions on one machine")
	void cashierDrawerRefusesNothing() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSessionService service = service();
		assertFalse(rfid.sessionMachineService.machineOf(
				service.openSession(cashier("amira"), 100.0, "192.168.1.99").getId()).isPresent());
		CashierSession first = service.openSession(cashier("sami"), 100.0, "192.168.1.11");
		CashierSession second = service.openSession(cashier("nour"), 100.0, "192.168.1.11");
		assertSame(caisse1, rfid.sessionMachineService.machineOf(first.getId()).get());
		assertSame(caisse1, rfid.sessionMachineService.machineOf(second.getId()).get(), "two open sessions, one machine");
		caisse1.setActive(false);
		assertTrue(rfid.sessionMachineService.machineOf(
				service.openSession(cashier("lina"), 100.0, "192.168.1.11").getId()).isPresent());
	}

	@Test
	@DisplayName("Drawer of the POS machine, rule 1: opened from a registered, active machine only; the refusal says the"
			+ " address the server sees")
	void rule1RegisteredActiveMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSessionService service = service();

		DrawerRuleException unregistered = assertThrows(DrawerRuleException.class,
				() -> service.openSession(cashier("amira"), 100.0, "::ffff:192.168.1.99"));
		assertEquals("POS_MACHINE_NOT_REGISTERED", unregistered.getCode());
		assertEquals("192.168.1.99", unregistered.getParams().get("address"));
		assertEquals("This PC (address 192.168.1.99) is not a registered POS machine: the drawer belongs to the POS"
				+ " machine, so a session opens only on one. Ask an administrator to add this address on the POS machines"
				+ " page.", unregistered.getMessage());
		assertTrue(sessions.isEmpty(), "nothing opened");

		caisse1.setActive(false);
		DrawerRuleException inactive = assertThrows(DrawerRuleException.class,
				() -> service.openSession(cashier("amira"), 100.0, "192.168.1.11"));
		assertEquals("POS_MACHINE_INACTIVE", inactive.getCode());
		assertEquals("Caisse 1", inactive.getParams().get("name"));
		assertEquals("192.168.1.11", inactive.getParams().get("address"));
		assertTrue(sessions.isEmpty());

		caisse1.setActive(true);
		CashierSession session = service.openSession(cashier("amira"), 100.0, "192.168.1.11");
		assertSame(caisse1, rfid.sessionMachineService.machineOf(session.getId()).get());
	}

	@Test
	@DisplayName("Drawer of the POS machine, rule 2: one open session per machine, the refusal names the cashier who holds"
			+ " it; free again once that session is closed")
	void rule2OneOpenSessionPerMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.machine("Caisse 2", "192.168.1.12", null);
		CashierSessionService service = service();
		CashierSession amira = service.openSession(cashier("Amira Ben Salah"), 100.0, "192.168.1.11");

		DrawerRuleException inUse = assertThrows(DrawerRuleException.class,
				() -> service.openSession(cashier("sami"), 50.0, "192.168.1.11"));
		assertEquals("POS_MACHINE_IN_USE", inUse.getCode());
		assertEquals("Caisse 1", inUse.getParams().get("name"));
		assertEquals("Amira Ben Salah", inUse.getParams().get("cashier"));
		assertEquals("The POS machine 'Caisse 1' already has an open session, of Amira Ben Salah: it must be closed"
				+ " first.", inUse.getMessage());
		assertEquals(1, sessions.size(), "nothing opened");
		assertEquals("{error=" + inUse.getMessage() + ", reason=POS_MACHINE_IN_USE, code=POS_MACHINE_IN_USE,"
				+ " params={name=Caisse 1, cashier=Amira Ben Salah}}", inUse.body().toString(), "the 409 body");

		assertTrue(rfid.sessionMachineService.machineOf(
				service.openSession(cashier("sami"), 50.0, "192.168.1.12").getId()).isPresent(), "another machine");
		amira.setStatus(SessionStatus.CLOSED);
		assertTrue(rfid.sessionMachineService.machineOf(
				service.openSession(cashier("nour"), 80.0, "192.168.1.11").getId()).isPresent(), "free again");
	}

	@Test
	@DisplayName("Drawer of the POS machine: the cashier's own open session is still refused with today's message first")
	void alreadyOpenFirst() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSessionService service = service();
		UserAccount amira = cashier("amira");
		service.openSession(amira, 100.0, "192.168.1.11");
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.openSession(amira, 100.0, "192.168.1.99"));
		assertEquals("Cashier already has an open session", e.getMessage());
	}

	@Test
	@DisplayName("The till's question: the drawer, the address, the session's machine and whether this PC is refused")
	void tillPosMachine() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		UserAccount amira = cashier("amira");
		rfid.session(1L, amira, SessionStatus.OPENED, caisse1);
		rfid.drawerOwner = DrawerOwner.CASHIER;
		TillPosMachine cashierDrawer = rfid.sessionMachineService.tillPosMachine(amira, "192.168.1.12");
		assertEquals("CASHIER", cashierDrawer.getDrawerOwner());
		assertEquals("Caisse 1", cashierDrawer.getPosMachineName());
		assertFalse(cashierDrawer.isBlocked());
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		TillPosMachine other = rfid.sessionMachineService.tillPosMachine(amira, "192.168.1.12");
		assertEquals("POS_MACHINE", other.getDrawerOwner());
		assertEquals("192.168.1.12", other.getAddress());
		assertTrue(other.isBlocked());
		assertFalse(rfid.sessionMachineService.tillPosMachine(amira, "192.168.1.11").isBlocked());
		TillPosMachine none = rfid.sessionMachineService.tillPosMachine(cashier("sami"), "192.168.1.12");
		assertNull(none.getPosMachineName());
		assertFalse(none.isBlocked());
	}

	@Test
	@DisplayName("The lists' names: one query for a page, a session without a machine absent")
	void namesOfAPage() throws Exception {
		rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.machine("Caisse 2", "192.168.1.12", null);
		CashierSessionService service = service();
		Long a = service.openSession(cashier("amira"), 100.0, "192.168.1.11").getId();
		Long b = service.openSession(cashier("sami"), 100.0, "192.168.1.12").getId();
		Long c = service.openSession(cashier("nour"), 100.0, "192.168.1.99").getId();
		java.util.Map<Long, String> names = rfid.sessionMachineService
				.namesBySession(java.util.Arrays.asList(a, b, c));
		assertEquals("Caisse 1", names.get(a));
		assertEquals("Caisse 2", names.get(b));
		assertFalse(names.containsKey(c));
		assertEquals(Optional.empty(), rfid.sessionMachineService.machineOf(null));
	}
}
