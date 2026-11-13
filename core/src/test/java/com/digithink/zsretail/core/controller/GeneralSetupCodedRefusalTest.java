package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.exception.CodedRefusal;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.service.GeneralSetupService;

/**
 * 3.2.0, POS machines step 2: PUT /general-setup/{id} answers a refusal of a value check that carries a code
 * (CodedRefusal, e.g. the store's drawer choice) with {error, code, params}, which the front shows in the language of the
 * screen; any other refusal keeps the plain message of before.
 */
class GeneralSetupCodedRefusalTest {

	static class Coded extends IllegalStateException implements CodedRefusal {
		private static final long serialVersionUID = 1L;

		Coded() {
			super("Who the drawer belongs to cannot change while a session is open (1 open): close every session first.");
		}

		@Override
		public String getCode() {
			return "CASH_DRAWER_OWNER_SESSIONS_OPEN";
		}

		@Override
		public Map<String, Object> getParams() {
			return Collections.singletonMap("count", 1L);
		}
	}

	private static GeneralSetupAPI api(RuntimeException refusal) throws Exception {
		GeneralSetupService service = new GeneralSetupService() {
			@Override
			public Optional<GeneralSetup> findById(Long id) {
				return Optional.empty();
			}

			@Override
			public GeneralSetup updateFromAdmin(Long id, GeneralSetup updatedSetting, String reason) {
				throw refusal;
			}
		};
		GeneralSetupAPI api = new GeneralSetupAPI();
		Field field = _BaseController.class.getDeclaredField("service");
		field.setAccessible(true);
		field.set(api, service);
		return api;
	}

	@Test
	@DisplayName("A coded refusal: 400 {error, code, params}")
	@SuppressWarnings("unchecked")
	void coded() throws Exception {
		ResponseEntity<?> answer = api(new Coded()).update(3L, new GeneralSetup());
		assertEquals(400, answer.getStatusCodeValue());
		Map<String, Object> body = (Map<String, Object>) answer.getBody();
		assertEquals("CASH_DRAWER_OWNER_SESSIONS_OPEN", body.get("code"));
		assertEquals(Collections.singletonMap("count", 1L), body.get("params"));
		assertEquals("Who the drawer belongs to cannot change while a session is open (1 open): close every session first.",
				body.get("error"));
	}

	@Test
	@DisplayName("Any other refusal: 400 and its plain message, as before")
	void plain() throws Exception {
		ResponseEntity<?> answer = api(new IllegalStateException("This setting is read only and cannot be modified."))
				.update(3L, new GeneralSetup());
		assertEquals(400, answer.getStatusCodeValue());
		assertEquals("This setting is read only and cannot be modified.", answer.getBody());
	}
}
