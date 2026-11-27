package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.UserAccount;

/**
 * 3.5.0, ticket templates step 3 (screen test of part 1): the returns list and a return's details send its cashier, so
 * the voucher's reprint prints it: the user who made the return, else its session's cashier, else none (never
 * "System"); only id, username and full name. Plain JUnit.
 */
class ReturnCashierTest {

	private static UserAccount user(long id, String username, String fullName) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(username);
		user.setFullName(fullName);
		user.setPassword("secret");
		return user;
	}

	@Test
	@DisplayName("The return's user; else its session's cashier; else null; no password")
	void cashierOf() {
		ReturnHeader h = new ReturnHeader();
		CashierSession session = new CashierSession();
		session.setCashier(user(5, "caisse5", "Caissière Cinq"));
		h.setCashierSession(session);
		h.setCreatedByUser(user(3, "cashier", "Caissier Trois"));
		Map<String, Object> made = ReturnHeaderAPI.cashierOf(h);
		assertEquals(3L, made.get("id"));
		assertEquals("cashier", made.get("username"));
		assertEquals("Caissier Trois", made.get("fullName"));
		assertFalse(made.containsKey("password"));

		h.setCreatedByUser(null);
		assertEquals("caisse5", ReturnHeaderAPI.cashierOf(h).get("username"), "the session's cashier");

		h.setCashierSession(null);
		assertNull(ReturnHeaderAPI.cashierOf(h), "none stored: no cashier row");
	}
}
