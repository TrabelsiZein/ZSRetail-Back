package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
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
 * the real PromotionService over in-memory tables.
 */
class PromotionGroupItemsTest {

	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private final InMemoryItems catalogue = new InMemoryItems(1000);
	private final Map<Long, Promotion> table = new LinkedHashMap<>();
	private PromotionService service;
	private long nextId = 1;

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

	// ─── Helpers ─────────────────────────────────────────────────

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
