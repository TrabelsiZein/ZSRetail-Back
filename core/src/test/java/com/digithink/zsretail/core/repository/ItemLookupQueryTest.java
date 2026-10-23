package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.digithink.zsretail.core.dto.ItemLookupLineDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.ItemType;

/**
 * Promotions for variants, step 4: the native statements of the promotion form's target lookup (ItemLookupQuery), run as
 * they are with Hibernate over an in-memory H2 database in SQL Server mode (no Spring context). NAV item 70061 (two
 * active variants and an inactive one), 14ZDU05 (one variant, active null); an item without an ERP item number (EMTOP,
 * Happyness); a pack and an inactive item that are never a line.
 */
class ItemLookupQueryTest {

	private static SessionFactory sessions;
	private static final Map<String, Long> ids = new HashMap<>();

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		// The application's names (snake_case tables and columns), as the native statements read them
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemCategory.class).addAnnotatedClass(ItemSection.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:itemlookup;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily men = family(session, "HOM", "Homme", true);
			family(session, "OLD", "Ancienne famille", false);
			ItemSubFamily shirts = subFamily(session, "CHEM", "Chemises", men);
			category(session, "VET", "Vetements");
			section(session, "MANCHE", "Manches longues", "CHEM");
			item(session, "70061~S", "70061", "CHEMISE", "Taille S", true, ItemType.PRODUCT);
			item(session, "70061~M", "70061", "CHEMISE", "Taille M", true, ItemType.PRODUCT);
			item(session, "70061~X", "70061", "CHEMISE", "Taille XL", false, ItemType.PRODUCT);
			item(session, "14ZDU05~A", "14ZDU05", "DUVET", "Bleu", null, ItemType.PRODUCT);
			item(session, "EMTOP-1", null, "CHEMISE EMTOP", null, true, ItemType.PRODUCT);
			item(session, "PACK-1", null, "CHEMISE PACK", null, true, ItemType.PACKAGE);
			item(session, "OFF-1", null, "ANCIEN ARTICLE", null, false, ItemType.PRODUCT);
			session.getTransaction().commit();
			shirts.getId();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String name, boolean active) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(name);
		family.setActive(active);
		session.persist(family);
		return family;
	}

	private static ItemSubFamily subFamily(Session session, String code, String name, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName(name);
		subFamily.setItemFamily(family);
		session.persist(subFamily);
		return subFamily;
	}

	private static void category(Session session, String code, String name) {
		ItemCategory category = new ItemCategory();
		category.setCode(code);
		category.setName(name);
		session.persist(category);
	}

	private static void section(Session session, String code, String name, String subFamilyCode) {
		ItemSection section = new ItemSection();
		section.setCode(code);
		section.setName(name);
		section.setSubFamilyCode(subFamilyCode);
		session.persist(section);
	}

	private static void item(Session session, String code, String navItemNo, String name, String variant, Boolean active,
			ItemType type) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setVariantDescription(variant);
		item.setActive(active);
		item.setType(type);
		session.persist(item);
		ids.put(code, item.getId());
	}

	private static Page<ItemLookupLineDTO> lookup(String scope, String search, List<Long> barcodeItemIds, int page,
			int size) {
		try (Session session = sessions.openSession()) {
			String like = search == null ? null : "%" + search.toLowerCase() + "%";
			return new ItemLookupQuery(session).page(scope, like, barcodeItemIds, PageRequest.of(page, size));
		}
	}

	private static List<String> lines(Page<ItemLookupLineDTO> page) {
		return page.getContent().stream().map(l -> l.getKind() + " " + l.getCode()).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Item: one line per ERP item number before its variants, a line per item; packs and inactive left out")
	void itemLines() {
		Page<ItemLookupLineDTO> page = lookup(ItemLookupQuery.ITEM, null, Collections.emptyList(), 0, 20);
		assertEquals(Arrays.asList("NAV_ITEM 70061", "ROW 70061~M", "ROW 70061~S", "ROW EMTOP-1", "NAV_ITEM 14ZDU05",
				"ROW 14ZDU05~A"), lines(page), "by name (CHEMISE, CHEMISE EMTOP, DUVET), the NAV line first, then by code");
		assertEquals(6, page.getTotalElements());
		Map<String, ItemLookupLineDTO> byCode = page.getContent().stream()
				.collect(Collectors.toMap(ItemLookupLineDTO::getCode, Function.identity()));
		ItemLookupLineDTO nav = byCode.get("70061");
		assertEquals(Math.min(ids.get("70061~S"), ids.get("70061~M")), nav.getId(), "its lowest active id");
		assertEquals(2, nav.getVariantCount().intValue(), "the inactive XL left out");
		assertEquals("CHEMISE", nav.getName());
		assertEquals("70061", nav.getNavItemNo());
		assertNull(nav.getVariantDescription());
		ItemLookupLineDTO row = byCode.get("70061~S");
		assertEquals(ids.get("70061~S"), row.getId());
		assertEquals("Taille S", row.getVariantDescription());
		assertEquals("70061", row.getNavItemNo());
		assertEquals(1, row.getVariantCount().intValue());
		assertNull(byCode.get("EMTOP-1").getNavItemNo());
		assertEquals(1, byCode.get("14ZDU05").getVariantCount().intValue(), "active null counts as active");
	}

	@Test
	@DisplayName("Without ERP item numbers (EMTOP, Happyness): only item lines, the same statement")
	void withoutNavItemNumbers() {
		assertEquals(Arrays.asList("ROW EMTOP-1"), lines(lookup(ItemLookupQuery.ITEM, "emtop", null, 0, 20)));
	}

	@Test
	@DisplayName("Search: code, name, ERP item number; a NAV line matches through a variant and keeps all its variants")
	void search() {
		Page<ItemLookupLineDTO> page = lookup(ItemLookupQuery.ITEM, "70061~m", Collections.emptyList(), 0, 20);
		assertEquals(Arrays.asList("NAV_ITEM 70061", "ROW 70061~M"), lines(page));
		assertEquals(2, page.getContent().get(0).getVariantCount().intValue(), "S counts though only M matched");
		assertEquals(Arrays.asList("NAV_ITEM 14ZDU05", "ROW 14ZDU05~A"), lines(lookup(ItemLookupQuery.ITEM, "Duv", null, 0, 20)));
		assertEquals(Arrays.asList("NAV_ITEM 14ZDU05", "ROW 14ZDU05~A"),
				lines(lookup(ItemLookupQuery.ITEM, "14zdu", null, 0, 20)), "by ERP item number");
		assertEquals(0, lookup(ItemLookupQuery.ITEM, "pack", null, 0, 20).getTotalElements(), "a pack is never a line");
	}

	@Test
	@DisplayName("Two ERP items of one name: each 'all variants' line right above its own variants (by ERP item number)")
	void sameNameErpItems() {
		// rows added for this test only, removed after (the other tests count the base rows)
		List<String> added = Arrays.asList("P2~R", "P1~B", "P2~A");
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			item(session, "P2~R", "P2", "PULL", "Rouge", true, ItemType.PRODUCT);
			item(session, "P1~B", "P1", "PULL", "Bleu", true, ItemType.PRODUCT);
			item(session, "P2~A", "P2", "PULL", "Ambre", true, ItemType.PRODUCT);
			session.getTransaction().commit();
		}
		try {
			assertEquals(Arrays.asList("NAV_ITEM P1", "ROW P1~B", "NAV_ITEM P2", "ROW P2~A", "ROW P2~R"),
					lines(lookup(ItemLookupQuery.ITEM, "pull", null, 0, 20)));
		} finally {
			try (Session session = sessions.openSession()) {
				session.beginTransaction();
				for (String code : added) {
					session.delete(session.get(Item.class, ids.remove(code)));
				}
				session.getTransaction().commit();
			}
		}
	}

	@Test
	@DisplayName("Barcode: the items of a matching barcode (ids read before) and their ERP item number's line")
	void barcode() {
		Page<ItemLookupLineDTO> page = lookup(ItemLookupQuery.ITEM, "6190005307694",
				Collections.singletonList(ids.get("70061~S")), 0, 20);
		assertEquals(Arrays.asList("NAV_ITEM 70061", "ROW 70061~S"), lines(page));
	}

	@Test
	@DisplayName("Paging: offset and size over the mixed lines, the total of all")
	void paging() {
		Page<ItemLookupLineDTO> first = lookup(ItemLookupQuery.ITEM, null, null, 0, 4);
		Page<ItemLookupLineDTO> second = lookup(ItemLookupQuery.ITEM, null, null, 1, 4);
		assertEquals(Arrays.asList("NAV_ITEM 70061", "ROW 70061~M", "ROW 70061~S", "ROW EMTOP-1"), lines(first));
		assertEquals(Arrays.asList("NAV_ITEM 14ZDU05", "ROW 14ZDU05~A"), lines(second));
		assertEquals(6, second.getTotalElements());
		assertEquals(2, second.getTotalPages());
		Page<ItemLookupLineDTO> beyond = lookup(ItemLookupQuery.ITEM, null, null, 5, 4);
		assertTrue(beyond.getContent().isEmpty());
		assertEquals(6, beyond.getTotalElements(), "a page past the end: the total counted apart");
		assertEquals(0, lookup(ItemLookupQuery.ITEM, "nothing at all", null, 0, 4).getTotalElements());
	}

	@Test
	@DisplayName("Famille, sous-famille (its famille), catégorie, section (its sous-famille): active rows, searched")
	void otherScopes() {
		assertEquals(Arrays.asList("FAMILY HOM"), lines(lookup(ItemLookupQuery.FAMILY, null, null, 0, 20)),
				"the inactive famille left out");
		ItemLookupLineDTO subFamily = lookup(ItemLookupQuery.SUBFAMILY, "chem", null, 0, 20).getContent().get(0);
		assertEquals("CHEM", subFamily.getCode());
		assertEquals("Homme", subFamily.getParentName());
		assertEquals(Arrays.asList("CATEGORY VET"), lines(lookup(ItemLookupQuery.CATEGORY, "vet", null, 0, 20)));
		ItemLookupLineDTO section = lookup(ItemLookupQuery.SECTION, "longues", null, 0, 20).getContent().get(0);
		assertEquals("MANCHE", section.getCode());
		assertEquals("Chemises", section.getParentName());
		assertTrue(lookup(ItemLookupQuery.SECTION, "nothing", null, 0, 20).getContent().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> ItemLookupQuery.from("BRAND", false, false));
	}
}
