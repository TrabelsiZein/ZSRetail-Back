package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.NewEvent;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.store.model.TillGrant;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;

/**
 * 3.4.0, event log step 2: what a store's event finds by itself. The badge's owner from the grants the request used
 * (TillRightsGuard keeps them: TillRequestGrants), the first one, the others named; none outside a request or after a
 * pass by the user's own role. The user's open session and its number; the POS machine at the request's address (step 3:
 * none from a PC that is no POS machine, never the session's). A value the caller gave is never replaced.
 */
class StoreEventContextTest {

	final InMemoryEventLog log = new InMemoryEventLog().logged(TillEventsTest.user(7L, "amira", "Amira", "POS_USER"));
	final CashierSession open = session(3L, "SESSION261123001");
	final CashierSession other = session(9L, "SESSION261122004");
	final PosMachine till1 = machine(1L, "Caisse 1");
	final PosMachine till2 = machine(2L, "Caisse 2");

	@AfterEach
	void noRequestLeft() {
		RequestContextHolder.resetRequestAttributes();
	}

	StoreEventContext context() {
		return new StoreEventContext(id -> id.equals(9L) ? Optional.of(other) : id.equals(3L) ? Optional.of(open)
				: Optional.empty(), userId -> userId.equals(7L) ? Optional.of(open) : Optional.empty(),
				address -> "192.168.1.21".equals(address) ? Optional.of(till1)
						: "192.168.1.22".equals(address) ? Optional.of(till2) : Optional.empty());
	}

	@Test
	@DisplayName("The badge grants of the request: the first owner authorises, the others are named in the details")
	void badgeOwners() {
		request("192.168.1.21");
		TillRequestGrants.keep(grant(1L, "karim", "Karim Responsable", BadgePermission.APPLY_LINE_DISCOUNT));
		TillRequestGrants.keep(grant(1L, "karim", "Karim Responsable", BadgePermission.APPLY_LINE_DISCOUNT));
		TillRequestGrants.keep(grant(4L, "rim", "Rim Gérante", BadgePermission.APPLY_TOTAL_DISCOUNT));
		log.contexts.add(context());
		log.eventLog().write(NewEvent.of(EventCode.SALE_VALIDATED).details("Vente validée."));
		EventLogEntry row = log.only("SALE_VALIDATED");
		assertEquals(Long.valueOf(1), row.getAuthorisedById());
		assertEquals("karim", row.getAuthorisedByLogin());
		assertEquals("Karim Responsable", row.getAuthorisedByName());
		assertEquals("Vente validée. Autorisé aussi par : Rim Gérante.", row.getDetails());
	}

	@Test
	@DisplayName("No grant (the user's own role), or no request (a job): no authoriser; a grant without owner keeps nothing")
	void nobodyAuthorised() {
		log.contexts.add(context());
		TillRequestGrants.keep(grant(1L, "karim", "Karim", BadgePermission.MAKE_RETURN)); // outside a request: nothing
		log.eventLog().write(NewEvent.of(EventCode.RETURN_DONE));
		request("192.168.1.21");
		TillRequestGrants.keep(new TillGrant());
		TillRequestGrants.keep(null);
		log.eventLog().write(NewEvent.of(EventCode.RETURN_DONE));
		assertNull(log.rows.get(0).getAuthorisedById());
		assertNull(log.rows.get(1).getAuthorisedById());
		assertTrue(TillRequestGrants.used().isEmpty());
	}

	@Test
	@DisplayName("The user's open session and its number, the POS machine at the request's address")
	void sessionAndMachine() {
		request("192.168.1.21");
		log.contexts.add(context());
		log.eventLog().write(NewEvent.of(EventCode.CASH_MOVEMENT));
		EventLogEntry row = log.rows.get(0);
		assertEquals(Long.valueOf(3), row.getSessionId());
		assertEquals("SESSION261123001", row.getSessionNumber());
		assertEquals("192.168.1.21", row.getClientAddress());
		assertEquals(Long.valueOf(1), row.getPosMachineId());
		assertEquals("Caisse 1", row.getPosMachineName());
	}

