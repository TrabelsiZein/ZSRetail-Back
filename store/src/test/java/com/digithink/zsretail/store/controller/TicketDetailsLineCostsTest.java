package com.digithink.zsretail.store.controller;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.SalesHeaderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.2: the admin ticket detail (GET /sales-header/{id}/details) sends the unit cost of each line apart, as lineCosts
 * (line id to cost, null when unknown at the sale), only to a user whose role has read:admin-margins; the lines
 * themselves never carry it.
 */
class TicketDetailsLineCostsTest {

	private final SalesHeader ticket = new SalesHeader();
	private final List<SalesLine> lines = new ArrayList<>();
	private final AppRole role = new AppRole();
	private SalesHeaderAPI api;

	@BeforeEach
	void setUp() throws Exception {
		ticket.setId(1L);
		ticket.setSalesNumber("T-1");
		ticket.setStatus(TransactionStatus.COMPLETED);
		lines.add(line(11L, new BigDecimal("7.124")));
		lines.add(line(12L, null));
		role.setPermissions(new HashSet<>());
		UserAccount user = new UserAccount();
		user.setUsername("admin");
		user.setAppRole(role);

		api = new SalesHeaderAPI();
		inject(api, _BaseController.class, "service", new SalesHeaderService() {
			@Override
			public Optional<SalesHeader> findById(Long id) {
				return Optional.ofNullable(id == 1L ? ticket : null);
			}
		});
		inject(api, SalesHeaderAPI.class, "salesLineRepository", proxy(SalesLineRepository.class,
				(m, a) -> "findBySalesHeader".equals(m) ? lines : UNHANDLED));
		inject(api, SalesHeaderAPI.class, "paymentRepository", proxy(PaymentRepository.class,
				(m, a) -> "findBySalesHeader".equals(m) ? new ArrayList<>() : UNHANDLED));
		inject(api, SalesHeaderAPI.class, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(m, a) -> "findByCode".equals(m) ? Optional.empty() : UNHANDLED));
		inject(api, SalesHeaderAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user;
			}
		});
	}

	@Test
	@DisplayName("With read:admin-margins: lineCosts by line id, null for a cost unknown at the sale")
	@SuppressWarnings("unchecked")
	void withTheRight() throws Exception {
		role.getPermissions().add(SalesHeaderAPI.MARGINS_PERMISSION);
		Map<String, Object> body = (Map<String, Object>) api.getTicketDetails(1L).getBody();
		Map<Long, BigDecimal> costs = (Map<Long, BigDecimal>) body.get("lineCosts");
		assertEquals(Arrays.asList(11L, 12L), new ArrayList<>(costs.keySet()));
		assertEquals(new BigDecimal("7.124"), costs.get(11L));
		assertTrue(costs.containsKey(12L));
		assertNull(costs.get(12L));

		JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(body);
		assertEquals(7.124, json.get("lineCosts").get("11").asDouble(), 0.0);
		assertTrue(json.get("lineCosts").get("12").isNull());
		assertFalse(json.get("salesLines").get(0).has("unitCost"), "never on the line itself");
	}

	@Test
	@DisplayName("Without it (the till's users, another admin right): no lineCosts")
	@SuppressWarnings("unchecked")
	void withoutTheRight() {
		role.getPermissions().add("write:admin-item-cost");
		Map<String, Object> body = (Map<String, Object>) api.getTicketDetails(1L).getBody();
		assertFalse(body.containsKey("lineCosts"));
		assertEquals(lines, body.get("salesLines"));
	}

	private SalesLine line(long id, BigDecimal cost) {
		SalesLine line = new SalesLine();
		line.setId(id);
		line.setSalesHeader(ticket);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(10.0);
		line.setLineTotal(10.0);
		line.setUnitPriceIncludingVat(10.0);
		line.setLineTotalIncludingVat(10.0);
		line.setUnitCost(cost);
		return line;
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
