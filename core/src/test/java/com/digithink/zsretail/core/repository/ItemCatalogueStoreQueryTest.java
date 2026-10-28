package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
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
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.1, the store's grouped Articles page: the item catalogue list (in core since then, {@link ItemCatalogueQuery},
 * {@link ItemCatalogueService}) over a store's tables only (no ho_ table: a store database has none), the real query on
 * an in-memory H2 database with the applications' naming strategies. A NAV item 43Z of three variants (the second with
 * the barcode 6191234000017, the third inactive) and two items the store made itself, LOCAL-1 and LOCAL-2 (no NAV
 * number): the lines, and with a search the ids of the items found in each line (matchedItemIds).
 */
class ItemCatalogueStoreQueryTest {

	private static SessionFactory sessions;
	private static long v1;
	private static long v2;
	private static long v3;
	private static long local1;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(ItemCategory.class)
				.addAnnotatedClass(ItemSection.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:storecatalogue;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			v1 = item(session, "43Z~001", "43Z", "PANTALON", true, 59.9).getId();
			Item second = item(session, "43Z~002", "43Z", "PANTALON", true, 49.9);
			v2 = second.getId();
			v3 = item(session, "43Z~003", "43Z", "PANTALON", false, 59.9).getId();
			local1 = item(session, "LOCAL-1", null, "Sac maison", true, 12.0).getId();
			item(session, "LOCAL-2", null, "Etiquette", true, null);
			ItemBarcode barcode = new ItemBarcode();
			barcode.setBarcode("6191234000017");
			barcode.setItem(second);
			barcode.setActive(true);
			session.persist(barcode);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code, String navItemNo, String name, boolean active, Double price) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setActive(active);
		item.setUnitPrice(price);
		session.persist(item);
		return item;
	}

	/** The service over one session (Hibernate's Session is the EntityManager), no catégorie or section here. */
	private static <T> T withService(Function<ItemCatalogueService, T> work) {
		try (Session session = sessions.openSession()) {
			ItemCatalogueQuery query = new ItemCatalogueQuery();
			query.portable = true; // H2: the code sort in portable SQL
			Field field = ItemCatalogueQuery.class.getDeclaredField("entityManager");
			field.setAccessible(true);
			field.set(query, session);
			return work.apply(new ItemCatalogueService(query, null, null, TestModes.standalone()));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The lines of a criteria (status All, as the store's Articles page by default). */
	private static List<ItemCatalogueLineDTO> lines(Consumer<ItemCatalogueQuery.Criteria> setup) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setActiveOnly(false);
		setup.accept(criteria);
		return withService(service -> service.page(criteria, 0, 50).getContent());
	}

	private static List<ItemCatalogueLineDTO> searched(String search) {
		return lines(c -> c.setLike(NavItemService.likeOf(search)));
	}

	private static ItemCatalogueLineDTO line(List<ItemCatalogueLineDTO> lines, String code) {
		return lines.stream().filter(line -> code.equals(line.getCode())).findFirst().orElse(null);
	}

	@Test
	@DisplayName("A store's tables only: the NAV item one line with its variants and price range, each local item its own"
			+ " line; no matched ids without a search")
	void lines() {
		List<ItemCatalogueLineDTO> lines = lines(c -> { });
		assertEquals(Arrays.asList("43Z", "LOCAL-1", "LOCAL-2"),
				lines.stream().map(ItemCatalogueLineDTO::getCode).collect(Collectors.toList()));
		ItemCatalogueLineDTO nav = line(lines, "43Z");
		assertEquals(3, nav.getVariants());
		assertEquals(49.9, nav.getPriceMin(), 0.0005);
		assertEquals(59.9, nav.getPriceMax(), 0.0005);
		assertTrue(line(lines, "LOCAL-1").isSingle());
		assertEquals(Long.valueOf(local1), line(lines, "LOCAL-1").getItemId());
		lines.forEach(line -> assertNull(line.getMatchedItemIds(), "no search: null"));
	}

	@Test
	@DisplayName("A barcode, a variant's code, the NAV number or the name: the line, with the ids of its items found")
	void matchedItemIds() {
		assertEquals(Collections.singletonList(v2), line(searched("6191234000017"), "43Z").getMatchedItemIds(),
				"the barcode's variant");
		assertEquals(Collections.singletonList(v3), line(searched("43z~003"), "43Z").getMatchedItemIds(),
				"a variant's code, any case; an inactive variant too");
		assertEquals(Arrays.asList(v1, v2, v3), line(searched("43Z"), "43Z").getMatchedItemIds(), "the NAV number: all");
		List<ItemCatalogueLineDTO> byName = searched("pantal");
		assertEquals(1, byName.size());
		assertEquals(Arrays.asList(v1, v2, v3), byName.get(0).getMatchedItemIds(), "the name: all");
		List<ItemCatalogueLineDTO> local = searched("sac");
		assertEquals(Arrays.asList("LOCAL-1"), local.stream().map(ItemCatalogueLineDTO::getCode).collect(Collectors.toList()));
		assertEquals(Collections.singletonList(local1), local.get(0).getMatchedItemIds(), "a local item: itself");
		assertTrue(searched("nothing-like-it").isEmpty());
	}

	@Test
	@DisplayName("The matched statement: none without a search or without a line; the keys given only")
	void matchedStatement() {
		assertNull(ItemCatalogueQuery.matched(null, Collections.singleton("43Z"), null));
		assertNull(ItemCatalogueQuery.matched("%a%", Collections.emptyList(), Collections.emptyList()));
		ItemCatalogueQuery.Built byNav = ItemCatalogueQuery.matched("%a%", Collections.singleton("43Z"), null);
		assertTrue(byNav.getLines().contains("m.nav_item_no IN (:navItemNos)"));
		assertTrue(!byNav.getLines().contains(":itemIds"));
		assertEquals(Arrays.asList("navItemNos", "search"), Arrays.asList(byNav.getParameters().keySet().toArray()));
	}
}
