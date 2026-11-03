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

import com.digithink.zsretail.core.dto.ItemExportDTO;
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
 * 3.1.2, the Excel export of the store's Articles page: {@link ItemCatalogueQuery#items} through
 * {@link ItemCatalogueService#export}, the real query on an in-memory H2 database. A NAV item 43Z of three variants
 * (the second with the primary barcode 6191234000017 and a stock of 4, the third inactive), LOCAL-1 (family Accessoires,
 * catégorie CAT1, 20 % VAT, in clearance), LOCAL-2 and LOCAL-3 (inactive).
 * <ul>
 * <li>the items of the lines the list shows, one row each, the list's code order;</li>
 * <li>the list's filters: status, search (a barcode gives the whole NAV line), family, variants yes / no;</li>
 * <li>the item's fields: barcode, names, the price with VAT, the clearance price; the stock and the cost only when given;
 * the limit.</li>
 * </ul>
 */
class ItemCatalogueExportQueryTest {

	private static SessionFactory sessions;
	private static ItemFamily accessoires;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(ItemCategory.class)
				.addAnnotatedClass(ItemSection.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:catalogueexport;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			accessoires = new ItemFamily();
			accessoires.setCode("ACC");
			accessoires.setName("Accessoires");
			session.persist(accessoires);
			ItemCategory femme = new ItemCategory();
			femme.setCode("CAT1");
			femme.setName("Femme");
			session.persist(femme);
			item(session, "43Z~001", "43Z", "PANTALON", true, 59.9).setVariantDescription("38 NOIR");
			Item second = item(session, "43Z~002", "43Z", "PANTALON", true, 49.9);
			second.setVariantDescription("40 NOIR");
			second.setStockQuantity(new BigDecimal("4"));
			second.setCostPrice(21.5);
			item(session, "43Z~003", "43Z", "PANTALON", false, 59.9);
			Item local = item(session, "LOCAL-1", null, "Sac maison", true, 10.0);
			local.setItemFamily(accessoires);
			local.setCategoryCode("CAT1");
			local.setDefaultVAT(20);
			local.setBarcode("2000000000011");
			local.setClearance(true);
			local.setPublicPrice(15.0);
			item(session, "LOCAL-2", null, "Etiquette", true, null);
			item(session, "LOCAL-3", null, "Ancien sac", false, 8.0);
			ItemBarcode other = barcode(session, second, "6191234000099", false);
			ItemBarcode primary = barcode(session, second, "6191234000017", true);
			assertTrue(other.getId() < primary.getId());
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

	private static ItemBarcode barcode(Session session, Item item, String code, boolean primary) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(code);
		barcode.setItem(item);
		barcode.setActive(true);
		barcode.setIsPrimary(primary);
		session.persist(barcode);
		return barcode;
	}

	/** The export of a criteria (status All by default, as the Articles page). */
	private static ItemExportDTO export(Consumer<ItemCatalogueQuery.Criteria> setup, boolean withCost, int limit) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setActiveOnly(false);
		setup.accept(criteria);
		try (Session session = sessions.openSession()) {
			ItemCatalogueQuery query = new ItemCatalogueQuery();
			query.portable = true;
			Field field = ItemCatalogueQuery.class.getDeclaredField("entityManager");
			field.setAccessible(true);
			field.set(query, session);
			return new ItemCatalogueService(query, null, null, TestModes.standalone()).export(criteria, withCost, limit);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> codes(Consumer<ItemCatalogueQuery.Criteria> setup) {
		return export(setup, false, 100).getRows().stream().map(ItemExportDTO.Row::getItemCode)
				.collect(Collectors.toList());
	}

	private static ItemExportDTO.Row row(ItemExportDTO answer, String code) {
		return answer.getRows().stream().filter(row -> code.equals(row.getItemCode())).findFirst().orElse(null);
	}

	@Test
	@DisplayName("Every item of the listed lines, one row each, in the list's code order; status Active, Inactive")
	void rowsAndStatus() {
		assertEquals(Arrays.asList("43Z~001", "43Z~002", "43Z~003", "LOCAL-1", "LOCAL-2", "LOCAL-3"), codes(c -> { }));
		assertEquals(Arrays.asList("43Z~001", "43Z~002", "LOCAL-1", "LOCAL-2"), codes(c -> c.setActiveOnly(true)));
		assertEquals(Arrays.asList("LOCAL-3"), codes(c -> c.setInactiveOnly(true)),
				"Inactive: the lines without an active variant only");
	}

	@Test
	@DisplayName("The list's filters: a barcode gives its NAV line's variants, the family, variants yes / no, the price")
	void filtersOfTheList() {
		assertEquals(Arrays.asList("43Z~001", "43Z~002", "43Z~003"),
				codes(c -> c.setLike(NavItemService.likeOf("6191234000017"))));
		assertEquals(Arrays.asList("LOCAL-1"), codes(c -> c.setFamilyId(accessoires.getId())));
		assertEquals(Arrays.asList("43Z~001", "43Z~002", "43Z~003"), codes(c -> c.setVariants("YES")));
		assertEquals(Arrays.asList("LOCAL-1", "LOCAL-2", "LOCAL-3"), codes(c -> c.setVariants("NO")));
		assertEquals(Arrays.asList("LOCAL-1", "LOCAL-3"), codes(c -> c.setPriceMax(13.0)));
	}

	@Test
	@DisplayName("The item's fields: primary barcode, else its own; names; price with VAT; clearance; stock; no cost"
			+ " without the right")
	void fields() {
		ItemExportDTO answer = export(c -> { }, false, 100);
		assertTrue(answer.isWithStock());
		assertFalse(answer.isTruncated());
		ItemExportDTO.Row variant = row(answer, "43Z~002");
		assertEquals("43Z", variant.getNavItemNo());
		assertEquals("40 NOIR", variant.getVariantDescription());
		assertEquals("6191234000017", variant.getBarcode());
		assertEquals(0, new BigDecimal("4").compareTo(variant.getStockQuantity()));
		assertEquals(49.9, variant.getPrice(), 0.0005);
		assertNull(variant.getCost(), "no cost without the right");
		assertTrue(variant.isActive());
		assertFalse(row(answer, "43Z~003").isActive());
		ItemExportDTO.Row local = row(answer, "LOCAL-1");
		assertEquals("2000000000011", local.getBarcode());
		assertEquals("Accessoires", local.getFamilyName());
		assertEquals("CAT1", local.getCategoryCode());
		assertEquals("Femme", local.getCategoryName());
		assertEquals(Integer.valueOf(20), local.getVatPercent());
		assertEquals(12.0, local.getPrice(), 0.0005);
		assertEquals(18.0, local.getPublicPrice(), 0.0005);
		assertNull(row(answer, "LOCAL-2").getPrice());
		assertNull(row(answer, "LOCAL-2").getBarcode());
	}

	@Test
	@DisplayName("The cost with the right; the list's sort by stock; the limit truncates")
	void costSortLimit() {
		ItemExportDTO withCost = export(c -> { }, true, 100);
		assertEquals(21.5, row(withCost, "43Z~002").getCost(), 0.0005);
		ItemExportDTO byStock = export(c -> {
			c.setSort("stock");
			c.setDescending(true);
		}, false, 100);
		assertEquals("43Z~002", byStock.getRows().get(0).getItemCode());
		ItemExportDTO limited = export(c -> { }, false, 2);
		assertTrue(limited.isTruncated());
		assertEquals(Arrays.asList("43Z~001", "43Z~002"),
				limited.getRows().stream().map(ItemExportDTO.Row::getItemCode).collect(Collectors.toList()));
	}
}
