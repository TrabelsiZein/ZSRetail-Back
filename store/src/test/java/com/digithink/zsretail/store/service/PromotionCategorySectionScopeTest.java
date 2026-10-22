package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.digithink.zsretail.core.dto.PricingResult;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.core.model.enumeration.PromotionScope;
import com.digithink.zsretail.core.model.enumeration.PromotionType;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CustomerService;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.service.PromotionService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.store.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;

/**
 * 3.1.0 (ZEN): the CATEGORY and SECTION promotion scopes. Order item > group > section > sub-family > family >
 * catégorie > all items; an item without a section or catégorie never reads those scopes (EMTOP, Happyness: no change).
 * Plain JUnit with in-memory stubs that mirror the JPQL of PromotionRepository, as PromotionAllItemsScopeTest.
 */
class PromotionCategorySectionScopeTest {

	private final List<Promotion> promotions = new ArrayList<>();
	private final Map<Long, Item> items = new HashMap<>();
	private PromotionCalculationService engine;
	private long nextId;
	/** Calls of findActiveBySectionCode / findActiveByCategoryCode. */
	private int newScopeReads;

	private final ItemCategory vet = category(1, "VET");
	private final ItemCategory meubles = category(2, "MEUBLES");
	private final ItemSection manche = section(1, "MANCHE");
	private final ItemFamily familyVet = family(1, "VET");
	private final ItemFamily familyPlain = family(2, null);
	private final ItemSubFamily subFamily = subFamily(5);

	@BeforeEach
	void setUp() throws Exception {
		nextId = 100;
		engine = new PromotionCalculationService();
		inject(engine, PromotionCalculationService.class, "itemRepository", itemRepository());
		inject(engine, PromotionCalculationService.class, "promotionRepository", promotionRepository());
		inject(engine, PromotionCalculationService.class, "customerService", new CustomerService() {
			@Override
			public Customer getDefaultCustomer() {
				return new Customer();
			}
		});
		PricingResult erp = new PricingResult();
		erp.setUnitPrice(100.0);
		erp.setPriceIncludesVat(true);
		erp.setSource("BASE_PRICE");
		inject(engine, PromotionCalculationService.class, "pricingService", new PricingService() {
			@Override
			public PricingResult calculateItemPrice(Item item, Customer customer, BigDecimal quantity,
					String responsibilityCenter) {
				return erp;
			}
		});
		item(30, familyVet, subFamily, null, "MANCHE"); // catégorie through its family
		item(31, familyVet, null, "MEUBLES", null); // its own catégorie wins over its family's
		item(32, familyPlain, subFamily, null, null); // as EMTOP / Happyness: no catégorie, no section
		item(33, null, null, " ", ""); // blank codes count as none
		item(40, null, null, null, null); // a benefit item
	}

	@Test
	@DisplayName("Order: section > sub-family > family > catégorie > all items")
	void cascade() {
		Promotion sec = promotion("SEC", PromotionScope.SECTION, 5);
		sec.setItemSection(manche);
		Promotion sub = promotion("SUB", PromotionScope.ITEM_SUBFAMILY, 6);
		sub.setItemSubFamily(subFamily);
		Promotion fam = promotion("FAM", PromotionScope.ITEM_FAMILY, 7);
		fam.setItemFamily(familyVet);
		Promotion cat = promotion("CAT", PromotionScope.CATEGORY, 8);
		cat.setItemCategory(vet);
		Promotion all = promotion("ALL", PromotionScope.ALL_ITEMS, 10);

		assertPromotion(sec, price(30));
		promotions.remove(sec);
		assertPromotion(sub, price(30));
		promotions.remove(sub);
		assertPromotion(fam, price(30));
		promotions.remove(fam);
		assertPromotion(cat, price(30), "the item has no catégorie code: its family's (VET)");
		promotions.remove(cat);
		assertPromotion(all, price(30));
	}

	@Test
	@DisplayName("Item and group promotions still beat a section promotion")
	void itemAndGroupFirst() {
		Promotion sec = promotion("SEC", PromotionScope.SECTION, 50);
		sec.setItemSection(manche);
		Promotion group = promotion("GRP", PromotionScope.ITEM_GROUP, 1);
		group.getGroupItems().add(items.get(30L));
		assertPromotion(group, price(30));
		Promotion item = promotion("ITEM", PromotionScope.ITEM, 1);
		item.setItem(items.get(30L));
		assertPromotion(item, price(30));
	}

