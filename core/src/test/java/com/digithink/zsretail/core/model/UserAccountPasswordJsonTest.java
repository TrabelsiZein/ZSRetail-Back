package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The password hash never leaves in an answer: the entities are served as they are, and every one that embeds its user (a
 * cashier session, a ticket, a return, a payment) carried user.password to the browser of any logged user. It stays
 * readable from JSON.
 */
class UserAccountPasswordJsonTest {

	private static final String HASH = "$2a$10$notARealHashNotARealHashNotARealHashNotARealHashNotARe";

	private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private static UserAccount user() {
		UserAccount user = new UserAccount();
		user.setUsername("cashier");
		user.setPassword(HASH);
		return user;
	}

	private static void noHash(Object served) throws Exception {
		String json = MAPPER.writeValueAsString(served);
		assertFalse(json.contains("\"password\""), json);
		assertFalse(json.contains(HASH), json);
	}

	@Test
	@DisplayName("A user: no password in its JSON, the rest of it still there")
	void user_() throws Exception {
		noHash(user());
		assertEquals("cashier", MAPPER.readTree(MAPPER.writeValueAsString(user())).get("username").asText());
	}

	@Test
	@DisplayName("A cashier session: its cashier and its verifier")
	void cashierSession() throws Exception {
		CashierSession session = new CashierSession();
		session.setCashier(user());
		session.setVerifiedBy(user());
		noHash(session);
	}

	@Test
	@DisplayName("A ticket, a return, a payment: their user")
	void documents() throws Exception {
		SalesHeader ticket = new SalesHeader();
		ticket.setCreatedByUser(user());
		noHash(ticket);
		ReturnHeader returned = new ReturnHeader();
		returned.setCreatedByUser(user());
		noHash(returned);
		Payment payment = new Payment();
		payment.setCreatedByUser(user());
		noHash(payment);
	}

	@Test
	@DisplayName("Still read from JSON")
	void read() throws Exception {
		assertEquals("typed", MAPPER.readValue("{\"password\":\"typed\"}", UserAccount.class).getPassword());
	}

	@Test
	@DisplayName("Not in a toString, so never in a log")
	void notInToString() {
		assertFalse(user().toString().contains(HASH));
	}
}
