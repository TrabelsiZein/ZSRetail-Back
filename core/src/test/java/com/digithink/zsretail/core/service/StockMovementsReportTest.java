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
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.digithink.zsretail.core.dto.report.StockCardDTO;
import com.digithink.zsretail.core.dto.report.StockCardLineDTO;
import com.digithink.zsretail.core.dto.report.StockMovementReportFilter;
import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;

/**
 * 3.1.2, the stock movements report rebuilt: ReportService.getStockMovementsReport, its one query run as it is with
 * Hibernate over an in-memory H2 database (every entity of core, Spring's naming strategy).
 * <ul>
 * <li>the period's movements only, COST_ADJUSTMENT never (a quantity of 0 that moves no stock);</li>
 * <li>an item row: its name as label, its code and family apart; the groups' distinct items;</li>
 * <li>the groupings FAMILY, CATEGORY (name, else "No Category"), SECTION, TYPE, DAY / MONTH zero-padded in time order;</li>
 * <li>the filters: the types (any of them), the family, the category, the search; the one-type call of before.</li>
 * </ul>
 */
class StockMovementsReportTest {

	private static final LocalDate D = LocalDate.now().minusDays(10);

	private static SessionFactory sessions;
	private static Session session;
	private static ReportService reports;
	private static ItemFamily parfums;
	private static Item alpha;
	private static Item bravo;
	private static ItemSubFamily eaux;

	@BeforeAll
	static void database() throws Exception {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition entity : scan.findCandidateComponents("com.digithink.zsretail.core.model")) {
			configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
		}
		configuration.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockmovements;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "com.digithink.zsretail.core.support.H2SqlServerModeDialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();
		parfums = family("F1", "Parfums");
		ItemFamily soins = family("F2", "Soins");
		ItemCategory femme = new ItemCategory();
		femme.setCode("CAT1");
		femme.setName("Femme");
		session.persist(femme);
		ItemSection toilette = new ItemSection();
		toilette.setCode("S1");
		toilette.setName("Eau de toilette");
		session.persist(toilette);

		eaux = new ItemSubFamily();
		eaux.setCode("SF1");
		eaux.setName("Eaux");
		eaux.setItemFamily(parfums);
		session.persist(eaux);
		alpha = item("A", "Alpha", parfums, "CAT1", "S1");
		alpha.setItemSubFamily(eaux);
		alpha.setStockQuantity(new BigDecimal("20")); // its stock now: 20 (2 came in after the period)
		bravo = item("B", "Bravo", parfums, "CAT1", null);
		Item charlie = item("C", "Charlie", soins, null, null);

		// The stock card's references: a ticket of a cashier's session, an inventory count's number in the note
		UserAccount cashier = new UserAccount();
		cashier.setUsername("cashier1");
		cashier.setPassword("-");
		cashier.setFullName("Caissier Un");
		session.persist(cashier);
		CashierSession till = new CashierSession();
		till.setSessionNumber("S-1");
		till.setCashier(cashier);
		till.setOpenedAt(D.atTime(8, 0));
		till.setOpeningCash(0.0);
		session.persist(till);
		SalesHeader ticket = new SalesHeader();
		ticket.setSalesNumber("T-0042");
		session.persist(ticket);

		move(alpha, StockMovementType.PURCHASE_RECEPTION, StockMovementDirection.IN, "10", D.atTime(9, 0));
		StockMovement sale = move(alpha, StockMovementType.SALE, StockMovementDirection.OUT, "3", D.atTime(15, 0));
		sale.setReferenceType("SALE");
		sale.setReferenceId(ticket.getId());
		sale.setCashierSession(till);
		move(alpha, StockMovementType.COST_ADJUSTMENT, StockMovementDirection.IN, "0", D.atTime(16, 0));
		move(alpha, StockMovementType.SALE, StockMovementDirection.OUT, "1.5", D.plusDays(1).atTime(10, 0));
		move(alpha, StockMovementType.PURCHASE_RECEPTION, StockMovementDirection.IN, "2", D.plusDays(5).atTime(9, 0));
		StockMovement count = move(bravo, StockMovementType.INVENTORY_IN, StockMovementDirection.IN, "4", D.plusDays(1).atTime(11, 0));
		count.setReferenceType("INVENTORY");
		count.setNotes("INV-0007");
		move(charlie, StockMovementType.SALE, StockMovementDirection.OUT, "2", D.atTime(12, 0));
		move(charlie, StockMovementType.PURCHASE_RECEPTION, StockMovementDirection.IN, "5", D.minusDays(40).atTime(9, 0));
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

