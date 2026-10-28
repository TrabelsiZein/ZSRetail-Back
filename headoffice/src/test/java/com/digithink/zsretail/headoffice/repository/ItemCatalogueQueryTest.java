package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.service.ItemCatalogueService;

/**
 * Fiches items (3.1.0), step 1: the native queries of the head office page "Fiches items"
 * (ItemCatalogueQuery.build: the lines and their count, plain and searched; the flags and filter choices) and its point count by item
 * (ITEM_POINT_COUNTS), run as they are with Hibernate over an in-memory H2 database (no Spring context), with the table
 * and column names of the applications (Spring's naming strategies).
 * <p>
 * NAV item 70061: S (active, barcode 3700000000017), M (active), L (inactive). NAV item DEAD: two inactive variants.
 * Items without NAV number: 70061 (its code is 70061's NAV number), PACK-1 (legacy barcode LEG-555, rows in an active and
 * an inactive point), OLD-1 (inactive). Prices without VAT: S 20, M 10, L 5, DEAD~A 8, PACK-1 15; the others none.
 */
class ItemCatalogueQueryTest {

	private static SessionFactory sessions;
	private static long pack;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(ItemCategory.class).addAnnotatedClass(ItemSection.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:itemcatalogue;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily hom = new ItemFamily();
			hom.setCode("HOM");
			hom.setName("Homme");
			session.persist(hom);
			ItemSubFamily chem = new ItemSubFamily();
			chem.setCode("CHEM");
			chem.setName("Chemises");
			chem.setItemFamily(hom);
			session.persist(chem);
			Item s = item(session, "70061~S", "70061", "CHEMISE", hom, chem, true, null, "4");
			s.setUnitPrice(20.0);
			item(session, "70061~M", "70061", "CHEMISE", hom, chem, true, null, "2").setUnitPrice(10.0);
			item(session, "70061~L", "70061", "CHEMISE", hom, chem, false, null, "7").setUnitPrice(5.0);
			item(session, "DEAD~A", "DEAD", "ANCIEN", null, null, false, null, "1").setUnitPrice(8.0);
			item(session, "DEAD~B", "DEAD", "ANCIEN", null, null, false, null, "1");
			item(session, "70061", null, "CODE EGAL A UN NUMERO NAV", null, null, null, null, "5");
			Item packItem = item(session, "PACK-1", null, "Pack cadeau", hom, null, true, "LEG-555", null);
			packItem.setUnitPrice(15.0);
			pack = packItem.getId();
			item(session, "OLD-1", null, "Retired", null, null, false, null, "3");
			ItemBarcode barcode = new ItemBarcode();
			barcode.setBarcode("3700000000017");
			barcode.setItem(s);
			session.persist(barcode);
			HoStockPoint franchise = point(session, "FRANCHISE", true);
			HoStockPoint old = point(session, "OLD", false);
			row(session, franchise, pack);
			row(session, old, pack);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code, String navItemNo, String name, ItemFamily family,
			ItemSubFamily subFamily, Boolean active, String legacyBarcode, String stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(active);
		item.setBarcode(legacyBarcode);
		item.setStockQuantity(stock == null ? null : new BigDecimal(stock));
		item.setCategoryCode(navItemNo == null ? null : "VET");
		session.persist(item);
		return item;
	}

