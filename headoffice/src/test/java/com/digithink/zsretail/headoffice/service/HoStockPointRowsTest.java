package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.erp.engine.StockPoints.Row;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;

/**
 * Item variants (ZEN), step 5: HoStockPointRows reads and writes the clearance of a point de stock row, its queries run
 * as they are with Hibernate over an in-memory H2 database (no Spring context; the repository's saveAll is the
 * session's). A row written before step 5 (clearance and clearance price null) is read without clearance.
 */
class HoStockPointRowsTest {

	private static final long POINT = 1;

	private SessionFactory sessions;
	private Session session;
	private HoStockPointRows rows;

	@BeforeEach
	void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockpointrows;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();
		Item bike = item("1000");
		Item tie = item("14ZDU05~001");
		row(bike, null, null); // written before step 5
		row(tie, Boolean.TRUE, new BigDecimal("7.000"));
		session.getTransaction().commit();
		session.clear();
		rows = new HoStockPointRows(null, saving(), session, null);
	}

	/** The repository's saveAll as the session's. */
	private HoStockPointItemRepository saving() {
		return (HoStockPointItemRepository) Proxy.newProxyInstance(HoStockPointItemRepository.class.getClassLoader(),
				new Class<?>[] { HoStockPointItemRepository.class }, (proxy, method, args) -> {
					if ("saveAll".equals(method.getName())) {
						List<Object> saved = new ArrayList<>();
						for (Object entity : (Iterable<?>) args[0]) {
							session.saveOrUpdate(entity);
							saved.add(entity);
						}
						return saved;
					}
					throw new UnsupportedOperationException("Unexpected call: " + method.getName());
				});
	}

	@AfterEach
	void close() {
		session.close();
		sessions.close();
	}

	@Test
	@DisplayName("Read: a row of before (clearance null) is without clearance; a row in clearance has both values")
	void readsTheClearance() {
		Map<String, Row> read = rows.rows(POINT);

		Row bike = read.get("1000");
		assertFalse(bike.clearance, "a null column is false");
		assertNull(bike.clearancePrice);
		Row tie = read.get("14ZDU05~001");
		assertTrue(tie.clearance);
		assertEquals(0, new BigDecimal("7.000").compareTo(tie.clearancePrice));
	}

	@Test
	@DisplayName("Write: both columns written, the row of before given its clearance, the other's taken off")
	void writesTheClearance() {
		session.beginTransaction();
		int written = rows.write(POINT, Arrays.asList(
				new Row("1000", "Bicyclette", null, null, null, 100.0, true, true, new BigDecimal("5.500")),
				new Row("14ZDU05~001", "CRAVATE", null, null, null, 19.5, true, false, null)));
		session.getTransaction().commit();
		session.clear();

		assertEquals(2, written);
		HoStockPointItem bike = stored("1000");
		assertEquals(Boolean.TRUE, bike.getClearance());
		assertEquals(0, new BigDecimal("5.500").compareTo(bike.getClearancePrice()));
		assertEquals(100.0, bike.getUnitPrice(), "the row's price as before");
		HoStockPointItem tie = stored("14ZDU05~001");
		assertEquals(Boolean.FALSE, tie.getClearance());
		assertNull(tie.getClearancePrice());
	}

	@Test
	@DisplayName("Clearance (2026-10-17): a row whose clearance alone changed is written and its item handed to the"
			+ " copies down for the point's stores, as any row change")
	void clearanceChangeRecordedForTheStores() {
		List<Long> recorded = new ArrayList<>();
		HoCatalogueService recorder = new HoCatalogueService(null, null, null, null, null, null, () -> null,
				TransactionOperations.withoutTransaction()) {
			@Override
			public void stockPointRowsChanged(long pointId, Collection<Long> itemIds, Collection<Long> barcodeItemIds) {
				assertEquals(POINT, pointId);
				recorded.addAll(itemIds);
			}
		};
		HoStockPointRows recording = new HoStockPointRows(null, saving(),
				session, new StaticListableBeanFactory(Collections.singletonMap("catalogue", recorder))
						.getBeanProvider(HoCatalogueService.class));
		Long tieId = stored("14ZDU05~001").getItemId();
		session.clear();

		session.beginTransaction();
		// The row of before: its name, price 1.0, active, clearance true at 7.000; only the clearance price changes
		recording.write(POINT, Collections.singletonList(
				new Row("14ZDU05~001", "14ZDU05~001", null, null, null, 1.0, true, true, new BigDecimal("5.000"))));
		session.getTransaction().commit();
		session.clear();

		assertEquals(Collections.singletonList(tieId), recorded);
		assertEquals(0, new BigDecimal("5.000").compareTo(stored("14ZDU05~001").getClearancePrice()));
	}

	@Test
	@DisplayName("Step 6: the rows of some item codes only, of this point only")
	void readsTheRowsOfSomeCodes() {
		session.beginTransaction();
		HoStockPointItem other = new HoStockPointItem(); // the same item at another point
		other.setStockPointId(POINT + 1);
		other.setItemId(stored("14ZDU05~001").getItemId());
		other.setName("in another point");
		other.setUnitPrice(2.0);
		session.persist(other);
		session.getTransaction().commit();
		session.clear();

		Map<String, Row> read = rows.rows(POINT, Arrays.asList("14ZDU05~001", "NOT-AN-ITEM"));

		assertEquals(1, read.size());
		Row tie = read.get("14ZDU05~001");
		assertEquals("14ZDU05~001", tie.name, "the row of this point");
		assertTrue(tie.clearance);
		assertTrue(rows.rows(POINT, java.util.Collections.emptyList()).isEmpty());
	}

	private Item item(String code) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setUnitPrice(1.0);
		item.setErpExternalId(code);
		session.persist(item);
		return item;
	}

	private void row(Item item, Boolean clearance, BigDecimal clearancePrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(POINT);
		row.setItemId(item.getId());
		row.setName(item.getName());
		row.setUnitPrice(1.0);
		row.setClearance(clearance);
		row.setClearancePrice(clearancePrice);
		session.persist(row);
	}

	private HoStockPointItem stored(String itemCode) {
		return session.createQuery("select r from HoStockPointItem r, Item i where r.itemId = i.id and i.itemCode = :code",
				HoStockPointItem.class).setParameter("code", itemCode).getSingleResult();
	}
}
