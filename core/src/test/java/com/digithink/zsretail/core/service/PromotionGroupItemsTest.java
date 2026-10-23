package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.0: the items of an ITEM_GROUP promotion never depend on Item equality (which leaves out the id). Emtop, 1.12.1:
 * a group sent as { id } only was saved with one item. The body is read by Jackson as a request body is, then saved by
 * the real PromotionService over in-memory tables. Also the save rules of the NAV item number targets (ZEN variants).
 */
class PromotionGroupItemsTest {

	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private final InMemoryItems catalogue = new InMemoryItems(1000);
	private final Map<Long, Promotion> table = new LinkedHashMap<>();
	private PromotionService service;
	private long nextId = 1;
	/** The sales lines and headers of every promotion (validateUpdateAllowed). */
	private long usages;

	@BeforeEach
	void setUp() throws Exception {
		for (long id = 1; id <= 6; id++) {
			Item item = new Item();
			item.setId(id);
			item.setItemCode("ART-" + id);
			item.setName("Item " + id);
			catalogue.items.put(id, item);
		}
		service = new PromotionService();
		inject(service, PromotionService.class, "promotionRepository", promotions());
		inject(service, PromotionService.class, "itemRepository", catalogue.itemRepository());
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
	}

	@Test
	@DisplayName("5 items sent as { id } only keep 5; saved again with 3 ids, exactly those 3")
	void idsOnly() throws Exception {
		Promotion created = service.save(body(null, "{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5}"));
		assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L), storedIds(created.getId()));

		service.save(body(created.getId(), "{\"id\":2},{\"id\":4},{\"id\":6}"));
		assertEquals(Arrays.asList(2L, 4L, 6L), storedIds(created.getId()));
	}

	@Test
	@DisplayName("Full items, { id, itemCode } and { id } give the same group, loaded from the database")
	void fullObjectsSameResult() throws Exception {
		Promotion full = service.save(body(null,
				"{\"id\":1,\"itemCode\":\"ART-1\",\"name\":\"Item 1\"},{\"id\":2,\"itemCode\":\"ART-2\",\"name\":\"Item 2\"},"
						+ "{\"id\":3,\"itemCode\":\"ART-3\",\"name\":\"Item 3\"}"));
		Promotion codes = service.save(body(null, "{\"id\":1,\"itemCode\":\"ART-1\"},{\"id\":2,\"itemCode\":\"ART-2\"},"
				+ "{\"id\":3,\"itemCode\":\"ART-3\"}"));
		Promotion ids = service.save(body(null, "{\"id\":1},{\"id\":2},{\"id\":3}"));
		for (Promotion p : Arrays.asList(full, codes, ids)) {
			assertEquals(Arrays.asList(1L, 2L, 3L), storedIds(p.getId()));
			assertTrue(table.get(p.getId()).getGroupItems().stream().allMatch(i -> catalogue.items.get(i.getId()) == i),
					"the database's items, not the request's");
		}
	}

	@Test
	@DisplayName("A duplicate id counts once; a bare id is read as { id }")
	void duplicates() throws Exception {
		Promotion p = service.save(body(null, "{\"id\":3},3,{\"id\":3,\"itemCode\":\"ART-3\"},{\"id\":5}"));
		assertEquals(Arrays.asList(3L, 5L), storedIds(p.getId()));
	}

	@Test
	@DisplayName("An unknown id is refused and nothing is saved; an item without an id is refused when read")
	void refused() throws Exception {
		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
				() -> service.save(body(null, "{\"id\":1},{\"id\":99}")));
		assertTrue(unknown.getMessage().contains("[99]"), unknown.getMessage());
		assertTrue(table.isEmpty());

		assertThrows(JsonMappingException.class, () -> body(null, "{\"id\":1},{\"itemCode\":\"ART-2\"}"));
	}

	// ─── 3.1.0 (ZEN): NAV item number targets ────────────────────

	@Test
	@DisplayName("NAV targets are read from the body and saved trimmed: ITEM on a NAV number, a group of NAV numbers or mixed")
	void navTargetsSaved() throws Exception {
		Promotion item = service.save(promotion("\"scope\":\"ITEM\",\"navItemNo\":\" N1 \""));
		assertEquals("N1", table.get(item.getId()).getNavItemNo());
		assertNull(table.get(item.getId()).getItem());

		Promotion navs = service.save(promotion("\"scope\":\"ITEM_GROUP\",\"groupNavItemNos\":[\" N1\",\"N1\",\"\",\"N2\"]"));
		assertEquals(new HashSet<>(Arrays.asList("N1", "N2")), table.get(navs.getId()).getGroupNavItemNos());
		assertTrue(table.get(navs.getId()).getGroupItems().isEmpty());

		Promotion mixed = service.save(promotion("\"scope\":\"ITEM_GROUP\",\"groupItems\":[{\"id\":2}],\"groupNavItemNos\":[\"N3\"]"));
		assertEquals(Arrays.asList(2L), storedIds(mixed.getId()));
		assertEquals(Collections.singleton("N3"), table.get(mixed.getId()).getGroupNavItemNos());
	}

	@Test
	@DisplayName("Refused (400): an ITEM on a row and a NAV number; a group with neither; NAV targets of a quantity promotion")
	void navTargetsRefused() {
		assertEquals("An ITEM promotion targets one item or one ERP item number (all its variants), not both",
				refusal("\"scope\":\"ITEM\",\"item\":{\"id\":1},\"navItemNo\":\"N1\""));
		assertEquals("An ITEM_GROUP promotion must target at least one item",
				refusal("\"scope\":\"ITEM_GROUP\",\"groupNavItemNos\":[\" \"]"));
		String quantity = "\"promotionType\":\"QUANTITY_PROMOTION\",\"minimumQuantity\":2,";
		assertEquals("A quantity promotion cannot target an ERP item number (all its variants) yet",
				refusal(quantity + "\"scope\":\"ITEM\",\"navItemNo\":\"N1\""));
		assertEquals("A quantity promotion cannot target an ERP item number (all its variants) yet",
				refusal(quantity + "\"scope\":\"ITEM_GROUP\",\"groupNavItemNos\":[\"N1\"]"));
		assertEquals("The benefit item of a quantity promotion cannot be an ERP item number (all its variants) yet",
				refusal(quantity + "\"scope\":\"ITEM\",\"item\":{\"id\":1},\"getNavItemNo\":\"N2\""));
		assertTrue(table.isEmpty());
	}

	@Test
	@DisplayName("Cleared: a NAV number outside ITEM, NAV numbers outside ITEM_GROUP, a NAV benefit outside quantity")
	void navTargetsCleared() throws Exception {
		Promotion family = service.save(promotion("\"scope\":\"ITEM_FAMILY\",\"itemFamily\":{\"id\":1},"
				+ "\"navItemNo\":\"N1\",\"groupNavItemNos\":[\"N2\"],\"getNavItemNo\":\"N3\""));
		Promotion saved = table.get(family.getId());
		assertNull(saved.getNavItemNo());
		assertTrue(saved.getGroupNavItemNos().isEmpty());
		assertNull(saved.getGetNavItemNo(), "a benefit item only for a quantity promotion, as getItem");

		Promotion row = service.save(promotion("\"scope\":\"ITEM\",\"item\":{\"id\":1},\"navItemNo\":\" \""));
		assertNull(table.get(row.getId()).getNavItemNo(), "blank is none: the row target alone");
	}

	@Test
	@DisplayName("Used promotion: the NAV targets are locked as the other targets (blanks around ignored)")
	void navTargetsLocked() throws Exception {
		Promotion created = service.save(promotion("\"scope\":\"ITEM\",\"navItemNo\":\"N1\""));
		usages = 1;
		String same = "\"id\":" + created.getId() + ",\"code\":\"" + created.getCode() + "\",\"scope\":\"ITEM\",";
		service.validateUpdateAllowed(created.getId(), promotion(same + "\"navItemNo\":\" N1\""));
		String locked = assertThrows(IllegalStateException.class, () -> service.validateUpdateAllowed(created.getId(),
				promotion(same + "\"navItemNo\":\"N2\",\"groupNavItemNos\":[\"N1\"],\"getNavItemNo\":\"N3\"")))
				.getMessage();
		assertTrue(locked.endsWith("[navItemNo, groupNavItemNos, getNavItemNo]"), locked);
	}

	// ─── Helpers ─────────────────────────────────────────────────

	/** A promotion as the form sends it: code, name, a 25 % discount, then the given fields (they may give code, type). */
	private Promotion promotion(String fields) throws Exception {
		String code = fields.contains("\"code\"") ? "" : "\"code\":\"NAV-" + nextId + "\",";
		String type = fields.contains("\"promotionType\"") ? "" : "\"promotionType\":\"SIMPLE_DISCOUNT\",";
		return json.readValue("{" + code + "\"name\":\"Nav\"," + type
				+ "\"benefitType\":\"PERCENTAGE_DISCOUNT\",\"discountPercentage\":25," + fields + "}", Promotion.class);
	}

	/** The message of the save's refusal. */
	private String refusal(String fields) {
		return assertThrows(IllegalArgumentException.class, () -> service.save(promotion(fields))).getMessage();
	}

	/** An ITEM_GROUP promotion as the promotion form sends it, its groupItems array given. */
	private Promotion body(Long id, String groupItems) throws Exception {
		return json.readValue("{" + (id == null ? "" : "\"id\":" + id + ",") + "\"code\":\"GRP-" + nextId
				+ "\",\"name\":\"Group\",\"promotionType\":\"SIMPLE_DISCOUNT\",\"scope\":\"ITEM_GROUP\","
				+ "\"benefitType\":\"PERCENTAGE_DISCOUNT\",\"discountPercentage\":25,\"groupItems\":[" + groupItems
				+ "]}", Promotion.class);
	}

	private List<Long> storedIds(Long promotionId) {
		return table.get(promotionId).getGroupItems().stream().map(Item::getId).sorted().collect(Collectors.toList());
	}

	private PromotionRepository promotions() {
		return proxy(PromotionRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(table.get(args[0]));
				case "countUsages":
					return usages;
				case "save": {
					Promotion saved = (Promotion) args[0];
					if (saved.getId() == null) {
						saved.setId(nextId++);
					}
					table.put(saved.getId(), saved);
					return saved;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
