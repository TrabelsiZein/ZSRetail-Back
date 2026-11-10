package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemImageRepository;
import com.digithink.zsretail.core.service.CatalogueHeadOfficeHooks;

/**
 * 3.1.1 (item images): JpaHeadOfficeImages run as it is with Hibernate over an in-memory H2 database (no Spring
 * context; ItemImageRepository answered by the same session): every variant of a NAV item gets its URL images, the
 * first main; a variant already right is left as it is; the FILE rows are never touched; an empty list removes the URL
 * rows; a NAV item the head office has not is counted; a dry run counts only; the variants written are recorded for
 * the stores, once each, after the transaction.
 */
class JpaHeadOfficeImagesTest {

	static final String FRONT = "https://images.zen.com.tn/uploads/8_U2_A9282_9d32e2eb62.JPG";
	static final String DETAILS = "https://images.zen.com.tn/uploads/8_U2_A9282_14ea5b40fc.JPG";

	private SessionFactory sessions;
	private Session session;
	private final List<String> recorded = new ArrayList<>();
	private JpaHeadOfficeImages images;

	@BeforeEach
	void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemCategory.class).addAnnotatedClass(ItemSection.class)
				.addAnnotatedClass(ItemImage.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:hoimages;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();
		Item s = save(item("43ZFCHD17~001", "43ZFCHD17"));
		save(item("43ZFCHD17~017", "43ZFCHD17"));
		Item other = save(item("70061~001", "70061"));
		save(item("LOCAL-1", null));
		save(image(other.getId(), ImageSource.FILE, "70061.jpg", 0, true));
		save(image(other.getId(), ImageSource.URL, "https://old/70061.jpg", 0, false));
		save(image(s.getId(), ImageSource.URL, FRONT, 0, true));
		session.getTransaction().commit();

		ItemImageRepository repository = mock(ItemImageRepository.class);
		when(repository.findOfItems(anyCollection())).thenAnswer(call -> session
				.createQuery("select m from ItemImage m where m.itemId in :itemIds" + ItemImageRepository.IMAGE_ORDER,
						ItemImage.class)
				.setParameter("itemIds", call.getArgument(0)).getResultList());
		when(repository.save(any(ItemImage.class))).thenAnswer(call -> {
			session.persist(call.getArgument(0));
			return call.getArgument(0);
		});
		doAnswer(call -> {
			for (Object image : (Iterable<?>) call.getArgument(0)) {
				session.remove(image);
			}
			return null;
		}).when(repository).deleteAll(any());
		TransactionOperations transactions = new TransactionOperations() {
			@Override
			public <T> T execute(TransactionCallback<T> action) {
				session.beginTransaction();
				T result = action.doInTransaction((TransactionStatus) null);
				session.getTransaction().commit();
				return result;
			}
		};
		CatalogueHeadOfficeHooks hooks = mock(CatalogueHeadOfficeHooks.class);
		doAnswer(call -> {
			assertEquals(CatalogueKind.ITEM, call.getArgument(0));
			recorded.addAll(call.getArgument(1));
			return null;
		}).when(hooks).afterImport(any(), anyCollection());
		images = new JpaHeadOfficeImages(session, repository, transactions, () -> hooks);
	}

	@AfterEach
	void close() {
		session.close();
		sessions.close();
	}

	private <T extends _BaseEntity> T save(T entity) {
		session.persist(entity);
		return entity;
	}

	private static Item item(String code, String navItemNo) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setUnitPrice(50.0);
		item.setDefaultVAT(19);
		item.setNavItemNo(navItemNo);
		return item;
	}

	private static ItemImage image(Long itemId, ImageSource source, String path, int sort, boolean main) {
		ItemImage image = new ItemImage();
		image.setItemId(itemId);
		image.setSource(source);
		image.setPath(path);
		image.setSortOrder(sort);
		image.setMain(main);
		return image;
	}

	/** The images of an item as "SOURCE path main sort", in the images' order. */
	private List<String> imagesOf(String itemCode) {
		session.clear();
		Long id = session.createQuery("select i.id from Item i where i.itemCode = :code", Long.class)
				.setParameter("code", itemCode).getSingleResult();
		return session
				.createQuery("select m from ItemImage m where m.itemId = :id" + ItemImageRepository.IMAGE_ORDER,
						ItemImage.class)
				.setParameter("id", id).getResultList().stream()
				.map(m -> m.getSource() + " " + m.getPath() + " " + (m.isMain() ? "main" : "-") + " " + m.getSortOrder())
				.collect(Collectors.toList());
	}

	private static Map<String, List<String>> urls(Object... pairs) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			@SuppressWarnings("unchecked")
			List<String> list = (List<String>) pairs[i + 1];
			map.put((String) pairs[i], list);
		}
		return map;
	}

	@Test
	@DisplayName("navItemNos: every NAV item number once, in order; an item without one left out")
	void navItemNos() {
		assertEquals(Arrays.asList("43ZFCHD17", "70061"), images.navItemNos());
	}

	@Test
	@DisplayName("Every variant gets the images (front main, sort 0; details sort 1); the FILE row kept; recorded once each")
	void applies() {
		HeadOfficeImages.Applied applied = images.apply(
				urls("43ZFCHD17", Arrays.asList(FRONT, DETAILS), "70061", Collections.singletonList(FRONT)), true);
		assertEquals(3, applied.itemsChanged, "the 2 variants of 43ZFCHD17 (one had the front only) and 70061~001");
		assertEquals(0, applied.itemsEqual);
		assertEquals(Arrays.asList("URL " + FRONT + " main 0", "URL " + DETAILS + " - 1"), imagesOf("43ZFCHD17~001"));
		assertEquals(Arrays.asList("URL " + FRONT + " main 0", "URL " + DETAILS + " - 1"), imagesOf("43ZFCHD17~017"));
		assertEquals(Arrays.asList("FILE 70061.jpg main 0", "URL " + FRONT + " main 0"), imagesOf("70061~001"),
				"the FILE row untouched, the old URL row replaced");
		assertEquals(Arrays.asList("43ZFCHD17~001", "43ZFCHD17~017", "70061~001"),
				recorded.stream().sorted().collect(Collectors.toList()));
		assertEquals(Collections.emptyList(), imagesOf("LOCAL-1"));
	}

	@Test
	@DisplayName("The same images again: every variant equal, nothing written, nothing recorded")
	void equal() {
		Map<String, List<String>> wanted = urls("43ZFCHD17", Arrays.asList(FRONT, DETAILS));
		images.apply(wanted, true);
		recorded.clear();
		HeadOfficeImages.Applied again = images.apply(wanted, true);
		assertEquals(0, again.itemsChanged);
		assertEquals(2, again.itemsEqual);
		assertTrue(recorded.isEmpty());
	}

	@Test
	@DisplayName("An empty list removes the URL rows (cleared), the FILE row stays; an unknown NAV item counted")
	void clearsAndCountsUnknown() {
		HeadOfficeImages.Applied applied = images
				.apply(urls("70061", Collections.emptyList(), "99ZZZ", Collections.singletonList(FRONT)), true);
		assertEquals(1, applied.itemsChanged);
		assertEquals(1, applied.itemsCleared);
		assertEquals(Collections.singleton("99ZZZ"), applied.unknown);
		assertEquals(Collections.singletonList("FILE 70061.jpg main 0"), imagesOf("70061~001"));
	}

	@Test
	@DisplayName("A dry run counts what would change and writes nothing, records nothing")
	void dryRun() {
		HeadOfficeImages.Applied applied = images.apply(urls("43ZFCHD17", Arrays.asList(FRONT, DETAILS)), false);
		assertEquals(2, applied.itemsChanged);
		assertEquals(Collections.singletonList("URL " + FRONT + " main 0"), imagesOf("43ZFCHD17~001"));
		assertTrue(recorded.isEmpty());
	}

	@Test
	@DisplayName("same(): the order, the main flag and the sort count")
	void same() {
		List<ItemImage> rows = Arrays.asList(image(1L, ImageSource.URL, FRONT, 0, true),
				image(1L, ImageSource.URL, DETAILS, 1, false));
		assertTrue(JpaHeadOfficeImages.same(rows, Arrays.asList(FRONT, DETAILS)));
		assertTrue(!JpaHeadOfficeImages.same(rows, Arrays.asList(DETAILS, FRONT)));
		assertTrue(!JpaHeadOfficeImages.same(rows, Collections.singletonList(FRONT)));
		Collection<ItemImage> notMain = Arrays.asList(image(1L, ImageSource.URL, FRONT, 0, false));
		assertTrue(!JpaHeadOfficeImages.same(new ArrayList<>(notMain), Collections.singletonList(FRONT)));
	}
}