	@Test
	@DisplayName("The item's own catégorie code wins over its family's")
	void ownCategoryFirst() {
		Promotion catVet = promotion("CATVET", PromotionScope.CATEGORY, 20);
		catVet.setItemCategory(vet);
		Promotion catMeubles = promotion("CATMEUBLES", PromotionScope.CATEGORY, 5);
		catMeubles.setItemCategory(meubles);
		assertPromotion(catMeubles, price(31));
		assertPromotion(catVet, price(30));
	}

	@Test
	@DisplayName("No catégorie, no section (EMTOP, Happyness): those scopes are never read; the cascade as before")
	void noCodesNoChange() {
		Promotion sec = promotion("SEC", PromotionScope.SECTION, 50);
		sec.setItemSection(manche);
		Promotion cat = promotion("CAT", PromotionScope.CATEGORY, 50);
		cat.setItemCategory(vet);
		Promotion fam = promotion("FAM", PromotionScope.ITEM_FAMILY, 7);
		fam.setItemFamily(familyPlain);
		Promotion all = promotion("ALL", PromotionScope.ALL_ITEMS, 10);

		assertPromotion(fam, price(32));
		assertPromotion(all, price(33));
		promotions.remove(fam);
		assertPromotion(all, price(32));
		assertEquals(0, newScopeReads, "no section or catégorie lookup for an item without one");
	}

	@Test
	@DisplayName("Lookups filter on the scope: a FAMILY promotion that kept a catégorie never matches as a catégorie")
	void scopeFilter() {
		Promotion stray = promotion("STRAY", PromotionScope.ITEM_FAMILY, 30);
		stray.setItemFamily(familyPlain);
		stray.setItemCategory(vet);
		assertPromotion(null, price(30));
	}

	@Test
	@DisplayName("Cross-product with a catégorie or section buy side: the lines of that catégorie / section count")
	void crossProduct() {
		Promotion x = promotion("XCAT", PromotionScope.CATEGORY, 0);
		x.setItemCategory(vet);
		x.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		x.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		x.setDiscountPercentage(null);
		x.setFreeQuantity(1);
		x.setMinimumQuantity(2);
		x.setGetItem(items.get(40L));

		List<CrossProductAdjustmentDTO> adj = engine.calculateCartPromotion(500.0,
				Arrays.asList(line(30, 2), line(32, 2), line(40, 1)), new ArrayList<>()).getCrossProductAdjustments();
		assertEquals(1, adj.size());
		assertEquals(1, adj.get(0).getEntitledUnits().intValue(), "item 30 (VET) counts, item 32 does not");

		x.setScope(PromotionScope.SECTION);
		x.setItemCategory(null);
		x.setItemSection(manche);
		adj = engine.calculateCartPromotion(500.0, Arrays.asList(line(31, 2), line(40, 1)), new ArrayList<>())
				.getCrossProductAdjustments();
		assertTrue(adj.isEmpty(), "item 31 has no section");
	}

