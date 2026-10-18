package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.service.HoItemCatalogueService;

/**
 * Fiches items (3.1.0): the list's filters, sorts and price columns and the page's flags and filter choices
 * (HoItemCatalogueQuery, HoItemCatalogueService.filters), the real query over an in-memory H2 database with the
 * applications' naming strategies (a Hibernate session as its EntityManager: the paging and the count as in the
 * applications).
 * <p>
 * NAV item 70061 (Homme / Chemises, division PAP, catégorie VET, section MANCHE): S 10 + 19 % (11.90), M 20 + 19 %
 * (23.80, a row in P1), L inactive 30 + 19 % (35.70). NAV item ONE: one variant (Femme / Robes, 50, an inactive row in
 * P2). Items without NAV number, codes of digits with uneven zeros and letters: 000001 (Femme / Robes, 5), 000002 (15,
 * a row in P2), 10 (25), 0000016 (no price), 0000017 (8), A1 (100), B2 (1). Points P1, P2, P3 (inactive).
 */
class ItemCatalogueFiltersQueryTest {

	private static SessionFactory sessions;
	private static long hom;
	private static long fem;
	private static long robes;
	private static long p1;
	private static long p2;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(ItemCategory.class)
				.addAnnotatedClass(ItemSection.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:itemcataloguefilters;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily homme = family(session, "HOM", "Homme");
			ItemFamily femme = family(session, "FEM", "Femme");
			ItemSubFamily chemises = subFamily(session, "CHEM", "Chemises", homme);
			ItemSubFamily robesRow = subFamily(session, "ROBE", "Robes", femme);
			hom = homme.getId();
			fem = femme.getId();
			robes = robesRow.getId();
			ItemCategory vet = new ItemCategory();
			vet.setCode("VET");
			vet.setName("Vêtements");
			session.persist(vet);
			ItemSection manche = new ItemSection();
			manche.setCode("MANCHE");
			manche.setName("Manches");
			session.persist(manche);
			item(session, "70061~S", "70061", "CHEMISE", homme, chemises, true, 10.0, 19, "PAP", "VET", "MANCHE");
			Item m = item(session, "70061~M", "70061", "CHEMISE", homme, chemises, true, 20.0, 19, "PAP", "VET", "MANCHE");
			item(session, "70061~L", "70061", "CHEMISE", homme, chemises, false, 30.0, 19, "PAP", "VET", "MANCHE");
			Item one = item(session, "ONE~A", "ONE", "ROBE", femme, robesRow, true, 50.0, 0, null, null, null);
			item(session, "000001", null, "DELTA", femme, robesRow, true, 5.0, 0, null, null, null);
			Item two = item(session, "000002", null, "BRAVO", null, null, true, 15.0, 0, null, null, null);
			item(session, "10", null, "ECHO", null, null, true, 25.0, 0, null, null, null);
			item(session, "0000016", null, "FOXTROT", null, null, true, null, 0, null, null, null);
			item(session, "0000017", null, "GOLF", null, null, true, 8.0, 0, null, null, null);
			item(session, "A1", null, "HOTEL", null, null, true, 100.0, 0, null, null, null);
			item(session, "B2", null, "ALPHA", null, null, true, 1.0, 0, null, null, null);
			p1 = point(session, "P1", 1, true);
			p2 = point(session, "P2", 2, true);
			point(session, "P3", 3, false);
			row(session, p1, m.getId(), true);
			row(session, p2, one.getId(), false);
			row(session, p2, two.getId(), true);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String name) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(name);
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

