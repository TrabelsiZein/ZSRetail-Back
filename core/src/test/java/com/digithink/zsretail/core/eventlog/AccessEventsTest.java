package com.digithink.zsretail.core.eventlog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.AccessEvents;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.4.0, event log step 3: the logins, the logout and the badge events. A refused login names a user only when the login
 * typed is his; anything else is "Identifiant inconnu" with the address, the text typed (maybe a password) never in any
 * column. A badge's code is never written. Off: nothing, not even the lookup.
 */
class AccessEventsTest {

	final InMemoryEventLog log = new InMemoryEventLog();
	final Map<String, UserAccount> users = new HashMap<>();
	int lookups;
	final AccessEvents events = new AccessEvents(log.eventLog(), login -> {
		lookups++;
		return users.get(login);
	});
	final UserAccount amine = user(7L, "amine", "Amine K.", "Caissier");
	final UserAccount sarra = user(2L, "sarra", "Sarra B.", "Responsable");

	{
		users.put("amine", amine);
	}

	@Test
	@DisplayName("LOGIN_OK (Step): the user, his role, the address")
	void loginOk() {
		events.loginOk(amine, "192.168.1.21");
		EventLogEntry row = log.only("LOGIN_OK");
		assertEquals(EventImportance.STEP, row.getImportance());
		assertEquals("LOGIN", row.getSubjectType());
		assertEquals("7", row.getSubjectId());
		assertEquals("amine", row.getUserLogin());
		assertEquals(Long.valueOf(7), row.getUserId());
		assertEquals("192.168.1.21", row.getClientAddress());
		assertEquals("Connexion de Amine K. (Caissier), depuis 192.168.1.21.", row.getDetails());
	}

	@Test
	@DisplayName("LOGIN_REFUSED (Notable since step 4) of an existing user: him (as stored), the reason, the address")
	void refusedKnownUser() {
		events.loginRefused("amine", new BadCredentialsException("Bad credentials"), "192.168.1.21");
		events.loginRefused("amine", new DisabledException("User is disabled"), "192.168.1.21");
		events.loginRefused("amine", new InternalAuthenticationServiceException("db"), null);
		EventLogEntry wrong = log.rows.get(0);
		assertEquals("LOGIN_REFUSED", wrong.getCode());
		assertEquals(EventImportance.NOTABLE, wrong.getImportance());
		assertEquals(Long.valueOf(7), wrong.getUserId());
		assertEquals("amine", wrong.getSubjectLabel());
		assertEquals("Connexion refusée pour Amine K. : mot de passe incorrect, depuis 192.168.1.21.", wrong.getDetails());
		assertEquals("Connexion refusée pour Amine K. : compte désactivé, depuis 192.168.1.21.",
				log.rows.get(1).getDetails());
		assertEquals("Connexion refusée pour Amine K. : erreur du serveur à la connexion"
				+ " (InternalAuthenticationServiceException).", log.rows.get(2).getDetails());
	}

	@Test
	@DisplayName("LOGIN_REFUSED of a login that is nobody's (a password typed in the login field): 'Identifiant inconnu', the"
			+ " address, the text typed in no column")
	void refusedUnknownLogin() throws Exception {
		events.loginRefused("MonMotDePasse!2026", new BadCredentialsException("Bad credentials"), "192.168.1.30");
		events.loginRefused(null, new BadCredentialsException("Bad credentials"), "192.168.1.30");
		events.loginRefused("  ", new BadCredentialsException("Bad credentials"), null);
		assertEquals(3, log.rows.size());
		EventLogEntry row = log.rows.get(0);
		assertEquals(AccessEvents.UNKNOWN_LOGIN, row.getSubjectLabel());
		assertNull(row.getSubjectId());
		assertNull(row.getUserId());
		assertNull(row.getUserLogin());
		assertNull(row.getUserName());
		assertEquals("192.168.1.30", row.getClientAddress());
		assertEquals("Connexion refusée : identifiant inconnu, depuis 192.168.1.30.", row.getDetails());
		assertEquals("Connexion refusée : identifiant inconnu.", log.rows.get(2).getDetails());
		assertEquals(1, lookups, "an empty login is not looked up");
		for (EventLogEntry entry : log.rows) {
			for (Method getter : EventLogEntry.class.getMethods()) {
				if (getter.getName().startsWith("get") && getter.getReturnType() == String.class
						&& getter.getParameterCount() == 0) {
					String value = (String) getter.invoke(entry);
					assertTrue(value == null || !value.contains("MonMotDePasse"), getter.getName() + ": " + value);
				}
			}
		}
	}