	@Test
	@DisplayName("Step 3: a session given (its number found); a PC of no POS machine (the back office): no machine, the"
			+ " address kept (never the session's till)")
	void givenSessionBackOfficeNoMachine() {
		request("10.0.0.5"); // the back office's PC
		log.contexts.add(context());
		log.eventLog().write(NewEvent.of(EventCode.PAYMENT_CHANGED).session(9L, null));
		EventLogEntry row = log.rows.get(0);
		assertEquals(Long.valueOf(9), row.getSessionId());
		assertEquals("SESSION261122004", row.getSessionNumber());
		assertNull(row.getPosMachineId());
		assertNull(row.getPosMachineName());
		assertEquals("10.0.0.5", row.getClientAddress());
	}

	@Test
	@DisplayName("Step 3: a refusal is never authorised by the grants the request used before it")
	void refusalNotAuthorised() {
		request("192.168.1.22");
		TillRequestGrants.keep(grant(1L, "karim", "Karim", BadgePermission.APPLY_LINE_DISCOUNT));
		log.contexts.add(context());
		log.eventLog().writeNow(NewEvent.of(EventCode.TILL_RIGHT_REFUSED).details("Refusé."));
		EventLogEntry row = log.only("TILL_RIGHT_REFUSED");
		assertNull(row.getAuthorisedById());
		assertEquals("Refusé.", row.getDetails());
		assertEquals("Caisse 2", row.getPosMachineName());
	}

	@Test
	@DisplayName("What the caller gave stays: its session, its machine, its authoriser")
	void givenValuesStay() {
		request("192.168.1.21");
		TillRequestGrants.keep(grant(1L, "karim", "Karim", BadgePermission.CASH_MOVEMENT));
		log.contexts.add(context());
		log.eventLog().write(NewEvent.of(EventCode.CASH_MOVEMENT).session(9L, "S-9").machine(5L, "Caisse 5")
				.authorisedBy(4L, "rim", "Rim"));
		EventLogEntry row = log.rows.get(0);
		assertEquals("S-9", row.getSessionNumber());
		assertEquals("Caisse 5", row.getPosMachineName());
		assertEquals("rim", row.getAuthorisedByLogin());
	}

	@Test
	@DisplayName("Nobody logged and no session: nothing found, the event written all the same")
	void nothingFound() {
		log.logged(null);
		log.contexts.add(context());
		log.eventLog().write(NewEvent.of(EventCode.EVENTLOG_STARTED));
		EventLogEntry row = log.rows.get(0);
		assertNull(row.getSessionId());
		assertNull(row.getPosMachineId());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private void request(String address) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(address);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		log.address = address; // EventLog reads it from the request in the application
	}

	private static TillGrant grant(Long ownerId, String login, String name, BadgePermission action) {
		UserAccount owner = new UserAccount();
		owner.setId(ownerId);
		owner.setUsername(login);
		owner.setFullName(name);
		TillGrant grant = new TillGrant();
		grant.setBadgeOwner(owner);
		grant.setAction(action);
		return grant;
	}

	private static CashierSession session(Long id, String number) {
		CashierSession session = new CashierSession();
		session.setId(id);
		session.setSessionNumber(number);
		return session;
	}

	private static PosMachine machine(Long id, String name) {
		PosMachine machine = new PosMachine();
		machine.setId(id);
		machine.setName(name);
		return machine;
	}

	@Test
	@DisplayName("Fixes after the test round: a refused login keeps no session (nobody is logged in), the machine kept")
	void refusedLoginNoSession() {
		request("192.168.1.21");
		log.contexts.add(context());
		log.eventLog().writeNow(NewEvent.of(EventCode.LOGIN_REFUSED).user(TillEventsTest.user(7L, "amira", "Amira", "POS_USER")));
		log.eventLog().writeNow(NewEvent.of(EventCode.LOGIN_OK).user(TillEventsTest.user(7L, "amira", "Amira", "POS_USER")));
		assertNull(log.only("LOGIN_REFUSED").getSessionId());
		assertEquals("Caisse 1", log.only("LOGIN_REFUSED").getPosMachineName());
		assertEquals(Long.valueOf(3), log.only("LOGIN_OK").getSessionId(), "a login keeps the user's open session");
	}
}
