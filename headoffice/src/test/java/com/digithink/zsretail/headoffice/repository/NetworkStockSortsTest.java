package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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
import org.springframework.data.domain.PageRequest;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.model.Store;

/**
 * Network stock (3.1.0), step 2: the sorts of the head office items and the own items' filters and sorts
 * (HoNetworkStockQuery.lines / ownLines over a Hibernate session on an in-memory H2 database, portable SQL).
 * <p>
 * Items (name, head office stock): 0000016 (Delta, 3), 000002 (Alpha, none), 14ZDU05~002 (Charlie, -1), 14ZDU05~001
 * (Bravo, 7), B10 (Echo, 0). Stores SA and SB active, SX inactive. Store stock: SA 0000016 5, 14ZDU05~001 -2, B10 1; SB
 * 0000016 1, 000002 4; SX 14ZDU05~002 100. Own items: SA OWN-10 (Zulu, 0, read 10:00), OWN-9 (Yankee, 3, no time); SB
 * OWN-1 (Xray, -1, 09:00); SX OWN-5 (Whiskey, 2, 11:00).
 */
class NetworkStockSortsTest {

	private static final String S2 = "14ZDU05~002";
	private static final String S1 = "14ZDU05~001";

	private static SessionFactory sessions;
	private static long sa;
	private static long sb;
	private static long sx;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (String base : new String[] { "com.digithink.zsretail.core.model", "com.digithink.zsretail.headoffice.model" }) {
			for (BeanDefinition entity : scan.findCandidateComponents(base)) {
				try {
					configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:networkstocksorts;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			item(session, "0000016", "Delta", "3");
			item(session, "000002", "Alpha", null);
			item(session, S2, "Charlie", "-1");
			item(session, S1, "Bravo", "7");
			item(session, "B10", "Echo", "0");
			sa = store(session, "SA", true);
			sb = store(session, "SB", true);
			sx = store(session, "SX", false);
			stock(session, sa, "0000016", "5", false, null);
			stock(session, sa, S1, "-2", false, null);
			stock(session, sa, "B10", "1", false, null);
			stock(session, sb, "0000016", "1", false, null);
			stock(session, sb, "000002", "4", false, null);
			stock(session, sx, S2, "100", false, null);
			stock(session, sa, "OWN-10", "0", true, LocalDateTime.of(2026, 10, 20, 10, 0)).setItemName("Zulu");
			stock(session, sa, "OWN-9", "3", true, null).setItemName("Yankee");
			stock(session, sb, "OWN-1", "-1", true, LocalDateTime.of(2026, 10, 20, 9, 0)).setItemName("Xray");
			stock(session, sx, "OWN-5", "2", true, LocalDateTime.of(2026, 10, 20, 11, 0)).setItemName("Whiskey");
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void item(Session session, String code, String name, String stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setType(ItemType.PRODUCT);
		item.setNavItemNo(code.startsWith("14ZDU05") ? "14ZDU05" : null);
		item.setStockQuantity(stock == null ? null : new BigDecimal(stock));
		session.persist(item);
	}

	private static long store(Session session, String code, boolean active) {
		Store store = new Store();
		store.setCode(code);
		store.setName("Magasin " + code);
		store.setApiKeyHash("hash-" + code);
		store.setActive(active);
		session.persist(store);
		return store.getId();
	}

	private static HoStoreStock stock(Session session, long storeId, String itemCode, String quantity, boolean own,
			LocalDateTime storeTime) {
		HoStoreStock row = new HoStoreStock();
		row.setStoreId(storeId);
		row.setItemCode(itemCode);
		row.setQuantity(new BigDecimal(quantity));
		row.setOwnItem(own);
		row.setStoreTime(storeTime);
		row.setReceivedAt(LocalDateTime.of(2026, 10, 20, 12, 0));
		session.persist(row);
		return row;
	}

	/** The codes of a list (lines or ownLines) for a criteria (every active store, the head office column). */
	private static List<String> codes(boolean own, Consumer<HoNetworkStockQuery.Criteria> set) {
		HoNetworkStockQuery.Criteria criteria = new HoNetworkStockQuery.Criteria();
		set.accept(criteria);
		try (Session session = sessions.openSession()) {
			HoNetworkStockQuery query = new HoNetworkStockQuery(session);
			Function<Object[], String> code = row -> (String) row[own ? HoNetworkStockQuery.OWN_ITEM_CODE
					: HoNetworkStockQuery.COL_ITEM_CODE];
			return (own ? query.ownLines(criteria, PageRequest.of(0, 50)) : query.lines(criteria, PageRequest.of(0, 50)))
					.getContent().stream().map(code).collect(Collectors.toList());
		}
	}

	private static List<String> items(String sort, boolean descending, Long sortStoreId) {
		return codes(false, c -> {
			c.setSort(sort);
			c.setDescending(descending);
			c.setSortStoreId(sortStoreId);
		});
	}

	private static List<String> own(String sort, boolean descending) {
		return codes(true, c -> {
			c.setSort(sort);
			c.setDescending(descending);
		});
	}

	@Test
	@DisplayName("Code, natural: the codes of digits only by their number (000002 before 0000016), then the others as text")
	void naturalCode() {
		assertEquals(Arrays.asList("000002", "0000016", S1, S2, "B10"), items(null, false, null), "the default");
		assertEquals(Arrays.asList("000002", "0000016", S1, S2, "B10"), items("code", false, null));
		assertEquals(Arrays.asList("B10", S2, S1, "0000016", "000002"), items("code", true, null));
	}

	@Test
	@DisplayName("Name and head office stock (none counts as 0), each then by code")
	void nameAndHeadOffice() {
		assertEquals(Arrays.asList("000002", S1, S2, "0000016", "B10"), items("name", false, null));
		assertEquals(Arrays.asList("B10", "0000016", S2, S1, "000002"), items("name", true, null));
		assertEquals(Arrays.asList(S2, "000002", "B10", "0000016", S1), items("headOffice", false, null));
		assertEquals(Arrays.asList(S1, "0000016", "000002", "B10", S2), items("headOffice", true, null),
				"ties (000002 and B10 at 0) still by code ascending");
		assertEquals(Arrays.asList("000002", "0000016", S1, S2, "B10"),
				codes(false, c -> { c.setSort("headOffice"); c.setHeadOfficeColumn(false); }), "no head office column: by code");
	}

	@Test
	@DisplayName("One store's column: by its quantity, the items it never sent last in both directions")
	void storeColumn() {
		assertEquals(Arrays.asList(S1, "B10", "0000016", "000002", S2), items("store", false, sa));
		assertEquals(Arrays.asList("0000016", "B10", S1, "000002", S2), items("store", true, sa));
		assertEquals(Arrays.asList("000002", "0000016", S1, S2, "B10"), items("store", false, null), "no store: by code");
	}

	@Test
	@DisplayName("Total of the active stores (SX left out), the items no active store sent last in both directions")
	void totalStores() {
		assertEquals(Arrays.asList(S1, "B10", "000002", "0000016", S2), items("total", false, null));
		assertEquals(Arrays.asList("0000016", "000002", "B10", S1, S2), items("total", true, null));
	}

	@Test
	@DisplayName("Own items: by store code then code by default, every store; the stock filter on the row's quantity; search; one store")
	void ownFilters() {
		assertEquals(Arrays.asList("OWN-10", "OWN-9", "OWN-1", "OWN-5"), own(null, false));
		assertEquals(Arrays.asList("OWN-9", "OWN-5"), codes(true, c -> c.setStock(HoNetworkStockQuery.IN_STOCK)));
		assertEquals(Arrays.asList("OWN-10"), codes(true, c -> c.setStock(HoNetworkStockQuery.ZERO)));
		assertEquals(Arrays.asList("OWN-1"), codes(true, c -> c.setStock(HoNetworkStockQuery.BELOW_ZERO)));
		assertEquals(Arrays.asList("OWN-9"), codes(true, c -> c.setLike("%yank%")));
		assertEquals(Arrays.asList("OWN-1"), codes(true, c -> c.setStoreId(sb)));
	}

	@Test
	@DisplayName("Own items' sorts: store, code, name, quantity, store time (none last), received; each then by store and code")
	void ownSorts() {
		assertEquals(Arrays.asList("OWN-5", "OWN-1", "OWN-10", "OWN-9"), own("store", true));
		assertEquals(Arrays.asList("OWN-1", "OWN-10", "OWN-5", "OWN-9"), own("code", false));
		assertEquals(Arrays.asList("OWN-5", "OWN-1", "OWN-9", "OWN-10"), own("name", false));
		assertEquals(Arrays.asList("OWN-1", "OWN-10", "OWN-5", "OWN-9"), own("quantity", false));
		assertEquals(Arrays.asList("OWN-9", "OWN-5", "OWN-10", "OWN-1"), own("quantity", true));
		assertEquals(Arrays.asList("OWN-1", "OWN-10", "OWN-5", "OWN-9"), own("storeTime", false));
		assertEquals(Arrays.asList("OWN-5", "OWN-10", "OWN-1", "OWN-9"), own("storeTime", true), "no time last both ways");
		assertEquals(Arrays.asList("OWN-10", "OWN-9", "OWN-1", "OWN-5"), own("receivedAt", false), "same time: by store, code");
	}

	@Test
	@DisplayName("Every column of the sorted statements has its own alias (SQL Server)")
	void columnAliases() {
		HoNetworkStockQuery.Criteria criteria = new HoNetworkStockQuery.Criteria();
		criteria.setSort("total");
		HoNetworkStockQuery.Built total = HoNetworkStockQuery.build(criteria, true);
		assertEquals(Arrays.asList("item_code", "item_name", "stock_quantity", "item_id", "nav_item_no"),
				ItemCatalogueQueryTest.columnLabels(sessions, total.getLines(), total.getParameters()));
		criteria.setSort("store");
		criteria.setSortStoreId(sa);
		HoNetworkStockQuery.Built store = HoNetworkStockQuery.build(criteria, true);
		assertEquals(5, ItemCatalogueQueryTest.columnLabels(sessions, store.getLines(), store.getParameters()).size());
		criteria.setStock(HoNetworkStockQuery.ZERO);
		HoNetworkStockQuery.Built own = HoNetworkStockQuery.buildOwn(criteria, true);
		assertEquals(Arrays.asList("store_id", "store_code", "store_name", "item_code", "item_name", "quantity", "store_time",
				"received_at"), ItemCatalogueQueryTest.columnLabels(sessions, own.getLines(), own.getParameters()));
		assertEquals(Arrays.asList(HoNetworkStockQuery.COUNT_COLUMN),
				ItemCatalogueQueryTest.columnLabels(sessions, own.getCount(), own.getParameters()));
	}
}
