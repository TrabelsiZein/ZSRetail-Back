package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.service.ItemVariant;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;

/**
 * Item variants (ZEN), step 3: the items query of JpaHeadOfficeCatalogue, run as it is with Hibernate over an in-memory
 * H2 database (no Spring context): the 14 variant fields and the brand come back with the item (blank as null), and an
 * item without them comes back as before ({@link ItemVariant#NONE}, no brand).
 */
class JpaHeadOfficeCatalogueTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:hocatalogue;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily family = new ItemFamily();
			family.setCode("HOM");
			family.setName("Famille Homme");
			session.persist(family);
			ItemSubFamily subFamily = new ItemSubFamily();
			subFamily.setCode("CHEM");
			subFamily.setName("Sous-Famille Chemise");
			subFamily.setItemFamily(family);
			session.persist(subFamily);

			Item variant = item("70061~001", "CHEMISE");
			variant.setItemFamily(family);
			variant.setItemSubFamily(subFamily);
			variant.setNavItemNo("70061");
			variant.setVariantCode("001");
			variant.setVariantDescription("S / Noir / 1er Choix");
			variant.setSizeCode("S");
			variant.setSizeDescription("");
			variant.setSizeSortOrder(20);
			variant.setColorCode("001");
			variant.setColorDescription("Noir");
			variant.setChoiceCode("01");
			variant.setChoiceDescription("1er Choix");
			variant.setSeasonCode("ANC");
			variant.setDivisionCode("DSZ");
			variant.setCategoryCode("VET");
			variant.setSectionCode("MANCHE");
			variant.setBrand("Marque Zen");
			session.persist(variant);
			session.persist(item("1000", "Bicyclette"));
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(String code, String name) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setUnitPrice(50.0);
		item.setDefaultVAT(19);
		item.setErpExternalId(code);
		return item;
	}

	@Test
	@DisplayName("items(): the variant fields and the brand with the item; an item without them as before")
	void itemsWithVariantFieldsAndBrand() {
		try (Session session = sessions.openSession()) {
			Map<String, HeadOfficeCatalogue.Item> items = new JpaHeadOfficeCatalogue(session, null).items();

			HeadOfficeCatalogue.Item small = items.get("70061~001");
			assertEquals("CHEMISE", small.name);
			assertEquals("HOM", small.familyCode);
			assertEquals("CHEM", small.subFamilyCode);
			assertEquals(new ItemVariant("70061", "001", "S / Noir / 1er Choix", "S", null, 20, "001", "Noir", "01",
					"1er Choix", "ANC", "DSZ", "VET", "MANCHE"), small.variant);
			assertNull(small.variant.sizeDescription, "blank in the table: null");
			assertEquals("Marque Zen", small.brand);

			HeadOfficeCatalogue.Item bike = items.get("1000");
			assertEquals("Bicyclette", bike.name);
			assertEquals(50.0, bike.unitPrice);
			assertEquals(19, bike.defaultVat);
			assertEquals("1000", bike.erpExternalId);
			assertEquals(ItemVariant.NONE, bike.variant);
			assertNull(bike.brand);
		}
	}
}
