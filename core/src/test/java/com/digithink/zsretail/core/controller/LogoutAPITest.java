package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.eventlog.service.AccessEvents;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.4.0, event log step 3: POST /logout answers 204 and only writes LOGOUT (the logged user); off, or when the user cannot
 * be read, 204 and nothing.
 */
class LogoutAPITest {

	@Test
	@DisplayName("POST /logout: 204 and LOGOUT; off: 204 and nothing; the user not read: 204 all the same")
	void logout() throws Exception {
		InMemoryEventLog log = new InMemoryEventLog();
		UserAccount amine = new UserAccount();
		amine.setId(7L);
		amine.setUsername("amine");
		amine.setFullName("Amine K.");
		boolean[] fails = { false };
		LogoutAPI api = new LogoutAPI();
		set(api, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				if (fails[0]) {
					throw new IllegalStateException("no user");
				}
				return amine;
			}
		});
		set(api, "accessEvents", new AccessEvents(log.eventLog(), login -> null));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();

		assertEquals(204, mvc.perform(post("/logout")).andReturn().getResponse().getStatus());
		assertEquals("amine", log.only("LOGOUT").getUserLogin());
		assertEquals("Déconnexion de Amine K.", log.only("LOGOUT").getDetails());

		log.on = false;
		assertEquals(204, mvc.perform(post("/logout")).andReturn().getResponse().getStatus());
		log.on = true;
		fails[0] = true;
		assertEquals(204, mvc.perform(post("/logout")).andReturn().getResponse().getStatus());
		assertEquals(1, log.rows.size());
		assertTrue(log.codes().contains("LOGOUT"));
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = LogoutAPI.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