	private static HoStockPoint point(Session session, String code, boolean active) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName(code);
		point.setSortOrder(1);
		point.setActive(active);
		session.persist(point);
		return point;
	}

	private static void row(Session session, HoStockPoint point, long itemId) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(point.getId());
		row.setItemId(itemId);
		row.setName("row");
		row.setUnitPrice(10.0);
		row.setActive(true);
		session.persist(row);
	}

	/** The lines as the service builds them (with the stock). search: as typed, blank for none. */
	private static ItemCatalogueQuery.Criteria criteria(String search, boolean all) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setLike(NavItemService.likeOf(search));
		criteria.setActiveOnly(!all);
		return criteria;
	}

	private static List<ItemCatalogueLineDTO> lines(String search, boolean all, int first, int max) {
		ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria(search, all), true);
		try (Session session = sessions.openSession()) {
			NativeQuery<?> query = session.createNativeQuery(built.getLines());
			built.getParameters().forEach(query::setParameter);
			return query.setFirstResult(first).setMaxResults(max).list().stream()
					.map(row -> ItemCatalogueService.line((Object[]) row, true)).collect(Collectors.toList());
		}
	}

	private static List<ItemCatalogueLineDTO> lines(String search, boolean all) {
		return lines(search, all, 0, 50);
	}

	private static long count(String search, boolean all) {
		ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria(search, all), true);
		try (Session session = sessions.openSession()) {
			NativeQuery<?> query = session.createNativeQuery(built.getCount());
			built.getParameters().forEach(query::setParameter);
			return ((Number) query.uniqueResult()).longValue();
		}
	}

	private static List<String> codes(List<ItemCatalogueLineDTO> lines) {
		return lines.stream().map(line -> (line.isSingle() ? "item:" : "nav:") + line.getCode())
				.collect(Collectors.toList());
	}

	private static ItemCatalogueLineDTO nav(List<ItemCatalogueLineDTO> lines, String navItemNo) {
		return lines.stream().filter(line -> navItemNo.equals(line.getNavItemNo())).findFirst().orElse(null);
	}

	@Test
	@DisplayName("Status All: one line per NAV number, one per item without; by code then id; an item code equal to a NAV number is its own line")
	void allLines() {
		List<ItemCatalogueLineDTO> lines = lines(null, true);
		assertEquals(Arrays.asList("nav:70061", "item:70061", "nav:DEAD", "item:OLD-1", "item:PACK-1"), codes(lines));
		assertEquals(5, count(null, true));
		ItemCatalogueLineDTO chemise = lines.get(0);
		assertEquals(3, chemise.getVariants(), "S, M and L");
		assertTrue(chemise.isActive());
		assertFalse(chemise.isSingle());
		assertNull(chemise.getItemId(), "a NAV line's key is its NAV number only");
		assertEquals("CHEMISE", chemise.getName());
		assertEquals("Homme", chemise.getFamilyName());
		assertEquals("Chemises", chemise.getSubFamilyName());
		assertEquals("VET", chemise.getCategoryCode());
		assertEquals(0, new BigDecimal("13").compareTo(chemise.getStockQuantity()), "4 + 2 + 7");
		ItemCatalogueLineDTO sameCode = lines.get(1);
		assertTrue(sameCode.isSingle());
		assertNull(sameCode.getNavItemNo());
		assertEquals(1, sameCode.getVariants());
		assertEquals("CODE EGAL A UN NUMERO NAV", sameCode.getName());
		assertTrue(sameCode.isActive(), "a null active counts as active");
		ItemCatalogueLineDTO dead = lines.get(2);
		assertEquals(2, dead.getVariants());
		assertFalse(dead.isActive(), "every variant inactive: an inactive line");
		assertFalse(lines.get(3).isActive());
		ItemCatalogueLineDTO pack = lines.get(4);
		assertEquals(Long.valueOf(ItemCatalogueQueryTest.pack), pack.getItemId());
		assertEquals("Homme", pack.getFamilyName());
		assertNull(pack.getSubFamilyName());
	}

	@Test
	@DisplayName("Status Active: the active variants only; a NAV item whose variants are all inactive and an inactive item have no line")
	void activeLines() {
		List<ItemCatalogueLineDTO> lines = lines(null, false);
		assertEquals(Arrays.asList("nav:70061", "item:70061", "item:PACK-1"), codes(lines));
		assertEquals(3, count(null, false));
		assertEquals(2, lines.get(0).getVariants(), "S and M; L inactive");
		assertEquals(0, new BigDecimal("6").compareTo(lines.get(0).getStockQuantity()));
	}

	/** The line codes of a sort (status All unless activeOnly). */
	private static List<String> sorted(String sort, boolean descending, boolean activeOnly) {
		ItemCatalogueQuery.Criteria criteria = criteria(null, !activeOnly);
		criteria.setSort(sort);
		criteria.setDescending(descending);
		ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria, true);
		try (Session session = sessions.openSession()) {
			NativeQuery<?> query = session.createNativeQuery(built.getLines());
			built.getParameters().forEach(query::setParameter);
			return codes(query.list().stream().map(row -> ItemCatalogueService.line((Object[]) row, true))
					.collect(Collectors.toList()));
		}
	}

	@Test
	@DisplayName("Variants sort: the line's variant count, then by code")
	void variantsSort() {
		assertEquals(Arrays.asList("item:70061", "item:OLD-1", "item:PACK-1", "nav:DEAD", "nav:70061"),
				sorted("variants", false, false));
		assertEquals(Arrays.asList("nav:70061", "nav:DEAD", "item:70061", "item:OLD-1", "item:PACK-1"),
				sorted("variants", true, false), "descending, the ties still by code ascending");
	}

	@Test
	@DisplayName("Price sort: the line's smallest price of the status, then by code; lines without a price last both ways")
	void priceSort() {
		assertEquals(Arrays.asList("nav:70061", "nav:DEAD", "item:PACK-1", "item:70061", "item:OLD-1"),
				sorted("price", false, false), "70061 from 5 (L), DEAD 8, PACK-1 15, then no price");
		assertEquals(Arrays.asList("item:PACK-1", "nav:DEAD", "nav:70061", "item:70061", "item:OLD-1"),
				sorted("price", true, false));
		assertEquals(Arrays.asList("nav:70061", "item:PACK-1", "item:70061"), sorted("price", false, true),
				"Active: 70061 from 10 (L inactive)");
	}

	@Test
	@DisplayName("Paging: pages of two by code then id, the count of every line")
	void paging() {
		assertEquals(Arrays.asList("nav:70061", "item:70061"), codes(lines(null, true, 0, 2)));
		assertEquals(Arrays.asList("nav:DEAD", "item:OLD-1"), codes(lines(null, true, 2, 2)));
		assertEquals(Arrays.asList("item:PACK-1"), codes(lines(null, true, 4, 2)));
		assertEquals(5, count(null, true));
	}

	@Test
	@DisplayName("Barcode search, exact and contains: the NAV line with every variant of the status counted, not only the matching one")
	void barcodeSearch() {
		List<ItemCatalogueLineDTO> exact = lines("3700000000017", false);
		assertEquals(Arrays.asList("nav:70061"), codes(exact));
		assertEquals(2, exact.get(0).getVariants(), "S matched, S and M counted");
		assertEquals(1, count("3700000000017", false));
		List<ItemCatalogueLineDTO> contains = lines(" 0000001 ", true);
		assertEquals(Arrays.asList("nav:70061"), codes(contains));
		assertEquals(3, contains.get(0).getVariants());
		assertEquals(Arrays.asList("item:PACK-1"), codes(lines("leg-5", false)), "legacy item.barcode, any case");
		assertEquals(Arrays.asList("item:PACK-1"), codes(lines("LEG-555", false)));
		assertEquals(0, count("9999999", true));
	}

	@Test
	@DisplayName("Search on NAV number, item code and name; contains, any case; the status applies to the matching variant")
	void search() {
		assertEquals(Arrays.asList("nav:70061", "item:70061"), codes(lines("70061", true)));
		assertEquals(2, count("70061", true));
		assertEquals(Arrays.asList("nav:70061"), codes(lines("061~m", false)), "an item code of a variant");
		assertEquals(Arrays.asList("item:PACK-1"), codes(lines("cadeau", false)), "a name");
		assertEquals(Arrays.asList("nav:DEAD"), codes(lines("dead~a", true)));
		assertEquals(0, lines("dead~a", false).size(), "an inactive variant matches nothing with the status Active");
		assertEquals(0, lines("70061~l", false).size(), "70061~L inactive: no line with the status Active");
		assertEquals(3, lines("70061~l", true).get(0).getVariants());
	}

	/** The result column labels of a statement without parameters. */
	static List<String> columnLabels(SessionFactory sessions, String sql) {
		return columnLabels(sessions, sql, java.util.Collections.emptyMap());
	}

	/**
	 * The result column labels of a native query, run over JDBC (its named parameters bound by name, in order of
	 * appearance). Lower case: H2 gives the aliases in upper case.
	 */
	static List<String> columnLabels(SessionFactory sessions, String sql, Map<String, Object> parameters) {
		List<Object> values = new ArrayList<>();
		Matcher parameter = Pattern.compile(":([A-Za-z]+)\\b").matcher(sql);
		StringBuffer jdbc = new StringBuffer();
		while (parameter.find()) {
			values.add(parameters.get(parameter.group(1)));
			parameter.appendReplacement(jdbc, "?");
		}
		parameter.appendTail(jdbc);
		try (Session session = sessions.openSession()) {
			return session.doReturningWork(connection -> {
				try (PreparedStatement statement = connection.prepareStatement(jdbc.toString())) {
					for (int i = 0; i < values.size(); i++) {
						statement.setObject(i + 1, values.get(i));
					}
					try (ResultSet result = statement.executeQuery()) {
						ResultSetMetaData meta = result.getMetaData();
						List<String> labels = new ArrayList<>();
						for (int i = 1; i <= meta.getColumnCount(); i++) {
							labels.add(meta.getColumnLabel(i) == null ? "" : meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
						}
						return labels;
					}
				}
			});
		}
	}

	@Test
	@DisplayName("Every column of the native queries has its own alias: SQL Server names an aggregate without one \"\"")
	void columnAliases() {
		ItemCatalogueQuery.Criteria every = criteria("a", true);
		every.setFamilyId(1L);
		every.setSubFamilyId(1L);
		every.setDivisionCode("D");
		every.setCategoryCode("C");
		every.setSectionCode("S");
		HoItemCatalogueQuery.stockPoint(every, 1L);
		every.setVariants("YES");
		every.setPriceMin(1.0);
		every.setPriceMax(9.0);
		every.setSort("family");
		for (ItemCatalogueQuery.Criteria criteria : Arrays.asList(criteria(null, true), criteria("a", false), every)) {
			ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria, true);
			assertEquals(ItemCatalogueQuery.LINE_COLUMNS, columnLabels(sessions, built.getLines(), built.getParameters()));
			assertEquals(Arrays.asList(ItemCatalogueQuery.COUNT_COLUMN),
					columnLabels(sessions, built.getCount(), built.getParameters()));
		}
		assertEquals(Arrays.asList("has_variants", "has_families", "has_division", "has_category", "has_section",
				"has_barcodes"), columnLabels(sessions, ItemCatalogueQuery.FLAGS));
		assertEquals(Arrays.asList("stock_point_count"), columnLabels(sessions, HoItemCatalogueQuery.STOCK_POINT_COUNT));
		assertEquals(Arrays.asList("id", "code", "name", "category_code"), columnLabels(sessions, ItemCatalogueQuery.FAMILIES));
		assertEquals(Arrays.asList("id", "code", "name", "family_id"), columnLabels(sessions, ItemCatalogueQuery.SUB_FAMILIES));
		assertEquals(Arrays.asList("code"), columnLabels(sessions, ItemCatalogueQuery.DIVISIONS));
		assertEquals(Arrays.asList("code", "name"), columnLabels(sessions, ItemCatalogueQuery.CATEGORIES));
		assertEquals(Arrays.asList("code", "name", "sub_family_code"), columnLabels(sessions, ItemCatalogueQuery.SECTIONS));
		assertEquals(Arrays.asList("id", "code", "name"), columnLabels(sessions, HoItemCatalogueQuery.STOCK_POINTS));
		List<String> columns = ItemCatalogueQuery.LINE_COLUMNS;
		assertEquals(columns.size(), new HashSet<>(columns).size(), "unique aliases");
		columns.forEach(column -> assertFalse(column.trim().isEmpty(), "no empty alias"));
		assertEquals("nav_item_no", columns.get(ItemCatalogueQuery.COL_NAV_ITEM_NO));
		assertEquals("item_id", columns.get(ItemCatalogueQuery.COL_ITEM_ID));
		assertEquals("item_code", columns.get(ItemCatalogueQuery.COL_ITEM_CODE));
		assertEquals("item_name", columns.get(ItemCatalogueQuery.COL_ITEM_NAME));
		assertEquals("family_name", columns.get(ItemCatalogueQuery.COL_FAMILY_NAME));
		assertEquals("sub_family_name", columns.get(ItemCatalogueQuery.COL_SUB_FAMILY_NAME));
		assertEquals("category_code", columns.get(ItemCatalogueQuery.COL_CATEGORY_CODE));
		assertEquals("section_code", columns.get(ItemCatalogueQuery.COL_SECTION_CODE));
		assertEquals("variant_count", columns.get(ItemCatalogueQuery.COL_VARIANT_COUNT));
		assertEquals("any_active", columns.get(ItemCatalogueQuery.COL_ANY_ACTIVE));
		assertEquals("stock_quantity", columns.get(ItemCatalogueQuery.COL_STOCK_QUANTITY));
		assertEquals("price_min", columns.get(ItemCatalogueQuery.COL_PRICE_MIN));
		assertEquals("price_max", columns.get(ItemCatalogueQuery.COL_PRICE_MAX));
	}

	@Test
	@DisplayName("Flags per field: a catégorie without any division or section (ZEN's shape) gives has_category only")
	void flagsPerField() {
		try (Session session = sessions.openSession()) {
			Object[] flags = (Object[]) session.createNativeQuery(ItemCatalogueQuery.FLAGS).uniqueResult();
			List<Integer> values = Arrays.stream(flags).map(value -> ((Number) value).intValue()).collect(Collectors.toList());
			// [has_variants, has_families, has_division, has_category, has_section, has_barcodes]
			assertEquals(Arrays.asList(1, 1, 0, 1, 0, 1), values);
			assertEquals(2, ((Number) session.createNativeQuery(HoItemCatalogueQuery.STOCK_POINT_COUNT).uniqueResult())
					.intValue(), "the head office counts its points de stock apart");
		}
	}

	@Test
	@DisplayName("Point counts by item: the active points where the item has an active row")
	void itemPointCounts() {
		Map<Long, Long> counts = pointCounts(Arrays.asList(pack, -1L));
		assertEquals(1, counts.size());
		assertEquals(Long.valueOf(1), counts.get(pack), "FRANCHISE; OLD is inactive");
	}

	private static Map<Long, Long> pointCounts(Collection<Long> itemIds) {
		try (Session session = sessions.openSession()) {
			Map<Long, Long> counts = new TreeMap<>();
			for (Object[] row : session.createQuery(HoItemCatalogueRepository.ITEM_POINT_COUNTS, Object[].class)
					.setParameterList("itemIds", itemIds).list()) {
				counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
			}
			return counts;
		}
	}
}
