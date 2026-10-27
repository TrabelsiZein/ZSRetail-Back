package com.digithink.zsretail.store.holink.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
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

import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;

/**
 * 3.1.1, the store's list of its orders to the head office (StoreOrderRepository.findPage and its count), run as they are
 * with Hibernate over an in-memory H2 database (no Spring context), the parameters bound with the types of the method
 * (anyStatus long: an int literal in the statement made Hibernate refuse it, as on the head office on 2026-10-27), with
 * and without each filter. Orders: A (DRAFT, created 10-20), B (SENT, 10-25), C (CANCELLED, 10-26); newest first.
 */
class StoreOrderPageQueryTest {

	private static SessionFactory sessions;
	private static String page;
	private static String count;

	@BeforeAll
	static void database() throws Exception {
		Method findPage = StoreOrderRepository.class.getMethod("findPage", long.class, StoreOrderStatus.class,
				LocalDateTime.class, LocalDateTime.class, Pageable.class);
		org.springframework.data.jpa.repository.Query query = findPage
				.getAnnotation(org.springframework.data.jpa.repository.Query.class);
		page = query.value();
		count = query.countQuery();
		sessions = new Configuration().addAnnotatedClass(StoreOrder.class).addAnnotatedClass(StoreOrderLine.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:storeorders;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			order(session, "A", StoreOrderStatus.DRAFT, LocalDateTime.of(2026, 10, 20, 9, 0));
			order(session, "B", StoreOrderStatus.SENT, LocalDateTime.of(2026, 10, 25, 9, 0));
			order(session, "C", StoreOrderStatus.CANCELLED, LocalDateTime.of(2026, 10, 26, 9, 0));
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void order(Session session, String number, StoreOrderStatus status, LocalDateTime createdAt) {
		StoreOrder order = new StoreOrder();
		order.setNumber(number);
		order.setStatus(status);
		order.setCreatedAt(createdAt);
		session.persist(order);
	}

	/** The numbers of the page and the count, the parameters bound as StoreOrderService.list binds them. */
	private static String run(StoreOrderStatus status, LocalDateTime from, LocalDateTime to) {
		try (Session session = sessions.openSession()) {
			Query<StoreOrder> rows = session.createQuery(page, StoreOrder.class);
			bind(rows, status, from, to);
			Query<Long> total = session.createQuery(count, Long.class);
			bind(total, status, from, to);
			return rows.list().stream().map(StoreOrder::getNumber).collect(Collectors.joining()) + "/" + total.uniqueResult();
		}
	}

	private static void bind(Query<?> query, StoreOrderStatus status, LocalDateTime from, LocalDateTime to) {
		long anyStatus = status == null ? 1L : 0L;
		query.setParameter("anyStatus", anyStatus).setParameter("status", status).setParameter("from", from)
				.setParameter("to", to);
	}

	@Test
	@DisplayName("No filter: every order, newest first (the long flag accepted)")
	void noFilter() {
		assertEquals("CBA/3", run(null, null, null));
	}

	@Test
	@DisplayName("Each filter alone, then together")
	void filters() {
		assertEquals("B/1", run(StoreOrderStatus.SENT, null, null), "status SENT");
		assertEquals("CB/2", run(null, LocalDateTime.of(2026, 10, 25, 0, 0), null), "from 10-25");
		assertEquals("A/1", run(null, null, LocalDateTime.of(2026, 10, 21, 0, 0)), "to 10-20 (before 10-21)");
		assertEquals("B/1", run(StoreOrderStatus.SENT, LocalDateTime.of(2026, 10, 25, 0, 0),
				LocalDateTime.of(2026, 10, 26, 0, 0)), "all three");
		assertEquals("/0", run(StoreOrderStatus.POSTED, null, null), "nothing matches");
	}
}