	@Test
	@DisplayName("Save: CATEGORY / SECTION need their target and own it alone; other scopes keep neither")
	void saveValidation() throws Exception {
		PromotionService service = new PromotionService();
		inject(service, PromotionService.class, "promotionRepository", promotionRepository());
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "test";
			}
		});

		Promotion noCategory = draft(PromotionScope.CATEGORY);
		assertEquals("A CATEGORY promotion must target a catégorie",
				assertThrows(IllegalArgumentException.class, () -> service.save(noCategory)).getMessage());
		Promotion noSection = draft(PromotionScope.SECTION);
		noSection.setItemCategory(vet); // the wrong target
		assertEquals("A SECTION promotion must target a section",
				assertThrows(IllegalArgumentException.class, () -> service.save(noSection)).getMessage());

		Promotion section = draft(PromotionScope.SECTION);
		section.setItemSection(manche);
		section.setItemCategory(vet);
		section.setItem(items.get(30L));
		section.setItemFamily(familyVet);
		section.setItemSubFamily(subFamily);
		service.save(section);
		assertSame(manche, section.getItemSection());
		assertNull(section.getItemCategory());
		assertNull(section.getItem());
		assertNull(section.getItemFamily());
		assertNull(section.getItemSubFamily());

		Promotion family = draft(PromotionScope.ITEM_FAMILY);
		family.setItemFamily(familyVet);
		family.setItemSubFamily(subFamily);
		family.setItemCategory(vet);
		family.setItemSection(manche);
		service.save(family);
		assertSame(familyVet, family.getItemFamily());
		assertSame(subFamily, family.getItemSubFamily(), "the existing scopes keep what they kept before");
		assertNull(family.getItemCategory());
		assertNull(family.getItemSection());
	}

	@Test
	@DisplayName("Admin search finds the two new scopes by name")
	void scopeLookup() {
		assertEquals(PromotionScope.CATEGORY, PromotionScope.fromString("category"));
		assertEquals(PromotionScope.SECTION, PromotionScope.fromString("Section"));
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private static Promotion draft(PromotionScope scope) {
		Promotion p = new Promotion();
		p.setScope(scope);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		return p;
	}

	private static ItemCategory category(long id, String code) {
		ItemCategory c = new ItemCategory();
		c.setId(id);
		c.setCode(code);
		return c;
	}

	private static ItemSection section(long id, String code) {
		ItemSection s = new ItemSection();
		s.setId(id);
		s.setCode(code);
		return s;
	}

	private static ItemFamily family(long id, String categoryCode) {
		ItemFamily f = new ItemFamily();
		f.setId(id);
		f.setCategoryCode(categoryCode);
		return f;
	}

	private static ItemSubFamily subFamily(long id) {
		ItemSubFamily s = new ItemSubFamily();
		s.setId(id);
		return s;
	}

	private void item(long id, ItemFamily family, ItemSubFamily subFamily, String categoryCode, String sectionCode) {
		Item i = new Item();
		i.setId(id);
		i.setItemCode("I" + id);
		i.setName("Item " + id);
		i.setItemFamily(family);
		i.setItemSubFamily(subFamily);
		i.setCategoryCode(categoryCode);
		i.setSectionCode(sectionCode);
		i.setUnitPrice(100.0);
		items.put(id, i);
	}

	private Promotion promotion(String code, PromotionScope scope, double percentage) {
		Promotion p = new Promotion();
		p.setId(nextId++);
		p.setCode(code);
		p.setName(code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(scope);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(percentage);
		p.setPriority(0);
		p.setRequiresCode(false);
		p.setActive(true);
		promotions.add(p);
		return p;
	}

	private static CartCalculateRequestDTO.CartItemDTO line(long itemId, int quantity) {
		CartCalculateRequestDTO.CartItemDTO l = new CartCalculateRequestDTO.CartItemDTO();
		l.setItemId(itemId);
		l.setQuantity(BigDecimal.valueOf(quantity));
		return l;
	}

	private PriceCalculateResponseDTO price(long itemId) {
		return engine.calculateItemPrice(itemId, BigDecimal.ONE, null, new ArrayList<>());
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r) {
		assertPromotion(expected, r, "");
	}

	private static void assertPromotion(Promotion expected, PriceCalculateResponseDTO r, String why) {
		if (expected == null) {
			assertNull(r.getPromotionId(), "expected no promotion, got " + r.getPromotionCode());
		} else {
			assertEquals(expected.getId(), r.getPromotionId(),
					"expected " + expected.getCode() + ", got " + r.getPromotionCode() + " " + why);
		}
	}

	// ─── In-memory repositories mirroring the JPQL of PromotionRepository ───

	private List<Promotion> query(Predicate<Promotion> filter, LocalDate today) {
		return promotions.stream().filter(p -> Boolean.TRUE.equals(p.getActive()))
				.filter(p -> p.getStartDate() == null || !p.getStartDate().isAfter(today))
				.filter(p -> p.getEndDate() == null || !p.getEndDate().isBefore(today)).filter(filter)
				.sorted(Comparator.comparing(Promotion::getPriority).reversed()).collect(Collectors.toList());
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
				case "findActiveBySectionCode":
					newScopeReads++;
					return query(p -> p.getScope() == PromotionScope.SECTION && p.getItemSection() != null
							&& a[0].equals(p.getItemSection().getCode()), (LocalDate) a[1]);
				case "findActiveByCategoryCode":
					newScopeReads++;
					return query(p -> p.getScope() == PromotionScope.CATEGORY && p.getItemCategory() != null
							&& a[0].equals(p.getItemCategory().getCode()), (LocalDate) a[1]);
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
