package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.QueryUtils;

import com.digithink.zsretail.core.service.ItemBarcodeService;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.HoStoreOrderColis;
import com.digithink.zsretail.headoffice.model.HoStoreOrderColisDetail;
import com.digithink.zsretail.headoffice.model.HoStoreOrderLine;

/**
 * 3.1.1, the head office's list of the stores' orders (HoStoreOrderRepository.findPage and its count), run as they are
 * with Hibernate over an in-memory H2 database (no Spring context), the parameters bound with the types of the method
 * (anyStore and anyStatus long: an int literal in the statement made Hibernate refuse them, the 500 of 2026-10-27), with
 * and without each filter, sorted as HoStoreOrderService asks (QueryUtils, as Spring Data appends the page's sort).
 * Orders: A (store 1, SENT, received 10-20), B (store 1, POSTED, 10-25), C (store 2, ERROR, 10-26).
 */
class HoStoreOrderPageQueryTest {

	private static SessionFactory sessions;
	private static String page;
	private static String count;

	@BeforeAll
	static void database() throws Exception {
		Method findPage = HoStoreOrderRepository.class.getMethod("findPage", long.class, Long.class, long.class,
				HoStoreOrderStatus.class, LocalDateTime.class, LocalDateTime.class, long.class, String.class, Pageable.class);
		org.springframework.data.jpa.repository.Query query = findPage
				.getAnnotation(org.springframework.data.jpa.repository.Query.class);
		page = query.value();
		count = query.countQuery();
		sessions = new Configuration().addAnnotatedClass(HoStoreOrder.class).addAnnotatedClass(HoStoreOrderLine.class)
				.addAnnotatedClass(HoStoreOrderColis.class)
				.addAnnotatedClass(HoStoreOrderColisDetail.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:hostoreorders;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			HoStoreOrder a = order(session, "A", 1L, HoStoreOrderStatus.SENT, LocalDateTime.of(2026, 10, 20, 9, 0));
			line(session, a, "70061~S", "70061", "Chemise 50% lin");
			HoStoreOrder b = order(session, "B", 1L, HoStoreOrderStatus.POSTED, LocalDateTime.of(2026, 10, 25, 9, 0));
			b.setNavOrderNo("CV26000004");
			line(session, b, "BOUGIE", null, "Bougie");
			order(session, "C", 2L, HoStoreOrderStatus.ERROR, LocalDateTime.of(2026, 10, 26, 9, 0));
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static HoStoreOrder order(Session session, String number, Long storeId, HoStoreOrderStatus status,
			LocalDateTime receivedAt) {
		HoStoreOrder order = new HoStoreOrder();
		order.setNumber(number);
		order.setStoreId(storeId);
		order.setStatus(status);
		order.setReceivedAt(receivedAt);
		order.setTotalQty(BigDecimal.ONE);
		session.persist(order);
		return order;
	}

	private static void line(Session session, HoStoreOrder order, String itemCode, String navItemNo, String description) {
		HoStoreOrderLine line = new HoStoreOrderLine();
		line.setOrder(order);
		line.setItemCode(itemCode);
		line.setNavItemNo(navItemNo);
		line.setDescription(description);
		line.setQuantity(BigDecimal.ONE);
		session.persist(line);
	}

	/** The numbers of a page and the count, the parameters bound as HoStoreOrderService.list binds them. */
	private static String run(Long storeId, HoStoreOrderStatus status, LocalDateTime from, LocalDateTime to, Sort sort) {
		return run(storeId, status, from, to, null, sort);
	}

	private static String run(Long storeId, HoStoreOrderStatus status, LocalDateTime from, LocalDateTime to, String search,
			Sort sort) {
		try (Session session = sessions.openSession()) {
			Query<HoStoreOrder> rows = session.createQuery(QueryUtils.applySorting(page, sort, "o"), HoStoreOrder.class);
			bind(rows, storeId, status, from, to, search);
			Query<Long> total = session.createQuery(count, Long.class);
			bind(total, storeId, status, from, to, search);
			return rows.list().stream().map(HoStoreOrder::getNumber).collect(Collectors.joining()) + "/" + total.uniqueResult();
		}
	}

	private static void bind(Query<?> query, Long storeId, HoStoreOrderStatus status, LocalDateTime from,
			LocalDateTime to, String search) {
		String text = ItemBarcodeService.containsPattern(search);
		long anyStore = storeId == null ? 1L : 0L;
		long anyStatus = status == null ? 1L : 0L;
		query.setParameter("anyStore", anyStore).setParameter("storeId", storeId).setParameter("anyStatus", anyStatus)
				.setParameter("status", status).setParameter("from", from).setParameter("to", to)
				.setParameter("anyText", text == null ? 1L : 0L).setParameter("text", text == null ? "%" : text);
	}

	private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "receivedAt").and(Sort.by(Sort.Direction.DESC, "id"));

	@Test
	@DisplayName("No filter: every order, newest received first (the 500 of 2026-10-27: the long flags refused)")
	void noFilter() {
		assertEquals("CBA/3", run(null, null, null, null, NEWEST));
	}

	@Test
	@DisplayName("Each filter alone, then together")
	void filters() {
		assertEquals("BA/2", run(1L, null, null, null, NEWEST), "store 1");
		assertEquals("C/1", run(null, HoStoreOrderStatus.ERROR, null, null, NEWEST), "status ERROR");
		assertEquals("CB/2", run(null, null, LocalDateTime.of(2026, 10, 25, 0, 0), null, NEWEST), "from 10-25");
		assertEquals("A/1", run(null, null, null, LocalDateTime.of(2026, 10, 21, 0, 0), NEWEST), "to 10-20 (before 10-21)");
		assertEquals("B/1", run(1L, HoStoreOrderStatus.POSTED, LocalDateTime.of(2026, 10, 25, 0, 0),
				LocalDateTime.of(2026, 10, 26, 0, 0), NEWEST), "all four");
		assertEquals("/0", run(2L, HoStoreOrderStatus.SENT, null, null, NEWEST), "nothing matches");
	}

	@Test
	@DisplayName("The sorts the page asks: by store, by number, by status, either way")
	void sorts() {
		List<String> got = Arrays.asList(
				run(null, null, null, null, Sort.by(Sort.Direction.ASC, "storeId").and(Sort.by(Sort.Direction.DESC, "id"))),
				run(null, null, null, null, Sort.by(Sort.Direction.ASC, "number")),
				run(null, null, null, null, Sort.by(Sort.Direction.DESC, "number")),
				run(null, null, null, null, Sort.by(Sort.Direction.ASC, "status")));
		assertEquals(Arrays.asList("BAC/3", "ABC/3", "CBA/3", "CBA/3"), got);
	}

	@Test
	@DisplayName("The search, run as it is: the number, the ERP order number, a line's code, ERP item number or name, any"
			+ " case; a % searched as a character; with the other filters")
	void search() {
		assertEquals("A/1", run(null, null, null, null, "a", NEWEST), "the number A (any case), and no other");
		assertEquals("B/1", run(null, null, null, null, "cv2600", NEWEST), "the ERP order number");
		assertEquals("A/1", run(null, null, null, null, "70061~s", NEWEST), "a line's code");
		assertEquals("A/1", run(null, null, null, null, "CHEMISE", NEWEST), "a line's name");
		assertEquals("A/1", run(null, null, null, null, "50%", NEWEST), "a % as a character");
		assertEquals("A/1", run(null, null, null, null, "%", NEWEST), "% alone: only the name that has one");
		assertEquals("/0", run(2L, null, null, null, "chemise", NEWEST), "with the store");
	}
}
