package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.digithink.zsretail.core.dto.report.StockReportFilter;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemCostHistory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;

/**
 * 3.1.2, the stock value at a date: ReportService.getStockReport with a past date, its queries run as they are with
 * Hibernate over an in-memory H2 database (every entity of core, Spring's naming strategy), and today's report on the
 * same data.
 * <ul>
 * <li>qty(D) = the stock now minus the movements after D (a movement at midnight after D counts after, one at 23:59 of D
 * before);</li>
 * <li>CMP(D): the new cost of the last change up to D (Alpha), else the old cost of the first change after D (Bravo; a
 * first cost after D gives none, Nouveau), else the CMP now (Charlie, counted at its current CMP);</li>
 * <li>an item without cost (Echo) has no value and is counted; a stock of 0 (Zulu, Nouveau) is listed, worth 0;</li>
 * <li>the families: the same labels and order as today's report, the sums of their items;</li>
 * <li>today: the same rows, labels and order as before, except an item without cost: no value (not 0), counted.</li>
 * <li>the rebuilt report (3.1.2): the values with VAT and the selling values (rounded once per row), the CATEGORY and
 * SECTION groupings, the filters in both paths (the status on the item's own qty at the date, before the grouping).</li>
 * </ul>
 */
class StockValueAtDateTest {

	private static final LocalDate D = LocalDate.now().minusDays(10);
	private static final LocalDateTime END = D.plusDays(1).atStartOfDay();

	private static SessionFactory sessions;
	private static Session session;
	private static ReportService reports;
	private static ItemFamily soins;

	@BeforeAll
	static void database() throws Exception {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition entity : scan.findCandidateComponents("com.digithink.zsretail.core.model")) {
			configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
		}
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockvalue;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();
		ItemFamily parfums = family("F1", "Parfums");
		soins = family("F2", "Soins");
		ItemCategory femme = new ItemCategory();
		femme.setCode("CAT1");
		femme.setName("Femme");
		session.persist(femme);
		ItemSection toilette = new ItemSection();
		toilette.setCode("S1");
		toilette.setName("Eau de toilette");
		session.persist(toilette);

		Item alpha = item("A", "Alpha", parfums, "10", 8.0, 10);
		sells(alpha, "CAT1", "S1", 12.0, 19);
		cost(alpha, D.minusDays(5).atTime(9, 0), 5.0, 6.0);
		cost(alpha, D.plusDays(3).atTime(9, 0), 6.0, 8.0);
		move(alpha, StockMovementDirection.IN, "1", D.atTime(23, 59)); // on D: in the stock of D
		move(alpha, StockMovementDirection.IN, "4", D.plusDays(3).atTime(9, 0));
		move(alpha, StockMovementDirection.OUT, "1", D.plusDays(5).atTime(9, 0));

		Item bravo = item("B", "Bravo", parfums, "5", 4.0, 0);
		sells(bravo, "CAT1", null, 6.0, null); // no VAT: 0, as at the till
		cost(bravo, D.plusDays(2).atTime(9, 0), 3.0, 4.0);
		move(bravo, StockMovementDirection.OUT, "2", END); // midnight after D: after

		sells(item("C", "Charlie", soins, "2", 9.0, 0), "CAT9", null, 18.90756, 19); // a code without its category row
		item("E", "Echo", null, "3", null, 0); // no cost, no price
		item("Z", "Zulu", soins, "0", null, 0);

		Item nouveau = item("N", "Nouveau", soins, "4", 5.0, 0);
		sells(nouveau, "CAT1", null, 10.0, 19);
		cost(nouveau, D.plusDays(2).atTime(9, 0), null, 5.0);
		move(nouveau, StockMovementDirection.IN, "4", D.plusDays(2).atTime(9, 0));

		Item off = item("X", "Inactive", soins, "7", 1.0, 0);
		off.setActive(false);
		session.getTransaction().commit();

