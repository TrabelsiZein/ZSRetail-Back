package com.digithink.zsretail.store.holink.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderColis;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;

/**
 * 3.1.1, the variants window's last order (StoreOrderRepository.findWithNavItem), run as it is with Hibernate over an
 * in-memory H2 database (no Spring context). Orders, by id: A (POSTED, 43ZBPND01), B (SENT, another item), C (CANCELLED,
 * 43ZBPND01), D (DRAFT, 43ZBPND01), E (SENT, colis of 55ZCOLIS1 only): the last one sent with the item is A, whatever
 * came after it; an order holding only colis of an item is one of its orders (E).
 */
class StoreOrderLastQueryTest {

	private static SessionFactory sessions;
	private static String statement;

	@BeforeAll
	static void database() throws Exception {
		Method method = StoreOrderRepository.class.getMethod("findWithNavItem", String.class, Collection.class,
				Pageable.class);
		statement = method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
		sessions = new Configuration().addAnnotatedClass(StoreOrder.class).addAnnotatedClass(StoreOrderLine.class)
				.addAnnotatedClass(StoreOrderColis.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:storeorderlast;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			line(session, order(session, "A", StoreOrderStatus.POSTED), 1L, "43ZBPND01");
			line(session, order(session, "B", StoreOrderStatus.SENT), 2L, "32ZFCHD21");
			line(session, order(session, "C", StoreOrderStatus.CANCELLED), 1L, "43ZBPND01");
			line(session, order(session, "D", StoreOrderStatus.DRAFT), 1L, "43ZBPND01");
			StoreOrderColis colis = new StoreOrderColis();
			colis.setOrder(order(session, "E", StoreOrderStatus.SENT));
			colis.setNavItemNo("55ZCOLIS1");
			colis.setNbColis(3);
			session.persist(colis);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static StoreOrder order(Session session, String number, StoreOrderStatus status) {
		StoreOrder order = new StoreOrder();
		order.setNumber(number);
		order.setStatus(status);
		order.setCreatedAt(LocalDateTime.of(2026, 10, 28, 9, 0));
		session.persist(order);
		return order;
	}

	private static void line(Session session, StoreOrder order, Long itemId, String navItemNo) {
		StoreOrderLine line = new StoreOrderLine();
		line.setOrder(order);
		line.setItemId(itemId);
		line.setItemCode(navItemNo + "~001");
		line.setNavItemNo(navItemNo);
		line.setQuantity(BigDecimal.ONE);
		session.persist(line);
	}

	private static String run(String navItemNo, List<StoreOrderStatus> statuses) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(statement, StoreOrder.class).setParameter("navItemNo", navItemNo)
					.setParameter("statuses", statuses).setMaxResults(1).list().stream().map(StoreOrder::getNumber)
					.collect(Collectors.joining());
		}
	}

	@Test
	@DisplayName("The newest order of the statuses holding the item: A (C cancelled and D a draft are not sent orders)")
	void lastSent() {
		List<StoreOrderStatus> sent = Arrays.asList(StoreOrderStatus.SENT, StoreOrderStatus.POSTED,
				StoreOrderStatus.ERROR, StoreOrderStatus.DELIVERED);
		assertEquals("A", run("43ZBPND01", sent));
		assertEquals("B", run("32ZFCHD21", sent));
		assertEquals("", run("99ZZZZZ99", sent));
		assertEquals("E", run("55ZCOLIS1", sent), "colis only: one of the item's orders");
		assertEquals("D", run("43ZBPND01", Arrays.asList(StoreOrderStatus.values())), "newest first");
	}
}