	@Test
	@DisplayName("Step 4: the 5th refusal in a row on one login is LOGIN_REFUSED_REPEATED (Alert), each one after it too;"
			+ " a successful login ends the run")
	void repeatedOnLogin() {
		LocalDateTime[] now = { LocalDateTime.of(2026, 11, 23, 10, 2, 0) };
		AccessEvents counted = new AccessEvents(log.eventLog(), users::get, () -> now[0]);
		for (int i = 1; i <= 6; i++) {
			now[0] = now[0].plusSeconds(20);
			counted.loginRefused("amine", new BadCredentialsException("x"), "192.168.1." + i); // another PC each time
		}
		assertEquals(java.util.Arrays.asList("LOGIN_REFUSED", "LOGIN_REFUSED", "LOGIN_REFUSED", "LOGIN_REFUSED",
				"LOGIN_REFUSED_REPEATED", "LOGIN_REFUSED_REPEATED"), log.codes());
		EventLogEntry fifth = log.rows.get(4);
		assertEquals(EventImportance.ALERT, fifth.getImportance());
		assertEquals(5.0, fifth.getAmount());
		assertEquals("amine", fifth.getUserLogin());
		assertEquals("Connexion refusée pour Amine K. : mot de passe incorrect, depuis 192.168.1.5. 5e refus de suite pour"
				+ " cet identifiant depuis 10:02:20.", fifth.getDetails());
		counted.loginOk(amine, "192.168.1.9");
		counted.loginRefused("amine", new BadCredentialsException("x"), "192.168.1.10");
		assertEquals("LOGIN_REFUSED", log.rows.get(log.rows.size() - 1).getCode(), "the run ended at the login");
	}

	@Test
	@DisplayName("Step 6: the refused logins of one run share a group key (the login's run, else the address's); a new"
			+ " run after a successful login, or 15 minutes later, has its own; nothing else is grouped")
	void groupKeys() {
		LocalDateTime[] now = { LocalDateTime.of(2026, 11, 24, 8, 44, 2) };
		AccessEvents counted = new AccessEvents(log.eventLog(), users::get, () -> now[0]);
		for (int i = 1; i <= 6; i++) {
			now[0] = now[0].plusSeconds(10);
			counted.loginRefused("amine", new BadCredentialsException("x"), "192.168.1.30"); // 5th, 6th: REPEATED
		}
		java.util.Set<String> keys = new java.util.HashSet<>();
		log.rows.forEach(row -> keys.add(row.getGroupKey()));
		assertEquals(java.util.Collections.singleton("U7@2026-11-24T08:44:12"), keys, "one run, on the login");
		counted.loginOk(amine, "192.168.1.30");
		now[0] = now[0].plusSeconds(10);
		counted.loginRefused("amine", new BadCredentialsException("x"), "192.168.1.30");
		assertEquals("U7@" + now[0], log.rows.get(log.rows.size() - 1).getGroupKey(), "a new run after the login");
		assertEquals(null, log.only("LOGIN_OK").getGroupKey());

		now[0] = now[0].plusMinutes(1);
		counted.loginRefused("nobody", new BadCredentialsException("x"), "10.0.0.66");
		assertEquals("A10.0.0.66@" + now[0], log.rows.get(log.rows.size() - 1).getGroupKey(), "unknown login: the address");
		LocalDateTime firstOfAddress = now[0];
		now[0] = now[0].plusMinutes(16);
		counted.loginRefused("nobody", new BadCredentialsException("x"), "10.0.0.66");
		assertTrue(!("A10.0.0.66@" + firstOfAddress).equals(log.rows.get(log.rows.size() - 1).getGroupKey()),
				"16 minutes later: another run");
	}

	@Test
	@DisplayName("Step 6: the badge's owner on its use (and as the authoriser) and on a refusal of a known badge; an"
			+ " unknown badge has none; a refusal authorised nothing")
	void badgeOwner() {
		events.badgeUsed(amine, sarra, BadgePermission.MAKE_RETURN, 3L);
		events.badgeRefused(amine, sarra, BadgePermission.MAKE_RETURN, "BADGE_NO_ACCESS", 3L);
		events.badgeRefused(amine, null, BadgePermission.MAKE_RETURN, "BADGE_NOT_EXISTS", 3L);
		EventLogEntry used = log.rows.get(0);
		assertEquals(Long.valueOf(2), used.getBadgeOwnerId());
		assertEquals("sarra", used.getBadgeOwnerLogin());
		assertEquals("Sarra B.", used.getBadgeOwnerName());
		assertEquals(Long.valueOf(2), used.getAuthorisedById());
		EventLogEntry noRight = log.rows.get(1);
		assertEquals(Long.valueOf(2), noRight.getBadgeOwnerId());
		assertEquals(null, noRight.getAuthorisedById());
		EventLogEntry unknown = log.rows.get(2);
		assertEquals(null, unknown.getBadgeOwnerId());
		assertEquals(null, unknown.getAuthorisedById());
	}

