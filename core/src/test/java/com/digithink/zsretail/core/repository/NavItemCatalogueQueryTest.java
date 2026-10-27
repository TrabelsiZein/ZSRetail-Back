package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.query.QueryUtils;

import com.digithink.zsretail.core.dto.NavItemFilter;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.service.NavItemService;

/**
 * 3.1.1 (store orders): the catalogue of an order, ItemRepository.NAV_ITEMS with its level filters, barcode match and
 * sorts, run as Spring Data runs it (the service's sort appended by QueryUtils) with Hibernate over an in-memory H2
 * database. NAV items 70061 CHEMISE (famille HOM of catégorie VET, its own catégorie VET, section MANCHE; variants S, L
 * and an inactive M), 20002 ALEZE (famille MAI of catégorie MAISON, no catégorie of its own; two variants), 10003 BOUGIE
 * (no famille, catégorie MAISON, section DECO); an EMTOP item without a NAV item number is never a line.
 */
class NavItemCatalogueQueryTest {

	private static SessionFactory sessions;
	private static Long homId;
	private static Long maiId;
	private static Long chemId;
	private static Long chemiseLargeId;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:navcatalogue;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily hom = family(session, "HOM", "VET");
			ItemFamily mai = family(session, "MAI", "MAISON");
			ItemSubFamily chem = subFamily(session, "CHEM", hom);
			ItemSubFamily drap = subFamily(session, "DRAP", mai);
			variant(session, "70061~S", "70061", "CHEMISE", hom, chem, "VET", "MANCHE", 1, true);
			chemiseLargeId = variant(session, "70061~L", "70061", "CHEMISE", hom, chem, "VET", "MANCHE", 3, true).getId();
			variant(session, "70061~M", "70061", "CHEMISE", hom, chem, "VET", "MANCHE", 2, false);
			variant(session, "20002~B", "20002", "ALEZE", mai, drap, null, null, 2, true);
			variant(session, "20002~A", "20002", "ALEZE", mai, drap, null, null, 1, null);
			variant(session, "10003~X", "10003", "BOUGIE", null, null, "MAISON", "DECO", null, true);
			variant(session, "EMTOP-1", null, "BOUGIE EMTOP", mai, drap, "MAISON", null, null, true);
			session.getTransaction().commit();
			homId = hom.getId();
			maiId = mai.getId();
			chemId = chem.getId();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String categoryCode) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(code);
		family.setCategoryCode(categoryCode);
		session.persist(family);
		return family;
	}

	private static ItemSubFamily subFamily(Session session, String code, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName(code);
		subFamily.setItemFamily(family);
		session.persist(subFamily);
		return subFamily;
	}

	private static Item variant(Session session, String code, String navItemNo, String name, ItemFamily family,
			ItemSubFamily subFamily, String categoryCode, String sectionCode, Integer sizeOrder, Boolean active) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
		item.setSizeSortOrder(sizeOrder);
		item.setActive(active);
		session.persist(item);
		return item;
	}

	private static NavItemFilter filter() {
		return new NavItemFilter(); // active variants only, by number
	}

	/** The lines of a filter: [number, variant count], as NavItemService.page binds it. */
	private static List<String> lines(NavItemFilter filter) {
		try (Session session = sessions.openSession()) {
			Query<Object[]> query = session.createQuery(
					QueryUtils.applySorting(ItemRepository.NAV_ITEMS, NavItemService.sortOf(filter), "i"), Object[].class);
			bind(query, filter);
			return query.list().stream().map(row -> row[0] + ":" + ((Number) row[6]).longValue())
					.collect(Collectors.toList());
		}
	}

	private static long count(NavItemFilter filter) {
		try (Session session = sessions.openSession()) {
			Query<Long> query = session.createQuery(ItemRepository.NAV_ITEMS_COUNT, Long.class);
			bind(query, filter);
			return query.uniqueResult();
		}
	}

	private static void bind(Query<?> query, NavItemFilter filter) {
		Collection<Long> ids = filter.getBarcodeItemIds().isEmpty() ? ItemRepository.NO_ITEM_IDS
				: filter.getBarcodeItemIds();
		query.setParameter("search", NavItemService.likeOf(filter.getSearch())).setParameterList("barcodeItemIds", ids)
				.setParameter("activeOnly", filter.isActiveOnly() ? Boolean.TRUE : null)
				.setParameter("categoryCode", filter.getCategoryCode()).setParameter("familyId", filter.getFamilyId())
				.setParameter("subFamilyId", filter.getSubFamilyId()).setParameter("sectionCode", filter.getSectionCode());
	}

	@Test
	@DisplayName("No filter: every NAV item with an active variant, by number, its active variants counted")
	void noFilter() {
		assertEquals(Arrays.asList("10003:1", "20002:2", "70061:2"), lines(filter()));
		assertEquals(3, count(filter()));
	}

	@Test
	@DisplayName("Catégorie: the item's own code, else its famille's (ALEZE has none of its own, its famille is MAISON)")
	void category() {
		NavItemFilter maison = filter();
		maison.setCategoryCode("MAISON");
		assertEquals(Arrays.asList("10003:1", "20002:2"), lines(maison), "the EMTOP item of MAISON is no line");
		assertEquals(2, count(maison));
		NavItemFilter vet = filter();
		vet.setCategoryCode("VET");
		assertEquals(Arrays.asList("70061:2"), lines(vet));
	}

	@Test
	@DisplayName("Famille, sous-famille and section each narrow the lines")
	void levels() {
		NavItemFilter family = filter();
		family.setFamilyId(maiId);
		assertEquals(Arrays.asList("20002:2"), lines(family));
		NavItemFilter subFamily = filter();
		subFamily.setSubFamilyId(chemId);
		assertEquals(Arrays.asList("70061:2"), lines(subFamily));
		NavItemFilter section = filter();
		section.setSectionCode("DECO");
		assertEquals(Arrays.asList("10003:1"), lines(section));
		NavItemFilter none = filter();
		none.setFamilyId(homId);
		none.setSectionCode("DECO");
		assertEquals(Collections.emptyList(), lines(none));
		assertEquals(0, count(none));
	}

	@Test
	@DisplayName("Search: number or name, and a barcode of one variant gives its NAV item with all its active variants")
	void search() {
		NavItemFilter name = filter();
		name.setSearch("ALE");
		assertEquals(Arrays.asList("20002:2"), lines(name));
		NavItemFilter barcode = filter();
		barcode.setSearch("6191234"); // no number nor name holds it: the barcode of 70061~L
		assertEquals(Collections.emptyList(), lines(barcode));
		barcode.setBarcodeItemIds(Collections.singletonList(chemiseLargeId));
		assertEquals(Arrays.asList("70061:2"), lines(barcode), "S and L counted, not the variant of the barcode only");
		assertEquals(1, count(barcode));
	}

	@Test
	@DisplayName("Sort: by name then number, either way; by number descending")
	void sorts() {
		NavItemFilter byName = filter();
		byName.setSort("name");
		assertEquals(Arrays.asList("20002:2", "10003:1", "70061:2"), lines(byName));
		byName.setDescending(true);
		assertEquals(Arrays.asList("70061:2", "10003:1", "20002:2"), lines(byName));
		NavItemFilter byNumber = filter();
		byNumber.setDescending(true);
		assertEquals(Arrays.asList("70061:2", "20002:2", "10003:1"), lines(byNumber));
	}

	@Test
	@DisplayName("Variants of a page: active only, by number then size order")
	void variantsOfNavItems() {
		try (Session session = sessions.openSession()) {
			String statement = ItemRepository.class.getMethod("findVariantsOfNavItems", Collection.class, Boolean.class)
					.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
			List<String> codes = session.createQuery(statement, Item.class)
					.setParameterList("navItemNos", Arrays.asList("70061", "20002")).setParameter("activeOnly", Boolean.TRUE)
					.list().stream().map(Item::getItemCode).collect(Collectors.toList());
			assertEquals(Arrays.asList("20002~A", "20002~B", "70061~S", "70061~L"), codes);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}
}
