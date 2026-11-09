package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.UserAccountDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The password hash and the badge code never leave in an entity: the entities are served as they are, and every one that
 * embeds its user (a cashier session, a ticket, a return, a payment) carried them to the browser of any logged user, and a
 * badge code grants a till right. Both stay readable from JSON. The Users pages read the badge through UserAccountDTO, behind
 * their permission (UserAccountApiAccessTest).
 */
class UserAccountSecretsJsonTest {

	private static final String HASH = "$2a$10$notARealHashNotARealHashNotARealHashNotARealHashNotARe";
	private static final String BADGE = "THE-BADGE-CODE";

	private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private static UserAccount user() {
		UserAccount user = new UserAccount();
		user.setUsername("cashier");
		user.setPassword(HASH);
		user.setBadgeCode(BADGE);
		return user;
	}

	private static void noSecret(Object served) throws Exception {
		String json = MAPPER.writeValueAsString(served);
		assertFalse(json.contains("\"password\""), json);
		assertFalse(json.contains(HASH), json);
		assertFalse(json.contains("\"badgeCode\""), json);
		assertFalse(json.contains(BADGE), json);
	}

	@Test
	@DisplayName("A user: no password, no badge code in its JSON, the rest of it still there")
	void user_() throws Exception {
		noSecret(user());
		assertEquals("cashier", MAPPER.readTree(MAPPER.writeValueAsString(user())).get("username").asText());
	}

	@Test
	@DisplayName("A cashier session: its cashier and its verifier")
	void cashierSession() throws Exception {
		CashierSession session = new CashierSession();
		session.setCashier(user());
		session.setVerifiedBy(user());
		noSecret(session);
	}

	@Test
	@DisplayName("A ticket, a return, a payment: their user")
	void documents() throws Exception {
		SalesHeader ticket = new SalesHeader();
		ticket.setCreatedByUser(user());
		noSecret(ticket);
		ReturnHeader returned = new ReturnHeader();
		returned.setCreatedByUser(user());
		noSecret(returned);
		Payment payment = new Payment();
		payment.setCreatedByUser(user());
		noSecret(payment);
	}

	@Test
	@DisplayName("A user whose badge was revoked by another: the second user carries nothing either")
	void revokedBy() throws Exception {
		UserAccount user = user();
		user.setBadgeRevokedBy(user());
		noSecret(user);
	}

	@Test
	@DisplayName("Both still read from JSON")
	void read() throws Exception {
		UserAccount read = MAPPER.readValue("{\"password\":\"typed\",\"badgeCode\":\"BADGE1\"}", UserAccount.class);
		assertEquals("typed", read.getPassword());
		assertEquals("BADGE1", read.getBadgeCode());
	}

	@Test
	@DisplayName("Neither in a toString, so never in a log")
	void notInToString() {
		assertFalse(user().toString().contains(HASH));
		assertFalse(user().toString().contains(BADGE));
	}

	@Test
	@DisplayName("The DTO of the Users pages keeps the badge code: the form and the list read it")
	void theUsersPagesKeepIt() throws Exception {
		assertTrue(MAPPER.writeValueAsString(UserAccountDTO.fromEntity(user())).contains(BADGE));
	}
}
