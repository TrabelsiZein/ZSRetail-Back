package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.support.InMemoryItems;

/**
 * CMP: the queries of ItemRepository run as they are with Hibernate over an in-memory H2 database in SQL Server mode
 * (no Spring context; Spring's naming strategy, so the columns are those of SQL Server).
 * <ul>
 * <li>APPLY_ENTRY_COST, the weighted average cost after an entry, on each rule (first cost, stock before at 0 or below,
 * the average, a quantity of 0, the rounding to 3 decimals, decimal quantities); the in-memory version of the tests
 * (InMemoryItems.entryCost) answers the same on every case.</li>
 * <li>Why the costs are written by bulk updates: an item loaded before a native stock update and saved after it puts
 * its loaded stock back (the update of every column), the stock entered is lost; the bulk update of the last costs
 * (updateLastCosts) keeps it.</li>
 * </ul>
 */
class EntryCostQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration().addAnnotatedClass(ItemFamily.class)
				.addAnnotatedClass(ItemSubFamily.class).addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:entrycost;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop");
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static long nextCode;

	private static Long item(Double cmp, BigDecimal stock) {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item item = new Item();
			item.setItemCode("CMP" + (++nextCode));
			item.setName("Item");
			item.setCostPrice(cmp);
			item.setStockQuantity(stock);
			session.persist(item);
			session.getTransaction().commit();
			return item.getId();
		}
	}

	private static Item read(Long id) {
		try (Session session = sessions.openSession()) {
			return session.get(Item.class, id);
		}
	}

	private static String query(String method, Class<?>... types) throws NoSuchMethodException {
		return ItemRepository.class.getMethod(method, types).getAnnotation(Query.class).value();
	}

	@ParameterizedTest(name = "CMP {0}, stock now {1} with {2} in at {3}: {4}")
	@CsvSource({
			// cmp, stock now (the entry in), qty in, unit cost, expected CMP
			", 10, 10, 6, 6", // no CMP yet: the unit cost
			"9, 16, 6, 6, 7.875", // (10 x 9 + 6 x 6) / 16
			"9, 14, 4, 6, 8.143", // 114 / 14 = 8.142857: 3 decimals
			"9, 6, 6, 6, 6", // stock before 0: the unit cost
			"9, 4, 6, 6, 6", // stock before -2 (negative stock allowed): the unit cost
			"9, 10, 0, 6, 9", // nothing entered: unchanged
			", 10, 0, 6, 6", // nothing entered, no CMP yet: the unit cost
			"1.23456, 3, 1, 2.34567, 1.605", // (2 x 1.23456 + 2.34567) / 3 = 1.604930
			"9, 2.5, 0.5, 4, 8", // decimal quantities: (2 x 9 + 0.5 x 4) / 2.5
			"9, 15, 2, 6, 8.6", // free units of the same entry stay at the CMP before: (13 x 9 + 2 x 6) / 15
	})
	@DisplayName("APPLY_ENTRY_COST on SQL Server's rules, as InMemoryItems.entryCost")
	void entryCost(Double cmp, BigDecimal stockNow, BigDecimal qtyIn, double unitCost, double expected) {
		Long id = item(cmp, stockNow);
		int rows;
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			rows = session.createNativeQuery(ItemRepository.APPLY_ENTRY_COST).setParameter("id", id)
					.setParameter("qtyIn", qtyIn).setParameter("unitCost", unitCost).setParameter("by", "TEST")
					.executeUpdate();
			session.getTransaction().commit();
		}
		assertEquals(expected, read(id).getCostPrice(), 0.0000001);
		assertEquals(qtyIn.signum() == 0 && cmp != null ? 0 : 1, rows, "unchanged: no row written");
		Double inMemory = InMemoryItems.entryCost(cmp, stockNow, qtyIn, unitCost);
		assertEquals(expected, inMemory == null ? cmp : inMemory, 0.0000001, "the in-memory rule of the tests");
		assertEquals(stockNow.compareTo(read(id).getStockQuantity()), 0, "the stock is never touched");
	}

	@Test
	@DisplayName("A loaded item saved after a native stock update puts its loaded stock back: the old purchase's last costs by a save lost a line's quantity")
	void savedItemPutsItsStockBack() throws Exception {
		Long id = item(9.0, BigDecimal.valueOf(10));
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item loaded = session.get(Item.class, id); // stock 10, as a purchase line reads it
			session.createNativeQuery(query("addToStockQuantity", Long.class, BigDecimal.class)).setParameter("itemId", id)
					.setParameter("delta", BigDecimal.valueOf(5)).executeUpdate(); // the first line: +5
			loaded.setLastDirectCost(7.0); // the second line of the same item, at another price
			session.flush(); // every column written: stock 10
			session.getTransaction().commit();
		}
		assertEquals(0, BigDecimal.valueOf(10).compareTo(read(id).getStockQuantity()), "the +5 is lost");
	}

	@Test
	@DisplayName("The last costs by the bulk update (updateLastCosts), the item loaded: the stock entered stays, the CMP untouched")
	void bulkLastCostsKeepTheStock() throws Exception {
		Long id = item(9.0, BigDecimal.valueOf(10));
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.get(Item.class, id);
			session.createNativeQuery(query("addToStockQuantity", Long.class, BigDecimal.class)).setParameter("itemId", id)
					.setParameter("delta", BigDecimal.valueOf(5)).executeUpdate();
			session.createQuery(query("updateLastCosts", Long.class, Double.class, Double.class, String.class))
					.setParameter("id", id).setParameter("gross", 7.0).setParameter("net", 6.3).setParameter("by", "TEST")
					.executeUpdate();
			session.flush();
			session.getTransaction().commit();
		}
		Item item = read(id);
		assertEquals(0, BigDecimal.valueOf(15).compareTo(item.getStockQuantity()));
		assertEquals(7.0, item.getLastDirectCost());
		assertEquals(6.3, item.getLastDirectNetCost());
		assertEquals(9.0, item.getCostPrice(), "updateLastCosts never writes the CMP");
	}
}
