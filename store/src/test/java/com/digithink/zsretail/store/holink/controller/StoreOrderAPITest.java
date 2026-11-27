package com.digithink.zsretail.store.holink.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
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
import com.digithink.zsretail.store.holink.service.StoreOrderService;

/**
 * 3.3.0: "Commandes centrale" is optional (storeorders.enabled, false when absent). Off, every /store-order endpoint
 * answers 404 {error} before the service is used; on, as before. A head office that answers 404 (it takes no store
 * orders) is a 409 {error, code HEAD_OFFICE_NO_STORE_ORDERS}, not a 503. The key is true only on a store whose goods
 * come from its head office.
 */
class StoreOrderAPITest {

	/** A store whose goods come from its head office (the beans of StoreOrderAPI exist there). */
	private static MockEnvironment supplied() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "k").withProperty("ownership.catalogue", "HEAD_OFFICE")
				.withProperty("ownership.supply", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("No storeorders.enabled line, or false: every endpoint answers 404 {error}, the service is never used")
	void offRefusesEverything() {
		for (MockEnvironment env : new MockEnvironment[] { supplied(),
				supplied().withProperty("storeorders.enabled", "false") }) {
			ApplicationModeService mode = TestModes.of(env);
			assertFalse(mode.isStoreOrdersEnabled());
			StoreOrderService service = mock(StoreOrderService.class);
			StoreOrderAPI api = new StoreOrderAPI(service, null, mode);
			List<Supplier<ResponseEntity<?>>> calls = List.of(
					() -> api.catalogue(0, 20, null, null, null, null, null, null, null), () -> api.variants("70061"),
					() -> api.last("70061"), api::draft, () -> api.removeLine(1L), () -> api.send(1L),
					() -> api.cancel(1L), () -> api.list(null, null, null, null, null, null), () -> api.get(1L));
			for (Supplier<ResponseEntity<?>> call : calls) {
				ResponseEntity<?> answer = call.get();
				assertEquals(HttpStatus.NOT_FOUND, answer.getStatusCode());
				assertEquals(Collections.singletonMap("error", StoreOrderAPI.OFF), answer.getBody());
			}
			verifyNoInteractions(service);
		}
	}

	@Test
	@DisplayName("On: the endpoints work as before; a head office that takes no orders is 409 with its code, not a 503")
	void onWorksAndNamesTheMissingHeadOfficeLine() {
		ApplicationModeService mode = TestModes.of(supplied().withProperty("storeorders.enabled", " TRUE "));
		assertTrue(mode.isStoreOrdersEnabled());
		StoreOrderService service = mock(StoreOrderService.class);
		when(service.draft()).thenReturn(java.util.Optional.empty());
		when(service.variants("70061")).thenThrow(new StoreOrderService.HeadOfficeTakesNoOrdersException(
				"read the stock of 70061"));
		StoreOrderAPI api = new StoreOrderAPI(service, null, mode);
		assertEquals(HttpStatus.NO_CONTENT, api.draft().getStatusCode());
		ResponseEntity<?> answer = api.variants("70061");
		assertEquals(HttpStatus.CONFLICT, answer.getStatusCode());
		Map<String, String> body = new LinkedHashMap<>();
		body.put("error", "The head office takes no store orders (headoffice.storeorders.post is not set in its file):"
				+ " cannot read the stock of 70061.");
		body.put("code", "HEAD_OFFICE_NO_STORE_ORDERS");
		assertEquals(body, answer.getBody());
	}

	@Test
	@DisplayName("Blank (3.5.0) is false, as a missing line, anywhere; any other value but true or false stops the start;"
			+ " true only on a store whose goods come from its head office")
	void startRules() {
		for (String value : new String[] { "", "  " }) {
			assertFalse(NodeOwnership.isStoreOrdersEnabledSet(supplied().withProperty("storeorders.enabled", value)), "'" + value + "'");
			assertFalse(NodeOwnership.isStoreOrdersEnabledSet(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
					.withProperty("storeorders.enabled", value)), "head office '" + value + "'");
			assertFalse(NodeOwnership.isStoreOrdersEnabledSet(new MockEnvironment().withProperty("storeorders.enabled", value)),
					"a store alone '" + value + "'");
		}
		for (String value : new String[] { "yes", "1", "on" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> NodeOwnership.isStoreOrdersEnabledSet(supplied().withProperty("storeorders.enabled", value)));
			assertTrue(e.getMessage().startsWith("Invalid value '" + value + "' for property storeorders.enabled: true"),
					e.getMessage());
		}
		// A store alone, a store linked but supplied locally: true stops the start; false is accepted
		MockEnvironment linkedLocalSupply = new MockEnvironment()
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api").withProperty("headoffice.api-key", "k");
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(), linkedLocalSupply }) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> NodeOwnership.isStoreOrdersEnabledSet(env.withProperty("storeorders.enabled", "true")));
			assertTrue(e.getMessage().contains("only a store whose goods come from its head office"), e.getMessage());
			assertFalse(NodeOwnership.isStoreOrdersEnabledSet(env.withProperty("storeorders.enabled", "false")));
		}
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> NodeOwnership.isStoreOrdersEnabledSet(
				new MockEnvironment().withProperty("node.type", "HEAD_OFFICE").withProperty("storeorders.enabled", "true")));
		assertTrue(e.getMessage().contains("headoffice.storeorders.post"), e.getMessage());
		// The start itself refuses (ApplicationModeService)
		assertThrows(IllegalStateException.class,
				() -> TestModes.of(new MockEnvironment().withProperty("storeorders.enabled", "true")));
		assertTrue(NodeOwnership.isStoreOrdersEnabledSet(supplied().withProperty("storeorders.enabled", "true")));
	}
}
