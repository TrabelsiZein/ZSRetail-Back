package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.dto.PriceCalculateResponseDTO;
import com.digithink.zsretail.dto.PricingResult;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * ALL_ITEMS promotion scope: least specific scope, used only when no item, group,
 * subfamily or family promotion is eligible. Plain JUnit with in-memory stubs that
 * mirror the JPQL filters of PromotionRepository (no Spring, no database).
 */
class PromotionAllItemsScopeTest {

	private final List<Promotion> promotions = new ArrayList<>();
	private final Map<Long, Item> items = new HashMap<>();
	private PromotionCalculationService engine;
	private PricingResult erpPricing;
	private long nextId;

	private final ItemFamily familyF = family(1);
	private final ItemFamily familyG = family(2);
	private final ItemSubFamily subFamily = subFamily(5);

	@BeforeEach
	void setUp() throws Exception {
		promotions.clear();
		items.clear();
		nextId = 100;
		erpPricing(null);
		engine = new PromotionCalculationService();
		inject(engine, PromotionCalculationService.class, "itemRepository", itemRepository());
		inject(engine, PromotionCalculationService.class, "promotionRepository", promotionRepository());
		inject(engine, PromotionCalculationService.class, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		inject(engine, PromotionCalculationService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item item, Customer customer, BigDecimal quantity, String responsibilityCenter) {
				return erpPricing;
			}
		});
		item(10, familyF, subFamily);
		item(11, familyG, null);
		item(12, null, null);
		item(20, null, null);
	}

	@Test
	@DisplayName("A family promotion beats an all-items promotion, even with lower priority and value")
	void familyBeatsAllItems() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);
		assertPromotion(family5, price(10, 1));
		assertPromotion(all10, price(11, 1));
		assertPromotion(all10, price(12, 1));
		assertEquals(10.0, price(12, 1).getDiscountPercentage().doubleValue());
	}

	@Test
	@DisplayName("A family promotion that is not eligible falls through to all-items")
	void ineligibleFamilyFallsThrough() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);

		family5.setDayOfWeek(LocalDate.now().getDayOfWeek().plus(1).name());
		assertPromotion(all10, price(10, 1));
		family5.setDayOfWeek(null);

		family5.setRequiresCode(true);
		assertPromotion(all10, price(10, 1));
		assertPromotion(family5, price(10, 1, "FAM5"));
		family5.setRequiresCode(false);

		family5.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		family5.setMinimumQuantity(3);
		assertPromotion(all10, price(10, 1));
		assertPromotion(family5, price(10, 3));
	}

	@Test
	@DisplayName("Item, group and subfamily promotions all beat all-items")
	void moreSpecificScopesWin() {
		promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 100);
		Promotion item3 = promotion("ITEM3", PromotionScope.ITEM, 3, 0);
		item3.setItem(items.get(10L));
		assertPromotion(item3, price(10, 1));

		Promotion group4 = promotion("GRP4", PromotionScope.ITEM_GROUP, 4, 0);
		group4.getGroupItems().add(items.get(11L));
		assertPromotion(group4, price(11, 1));

		Promotion sub6 = promotion("SUB6", PromotionScope.ITEM_SUBFAMILY, 6, 0);
		sub6.setItemSubFamily(subFamily);
		promotions.remove(item3);
		assertPromotion(sub6, price(10, 1));
	}

	@Test
	@DisplayName("Between all-items promotions: priority first, then the higher percentage")
	void priorityThenValue() {
		Promotion p1 = promotion("A_P1_10", PromotionScope.ALL_ITEMS, 10, 1);
		promotion("A_P0_20", PromotionScope.ALL_ITEMS, 20, 0);
		assertPromotion(p1, price(12, 1));

		promotions.clear();
		promotion("B_P0_10", PromotionScope.ALL_ITEMS, 10, 0);
		Promotion b20 = promotion("B_P0_20", PromotionScope.ALL_ITEMS, 20, 0);
		assertPromotion(b20, price(12, 1));
	}

	@Test
	@DisplayName("All-items respects promo codes, the active flag and the end date")
	void codesActiveAndDates() {
		Promotion coded = promotion("ALLCODE", PromotionScope.ALL_ITEMS, 10, 0);
		coded.setRequiresCode(true);
		assertPromotion(null, price(12, 1));
		assertPromotion(coded, price(12, 1, "allcode"));

		coded.setRequiresCode(false);
		coded.setActive(false);
		assertPromotion(null, price(12, 1));

		coded.setActive(true);
		coded.setEndDate(LocalDate.now().minusDays(1));
		assertPromotion(null, price(12, 1));
	}

	@Test
	@DisplayName("The SalesDiscount comparison is unchanged: the better discount wins")
	void erpComparisonUnchanged() {
		Promotion all10 = promotion("ALL10", PromotionScope.ALL_ITEMS, 10, 0);
		erpPricing(15.0);
		PriceCalculateResponseDTO r = price(12, 1);
		assertNull(r.getPromotionId());
		assertEquals("SALES_DISCOUNT", r.getSource());
		assertEquals(15.0, r.getDiscountPercentage().doubleValue());
		erpPricing(5.0);
		assertPromotion(all10, price(12, 1));
	}

	@Test
	@DisplayName("An all-items quantity promotion checks the quantity per line")
	void quantityPromotionPerLine() {
		Promotion q = promotion("ALLQTY", PromotionScope.ALL_ITEMS, 0, 0);
		q.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		q.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		q.setDiscountPercentage(null);
		q.setFreeQuantity(1);
		q.setMinimumQuantity(3);
		assertPromotion(null, price(12, 2));
		PriceCalculateResponseDTO r = price(12, 3);
		assertPromotion(q, r);
		assertEquals(1, r.getFreeQuantity().intValue());
	}

	@Test
	@DisplayName("Without all-items promotions the results are exactly as before")
	void noAllItemsPromotionMeansNoChange() {
		Promotion family5 = promotion("FAM5", PromotionScope.ITEM_FAMILY, 5, 0);
		family5.setItemFamily(familyF);
		assertPromotion(null, price(11, 1));
		assertPromotion(family5, price(10, 1));
		assertEquals("BASE_PRICE", price(12, 1).getSource());
	}

	@Test
	@DisplayName("Cross-product with an all-items buy side: every line counts except the benefit item")
	void crossProductAllItems() {
		Promotion x = promotion("XALL", PromotionScope.ALL_ITEMS, 0, 0);
		x.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		x.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		x.setDiscountPercentage(null);
		x.setFreeQuantity(1);
		x.setMinimumQuantity(2);
		x.setGetItem(items.get(20L));

		List<CrossProductAdjustmentDTO> adj = engine.calculateCartPromotion(300.0,
				Arrays.asList(line(10, 2), line(20, 1)), new ArrayList<>()).getCrossProductAdjustments();
		assertEquals(1, adj.size());
		assertEquals(20L, adj.get(0).getGetItemId().longValue());
		assertEquals(1, adj.get(0).getEntitledUnits().intValue());
		assertEquals(1, adj.get(0).getFreeUnits().intValue());

		adj = engine.calculateCartPromotion(400.0, Collections.singletonList(line(20, 4)), new ArrayList<>())
				.getCrossProductAdjustments();
		assertTrue(adj.isEmpty(), "the benefit item's own line must not count as buy quantity");
		assertPromotion(null, price(10, 2)); // never discounts the buy line itself
	}

	@Test
	@DisplayName("Saving an all-items promotion clears every target; other scopes keep theirs")
	void saveNormalisesTargets() throws Exception {
		PromotionService service = new PromotionService();
		inject(service, PromotionService.class, "promotionRepository", promotionRepository());
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "test";
			}
		});

		Promotion all = new Promotion();
		all.setScope(PromotionScope.ALL_ITEMS);
		all.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		all.setItem(items.get(10L));
		all.setItemFamily(familyF);
		all.setItemSubFamily(subFamily);
		all.getGroupItems().add(items.get(11L));
		service.save(all);
		assertNull(all.getItem());
		assertNull(all.getItemFamily());
		assertNull(all.getItemSubFamily());
		assertTrue(all.getGroupItems().isEmpty());

		Promotion family = new Promotion();
		family.setScope(PromotionScope.ITEM_FAMILY);
		family.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		family.setItemFamily(familyF);
		service.save(family);
		assertSame(familyF, family.getItemFamily());
	}

	@Test
	@DisplayName("Admin search: 'All Items' finds the new scope, existing lookups are unchanged")
	void scopeLookup() {
		assertSame(PromotionScope.ALL_ITEMS, PromotionScope.fromString("All Items"));
		assertSame(PromotionScope.ALL_ITEMS, PromotionScope.fromString("all_items"));
		assertSame(PromotionScope.ITEM, PromotionScope.fromString("item"));
		assertSame(PromotionScope.CART, PromotionScope.fromString("cart"));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static ItemFamily family(long id) {
		ItemFamily f = new ItemFamily();
		f.setId(id);
		return f;
	}

	private static ItemSubFamily subFamily(long id) {
		ItemSubFamily s = new ItemSubFamily();
		s.setId(id);
		return s;
	}

	private void item(long id, ItemFamily family, ItemSubFamily subFamily) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("I" + id);
		i.setName("Item " + id);
		i.setItemFamily(family);
		i.setItemSubFamily(subFamily);
		i.setUnitPrice(100.0);
		items.put(id, i);
	}

	private Promotion promotion(String code, PromotionScope scope, double percentage, int priority) {
		Promotion p = new Promotion();
		p.setId(nextId++);
		p.setCode(code);
		p.setName(code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(scope);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(percentage);
		p.setPriority(priority);
		p.setRequiresCode(false);
		p.setActive(true);
		promotions.add(p);
		return p;
	}

	private void erpPricing(Double salesDiscountPercentage) {
		PricingResult r = new PricingResult();
		r.setUnitPrice(100.0);
		r.setPriceIncludesVat(true);
		r.setDiscountPercentage(salesDiscountPercentage);
		r.setSource(salesDiscountPercentage != null ? "SALES_DISCOUNT" : "BASE_PRICE");
		erpPricing = r;
	}

	private static CartCalculateRequestDTO.CartItemDTO line(long itemId, int quantity) {
		CartCalculateRequestDTO.CartItemDTO l = new CartCalculateRequestDTO.CartItemDTO();
		l.setItemId(itemId);
		l.setQuantity(BigDecimal.valueOf(quantity));
		return l;
	}

	private PriceCalculateResponseDTO price(long itemId, int quantity, String... codes) {
		return engine.calculateItemPrice(itemId, BigDecimal.valueOf(quantity), null, Arrays.asList(codes));
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r) {
		if (expected == null) {
			assertNull(r.getPromotionId(), "expected no promotion, got " + r.getPromotionCode());
		} else {
			assertEquals(expected.getId(), r.getPromotionId(), "expected " + expected.getCode() + ", got " + r.getPromotionCode());
			assertEquals("PROMOTION", r.getSource());
		}
	}

	// ─── In-memory repositories mirroring the JPQL in PromotionRepository ───

	private List<Promotion> query(Predicate<Promotion> filter, LocalDate today) {
		return promotions.stream()
				.filter(p -> Boolean.TRUE.equals(p.getActive()))
				.filter(p -> p.getStartDate() == null || !p.getStartDate().isAfter(today))
				.filter(p -> p.getEndDate() == null || !p.getEndDate().isBefore(today))
				.filter(filter)
				.sorted(Comparator.comparing(Promotion::getPriority).reversed())
				.collect(Collectors.toList());
	}

	private static boolean hasId(_BaseEntity entity, Object id) {
		return entity != null && entity.getId() != null && entity.getId().equals(id);
	}

	private PromotionRepository promotionRepository() {
		return stub(PromotionRepository.class, (method, a) -> {
			switch (method) {
				case "findActiveByItemId": return query(p -> hasId(p.getItem(), a[0]), (LocalDate) a[1]);
				case "findActiveByGroupItemId": return query(p -> p.getScope() == PromotionScope.ITEM_GROUP
						&& p.getGroupItems().stream().anyMatch(gi -> hasId(gi, a[0])), (LocalDate) a[1]);
				case "findActiveByItemSubFamilyId": return query(p -> hasId(p.getItemSubFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByItemFamilyId": return query(p -> hasId(p.getItemFamily(), a[0]), (LocalDate) a[1]);
				case "findActiveByScope": return query(p -> p.getScope() == a[0], (LocalDate) a[1]);
				case "findActiveCrossProductPromotions": return query(p -> p.getGetItem() != null, (LocalDate) a[0]);
				case "save": return a[0];
				default: return UNHANDLED;
			}
		});
	}

	private ItemRepository itemRepository() {
		return stub(ItemRepository.class, (method, a) -> {
			switch (method) {
				case "findById": return Optional.ofNullable(items.get(a[0]));
				case "findAllById": {
					List<Item> found = new ArrayList<>();
					for (Object id : (Iterable<?>) a[0]) {
						if (items.containsKey(id)) found.add(items.get(id));
					}
					return found;
				}
				default: return UNHANDLED;
			}
		});
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

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
