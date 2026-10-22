package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.model.Store;

/**
 * Network stock (3.1.0), step 1: the native statements of the page's head office items (HoNetworkStockQuery.build), run
 * as they are with Hibernate over an in-memory H2 database (no Spring context), with the applications' naming strategies.
 * <p>
 * Items (head office stock): A001 (HOM / CHEM, division D1, catégorie VET, section S1; 5), A002 (FEM; 0), A003 (a pack;
 * -1), A004 (HOM; none), A005 (0); SRV1 (a service) and TAX_STAMP are never rows. Stores ST1 and ST2 active, ST3
 * inactive. Store stock: A001 0 and A002 3 at ST1, A004 -2 at ST2 and 7 at ST3.
 */
class NetworkStockQueryTest {

	private static SessionFactory sessions;
	private static long hom;
	private static long chem;
	private static long st1;
	private static long st3;

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
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:networkstock;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily homme = new ItemFamily();
			homme.setCode("HOM");
			homme.setName("Homme");
			session.persist(homme);
			ItemFamily femme = new ItemFamily();
			femme.setCode("FEM");
			femme.setName("Femme");
			session.persist(femme);
			ItemSubFamily chemises = new ItemSubFamily();
			chemises.setCode("CHEM");
			chemises.setName("Chemises");
			chemises.setItemFamily(homme);
			session.persist(chemises);
			hom = homme.getId();
			chem = chemises.getId();
			Item a001 = item(session, "A001", "Chemise lin", ItemType.PRODUCT, homme, "5");
			a001.setItemSubFamily(chemises);
			a001.setDivisionCode("D1");
			a001.setCategoryCode("VET");
			a001.setSectionCode("S1");
			item(session, "A002", "Robe", ItemType.PRODUCT, femme, "0");
			item(session, "A003", "Pack cadeau", ItemType.PACKAGE, null, "-1");
			item(session, "A004", "Chemise coton", ItemType.PRODUCT, homme, null);
			item(session, "A005", "Ceinture", null, null, "0");
			item(session, "SRV1", "Retouche", ItemType.SERVICE, null, "0");
			item(session, "TAX_STAMP", "Timbre", ItemType.PRODUCT, null, "0");
			st1 = store(session, "ST1", true);
			long st2 = store(session, "ST2", true);
			st3 = store(session, "ST3", false);
			stock(session, st1, "A001", "0");
			stock(session, st1, "A002", "3");
			stock(session, st2, "A004", "-2");
			stock(session, st3, "A004", "7");
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code, String name, ItemType type, ItemFamily family, String stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setType(type);
		item.setItemFamily(family);
		item.setStockQuantity(stock == null ? null : new BigDecimal(stock));
		session.persist(item);
		return item;
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

	private static void stock(Session session, long storeId, String itemCode, String quantity) {
		HoStoreStock row = new HoStoreStock();
		row.setStoreId(storeId);
		row.setItemCode(itemCode);
		row.setQuantity(new BigDecimal(quantity));
		session.persist(row);
	}

	/** The codes of the rows of a criteria (every active store, the head office column), checked against its count. */
	private static List<String> codes(Consumer<HoNetworkStockQuery.Criteria> set) {
		HoNetworkStockQuery.Criteria criteria = new HoNetworkStockQuery.Criteria();
		set.accept(criteria);
		HoNetworkStockQuery.Built built = HoNetworkStockQuery.build(criteria, true);
		try (Session session = sessions.openSession()) {
			NativeQuery<?> lines = session.createNativeQuery(built.getLines());
			NativeQuery<?> count = session.createNativeQuery(built.getCount());
			built.getParameters().forEach((name, value) -> {
				lines.setParameter(name, value);
				count.setParameter(name, value);
			});
			List<String> codes = lines.list().stream().map(row -> (String) ((Object[]) row)[HoNetworkStockQuery.COL_ITEM_CODE])
					.collect(Collectors.toList());
			assertEquals(codes.size(), ((Number) count.uniqueResult()).intValue(), "the count of " + codes);
			return codes;
		}
	}