	private static Item item(String code, String name, ItemFamily family, String categoryCode, String sectionCode) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setType(ItemType.PRODUCT);
		item.setItemFamily(family);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
		item.setStockQuantity(BigDecimal.ZERO);
		session.persist(item);
		return item;
	}

	private static StockMovement move(Item item, StockMovementType type, StockMovementDirection direction, String quantity, LocalDateTime at) {
		StockMovement movement = new StockMovement();
		movement.setItem(item);
		movement.setMovementType(type);
		movement.setDirection(direction);
		movement.setQuantity(new BigDecimal(quantity));
		movement.setCreatedAt(at);
		// the inventory count of the stock card: written by a user (created_by is not updatable once persisted)
		if (type == StockMovementType.INVENTORY_IN) movement.setCreatedBy("admin");
		session.persist(movement);
		return movement;
	}

	/** The period D .. D + 1, grouped, with a filter (none: every movement). */
	private static List<StockMovementReportRowDTO> report(String groupBy, StockMovementReportFilter filter) {
		return reports.getStockMovementsReport(D, D.plusDays(1), groupBy, filter);
	}

	private static Map<String, StockMovementReportRowDTO> byLabel(List<StockMovementReportRowDTO> rows) {
		return rows.stream().collect(Collectors.toMap(StockMovementReportRowDTO::getGroupLabel, Function.identity()));
	}

	private static List<String> labels(List<StockMovementReportRowDTO> rows) {
		return rows.stream().map(StockMovementReportRowDTO::getGroupLabel).sorted().collect(Collectors.toList());
	}

	private static String q(BigDecimal quantity) {
		return quantity.toPlainString();
	}

	@Test
	@DisplayName("By item: the period only, COST_ADJUSTMENT left out; the name as label, the code and family apart")
	void items() {
		List<StockMovementReportRowDTO> rows = report("ITEM", null);
		assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie"), labels(rows), "Charlie's purchase 40 days before: out");
		StockMovementReportRowDTO alpha = byLabel(rows).get("Alpha");
		assertEquals("A", alpha.getItemCode());
		assertEquals("Parfums", alpha.getFamilyName());
		assertEquals("10", q(alpha.getQtyIn()));
		assertEquals("4.5", q(alpha.getQtyOut()));
		assertEquals("5.5", q(alpha.getNetQty()));
		assertEquals(Long.valueOf(3), alpha.getNbMovements(), "the CMP adjustment is not a movement of stock");
		assertEquals(1, alpha.getItemCount());
		assertEquals("-2", q(byLabel(rows).get("Charlie").getNetQty()));
	}

	@Test
	@DisplayName("By family, category and section: the sums and the distinct items; no category or section: their \"No\" row")
	void levels() {
		Map<String, StockMovementReportRowDTO> families = byLabel(report("FAMILY", null));
		assertEquals("14", q(families.get("Parfums").getQtyIn()));
		assertEquals("4.5", q(families.get("Parfums").getQtyOut()));
		assertEquals(Long.valueOf(4), families.get("Parfums").getNbMovements());
		assertEquals(2, families.get("Parfums").getItemCount());
		assertNull(families.get("Parfums").getItemCode());

		List<StockMovementReportRowDTO> categories = report("CATEGORY", null);
		assertEquals(Arrays.asList("Femme", "No Category"), labels(categories));
		assertEquals(2, byLabel(categories).get("Femme").getItemCount());

		Map<String, StockMovementReportRowDTO> sections = byLabel(report("SECTION", null));
		assertEquals(1, sections.get("Eau de toilette").getItemCount());
		assertEquals(2, sections.get("No Section").getItemCount(), "Bravo and Charlie");
	}

	@Test
	@DisplayName("By type, day and month: no COST_ADJUSTMENT row; days and months zero-padded, in time order")
	void typesAndTime() {
		Map<String, StockMovementReportRowDTO> types = byLabel(report("TYPE", null));
		assertEquals(Arrays.asList("INVENTORY_IN", "PURCHASE_RECEPTION", "SALE"), types.keySet().stream().sorted().collect(Collectors.toList()));
		assertEquals("6.5", q(types.get("SALE").getQtyOut()));
		assertEquals(Long.valueOf(3), types.get("SALE").getNbMovements());

		List<StockMovementReportRowDTO> days = report("DAY", null);
		assertEquals(Arrays.asList(D.toString(), D.plusDays(1).toString()),
				days.stream().map(StockMovementReportRowDTO::getGroupLabel).collect(Collectors.toList()), "yyyy-MM-dd, in time order");
		assertEquals("10", q(days.get(0).getQtyIn()));
		assertEquals("5", q(days.get(0).getQtyOut()));
		assertEquals(2, days.get(0).getItemCount());

		List<StockMovementReportRowDTO> months = report("MONTH", null);
		assertEquals(String.format("%04d-%02d", D.getYear(), D.getMonthValue()), months.get(0).getGroupLabel());
	}

	@Test
	@DisplayName("Filters: the types (any of them), the family, the category, the search; the one-type call of before")
	void filters() {
		StockMovementReportFilter sales = new StockMovementReportFilter();
		sales.getTypes().add(StockMovementType.SALE);
		assertEquals(Arrays.asList("Alpha", "Charlie"), labels(report("ITEM", sales)));

		StockMovementReportFilter family = new StockMovementReportFilter();
		family.setFamilyId(parfums.getId());
		Map<String, StockMovementReportRowDTO> types = byLabel(report("TYPE", family));
		assertEquals("4.5", q(types.get("SALE").getQtyOut()), "Charlie (Soins) left out");

		StockMovementReportFilter femme = new StockMovementReportFilter();
		femme.setCategoryCode("CAT1");
		assertEquals(Arrays.asList("Parfums"), labels(report("FAMILY", femme)));

		StockMovementReportFilter search = new StockMovementReportFilter();
		search.setSearch("RAV");
		assertEquals(Arrays.asList("Bravo"), labels(report("ITEM", search)), "the name contains it, case ignored");

		assertEquals(Arrays.asList("Alpha", "Charlie"),
				labels(reports.getStockMovementsReport(D, D.plusDays(1), "ITEM", "SALE")), "movementType of before 3.1.2");
	}

	@Test
	@DisplayName("Keys: an item row its item's id (the stock card), a group row what the page filters on to open it")
	void keys() {
		assertEquals(alpha.getId(), byLabel(report("ITEM", null)).get("Alpha").getItemId());
		assertNull(byLabel(report("FAMILY", null)).get("Parfums").getItemId());
		assertEquals(String.valueOf(parfums.getId()), byLabel(report("FAMILY", null)).get("Parfums").getGroupKey());
		Map<String, StockMovementReportRowDTO> categories = byLabel(report("CATEGORY", null));
		assertEquals("CAT1", categories.get("Femme").getGroupKey());
		assertNull(categories.get("No Category").getGroupKey(), "the items without one: nothing to filter on");
		assertEquals("SALE", byLabel(report("TYPE", null)).get("SALE").getGroupKey());
		assertEquals(D.toString(), report("DAY", null).get(0).getGroupKey());
		Map<String, StockMovementReportRowDTO> subFamilies = byLabel(report("SUBFAMILY", null));
		assertEquals(String.valueOf(eaux.getId()), subFamilies.get("Eaux").getGroupKey());
		assertEquals("Parfums", subFamilies.get("Eaux").getFamilyName(), "sub-family names repeat across families");
		assertNull(subFamilies.get("No Subfamily").getGroupKey());
		assertNull(subFamilies.get("No Subfamily").getFamilyName(), "the items without one: of several families");
		assertEquals(2, subFamilies.get("No Subfamily").getItemCount(), "Bravo (Parfums) and Charlie (Soins), one row");
	}

	@Test
	@DisplayName("Stock card: opening and closing counted back from the stock now, the stock after each line, the period's sums")
	void stockCard() {
		StockCardDTO card = reports.getStockCard(alpha.getId(), D, D.plusDays(1), ReportService.STOCK_CARD_MAX);
		assertEquals("A", card.getItemCode());
		assertEquals("18", q(card.getClosingQty()), "20 now, minus the 2 that came in after the period");
		assertEquals("12.5", q(card.getOpeningQty()), "18 - 10 + 4.5");
		assertEquals("10", q(card.getQtyIn()));
		assertEquals("4.5", q(card.getQtyOut()));
		assertEquals(3, card.getMovementCount(), "the CMP adjustment is not a line");
		assertEquals(false, card.isTruncated());
		List<StockCardLineDTO> lines = card.getLines();
		assertEquals(Arrays.asList("22.5", "19.5", "18"), lines.stream().map(l -> q(l.getBalanceAfter())).collect(Collectors.toList()),
				"in time order, the stock after each movement, the last one the closing stock");
		assertEquals(Arrays.asList("PURCHASE_RECEPTION", "SALE", "SALE"), lines.stream().map(StockCardLineDTO::getType).collect(Collectors.toList()));
	}

	@Test
	@DisplayName("Stock card: the ticket's number and its cashier, an inventory count's number from its note, System kept without session")
	void stockCardReferences() {
		List<StockCardLineDTO> lines = reports.getStockCard(alpha.getId(), D, D.plusDays(1), ReportService.STOCK_CARD_MAX).getLines();
		assertEquals("T-0042", lines.get(1).getReference(), "the ticket's number, not its id");
		assertEquals("Caissier Un", lines.get(1).getUser(), "a till movement written by System: the session's cashier");
		assertNull(lines.get(2).getReference(), "a sale without its ticket here: no number (the page shows the id)");
		assertEquals("System", lines.get(2).getUser(), "no session: as written");

		StockCardLineDTO count = reports.getStockCard(bravo.getId(), D, D.plusDays(1), ReportService.STOCK_CARD_MAX).getLines().get(0);
		assertEquals("INV-0007", count.getReference(), "the count's number is its note");
		assertNull(count.getNote(), "not shown twice");
		assertEquals("admin", count.getUser());
	}

	@Test
	@DisplayName("Stock card: at most the limit, the latest lines with their stock; the sums whole; an unknown item none")
	void stockCardLimit() {
		StockCardDTO card = reports.getStockCard(alpha.getId(), D, D.plusDays(1), 2);
		assertEquals(true, card.isTruncated());
		assertEquals(3, card.getMovementCount());
		assertEquals(Arrays.asList("19.5", "18"), card.getLines().stream().map(l -> q(l.getBalanceAfter())).collect(Collectors.toList()));
		assertEquals("12.5", q(card.getOpeningQty()), "the opening from the sums, not from the lines read");
		assertNull(reports.getStockCard(-1L, D, D.plusDays(1), 10));
	}
}
