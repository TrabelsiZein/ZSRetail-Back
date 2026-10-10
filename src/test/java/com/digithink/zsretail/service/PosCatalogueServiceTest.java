package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * 2.2.2, step 3: the families and sub-families of the POS screen with the two store settings, in their four
 * combinations; both off, the lists are returned as they are and nothing is counted (the answers of 2.2.1). The items of
 * a family without sub-family, by the grid's rule. Plain JUnit over in-memory stubs.
 */
class PosCatalogueServiceTest {

	private final Map<String, String> settings = new HashMap<>();
	private final List<String> repositoryCalls = new ArrayList<>();
	private final List<Object[]> rows = new ArrayList<>();
	private final List<Item> items = new ArrayList<>();
	private PosCatalogueService service;

	private final ItemFamily withItems = family(1L, "WITH");
	private final ItemFamily onlyWithoutSub = family(2L, "NOSUB");
	private final ItemFamily allInactive = family(3L, "INACTIVE");
	private final ItemFamily empty = family(4L, "EMPTY");
	private final ItemSubFamily full = subFamily(11L, withItems);
	private final ItemSubFamily emptySub = subFamily(12L, withItems);
	private final ItemSubFamily inactiveSub = subFamily(31L, allInactive);

	@BeforeEach
	void setUp() throws Exception {
		// what countPosGridItems answers for this catalogue: {sub-family's family, item's family, sub-family, count}
		rows.add(new Object[] { 1L, 1L, 11L, 3L });
		rows.add(new Object[] { null, 1L, null, 1L });
		rows.add(new Object[] { null, 2L, null, 2L });
		ItemRepository repository = stub(ItemRepository.class, (m, a) -> {
			repositoryCalls.add(m);
			switch (m) {
				case "countPosGridItems": return rows;
				case "findByItemFamilyAndItemSubFamilyIsNull":
					return items.stream().filter(i -> i.getItemFamily() == a[0] && i.getItemSubFamily() == null)
							.collect(Collectors.toList());
				default: return UNHANDLED;
			}
		});
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return settings.get(code);
			}
		};
		service = new PosCatalogueService();
		inject(service, "itemRepository", repository);
		inject(service, "generalSetupService", setup);
	}

	private static ItemFamily family(Long id, String code) {
		ItemFamily family = new ItemFamily();
		family.setId(id);
		family.setCode(code);
		return family;
	}

	private static ItemSubFamily subFamily(Long id, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setId(id);
		subFamily.setItemFamily(family);
		return subFamily;
	}

	private List<ItemFamily> families() {
		return Arrays.asList(withItems, onlyWithoutSub, allInactive, empty);
	}

	private List<ItemSubFamily> subFamilies() {
		return Arrays.asList(full, emptySub, inactiveSub);
	}

	private static List<Long> ids(List<?> list) {
		return list.stream().map(o -> o instanceof ItemFamily ? ((ItemFamily) o).getId() : ((ItemSubFamily) o).getId())
				.collect(Collectors.toList());
	}

	@Test
	@DisplayName("Both off (and absent): the very lists of 2.2.1, nothing counted")
	void bothOff() {
		List<ItemFamily> families = families();
		List<ItemSubFamily> subFamilies = subFamilies();
		assertSame(families, service.families(families));
		assertSame(subFamilies, service.subFamilies(subFamilies));
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "false");
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, "false");
		assertSame(families, service.families(families));
		assertSame(subFamilies, service.subFamilies(subFamilies));
		assertEquals(0, repositoryCalls.size(), "no count when both are off");
	}

	@Test
	@DisplayName("Families on, sub-families off: empty families hidden (a family with items only without sub-family stays); every sub-family listed")
	void familiesOnly() {
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		assertEquals(Arrays.asList(1L, 2L), ids(service.families(families())));
		assertEquals(Arrays.asList(11L, 12L, 31L), ids(service.subFamilies(subFamilies())));
	}

	@Test
	@DisplayName("Sub-families on, families off: every family listed; empty sub-families hidden")
	void subFamiliesOnly() {
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, "TRUE");
		assertEquals(Arrays.asList(1L, 2L, 3L, 4L), ids(service.families(families())));
		assertEquals(Arrays.asList(11L), ids(service.subFamilies(subFamilies())));
	}

	@Test
	@DisplayName("Both on: empty families and empty sub-families hidden, one query per list")
	void bothOn() {
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, " true ");
		assertEquals(Arrays.asList(1L, 2L), ids(service.families(families())));
		assertEquals(Arrays.asList(11L), ids(service.subFamilies(subFamilies())));
		assertEquals(Arrays.asList("countPosGridItems", "countPosGridItems"), repositoryCalls);
	}

	@Test
	@DisplayName("Items of a family without sub-family: the grid's rule (active, shown in the POS, a price); unknown family refused")
	void itemsWithoutSubFamily() throws Exception {
		Item listed = item("LISTED", onlyWithoutSub, null, true, true, 3.0);
		Item nullFlags = item("NULL-FLAGS", onlyWithoutSub, null, null, null, 4.0);
		item("INACTIVE", onlyWithoutSub, null, false, true, 3.0);
		item("HIDDEN", onlyWithoutSub, null, true, false, 3.0);
		item("NO-PRICE", onlyWithoutSub, null, true, true, 0.0);
		item("IN-A-SUB", onlyWithoutSub, full, true, true, 3.0);
		ItemService itemService = new ItemService();
		Field repository = ItemService.class.getDeclaredField("itemRepository");
		repository.setAccessible(true);
		repository.set(itemService, inject(service, null, null));
		Field families = ItemService.class.getDeclaredField("itemFamilyRepository");
		families.setAccessible(true);
		families.set(itemService, stub(ItemFamilyRepository.class, (m, a) ->
				"findById".equals(m) ? Optional.ofNullable(Long.valueOf(2L).equals(a[0]) ? onlyWithoutSub : null) : UNHANDLED));
		assertEquals(Arrays.asList(listed, nullFlags), itemService.findPosItemsWithoutSubFamily(2L));
		assertEquals("Item family not found: 9",
				org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
						() -> itemService.findPosItemsWithoutSubFamily(9L)).getMessage());
	}

	private Item item(String code, ItemFamily family, ItemSubFamily subFamily, Boolean active, Boolean showInPos,
			Double price) {
		Item item = new Item();
		item.setItemCode(code);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(active);
		item.setShowInPos(showInPos);
		item.setUnitPrice(price);
		items.add(item);
		return item;
	}

	// ─── Stub plumbing ───────────────────────────────────────────────

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}

	/** Sets a field of the service; with a null name, returns the item repository stub already set. */
	private static Object inject(Object target, String fieldName, Object value) throws Exception {
		Field field = PosCatalogueService.class.getDeclaredField(fieldName == null ? "itemRepository" : fieldName);
		field.setAccessible(true);
		if (fieldName == null) {
			return field.get(target);
		}
		field.set(target, value);
		return value;
	}
}