	@Test
	@DisplayName("Step 4: unknown logins from one address add up (5th: Alert); 15 minutes without a refusal end the run")
	void repeatedFromAddress() {
		LocalDateTime[] now = { LocalDateTime.of(2026, 11, 23, 22, 0, 0) };
		AccessEvents counted = new AccessEvents(log.eventLog(), users::get, () -> now[0]);
		for (int i = 1; i <= 5; i++) {
			now[0] = now[0].plusMinutes(1);
			counted.loginRefused("guess" + i, new BadCredentialsException("x"), "10.0.0.66");
		}
		EventLogEntry fifth = log.rows.get(4);
		assertEquals("LOGIN_REFUSED_REPEATED", fifth.getCode());
		assertEquals(AccessEvents.UNKNOWN_LOGIN, fifth.getSubjectLabel());
		assertTrue(fifth.getDetails().endsWith("5e refus de suite depuis cette adresse depuis 22:01:00."), fifth.getDetails());
		now[0] = now[0].plusMinutes(16);
		counted.loginRefused("guess6", new BadCredentialsException("x"), "10.0.0.66");
		assertEquals("LOGIN_REFUSED", log.rows.get(5).getCode(), "a new run");
	}

	@Test
	@DisplayName("A lookup that fails: the login is unknown, the event written all the same")
	void lookupFails() {
		AccessEvents failing = new AccessEvents(log.eventLog(), login -> {
			throw new IllegalStateException("database down");
		});
		failing.loginRefused("amine", new BadCredentialsException("x"), "10.0.0.5");
		assertEquals(AccessEvents.UNKNOWN_LOGIN, log.only("LOGIN_REFUSED").getSubjectLabel());
	}

	@Test
	@DisplayName("LOGOUT (Step): the user")
	void logout() {
		events.logout(amine);
		EventLogEntry row = log.only("LOGOUT");
		assertEquals(EventImportance.STEP, row.getImportance());
		assertEquals("amine", row.getUserLogin());
		assertEquals("Déconnexion de Amine K.", row.getDetails(), "one period after a name ending with one");
	}

	@Test
	@DisplayName("TILL_RIGHT_USED (Step): the badge's owner authorises, the user who scanned, the session; no badge code")
	void badgeUsed() {
		sarra.setBadgeCode("SARRA-1234");
		events.badgeUsed(amine, sarra, BadgePermission.APPLY_LINE_DISCOUNT, 3L);
		EventLogEntry row = log.only("TILL_RIGHT_USED");
		assertEquals(EventImportance.STEP, row.getImportance());
		assertEquals("TILL_RIGHT", row.getSubjectType());
		assertEquals("APPLY_LINE_DISCOUNT", row.getSubjectId());
		assertEquals("Appliquer une remise sur la ligne", row.getSubjectLabel());
		assertEquals("amine", row.getUserLogin());
		assertEquals(Long.valueOf(2), row.getAuthorisedById());
		assertEquals("Sarra B.", row.getAuthorisedByName());
		assertEquals(Long.valueOf(3), row.getSessionId());
		assertEquals("Droit « Appliquer une remise sur la ligne » ouvert par le badge de Sarra B., pour Amine K.",
				row.getDetails());
	}

