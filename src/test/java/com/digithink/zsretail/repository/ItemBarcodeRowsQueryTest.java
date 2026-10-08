package com.digithink.zsretail.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.ItemBarcodeRowDTO;
import com.digithink.zsretail.dto.ItemWithoutBarcodeRowDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.service.ItemBarcodeService;

/**
 * The barcodes page queries of ItemBarcodeRepository (BARCODE_ROWS, WITHOUT_BARCODE and their counts), run as they are on
 * the four catalogue entities with Hibernate over an in-memory H2 database (no Spring context): search by barcode (equal
 * or starting with), by item code or name, family and sub-family filters, items without barcode, TAX_STAMP never.
 */
class ItemBarcodeRowsQueryTest {

	private static SessionFactory sessions;
	private static Long familyA;
	private static Long familyB;
	private static Long subA1;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:barcodes;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily a = family(session, "FA", "Family A");
			ItemFamily b = family(session, "FB", "Family B");
			ItemSubFamily a1 = subFamily(session, "SA1", "Sub A1", a);
			ItemSubFamily b1 = subFamily(session, "SB1", "Sub B1", b);
			Item shampoo = item(session, "000001", "Shampoo Petales", a, a1, true, true);
			Item soap = item(session, "000002", "Soap", b, b1, true, true);
			Item old = item(session, "000003", "Old cream", a, a1, false, true);
			item(session, "000004", "Without barcode", a, a1, true, true);
			item(session, "000005", "Only an inactive barcode", b, b1, true, true);
			Item legacy = item(session, "000006", "Old barcode field", b, b1, true, true);
			legacy.setBarcode("LEGACY-1");
			Item taxStamp = item(session, "TAX_STAMP", "Timbre fiscal", null, null, true, false);
			Item hidden = item(session, "HIDDEN", "Hidden from the till", null, null, true, false);
			barcode(session, "6191234567890", shampoo, true);
			barcode(session, "6191234567891", shampoo, true);
			barcode(session, "3600000000001", soap, true);
			barcode(session, "6190000000000", old, true);
			barcode(session, "50_PERCENT", soap, true);
			barcode(session, "INACTIVE-5", session.get(Item.class, idOf(session, "000005")), false);
			barcode(session, "TS-1", taxStamp, true);
			barcode(session, "619HIDDEN", hidden, true);
			session.getTransaction().commit();
			familyA = a.getId();
			familyB = b.getId();
			subA1 = a1.getId();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String name) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(name);
		session.persist(family);
		return family;
	}

	private static ItemSubFamily subFamily(Session session, String code, String name, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName(name);
		subFamily.setItemFamily(family);
		session.persist(subFamily);
		return subFamily;
	}

	private static Item item(Session session, String code, String name, ItemFamily family, ItemSubFamily subFamily,
			boolean active, boolean showInPos) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(active);
		item.setShowInPos(showInPos);
		session.persist(item);
		return item;
	}

	private static Long idOf(Session session, String code) {
		return session.createQuery("select i.id from Item i where i.itemCode = :code", Long.class)
				.setParameter("code", code).getSingleResult();
	}

	private static void barcode(Session session, String value, Item item, boolean active) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(value);
		barcode.setItem(item);
		barcode.setActive(active);
		session.persist(barcode);
	}

	/** One page of the barcode rows, as the repository runs it (the parameters as ItemBarcodeService builds them). */
	private static List<ItemBarcodeRowDTO> rows(String search, Long familyId, Long subFamilyId, int first, int max) {
		try (Session session = sessions.openSession()) {
			Query<ItemBarcodeRowDTO> query = session.createQuery(ItemBarcodeRepository.BARCODE_ROWS, ItemBarcodeRowDTO.class);
			bind(query, search, familyId, subFamilyId, true);
			return query.setFirstResult(first).setMaxResults(max).getResultList();
		}
	}

	private static long count(String jpql, String search, Long familyId, Long subFamilyId, boolean prefix) {
		try (Session session = sessions.openSession()) {
			Query<Long> query = session.createQuery(jpql, Long.class);
			bind(query, search, familyId, subFamilyId, prefix);
			return query.getSingleResult();
		}
	}

	private static List<ItemWithoutBarcodeRowDTO> withoutBarcode(String search, Long familyId) {
		try (Session session = sessions.openSession()) {
			Query<ItemWithoutBarcodeRowDTO> query = session.createQuery(ItemBarcodeRepository.WITHOUT_BARCODE,
					ItemWithoutBarcodeRowDTO.class);
			bind(query, search, familyId, null, false);
			return query.getResultList();
		}
	}

	private static void bind(Query<?> query, String search, Long familyId, Long subFamilyId, boolean prefix) {
		query.setParameter("familyId", familyId).setParameter("subFamilyId", subFamilyId)
				.setParameter("contains", ItemBarcodeService.containsPattern(search));
		if (prefix) {
			query.setParameter("prefix", ItemBarcodeService.prefixPattern(search));
		}
	}

	private static <T> List<String> values(List<T> rows, Function<T, String> value) {
		return rows.stream().map(value).collect(Collectors.toList());
	}

	@Test
	@DisplayName("No search: every barcode of a listed item, by barcode; TAX_STAMP and items hidden from the till never")
	void allRows() {
		List<ItemBarcodeRowDTO> rows = rows(null, null, null, 0, 50);
		assertEquals(java.util.Arrays.asList("3600000000001", "50_PERCENT", "6190000000000", "6191234567890",
				"6191234567891", "INACTIVE-5"), values(rows, ItemBarcodeRowDTO::getBarcode));
		assertEquals(6, count(ItemBarcodeRepository.BARCODE_ROWS_COUNT, null, null, null, true));
		ItemBarcodeRowDTO first = rows.get(3);
		assertEquals("000001", first.getItemCode());
		assertEquals("Shampoo Petales", first.getItemName());
		assertEquals("Family A", first.getFamilyName());
		assertEquals("Sub A1", first.getSubFamilyName());
		assertEquals(Boolean.TRUE, first.getActive());
		assertEquals(Boolean.TRUE, first.getItemActive());
		ItemBarcodeRowDTO inactiveItem = rows.get(2);
		assertEquals(Boolean.FALSE, inactiveItem.getItemActive());
		assertEquals(Boolean.FALSE, rows.get(5).getActive(), "an inactive barcode is listed, flagged");
		assertEquals(2, rows(null, null, null, 2, 2).size(), "paged");
		assertEquals("6190000000000", rows(null, null, null, 2, 2).get(0).getBarcode());
	}

	@Test
	@DisplayName("Search by barcode: the exact value, or the values starting with it")
	void searchByBarcode() {
		assertEquals(java.util.Arrays.asList("6191234567890"),
				values(rows("6191234567890", null, null, 0, 50), ItemBarcodeRowDTO::getBarcode));
		assertEquals(java.util.Arrays.asList("6190000000000", "6191234567890", "6191234567891"),
				values(rows(" 619 ", null, null, 0, 50), ItemBarcodeRowDTO::getBarcode), "trimmed; TS-1 and 619HIDDEN never");
		assertEquals(3, count(ItemBarcodeRepository.BARCODE_ROWS_COUNT, "619", null, null, true));
		assertTrue(rows("1234567890", null, null, 0, 50).isEmpty(), "a barcode is matched from its start only");
		assertEquals(java.util.Arrays.asList("50_PERCENT"), values(rows("50_", null, null, 0, 50),
				ItemBarcodeRowDTO::getBarcode), "the _ of the search is a character, not a wildcard");
		assertTrue(rows("TS-1", null, null, 0, 50).isEmpty(), "TAX_STAMP never");
	}

	@Test
	@DisplayName("Search by item code or name, any case; family and sub-family filters")
	void searchByItemAndFamily() {
		assertEquals(java.util.Arrays.asList("6191234567890", "6191234567891"),
				values(rows("petales", null, null, 0, 50), ItemBarcodeRowDTO::getBarcode));
		assertEquals(java.util.Arrays.asList("3600000000001", "50_PERCENT"),
				values(rows("000002", null, null, 0, 50), ItemBarcodeRowDTO::getBarcode));
		assertEquals(java.util.Arrays.asList("6190000000000", "6191234567890", "6191234567891"),
				values(rows(null, familyA, null, 0, 50), ItemBarcodeRowDTO::getBarcode));
		assertEquals(3, count(ItemBarcodeRepository.BARCODE_ROWS_COUNT, null, familyA, subA1, true));
		assertEquals(java.util.Arrays.asList("3600000000001", "50_PERCENT", "INACTIVE-5"),
				values(rows(null, familyB, null, 0, 50), ItemBarcodeRowDTO::getBarcode));
		assertTrue(rows("timbre", null, null, 0, 50).isEmpty(), "TAX_STAMP never, by its name either");
	}

	@Test
	@DisplayName("Items without barcode: no active barcode and nothing in the old barcode field; search and family; TAX_STAMP never")
	void itemsWithoutBarcode() {
		assertEquals(java.util.Arrays.asList("000004", "000005"),
				values(withoutBarcode(null, null), ItemWithoutBarcodeRowDTO::getItemCode));
		assertEquals(2, count(ItemBarcodeRepository.WITHOUT_BARCODE_COUNT, null, null, null, false));
		assertEquals(java.util.Arrays.asList("000004"),
				values(withoutBarcode(null, familyA), ItemWithoutBarcodeRowDTO::getItemCode));
		assertEquals(java.util.Arrays.asList("000005"),
				values(withoutBarcode("INACTIVE BARCODE", null), ItemWithoutBarcodeRowDTO::getItemCode));
		ItemWithoutBarcodeRowDTO row = withoutBarcode("000004", null).get(0);
		assertEquals("Without barcode", row.getItemName());
		assertEquals("Family A", row.getFamilyName());
		assertNull(row.getOrigin());
	}
}
