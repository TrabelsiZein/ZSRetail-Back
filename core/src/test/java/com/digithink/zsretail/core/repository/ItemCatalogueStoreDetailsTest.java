package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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

import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.support.TestModes;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.2, the store's Articles page on every store: what its lines show beyond the grouped columns
 * ({@link ItemCatalogueService#storeDetails}, the query {@link ItemCatalogueQuery#details}) and the stock sort, the real
 * statements on an in-memory H2 database with the applications' naming strategies. A NAV item 50A of three variants
 * (costs 7.5, 9.25 and none), a head office item with the store's own price, two items in clearance (one sold below its
 * public price, one not), an item with a minimum stock and a maximum discount, and one at a negative stock.
 */
class ItemCatalogueStoreDetailsTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(ItemCategory.class)
				.addAnnotatedClass(ItemSection.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:storedetails;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			item(session, "50A~1", "50A", it -> {
				it.setCostPrice(7.5);
				it.setStockQuantity(new BigDecimal("4"));
			});
			item(session, "50A~2", "50A", it -> it.setCostPrice(9.25));
			item(session, "50A~3", "50A", it -> { });
			item(session, "HO-OWN", null, it -> {
				it.setOrigin(RecordOrigin.HEAD_OFFICE);
				it.setOwnPrice(true);
				it.setUnitPrice(10.0);
				it.setHeadOfficePrice(9.0);
				it.setCostPrice(6.125);
			});
			item(session, "CLEAR", null, it -> {
				it.setClearance(true);
				it.setUnitPrice(15.0);
				it.setPublicPrice(20.0);
			});
			item(session, "CLEAR-SAME", null, it -> {
				it.setClearance(true);
				it.setUnitPrice(15.0);
				it.setPublicPrice(15.0);
			});
			item(session, "LOW", null, it -> {
				it.setOrigin(RecordOrigin.LOCAL);
				it.setMinStockLevel(5);
				it.setStockQuantity(new BigDecimal("2"));
				it.setMaximumAuthorizedDiscount(10.0);
			});
			item(session, "NEG", null, it -> it.setStockQuantity(new BigDecimal("-3")));
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	/** An item of 10 DT before a VAT of 19 %, active, unless the setup says otherwise. */
	private static void item(Session session, String code, String navItemNo, Consumer<Item> setup) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(code);
		item.setActive(true);
		item.setUnitPrice(10.0);
		item.setDefaultVAT(19);
		setup.accept(item);
		session.persist(item);
	}

	private static <T> T withService(Function<ItemCatalogueService, T> work) {
		try (Session session = sessions.openSession()) {
			ItemCatalogueQuery query = new ItemCatalogueQuery();
			query.portable = true;
			Field field = ItemCatalogueQuery.class.getDeclaredField("entityManager");
			field.setAccessible(true);
			field.set(query, session);
			return work.apply(new ItemCatalogueService(query, null, null, TestModes.standalone()));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Every line (status All) with the store's details, with or without the cost. */
	private static List<ItemCatalogueLineDTO> lines(boolean withCost, Consumer<ItemCatalogueQuery.Criteria> setup) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setActiveOnly(false);
		setup.accept(criteria);
		return withService(service -> {
			List<ItemCatalogueLineDTO> content = service.page(criteria, 0, 50).getContent();
			service.storeDetails(content, withCost);
			return content;
		});
	}

	private static ItemCatalogueLineDTO line(List<ItemCatalogueLineDTO> lines, String code) {
		return lines.stream().filter(line -> code.equals(line.getCode())).findFirst().orElse(null);
	}

	@Test
	@DisplayName("A single item: its origin, price before VAT and VAT %, its own price with the head office's price with VAT")
	void singleItem() {
		ItemCatalogueLineDTO own = line(lines(false, c -> { }), "HO-OWN");
		assertEquals("HEAD_OFFICE", own.getOrigin());
		assertEquals(10.0, own.getUnitPrice(), 0.0);
		assertEquals(Integer.valueOf(19), own.getVatPercent());
		assertEquals(Boolean.TRUE, own.getOwnPrice());
		assertEquals(10.71, own.getHeadOfficePrice(), 0.0005, "9 x 1.19");
		ItemCatalogueLineDTO low = line(lines(false, c -> { }), "LOW");
		assertEquals("LOCAL", low.getOrigin());
		assertNull(low.getOwnPrice(), "no own price: left out");
		assertNull(low.getHeadOfficePrice());
		assertEquals(Integer.valueOf(5), low.getMinStockLevel());
		assertEquals(10.0, low.getMaximumAuthorizedDiscount(), 0.0);
		assertNull(line(lines(false, c -> { }), "NEG").getOrigin(), "no origin: null");
	}

	@Test
	@DisplayName("Clearance: the public price with VAT only when the item is sold below it (the till's rule)")
	void clearance() {
		List<ItemCatalogueLineDTO> lines = lines(false, c -> { });
		assertEquals(23.8, line(lines, "CLEAR").getPublicPrice(), 0.0005, "20 x 1.19");
		assertNull(line(lines, "CLEAR-SAME").getPublicPrice(), "sold at its public price: nothing struck");
		assertNull(line(lines, "LOW").getPublicPrice());
	}

	@Test
	@DisplayName("The cost (CMP, before VAT): a single item's, a NAV item's smallest and largest; none without withCost")
	void cost() {
		List<ItemCatalogueLineDTO> lines = lines(true, c -> { });
		ItemCatalogueLineDTO nav = line(lines, "50A");
		assertEquals(7.5, nav.getCostMin(), 0.0);
		assertEquals(9.25, nav.getCostMax(), 0.0);
		assertNull(nav.getOrigin(), "a NAV line has no single item's fields");
		assertNull(nav.getUnitPrice());
		assertEquals(6.125, line(lines, "HO-OWN").getCostMin(), 0.0);
		assertEquals(6.125, line(lines, "HO-OWN").getCostMax(), 0.0);
		assertNull(line(lines, "LOW").getCostMin(), "no cost: null");
		lines(false, c -> { }).forEach(line -> {
			assertNull(line.getCostMin(), line.getCode());
			assertNull(line.getCostMax(), line.getCode());
		});
	}

	@Test
	@DisplayName("Sort by stock: the line's stock (a NAV item's sum, none read as 0), then by code; descending too")
	void stockSort() {
		List<String> ascending = lines(false, c -> c.setSort("stock")).stream().map(ItemCatalogueLineDTO::getCode)
				.collect(Collectors.toList());
		assertEquals("NEG", ascending.get(0));
		assertEquals(Arrays.asList("LOW", "50A"), ascending.subList(ascending.size() - 2, ascending.size()));
		List<String> descending = lines(false, c -> {
			c.setSort("stock");
			c.setDescending(true);
		}).stream().map(ItemCatalogueLineDTO::getCode).collect(Collectors.toList());
		assertEquals(Arrays.asList("50A", "LOW"), descending.subList(0, 2));
		assertEquals("NEG", descending.get(descending.size() - 1));
	}

	@Test
	@DisplayName("JSON: a line without the store's details (the head office's) carries none of their keys")
	void headOfficeJsonUnchanged() throws Exception {
		String json = new ObjectMapper().writeValueAsString(line(withService(service -> {
			ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
			criteria.setActiveOnly(false);
			return service.page(criteria, 0, 50).getContent();
		}), "HO-OWN"));
		for (String key : new String[] { "origin", "ownPrice", "headOfficePrice", "unitPrice", "vatPercent", "publicPrice",
				"minStockLevel", "maximumAuthorizedDiscount", "costMin", "costMax" }) {
			assertFalse(json.contains("\"" + key + "\""), key + " in " + json);
		}
		assertTrue(json.contains("\"priceMin\""), json);
	}

	@Test
	@DisplayName("No line: no statement")
	void noLine() {
		assertNull(ItemCatalogueQuery.details(null, null));
		assertEquals(0, withService(service -> {
			List<ItemCatalogueLineDTO> none = new java.util.ArrayList<>();
			service.storeDetails(none, true);
			return none.size();
		}).intValue());
	}
}