		reports = new ReportService();
		Field em = ReportService.class.getDeclaredField("em");
		em.setAccessible(true);
		em.set(reports, session);
	}

	@AfterAll
	static void close() {
		session.close();
		sessions.close();
	}

	private static ItemFamily family(String code, String name) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(name);
		session.persist(family);
		return family;
	}

	private static Item item(String code, String name, ItemFamily family, String stock, Double cost, int min) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setType(ItemType.PRODUCT);
		item.setItemFamily(family);
		item.setStockQuantity(new BigDecimal(stock));
		item.setCostPrice(cost);
		item.setMinStockLevel(min);
		session.persist(item);
		return item;
	}

	private static void sells(Item item, String categoryCode, String sectionCode, Double price, Integer vat) {
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
		item.setUnitPrice(price);
		item.setDefaultVAT(vat);
	}

	private static void cost(Item item, LocalDateTime at, Double oldCost, double newCost) {
		ItemCostHistory row = new ItemCostHistory();
		row.setItemId(item.getId());
		row.setChangedAt(at);
		row.setOldCost(oldCost);
		row.setNewCost(newCost);
		row.setSource(CostChangeSource.LOCAL_PURCHASE);
		session.persist(row);
	}

	private static void move(Item item, StockMovementDirection direction, String quantity, LocalDateTime at) {
		StockMovement movement = new StockMovement();
		movement.setItem(item);
		movement.setDirection(direction);
		movement.setMovementType(direction == StockMovementDirection.IN ? StockMovementType.PURCHASE_RECEPTION
				: StockMovementType.SALE);
		movement.setQuantity(new BigDecimal(quantity));
		movement.setCreatedAt(at);
		session.persist(movement);
	}

	private static Map<String, StockReportRowDTO> byLabel(List<StockReportRowDTO> rows) {
		return rows.stream().collect(Collectors.toMap(StockReportRowDTO::getGroupLabel, Function.identity()));
	}

	private static String qty(StockReportRowDTO row) {
		return row.getCurrentQty().toPlainString();
	}

	@Test
	@DisplayName("By item at D: qty(D) and CMP(D) by the three rules, the item without cost, the stocks of 0 listed")
	void itemsAtDate() {
		List<StockReportRowDTO> rows = reports.getStockReport("ITEM", false, D);
		assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie", "Echo", "Nouveau", "Zulu"),
				rows.stream().map(StockReportRowDTO::getGroupLabel).collect(Collectors.toList()), "active items, by name");
		Map<String, StockReportRowDTO> row = byLabel(rows);

		assertEquals("7", qty(row.get("Alpha")), "10 - (4 - 1); the entry at 23:59 of D stays");
		assertEquals(42.0, row.get("Alpha").getStockValue(), 1e-9, "7 x 6: the last change up to D");
		assertNull(row.get("Alpha").getItemsAtCurrentCmp());
		assertEquals("LOW", row.get("Alpha").getStatus(), "7 below the minimum 10");

		assertEquals("7", qty(row.get("Bravo")), "5 + 2: the exit at midnight after D counts after");
		assertEquals(21.0, row.get("Bravo").getStockValue(), 1e-9, "7 x 3: the old cost of the first change after D");

		assertEquals("2", qty(row.get("Charlie")));
		assertEquals(18.0, row.get("Charlie").getStockValue(), 1e-9, "no history: its CMP now");
		assertEquals(Long.valueOf(1), row.get("Charlie").getItemsAtCurrentCmp());

		assertNull(row.get("Echo").getStockValue(), "no cost: no value, never 0");
		assertEquals(Long.valueOf(1), row.get("Echo").getItemsWithoutCost());

		assertEquals("0", qty(row.get("Nouveau")), "its opening stock came after D");
		assertEquals(0.0, row.get("Nouveau").getStockValue(), "a stock of 0 is worth 0 (its first cost came after D)");
		assertNull(row.get("Nouveau").getItemsWithoutCost());
		assertEquals("OUT", row.get("Zulu").getStatus());
		assertEquals(0.0, row.get("Zulu").getStockValue());
	}

	@Test
	@DisplayName("By family at D: the labels and order of today's report, the sums of their items and counts")
	void familiesAtDate() {
		List<StockReportRowDTO> rows = reports.getStockReport("FAMILY", false, D);
		assertEquals(Arrays.asList("No Family", "Parfums", "Soins"),
				rows.stream().map(StockReportRowDTO::getGroupLabel).collect(Collectors.toList()));
		Map<String, StockReportRowDTO> row = byLabel(rows);
		assertEquals("14", qty(row.get("Parfums")));
		assertEquals(63.0, row.get("Parfums").getStockValue(), 1e-9);
		assertEquals(Long.valueOf(10), row.get("Parfums").getMinStockLevel());
		assertEquals("2", qty(row.get("Soins")));
		assertEquals(18.0, row.get("Soins").getStockValue(), 1e-9);
		assertEquals(Long.valueOf(1), row.get("Soins").getItemsAtCurrentCmp());
		assertEquals(0.0, row.get("No Family").getStockValue(), "the sum of its items with a cost: none");
		assertEquals(Long.valueOf(1), row.get("No Family").getItemsWithoutCost());
		assertNull(row.get("No Family").getItemCode());
	}

	@Test
	@DisplayName("Below the minimum at D: on qty(D), the statuses LOW, OUT, NEGATIVE (3.1.2: OUT with no minimum too)")
	void belowMinimumAtDate() {
		assertEquals(Arrays.asList("Alpha", "Nouveau", "Zulu"), labels(reports.getStockReport("ITEM", true, D)),
				"Alpha 7 below 10; Nouveau 0 at D and Zulu 0: out of stock");
	}

	@Test
	@DisplayName("By item at D: the CMP of D and its value with VAT, the selling value at today's price")
	void itemValuesAtDate() {
		Map<String, StockReportRowDTO> row = byLabel(reports.getStockReport("ITEM", false, D));
		StockReportRowDTO alpha = row.get("Alpha");
		assertEquals(6.0, alpha.getCostPrice(), "the CMP of D, not today's 8");
		assertEquals(7.14, alpha.getCostPriceTtc(), 1e-9, "6 x 1.19");
		assertEquals(49.98, alpha.getStockValueTtc(), 1e-9, "42 x 1.19");
		assertEquals(84.0, alpha.getSellingValue(), 1e-9, "7 x 12, today's price");
		assertEquals(99.96, alpha.getSellingValueTtc(), 1e-9);
		assertEquals(9.0, row.get("Charlie").getCostPrice(), "no history: its CMP now");
		assertNull(row.get("Echo").getCostPrice());
		assertNull(row.get("Echo").getStockValueTtc(), "no cost: neither value");
	}

	@Test
	@DisplayName("By category at D: the name, else the code, else No Category; the group's four values summed")
	void categoriesAtDate() {
		List<StockReportRowDTO> rows = reports.getStockReport("CATEGORY", new StockReportFilter(), D);
		assertEquals(Arrays.asList("No Category", "CAT9", "Femme"), labels(rows), "by name; CAT9 has no category row");
		Map<String, StockReportRowDTO> row = byLabel(rows);
		StockReportRowDTO femme = row.get("Femme");
		assertNull(femme.getItemCode());
		assertNull(femme.getCostPrice(), "no price on a group row");
		assertNull(femme.getUnitPrice());
		assertEquals("14", qty(femme), "Alpha 7 + Bravo 7 + Nouveau 0");
		assertEquals(63.0, femme.getStockValue(), 1e-9, "42 + 21");
		assertEquals(70.98, femme.getStockValueTtc(), 1e-9, "42 x 1.19 + 21 (Bravo: no VAT)");
		assertEquals(126.0, femme.getSellingValue(), 1e-9, "7 x 12 + 7 x 6");
		assertEquals(141.96, femme.getSellingValueTtc(), 1e-9, "84 x 1.19 + 42");
		assertEquals(37.815, row.get("CAT9").getSellingValue(), 1e-9, "2 x 18.90756 = 37.81512, 3 decimals");
		assertEquals(Long.valueOf(1), row.get("CAT9").getItemsAtCurrentCmp());
		StockReportRowDTO none = row.get("No Category");
		assertEquals("3", qty(none), "Echo 3 + Zulu 0");
		assertEquals(0.0, none.getSellingValue(), "the sum of its items with a price: none");
		assertEquals(Long.valueOf(1), none.getItemsWithoutPrice(), "Echo");
		assertEquals(Long.valueOf(1), none.getItemsWithoutCost());
	}

	@Test
	@DisplayName("Filters at D: the code and search in the items query, the status on qty(D) before the grouping")
	void filtersAtDate() {
		StockReportFilter femmeWithO = new StockReportFilter();
		femmeWithO.setCategoryCode("CAT1");
		femmeWithO.setSearch("O");
		assertEquals(Arrays.asList("Bravo", "Nouveau"), labels(reports.getStockReport("ITEM", femmeWithO, D)),
				"Femme, an o in the name, case ignored");

		StockReportFilter low = status("LOW");
		List<StockReportRowDTO> families = reports.getStockReport("FAMILY", low, D);
		assertEquals(Arrays.asList("Parfums"), labels(families), "Alpha only: 7 below 10 at D");
		assertEquals("7", qty(families.get(0)), "Bravo (OK) left out of its family's sums");
		assertEquals(42.0, families.get(0).getStockValue(), 1e-9);

		assertEquals(Arrays.asList("Echo"), labels(reports.getStockReport("ITEM", status("WITHOUT_COST"), D)),
				"no CMP at D and a stock: Echo; Nouveau has none at D but a stock of 0");
	}

	@Test
	@DisplayName("Filters now: search (wildcards as themselves), family, section, status; the SECTION grouping")
	void filtersToday() {
		StockReportFilter search = new StockReportFilter();
		search.setSearch("HA");
		assertEquals(Arrays.asList("Alpha", "Charlie"), labels(reports.getStockReport("ITEM", search, null)));
		search.setSearch("a%");
		assertEquals(Arrays.asList(), labels(reports.getStockReport("ITEM", search, null)), "a typed % is a %");

		StockReportFilter family = new StockReportFilter();
		family.setFamilyId(soins.getId());
		assertEquals(Arrays.asList("Charlie", "Nouveau", "Zulu"), labels(reports.getStockReport("ITEM", family, null)));

		assertEquals(Arrays.asList("No Section", "Eau de toilette"),
				labels(reports.getStockReport("SECTION", new StockReportFilter(), null)));
		StockReportFilter section = new StockReportFilter();
		section.setSectionCode("S1");
		List<StockReportRowDTO> sections = reports.getStockReport("SECTION", section, null);
		assertEquals(Arrays.asList("Eau de toilette"), labels(sections));
		assertEquals(120.0, sections.get(0).getSellingValue(), 1e-9, "10 x 12");

		assertEquals(Arrays.asList("Echo"), labels(reports.getStockReport("ITEM", status("WITHOUT_COST"), null)));
		assertEquals(Arrays.asList("Zulu"), labels(reports.getStockReport("ITEM", true)),
				"below the minimum now: Zulu out of stock (3.1.2); Alpha 10 is not below 10");
		assertEquals(Arrays.asList("Echo", "Zulu"), labels(reports.getStockReport("ITEM", status("OUT", "WITHOUT_COST"), null)),
				"any of the statuses");
	}

	@Test
	@DisplayName("Counts and names at D: a row's items, low and out (out or negative) by their own status; an item's family")
	void countsAndNamesAtDate() {
		Map<String, StockReportRowDTO> family = byLabel(reports.getStockReport("FAMILY", false, D));
		StockReportRowDTO parfums = family.get("Parfums");
		assertEquals(2, parfums.getItemCount());
		assertEquals(1, parfums.getItemsLow(), "Alpha 7 below 10 at D");
		assertEquals(0, parfums.getItemsOut());
		StockReportRowDTO soins = family.get("Soins");
		assertEquals(3, soins.getItemCount());
		assertEquals(0, soins.getItemsLow());
		assertEquals(2, soins.getItemsOut(), "Nouveau 0 at D and Zulu 0");
		assertNull(soins.getFamilyName(), "no names on a group row");

		Map<String, StockReportRowDTO> item = byLabel(reports.getStockReport("ITEM", false, D));
		assertEquals("Parfums", item.get("Alpha").getFamilyName());
		assertNull(item.get("Alpha").getSubFamilyName());
		assertEquals(1, item.get("Alpha").getItemCount());
		assertEquals(1, item.get("Alpha").getItemsLow());
		assertEquals(1, item.get("Zulu").getItemsOut());
		assertNull(item.get("Echo").getFamilyName());
	}

	private static StockReportFilter status(String... statuses) {
		StockReportFilter filter = new StockReportFilter();
		filter.getStatuses().addAll(Arrays.asList(statuses));
		return filter;
	}

	private static List<String> labels(List<StockReportRowDTO> rows) {
		return rows.stream().map(StockReportRowDTO::getGroupLabel).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Today (no date, or today's date): the stock now; an item without cost has no value and is counted")
	void today() {
		for (List<StockReportRowDTO> rows : Arrays.asList(reports.getStockReport("ITEM", false),
				reports.getStockReport("ITEM", false, null), reports.getStockReport("ITEM", false, LocalDate.now()))) {
			Map<String, StockReportRowDTO> row = byLabel(rows);
			assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie", "Echo", "Nouveau", "Zulu"), labels(rows),
					"the active items, by name, as before 3.1.2");
			assertEquals("10", qty(row.get("Alpha")));
			assertEquals("OK", row.get("Alpha").getStatus(), "10, the minimum 10");
			assertEquals("OUT", row.get("Zulu").getStatus());
			assertEquals(80.0, row.get("Alpha").getStockValue(), 1e-9);
			assertNull(row.get("Alpha").getItemsWithoutCost());
			assertNull(row.get("Echo").getStockValue(), "no cost: no value, never 0");
			assertEquals(Long.valueOf(1), row.get("Echo").getItemsWithoutCost());
			assertEquals(0.0, row.get("Zulu").getStockValue(), "no cost and a stock of 0: 0");
			assertNull(row.get("Zulu").getItemsWithoutCost());
			assertNull(row.get("Charlie").getItemsAtCurrentCmp(), "today: no such note");

			StockReportRowDTO alpha = row.get("Alpha");
			assertEquals(8.0, alpha.getCostPrice());
			assertEquals(9.52, alpha.getCostPriceTtc(), 1e-9, "8 x 1.19");
			assertEquals(12.0, alpha.getUnitPrice());
			assertEquals(14.28, alpha.getUnitPriceTtc(), 1e-9, "12 x 1.19");
			assertEquals(95.2, alpha.getStockValueTtc(), 1e-9);
			assertEquals(120.0, alpha.getSellingValue(), 1e-9);
			assertEquals(142.8, alpha.getSellingValueTtc(), 1e-9);
			assertEquals(6.0, row.get("Bravo").getUnitPriceTtc(), 1e-9, "no VAT: 0, as at the till");
			assertEquals(22.5, row.get("Charlie").getUnitPriceTtc(), 1e-9, "18.90756 x 1.19 = 22.4999964, 3 decimals");
			assertEquals(45.0, row.get("Charlie").getSellingValueTtc(), 1e-9, "2 x 22.4999964 = 44.9999928, rounded once");
			assertNull(row.get("Echo").getSellingValue(), "no price: no selling value, never 0");
			assertNull(row.get("Echo").getSellingValueTtc());
			assertEquals(Long.valueOf(1), row.get("Echo").getItemsWithoutPrice());
			assertEquals(0.0, row.get("Zulu").getSellingValue(), "no price and a stock of 0: 0");
			assertNull(row.get("Zulu").getItemsWithoutPrice());
		}
		List<StockReportRowDTO> familyRows = reports.getStockReport("FAMILY", false);
		assertEquals(Arrays.asList("No Family", "Parfums", "Soins"), labels(familyRows), "as before 3.1.2");
		Map<String, StockReportRowDTO> families = byLabel(familyRows);
		assertEquals(Long.valueOf(1), families.get("No Family").getItemsWithoutCost());
		assertEquals(0.0, families.get("No Family").getStockValue());
		assertNull(families.get("Parfums").getItemsWithoutCost());
		assertEquals(100.0, families.get("Parfums").getStockValue(), 1e-9, "10 x 8 + 5 x 4");
		assertEquals("15", qty(families.get("Parfums")));
		assertEquals(Long.valueOf(10), families.get("Parfums").getMinStockLevel());
		assertEquals(38.0, families.get("Soins").getStockValue(), 1e-9, "2 x 9 + 4 x 5 + 0");
		assertEquals(150.0, families.get("Parfums").getSellingValue(), 1e-9, "10 x 12 + 5 x 6: a group row's selling value");
		assertEquals(172.8, families.get("Parfums").getSellingValueTtc(), 1e-9, "142.8 + 30");
	}
}