	private static Item item(Session session, String code, String navItemNo, String name, ItemFamily family,
			ItemSubFamily subFamily, boolean active, Double price, int vat, String division, String category, String section) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(active);
		item.setUnitPrice(price);
		item.setDefaultVAT(vat);
		item.setDivisionCode(division);
		item.setCategoryCode(category);
		item.setSectionCode(section);
		session.persist(item);
		return item;
	}

	private static long point(Session session, String code, int order, boolean active) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName("Point " + code);
		point.setSortOrder(order);
		point.setActive(active);
		session.persist(point);
		return point.getId();
	}

	private static void row(Session session, long pointId, long itemId, boolean active) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(itemId);
		row.setName("row");
		row.setUnitPrice(1.0);
		row.setActive(active);
		session.persist(row);
	}

	/** The real query over one session (Hibernate's Session is the EntityManager). */
	private static <T> T withQuery(Function<HoItemCatalogueQuery, T> work) {
		try (Session session = sessions.openSession()) {
			HoItemCatalogueQuery query = new HoItemCatalogueQuery();
			query.portable = true; // H2: the code sort in portable SQL
			Field field = HoItemCatalogueQuery.class.getDeclaredField("entityManager");
			field.setAccessible(true);
			field.set(query, session);
			return work.apply(query);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** One page of lines of a criteria (status All unless the setup says otherwise), as the service maps them. */
	private static Page<ItemCatalogueLineDTO> page(Consumer<HoItemCatalogueQuery.Criteria> setup, int page, int size) {
		HoItemCatalogueQuery.Criteria criteria = new HoItemCatalogueQuery.Criteria();
		criteria.setActiveOnly(false);
		setup.accept(criteria);
		return withQuery(query -> query.lines(criteria, PageRequest.of(page, size))
				.map(row -> HoItemCatalogueService.line(row, true)));
	}

	private static List<String> codes(Consumer<HoItemCatalogueQuery.Criteria> setup) {
		return page(setup, 0, 50).getContent().stream().map(ItemCatalogueLineDTO::getCode).collect(Collectors.toList());
	}

	private static ItemCatalogueLineDTO line(Consumer<HoItemCatalogueQuery.Criteria> setup, String code) {
		return page(setup, 0, 50).getContent().stream().filter(l -> code.equals(l.getCode())).findFirst().orElse(null);
	}

	@Test
	@DisplayName("Code sort: the codes of digits only by their number (000002 before 0000016), then the others as text")
	void naturalCodeSort() {
		assertEquals(Arrays.asList("000001", "000002", "10", "0000016", "0000017", "70061", "A1", "B2", "ONE"),
				codes(c -> { }));
		assertEquals(Arrays.asList("ONE", "B2", "A1", "70061", "0000017", "0000016", "10", "000002", "000001"),
				codes(c -> c.setDescending(true)));
		Page<ItemCatalogueLineDTO> second = page(c -> { }, 1, 4);
		assertEquals(9, second.getTotalElements());
		assertEquals(Arrays.asList("0000017", "70061", "A1", "B2"),
				second.getContent().stream().map(ItemCatalogueLineDTO::getCode).collect(Collectors.toList()));
	}

	@Test
	@DisplayName("Name and family sorts, each then by code; lines without a family last")
	void nameAndFamilySorts() {
		assertEquals(Arrays.asList("B2", "000002", "70061", "000001", "10", "0000016", "0000017", "A1", "ONE"),
				codes(c -> c.setSort("name")));
		assertEquals(Arrays.asList("000001", "ONE", "70061", "000002", "10", "0000016", "0000017", "A1", "B2"),
				codes(c -> c.setSort("family")));
		assertEquals(Arrays.asList("70061", "000001", "ONE", "000002", "10", "0000016", "0000017", "A1", "B2"),
				codes(c -> { c.setSort("family"); c.setDescending(true); }));
	}

	@Test
	@DisplayName("Family, sub-family, division, catégorie and section keep whole lines (every variant of the status counted)")
	void treeFilters() {
		assertEquals(Arrays.asList("70061"), codes(c -> c.setFamilyId(hom)));
		assertEquals(3, line(c -> c.setFamilyId(hom), "70061").getVariants());
		assertEquals(2, line(c -> { c.setFamilyId(hom); c.setActiveOnly(true); }, "70061").getVariants());
		assertEquals(Arrays.asList("000001", "ONE"), codes(c -> c.setFamilyId(fem)));
		assertEquals(Arrays.asList("000001", "ONE"), codes(c -> c.setSubFamilyId(robes)));
		assertEquals(Arrays.asList("70061"), codes(c -> c.setDivisionCode(" PAP ")));
		assertEquals(Arrays.asList("70061"), codes(c -> c.setCategoryCode("VET")));
		assertEquals(Arrays.asList("70061"), codes(c -> c.setSectionCode("MANCHE")));
		assertEquals(1, page(c -> c.setSectionCode("MANCHE"), 0, 20).getTotalElements());
		assertTrue(codes(c -> c.setCategoryCode("NONE")).isEmpty());
	}

	@Test
	@DisplayName("Point de stock: a line when one of its variants has an active row there; the line counts all its variants")
	void stockPointFilter() {
		assertEquals(Arrays.asList("70061"), codes(c -> c.setStockPointId(p1)));
		assertEquals(3, line(c -> c.setStockPointId(p1), "70061").getVariants(), "M has the row, S and L counted");
		assertEquals(Arrays.asList("000002"), codes(c -> c.setStockPointId(p2)), "ONE's row in P2 is inactive");
	}

	@Test
	@DisplayName("Has variants: 2 variants or more of the status (yes), one (no)")
	void variantsFilter() {
		assertEquals(Arrays.asList("70061"), codes(c -> c.setVariants("YES")));
		assertEquals(Arrays.asList("70061"), codes(c -> { c.setVariants("yes"); c.setActiveOnly(true); }));
		assertEquals(Arrays.asList("000001", "000002", "10", "0000016", "0000017", "A1", "B2", "ONE"),
				codes(c -> c.setVariants("NO")));
		assertEquals(8, page(c -> c.setVariants("NO"), 0, 3).getTotalElements());
	}

	@Test
	@DisplayName("Price with VAT: the column is the range of the status' variants; the filter keeps a line when one variant is in it")
	void prices() {
		ItemCatalogueLineDTO chemise = line(c -> c.setActiveOnly(true), "70061");
		assertEquals(11.9, chemise.getPriceMin(), 0.001);
		assertEquals(23.8, chemise.getPriceMax(), 0.001);
		assertEquals(35.7, line(c -> { }, "70061").getPriceMax(), 0.001, "status All: L counted");
		assertEquals(null, line(c -> { }, "0000016").getPriceMin());
		assertEquals(Arrays.asList("10", "70061"), codes(c -> { c.setPriceMin(20.0); c.setPriceMax(30.0); }));
		assertEquals(Arrays.asList("A1", "ONE"), codes(c -> c.setPriceMin(40.0)));
		assertEquals(Arrays.asList("000001", "0000017", "B2"), codes(c -> c.setPriceMax(8.0)));
		assertEquals(Arrays.asList("70061"), codes(c -> { c.setPriceMin(30.0); c.setPriceMax(36.0); c.setFamilyId(hom); }));
		assertTrue(codes(c -> { c.setPriceMin(30.0); c.setPriceMax(36.0); c.setActiveOnly(true); }).isEmpty(),
				"L (35.70) is inactive");
	}

	@Test
	@DisplayName("Status Inactive: only the lines without an active variant, every variant counted (70061, with L inactive, is not one)")
	void inactiveStatus() {
		assertTrue(codes(c -> c.setInactiveOnly(true)).isEmpty(), "every fixture line has an active variant");
		List<Long> added;
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			added = Arrays.asList(
					item(session, "ZZ~A", "ZZ", "ZULU", null, null, false, 2.0, 0, null, null, null).getId(),
					item(session, "ZZ~B", "ZZ", "ZULU", null, null, false, 3.0, 0, null, null, null).getId(),
					item(session, "Y9", null, "YANKEE", null, null, false, 4.0, 0, null, null, null).getId());
			session.getTransaction().commit();
		}
		try {
			assertEquals(Arrays.asList("Y9", "ZZ"), codes(c -> c.setInactiveOnly(true)));
			// Inactive wins over activeOnly: the inactive variants are read
			assertEquals(Arrays.asList("Y9", "ZZ"), codes(c -> { c.setActiveOnly(true); c.setInactiveOnly(true); }));
			assertEquals(2, line(c -> c.setInactiveOnly(true), "ZZ").getVariants());
			assertFalse(line(c -> c.setInactiveOnly(true), "ZZ").isActive());
			assertEquals(Arrays.asList("ZZ"), codes(c -> { c.setInactiveOnly(true); c.setVariants("YES"); }));
			assertEquals(Arrays.asList("Y9"), codes(c -> { c.setInactiveOnly(true); c.setLike("%yank%"); }));
			assertEquals(2, page(c -> c.setInactiveOnly(true), 0, 1).getTotalElements());
			assertFalse(codes(c -> c.setActiveOnly(true)).contains("ZZ"), "status Active: no line");
		} finally {
			try (Session session = sessions.openSession()) {
				session.beginTransaction();
				added.forEach(id -> session.delete(session.get(Item.class, id)));
				session.getTransaction().commit();
			}
		}
	}

	@Test
	@DisplayName("Search and filters together")
	void searchWithFilters() {
		assertEquals(Arrays.asList("70061"), codes(c -> { c.setLike("%chem%"); c.setFamilyId(hom); }));
		assertTrue(codes(c -> { c.setLike("%chem%"); c.setFamilyId(fem); }).isEmpty());
		assertEquals(Arrays.asList("ONE"), codes(c -> { c.setLike("%robe%"); c.setVariants("NO"); }));
	}

	@Test
	@DisplayName("The page's flags and filter choices, from the data")
	void filters() {
		ItemCatalogueFiltersDTO filters = withQuery(query -> new HoItemCatalogueService(null, null, null, null, null, null,
				null, null, null, query).filters());
		assertTrue(filters.isHasVariants());
		assertTrue(filters.isHasFamilies());
		assertTrue(filters.isHasDivision());
		assertTrue(filters.isHasCategory());
		assertTrue(filters.isHasSection());
		assertEquals(3, filters.getStockPointCount());
		assertFalse(filters.isHasBarcodes());
		assertEquals(Arrays.asList("FEM", "HOM"),
				filters.getFamilies().stream().map(ItemCatalogueFiltersDTO.Option::getCode).collect(Collectors.toList()));
		assertEquals(Arrays.asList("CHEM", "ROBE"),
				filters.getSubFamilies().stream().map(ItemCatalogueFiltersDTO.Option::getCode).collect(Collectors.toList()));
		assertEquals(Long.valueOf(hom), filters.getSubFamilies().get(0).getParentId());
		assertEquals("PAP", filters.getDivisions().get(0).getCode());
		assertEquals("Vêtements", filters.getCategories().get(0).getName());
		assertEquals("Manches", filters.getSections().get(0).getName());
		assertEquals(Arrays.asList("P1", "P2", "P3"),
				filters.getStockPoints().stream().map(ItemCatalogueFiltersDTO.Option::getCode).collect(Collectors.toList()));
	}
}
