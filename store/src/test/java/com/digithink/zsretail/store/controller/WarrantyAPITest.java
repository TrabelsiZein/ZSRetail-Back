package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.NodeOwnership;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.store.model.Warranty;
import com.digithink.zsretail.store.service.WarrantyService;

/**
 * 3.3.0: the Warranties page is optional (warranty.enabled, false when absent). Off, every /warranty endpoint answers
 * 404 {error} before any service is used; on, the endpoints work as before.
 */
class WarrantyAPITest {

	private static void set(Object target, String name, Object value) {
		try {
			Field field = WarrantyAPI.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The API on the given installation, with a service that knows no warranty (null: no service at all). */
	private static WarrantyAPI api(ApplicationModeService mode, WarrantyService service) {
		WarrantyAPI api = new WarrantyAPI();
		set(api, "applicationModeService", mode);
		set(api, "warrantyService", service);
		return api;
	}

	@Test
	@DisplayName("No warranty.enabled line, or false: the five endpoints answer 404 {error}, no service used")
	void offRefusesEverything() {
		for (ApplicationModeService mode : new ApplicationModeService[] { TestModes.standalone(), TestModes.erp(),
				TestModes.of(new MockEnvironment().withProperty("warranty.enabled", "false")) }) {
			assertFalse(mode.isWarrantyEnabled());
			WarrantyAPI api = api(mode, null); // a service call would throw a NullPointerException
			List<Supplier<ResponseEntity<?>>> calls = List.of(() -> api.getTicketDetails("T-1"),
					() -> api.create(Collections.singletonMap("salesLineId", 1)), () -> api.list(null, null),
					() -> api.getById(1L), () -> api.markAsUsed(1L));
			for (Supplier<ResponseEntity<?>> call : calls) {
				ResponseEntity<?> answer = call.get();
				assertEquals(HttpStatus.NOT_FOUND, answer.getStatusCode());
				assertEquals(Map.of("error", "Warranties are off on this store (warranty.enabled=true turns them on)."),
						answer.getBody());
			}
		}
	}

	@Test
	@DisplayName("warranty.enabled=true (any case, trimmed): the endpoints work as before")
	void onWorksAsBefore() {
		for (String value : new String[] { "true", " TRUE " }) {
			ApplicationModeService mode = TestModes.of(new MockEnvironment().withProperty("warranty.enabled", value));
			assertTrue(mode.isWarrantyEnabled(), value);
			WarrantyAPI api = api(mode, new WarrantyService() {
				@Override
				public List<Warranty> findAll() {
					return Collections.emptyList();
				}
			});
			ResponseEntity<?> answer = api.list(null, null);
			assertEquals(HttpStatus.OK, answer.getStatusCode(), value);
			assertEquals(Collections.emptyList(), answer.getBody(), value);
		}
	}

	@Test
	@DisplayName("Any other value stops the startup, as rfid.enabled; true on a head office too")
	void otherValuesStopTheStartup() {
		for (String value : new String[] { "", "yes", "1", "on" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> NodeOwnership.isWarrantySet(new MockEnvironment().withProperty("warranty.enabled", value)), value);
			assertTrue(String.valueOf(e.getMessage()).contains("for property warranty.enabled"), e.getMessage());
		}
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> NodeOwnership.isWarrantySet(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE").withProperty("warranty.enabled", "true")));
		assertTrue(String.valueOf(e.getMessage()).contains("a head office registers no warranties"), e.getMessage());
		assertFalse(TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("warranty.enabled", "false")).isWarrantyEnabled());
	}
}
