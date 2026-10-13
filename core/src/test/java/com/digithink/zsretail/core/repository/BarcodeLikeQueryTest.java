package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;

/**
 * 3.1.0: the purchase search's first step (ItemBarcodeRepository.findItemIdsByBarcodeLike), its JPQL run as it is with
 * Hibernate over an in-memory H2 database (no Spring context): the items of the active barcodes holding the text, each
 * once, at most the page size.
 */
class BarcodeLikeQueryTest {

	private static SessionFactory sessions;
	private static final List<Long> ids = new ArrayList<>();

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:barcodelike;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item a = item(session, "A");
			barcode(session, a, "6192464102681", true);
			Item b = item(session, "B");
			barcode(session, b, "6192000000017", false); // inactive: not a match
			Item c = item(session, "C");
			barcode(session, c, "6192000000024", true);
			barcode(session, c, "6192000000031", null); // active not set counts as active; C still once
			Item d = item(session, "D");
			barcode(session, d, "ABC-6192-X", true);
			session.getTransaction().commit();
			ids.addAll(Arrays.asList(a.getId(), b.getId(), c.getId(), d.getId()));
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		session.persist(item);
		return item;
	}

	private static void barcode(Session session, Item item, String value, Boolean active) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setItem(item);
		barcode.setBarcode(value);
		barcode.setActive(active);
		session.persist(barcode);
	}

	private static List<Long> run(String pattern, int max) throws Exception {
		String jpql = ItemBarcodeRepository.class.getMethod("findItemIdsByBarcodeLike", String.class, Pageable.class)
				.getAnnotation(Query.class).value();
		try (Session session = sessions.openSession()) {
			List<Long> found = new ArrayList<>(session.createQuery(jpql, Long.class).setParameter("pattern", pattern)
					.setMaxResults(max).getResultList());
			Collections.sort(found);
			return found;
		}
	}

	@Test
	@DisplayName("A part of a barcode (lower-case pattern): the items of the active barcodes holding it, each once")
	void partOfBarcode() throws Exception {
		assertEquals(Arrays.asList(ids.get(0)), run("%46410%", 1000));
		assertEquals(Arrays.asList(ids.get(0), ids.get(2), ids.get(3)), run("%6192%", 1000));
		assertEquals(Arrays.asList(ids.get(3)), run("%abc-%", 1000), "compared in lower case");
		assertEquals(Collections.emptyList(), run("%0000017%", 1000), "an inactive barcode is not a match");
	}

	@Test
	@DisplayName("At most the page size")
	void limit() throws Exception {
		assertEquals(2, run("%6192%", 2).size());
	}
}