	@Test
	@DisplayName("TILL_RIGHT_REFUSED at the scan (Alert): each reason in French, the owner named, the reason code kept")
	void badgeRefused() {
		events.badgeRefused(amine, null, BadgePermission.MAKE_RETURN, "BADGE_NOT_EXISTS", 3L);
		events.badgeRefused(amine, sarra, BadgePermission.MAKE_RETURN, "BADGE_REVOKED", 3L);
		events.badgeRefused(amine, sarra, BadgePermission.MAKE_RETURN, "BADGE_EXPIRED", 3L);
		events.badgeRefused(amine, sarra, BadgePermission.MAKE_RETURN, "BADGE_OWNER_INACTIVE", 3L);
		events.badgeRefused(amine, sarra, BadgePermission.CASH_MOVEMENT, "BADGE_NO_ACCESS", 3L);
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : badge inconnu.", log.rows.get(0).getDetails());
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : badge de Sarra B. révoqué.",
				log.rows.get(1).getDetails());
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : badge de Sarra B. expiré.",
				log.rows.get(2).getDetails());
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : badge de Sarra B., compte désactivé.",
				log.rows.get(3).getDetails());
		assertEquals("Badge refusé au scan pour « Mouvement de caisse » : le rôle de Sarra B. n'a pas ce droit.",
				log.rows.get(4).getDetails());
		EventLogEntry first = log.rows.get(0);
		assertEquals(EventImportance.ALERT, first.getImportance());
		assertEquals("BADGE_NOT_EXISTS", first.getNewValue());
		assertEquals("amine", first.getUserLogin());
		assertNull(first.getAuthorisedById(), "a refusal authorises nothing");
	}

	@Test
	@DisplayName("TILL_RIGHT_REFUSED of POST /badge/pass: the role without the right")
	void rolePassRefused() {
		events.rolePassRefused(amine, BadgePermission.CONSULT_CUSTOMER_LIST);
		EventLogEntry row = log.only("TILL_RIGHT_REFUSED");
		assertEquals("ROLE_WITHOUT_RIGHT", row.getNewValue());
		assertEquals("Refusé à la requête (passage sur le droit du rôle) : le rôle de Amine K. n'a pas le droit"
				+ " « Consulter la liste des clients » (changé depuis la connexion). Un badge est demandé.", row.getDetails());
	}

	@Test
	@DisplayName("Off: nothing written, nothing looked up; a value missing costs the event, never the caller; every action"
			+ " has its French name")
	void offAndMistakes() {
		log.on = false;
		events.loginRefused("amine", new BadCredentialsException("x"), "10.0.0.5");
		events.loginOk(amine, null);
		events.badgeUsed(amine, sarra, BadgePermission.MAKE_RETURN, null);
		assertTrue(log.rows.isEmpty());
		assertEquals(0, lookups);
		log.on = true;
		assertDoesNotThrow(() -> events.loginOk(null, null));
		assertDoesNotThrow(() -> events.logout(null));
		assertTrue(log.rows.isEmpty());
		for (BadgePermission action : BadgePermission.values()) {
			assertTrue(!AccessEvents.actionLabel(action).equals(action.name()), action.name());
		}
	}

	private static UserAccount user(Long id, String login, String name, String roleLabel) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(login);
		user.setFullName(name);
		AppRole role = new AppRole();
		role.setName(login.toUpperCase());
		role.setLabel(roleLabel);
		user.setAppRole(role);
		return user;
	}

	@Test
	@DisplayName("Fixes after the test round: the till's admin-return password check is ADMIN_RETURN_VERIFIED (Step), never a"
			+ " LOGIN_OK; its wrong password is a LOGIN_REFUSED that says so, still counted toward the run")
	void adminReturn() {
		LocalDateTime[] now = { LocalDateTime.of(2026, 11, 23, 21, 32, 0) };
		AccessEvents counted = new AccessEvents(log.eventLog(), users::get, () -> now[0]);
		counted.loginOk(amine, "127.0.0.1", AccessEvents.TILL_ADMIN_RETURN);
		EventLogEntry verified = log.only("ADMIN_RETURN_VERIFIED");
		assertEquals(EventImportance.STEP, verified.getImportance());
		assertEquals("amine", verified.getUserLogin());
		assertEquals("Retour à l'administration — mot de passe vérifié pour Amine K., depuis 127.0.0.1.", verified.getDetails());
		assertTrue(!log.codes().contains("LOGIN_OK"));

		for (int i = 1; i <= 5; i++) {
			now[0] = now[0].plusSeconds(10);
			counted.loginRefused("amine", new BadCredentialsException("x"), "127.0.0.1",
					i % 2 == 0 ? AccessEvents.TILL_ADMIN_RETURN : null);
		}
		EventLogEntry second = log.rows.get(2);
		assertEquals("LOGIN_REFUSED", second.getCode());
		assertEquals("Connexion refusée pour Amine K. : mot de passe incorrect (retour à l'administration), depuis 127.0.0.1.",
				second.getDetails());
		assertEquals("LOGIN_REFUSED_REPEATED", log.rows.get(5).getCode(), "the popup's refusals count toward the run");
	}

	@Test
	@DisplayName("Fixes after the test round: the start event in French")
	void startText() {
		new com.digithink.zsretail.core.eventlog.service.EventLogStart(log.eventLog(), "3.4.0").started();
		assertEquals("Journal des événements actif, version 3.4.0.", log.only("EVENTLOG_STARTED").getDetails());
	}
}
