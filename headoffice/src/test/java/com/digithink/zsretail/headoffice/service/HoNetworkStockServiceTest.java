package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoNetworkStockQuery;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * Network stock (3.1.0), step 2: HoNetworkStockService.page / ownItems through the real repositories and query, Hibernate
 * over an in-memory H2 database (no Spring context): the sort asked for as the query reads it (a store not shown and an
 * unknown value by code), the total of the stores shown, itemId and navItemNo for the item card, the own items' values.
 * <p>
 * Items: N~1 (NAV item N; head office 1), P1 (2), Q1 (0). Stores S1, S2 active, S3 inactive. Stock: N~1 2 at S1, 3 at S2,
 * 50 at S3; P1 -1 at S1; Q1 nowhere. Own item OWN1 at S2: 2.500, read 2026-10-20 09:30.
 */
class HoNetworkStockServiceTest {

	private static SessionFactory sessions;
	private static long s1;
	private static long s3;

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
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:networkstockservice;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			item(session, "N~1", "N", "1");
			item(session, "P1", null, "2");
			item(session, "Q1", null, "0");
			s1 = store(session, "S1", true);
			long s2 = store(session, "S2", true);
			s3 = store(session, "S3", false);
			stock(session, s1, "N~1", "2", false);
			stock(session, s2, "N~1", "3", false);
			stock(session, s3, "N~1", "50", false);
			stock(session, s1, "P1", "-1", false);
			stock(session, s2, "OWN1", "2.500", true);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void item(Session session, String code, String navItemNo, String stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName("Article " + code);
		item.setType(ItemType.PRODUCT);
		item.setNavItemNo(navItemNo);
		item.setStockQuantity(new BigDecimal(stock));
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

	private static void stock(Session session, long storeId, String itemCode, String quantity, boolean own) {
		HoStoreStock row = new HoStoreStock();
		row.setStoreId(storeId);
		row.setItemCode(itemCode);
		row.setItemName("Own " + itemCode);
		row.setQuantity(new BigDecimal(quantity));
		row.setOwnItem(own);
		row.setStoreTime(LocalDateTime.of(2026, 10, 20, 9, 30));
		row.setReceivedAt(LocalDateTime.of(2026, 10, 20, 9, 31));
		session.persist(row);
	}

	private static <T> T withService(Function<HoNetworkStockService, T> work) {
		try (Session session = sessions.openSession()) {
			JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
			HoNetworkStockService service = new HoNetworkStockService(
					repositories.getRepository(HoStoreStockRepository.class),
					repositories.getRepository(StoreRepository.class), new HoNetworkStockQuery(session),
					TransactionOperations.withoutTransaction(), LocalDateTime::now);
			return work.apply(service);
		}
	}

	private static Map<String, Object> page(Long storeId, String sort, boolean descending) {
		return withService(service -> service.page(storeId, null, 0, 20, new HoNetworkStockQuery.Criteria(), sort, descending));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("content");
	}

	private static List<Object> codes(Map<String, Object> page) {
		return rows(page).stream().map(row -> row.get("itemCode")).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Every active store: withTotal, the total of the stores shown (S3 inactive left out, none sent: null), itemId and navItemNo")
	void totalsAndCardKeys() {
		Map<String, Object> page = page(null, null, false);
		assertEquals(Boolean.TRUE, page.get("withTotal"));
		List<Map<String, Object>> rows = rows(page);
		assertEquals(Arrays.asList("N~1", "P1", "Q1"), codes(page));
		assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) rows.get(0).get("totalStores")));
		assertEquals("N", rows.get(0).get("navItemNo"));
		assertTrue(rows.get(0).get("itemId") instanceof Long);
		assertEquals(0, new BigDecimal("-1").compareTo((BigDecimal) rows.get(1).get("totalStores")));
		assertNull(rows.get(1).get("navItemNo"), "an item without NAV number: the card by its id");
		assertTrue(rows.get(2).containsKey("totalStores"));
		assertNull(rows.get(2).get("totalStores"), "no store sent Q1");
	}

	@Test
	@DisplayName("One store shown: no total")
	void oneStoreNoTotal() {
		Map<String, Object> page = page(s1, null, false);
		assertEquals(Boolean.FALSE, page.get("withTotal"));
		rows(page).forEach(row -> assertFalse(row.containsKey("totalStores")));
		assertEquals(Arrays.asList("Q1", "P1", "N~1"), codes(page(s1, "total", true)),
				"total without the column: by code, in the direction asked");
	}

	@Test
	@DisplayName("Sorts as asked: a store shown, the total, descending; a store not shown or an unknown value by code")
	void sorts() {
		assertEquals(Arrays.asList("P1", "N~1", "Q1"), codes(page(null, "store-" + s1, false)), "Q1 never sent: last");
		assertEquals(Arrays.asList("N~1", "P1", "Q1"), codes(page(null, "store-" + s1, true)));
		assertEquals(Arrays.asList("N~1", "P1", "Q1"), codes(page(null, "total", true)));
		assertEquals(Arrays.asList("P1", "N~1", "Q1"), codes(page(null, "total", false)));
		assertEquals(Arrays.asList("Q1", "P1", "N~1"), codes(page(null, "store-" + s3, true)),
				"S3 not shown (inactive), as from an old link: by code, in the direction asked");
		assertEquals(Arrays.asList("N~1", "P1", "Q1"), codes(page(null, "store-abc", false)), "an unknown value: by code");
	}

	@Test
	@DisplayName("Own items: the same values as before (store from the join, decimals without trailing zeros, the store time)")
	void ownItems() {
		Map<String, Object> page = withService(service -> service.ownItems(null, null, 0, 20, null, null, false));
		List<Map<String, Object>> rows = rows(page);
		assertEquals(1, rows.size());
		Map<String, Object> row = rows.get(0);
		assertEquals("S2", row.get("storeCode"));
		assertEquals("Magasin S2", row.get("storeName"));
		assertTrue(row.get("storeId") instanceof Long);
		assertEquals("OWN1", row.get("itemCode"));
		assertEquals("2.5", ((BigDecimal) row.get("quantity")).toPlainString());
		assertEquals("2026-10-20T09:30", row.get("storeTime"));
		assertEquals("2026-10-20T09:31", row.get("receivedAt"));
		assertEquals(1L, page.get("totalElements"));
		assertEquals(0, rows(withService(service -> service.ownItems(null, null, 0, 20, "BELOW_ZERO", null, false))).size());
	}
}