	@Test
	@DisplayName("No filter: the products and packs (or no type) by code, not a service nor the tax stamp")
	void everyItem() {
		assertEquals(Arrays.asList("A001", "A002", "A003", "A004", "A005"), codes(c -> { }));
		assertEquals(Arrays.asList("A001", "A004"), codes(c -> c.setLike("%chemise%")), "search on the name, any case");
		assertEquals(Arrays.asList("A003"), codes(c -> c.setLike("%a003%")), "search on the code");
	}

	@Test
	@DisplayName("Item filters: family, sub-family, division, catégorie, section")
	void itemFilters() {
		assertEquals(Arrays.asList("A001", "A004"), codes(c -> c.setFamilyId(hom)));
		assertEquals(Arrays.asList("A001"), codes(c -> c.setSubFamilyId(chem)));
		assertEquals(Arrays.asList("A001"), codes(c -> c.setDivisionCode(" D1 ")));
		assertEquals(Arrays.asList("A001"), codes(c -> c.setCategoryCode("VET")));
		assertEquals(Arrays.asList("A001"), codes(c -> c.setSectionCode("S1")));
		assertEquals(Collections.emptyList(), codes(c -> { c.setFamilyId(hom); c.setSectionCode("S9"); }));
	}

	@Test
	@DisplayName("Stock on the columns shown (head office and every active store): in stock, zero, below zero")
	void stockEveryStore() {
		assertEquals(Arrays.asList("A001", "A002"), codes(c -> c.setStock(HoNetworkStockQuery.IN_STOCK)),
				"A001 at the head office, A002 at ST1; A004's 7 at the inactive ST3 not shown");
		assertEquals(Arrays.asList("A003", "A004"), codes(c -> c.setStock(HoNetworkStockQuery.BELOW_ZERO)));
		assertEquals(Arrays.asList("A005"), codes(c -> c.setStock(HoNetworkStockQuery.ZERO)));
		assertEquals(Arrays.asList("A001", "A002", "A003", "A004", "A005"), codes(c -> c.setStock("ALL")));
	}

	@Test
	@DisplayName("Stock with one store shown: that store and the head office; a store without the item counts as 0")
	void stockOneStore() {
		assertEquals(Arrays.asList("A004", "A005"), codes(c -> { c.setStoreId(st1); c.setStock(HoNetworkStockQuery.ZERO); }),
				"A004: none at the head office, none at ST1");
		assertEquals(Arrays.asList("A001", "A004"), codes(c -> { c.setStoreId(st3); c.setStock(HoNetworkStockQuery.IN_STOCK); }),
				"an inactive store asked for is shown");
		assertEquals(Arrays.asList("A003"), codes(c -> { c.setStoreId(st1); c.setStock(HoNetworkStockQuery.BELOW_ZERO); }));
	}

	@Test
	@DisplayName("A head office without stock: the stock filter looks at the stores only")
	void storesOnly() {
		assertEquals(Arrays.asList("A002"), codes(c -> { c.setHeadOfficeColumn(false); c.setStock(HoNetworkStockQuery.IN_STOCK); }));
		assertEquals(Arrays.asList("A004"), codes(c -> { c.setHeadOfficeColumn(false); c.setStock(HoNetworkStockQuery.BELOW_ZERO); }));
		assertEquals(Arrays.asList("A001", "A003", "A005"),
				codes(c -> { c.setHeadOfficeColumn(false); c.setStock(HoNetworkStockQuery.ZERO); }), "A001's 0 at ST1 counts as zero");
	}

	@Test
	@DisplayName("Every column of the statements has its own alias (SQL Server)")
	void columnAliases() {
		HoNetworkStockQuery.Criteria every = new HoNetworkStockQuery.Criteria();
		every.setLike("%a%");
		every.setFamilyId(1L);
		every.setSubFamilyId(1L);
		every.setDivisionCode("D");
		every.setCategoryCode("C");
		every.setSectionCode("S");
		every.setStock(HoNetworkStockQuery.ZERO);
		HoNetworkStockQuery.Built built = HoNetworkStockQuery.build(every, true);
		assertEquals(Arrays.asList("item_code", "item_name", "stock_quantity", "item_id", "nav_item_no"),
				ItemCatalogueQueryTest.columnLabels(sessions, built.getLines(), built.getParameters()));
		assertEquals(Arrays.asList(HoNetworkStockQuery.COUNT_COLUMN),
				ItemCatalogueQueryTest.columnLabels(sessions, built.getCount(), built.getParameters()));
	}
}
