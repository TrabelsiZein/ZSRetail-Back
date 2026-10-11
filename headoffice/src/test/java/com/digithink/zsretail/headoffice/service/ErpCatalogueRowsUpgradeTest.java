package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoDownSequence;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;

/**
 * Stock point copies, step 5: the upgrade of an existing database. Its SQL runs as it is on H2 (tables named as on SQL
 * Server, Spring's naming strategy). Before: every-store rows for every catalogue record (written before step 4); store
 * S1 created with P1 before step 3 (no row of its own, never pulled), S2 given P2 later (its own rows, one of them older
 * than the every-store row), S0 with no point. After: for each store, every code of its point's items keeps exactly the
 * number it would read (so a store at any cursor reads the same codes of its point), the other ERP codes are gone, the
 * hand-made item, the families and an unknown code are untouched; the sequence is untouched; a second run changes nothing.
 */
class ErpCatalogueRowsUpgradeTest {

	private static final long P1 = 1;
	private static final long P2 = 2;

	private SessionFactory sessions;
	private JdbcTemplate jdbc;
	private long s1;
	private long s2;
	private long s0;

	@BeforeEach
	void database() {
		String url = "jdbc:h2:mem:erprowsupgrade;DB_CLOSE_DELAY=-1;MODE=MSSQLServer";
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy()); // the tables as on SQL Server
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(Store.class)
				.addAnnotatedClass(HoStockPointItem.class).addAnnotatedClass(HoDownChange.class)
				.addAnnotatedClass(HoDownSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "sa")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL(url);
		dataSource.setUser("sa");
		jdbc = new JdbcTemplate(dataSource);
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item a1 = item(session, "A1", true);
			Item a2 = item(session, "A2", true);
			Item b1 = item(session, "B1", true);
			Item ab = item(session, "AB", true);
			Item none = item(session, "NONE", true);
			Item main = item(session, "MAIN", false);
			barcode(session, "101", a1);
			barcode(session, "201", a2);
			barcode(session, "301", b1);
			barcode(session, "401", ab);
			barcode(session, "501", none);
			barcode(session, "901", main);
			row(session, P1, a1, true);
			row(session, P1, a2, false);
			row(session, P1, ab, true);
			row(session, P2, b1, true);
			row(session, P2, ab, true);
			s1 = store(session, "S1", P1);
			s2 = store(session, "S2", P2);
			s0 = store(session, "S0", null);
			String[] everyStore = { "FAMILY:F1", "ITEM:A1", "ITEM:A2", "ITEM:B1", "ITEM:AB", "ITEM:NONE", "ITEM:MAIN",
					"BARCODE:101", "BARCODE:201", "BARCODE:301", "BARCODE:401", "BARCODE:501", "BARCODE:901",
					"ITEM:GONE" };
			long version = 10;
			for (String code : everyStore) {
				change(session, code, null, code.equals("ITEM:B1") ? 30 : version++);
			}
			change(session, "ITEM:B1", s2, 5); // older than the every-store row: raised to 30
			change(session, "ITEM:AB", s2, 50); // newer: kept
			change(session, "BARCODE:301", s2, 50);
			change(session, "BARCODE:401", s2, 50);
			HoDownSequence sequence = new HoDownSequence();
			sequence.setDomain(DataDomain.CATALOGUE);
			sequence.setLastVersion(60);
			session.persist(sequence);
			session.getTransaction().commit();
		}
	}

	@AfterEach
	void close() {
		sessions.close();
	}

	private static Item item(Session session, String code, boolean fromErp) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName("Item " + code);
		item.setErpExternalId(fromErp ? code : null);
		session.persist(item);
		return item;
	}

	private static void barcode(Session session, String value, Item item) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(value);
		barcode.setItem(item);
		session.persist(barcode);
	}

	private static void row(Session session, long pointId, Item item, boolean active) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(item.getId());
		row.setName(item.getName());
		row.setUnitPrice(10.0);
		row.setActive(active);
		session.persist(row);
	}

	private static long store(Session session, String code, Long pointId) {
		Store store = new Store();
		store.setCode(code);
		store.setName("Store " + code);
		store.setApiKeyHash("x");
		store.setStockPointId(pointId);
		session.persist(store);
		return store.getId();
	}

	private static void change(Session session, String code, Long storeId, long version) {
		HoDownChange change = new HoDownChange();
		change.setDomain(DataDomain.CATALOGUE);
		change.setRecordCode(code);
		change.setStoreId(storeId);
		change.setChangeVersion(version);
		session.persist(change);
	}

	/** What a store reads from cursor 0: each code with its number (its own row or the every-store row, the higher). */
	private Map<String, Long> readBy(long storeId) {
		Map<String, Long> read = new TreeMap<>();
		for (Map<String, Object> row : jdbc.queryForList("SELECT record_code, MAX(change_version) AS v FROM ho_down_change"
				+ " WHERE domain = 'CATALOGUE' AND (store_id = ? OR store_id IS NULL) GROUP BY record_code", storeId)) {
			read.put((String) row.get("record_code"), ((Number) row.get("v")).longValue());
		}
		return read;
	}

	private static Map<String, Long> only(Map<String, Long> read, String... codes) {
		Set<String> kept = new HashSet<>(Arrays.asList(codes));
		Map<String, Long> result = new TreeMap<>(read);
		result.keySet().retainAll(kept);
		return result;
	}

	private List<Map<String, Object>> everyRow() {
		return jdbc.queryForList("SELECT record_code, store_id, change_version FROM ho_down_change"
				+ " ORDER BY record_code, store_id");
	}

	private static final String[] NOT_ERP = { "FAMILY:F1", "ITEM:MAIN", "BARCODE:901", "ITEM:GONE" };

	private static String[] with(String... codes) {
		String[] all = Arrays.copyOf(NOT_ERP, NOT_ERP.length + codes.length);
		System.arraycopy(codes, 0, all, NOT_ERP.length, codes.length);
		return all;
	}

	@Test
	@DisplayName("Case 9: each store reads the same numbers for its point's codes, nothing of the other ERP codes; families, hand-made and unknown codes untouched")
	void split() {
		Map<String, Long> s1Before = readBy(s1);
		Map<String, Long> s2Before = readBy(s2);
		Map<String, Long> s0Before = readBy(s0);
		assertEquals(Long.valueOf(30), s2Before.get("ITEM:B1"));

		String done = new ErpCatalogueRowsUpgrade(jdbc).run();
		assertEquals("every-store rows of ERP items and barcodes: 10 deleted; store rows: 6 written with their number,"
				+ " 1 raised to it", done);

		assertEquals(only(s1Before, with("ITEM:A1", "ITEM:A2", "ITEM:AB", "BARCODE:101", "BARCODE:201", "BARCODE:401")),
				readBy(s1), "S1, created with P1 and never pulled: every code of P1 with its number, the inactive row too");
		assertEquals(only(s2Before, with("ITEM:B1", "ITEM:AB", "BARCODE:301", "BARCODE:401")), readBy(s2),
				"S2: its own newer rows kept, the older one raised");
		assertEquals(only(s0Before, NOT_ERP), readBy(s0), "S0, no point: no ERP code any more");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ho_down_change WHERE store_id IS NULL"
				+ " AND record_code IN ('ITEM:A1', 'ITEM:NONE', 'BARCODE:501')", Long.class).longValue());
		assertEquals(Long.valueOf(60), jdbc.queryForObject("SELECT last_version FROM ho_down_sequence", Long.class),
				"the sequence is not touched");
	}

	@Test
	@DisplayName("A second run finds no every-store ERP row and changes nothing")
	void secondRun() {
		new ErpCatalogueRowsUpgrade(jdbc).run();
		List<Map<String, Object>> after = everyRow();
		assertNull(new ErpCatalogueRowsUpgrade(jdbc).run());
		assertEquals(after, everyRow());
	}

	@Test
	@DisplayName("A database with no every-store ERP row (a head office set up after step 4): nothing to do")
	void nothingToDo() {
		jdbc.update("DELETE FROM ho_down_change WHERE store_id IS NULL AND record_code NOT IN ('FAMILY:F1', 'ITEM:MAIN',"
				+ " 'BARCODE:901', 'ITEM:GONE')");
		List<Map<String, Object>> before = everyRow();
		assertNull(new ErpCatalogueRowsUpgrade(jdbc).run());
		assertEquals(before, everyRow());
		assertTrue(readBy(s1).containsKey("ITEM:MAIN"));
	}
}
