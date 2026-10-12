package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.repository.RfidSaleTagRepository;
import com.digithink.zsretail.store.rfid.repository.RfidTagRepository;
import com.digithink.zsretail.store.service.SaleTagHooks.LineTags;

/**
 * RFID POS step 7: the tags of a ticket through the real repositories, Hibernate over an in-memory H2 database (no
 * Spring context; the Spring Data repositories made over each transaction's session). The in-memory tables of
 * RfidSaleTagsTest delete at once; Hibernate keeps a delete until the next flush but inserts a new row (identity id) at
 * once, so a pending ticket paid with its own tags inserted its rows while the old ones were still there:
 * uk_rfid_sale_tag_header_epc (bug of 2026-10-12, ticket 6). As SalesHeaderService does, each step saves the ticket's
 * lines and flushes its own work before calling the hook.
 */
class RfidSaleTagsJpaTest {

	private static SessionFactory sessions;
	private static final AtomicLong NEXT = new AtomicLong(1);

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (String base : new String[] { "com.digithink.zsretail.core.model", "com.digithink.zsretail.store.rfid.model" }) {
			for (BeanDefinition entity : scan.findCandidateComponents(base)) {
				try {
					configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:rfidsaletags;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	/** One transaction, with the hooks over its session's repositories. */
	private static void inTransaction(BiConsumer<Session, RfidSaleTags> work) {
		try (Session session = sessions.openSession()) {
			JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
			RfidSaleTags hooks = new RfidSaleTags(repositories.getRepository(RfidTagRepository.class),
					repositories.getRepository(RfidSaleTagRepository.class));
			session.beginTransaction();
			try {
				work.accept(session, hooks);
				session.getTransaction().commit();
			} catch (RuntimeException e) {
				session.getTransaction().rollback();
				throw e;
			}
		}
	}

	/** An item with these tags in stock; returns the item id. */
	private static long itemWithTags(String... epcs) {
		long[] id = new long[1];
		inTransaction((session, hooks) -> {
			Item item = new Item();
			item.setItemCode("I" + NEXT.getAndIncrement());
			item.setName("Shirt");
			session.persist(item);
			for (String epc : epcs) {
				RfidTag tag = new RfidTag();
				tag.setEpc(epc);
				tag.setItem(item);
				tag.setStatus(RfidTagStatus.IN_STOCK);
				session.persist(tag);
			}
			id[0] = item.getId();
		});
		return id[0];
	}

	/** A pending ticket holding these tags (save-pending: its line holds all the pieces); returns its id. */
	private static long pendingWith(long itemId, String... epcs) {
		long[] id = new long[1];
		inTransaction((session, hooks) -> {
			SalesHeader ticket = new SalesHeader();
			ticket.setSalesNumber("T" + NEXT.getAndIncrement());
			ticket.setStatus(TransactionStatus.PENDING);
			session.persist(ticket);
			SalesLine line = line(session, ticket, itemId, epcs.length);
			session.flush();
			hooks.ticketHeld(ticket, Collections.singletonList(new LineTags(line, Arrays.asList(epcs))));
			id[0] = ticket.getId();
		});
		return id[0];
	}

	/** completePendingSale: the old lines deleted, the paid and free lines made again, then the hook (paid, free). */
	private static void pay(long ticketId, long itemId, int paid, int free, String... epcs) {
		inTransaction((session, hooks) -> {
			SalesHeader ticket = session.get(SalesHeader.class, ticketId);
			linesOf(session, ticketId).forEach(session::remove);
			SalesLine line = line(session, ticket, itemId, paid);
			if (free > 0) {
				line(session, ticket, itemId, free);
			}
			ticket.setStatus(TransactionStatus.COMPLETED);
			session.flush();
			hooks.ticketCompleted(ticket, Collections.singletonList(new LineTags(line, Arrays.asList(epcs))));
		});
	}

	private static SalesLine line(Session session, SalesHeader ticket, long itemId, int quantity) {
		SalesLine line = new SalesLine();
		line.setSalesHeader(ticket);
		line.setItem(session.get(Item.class, itemId));
		line.setQuantity(BigDecimal.valueOf(quantity));
		line.setUnitPrice(10.0);
		line.setLineTotal(10.0 * quantity);
		line.setUnitPriceIncludingVat(10.0);
		line.setLineTotalIncludingVat(10.0 * quantity);
		session.persist(line);
		return line;
	}

	private static List<SalesLine> linesOf(Session session, long ticketId) {
		return session.createQuery("select l from SalesLine l where l.salesHeader.id = :id", SalesLine.class)
				.setParameter("id", ticketId).getResultList();
	}

	/** The ticket's rows: epc → its sales line id (null: held by a pending ticket). */
	private static Map<String, Long> rowsOf(long ticketId) {
		Map<String, Long> rows = new TreeMap<>();
		inTransaction((session, hooks) -> session
				.createQuery("select r from RfidSaleTag r where r.salesHeader.id = :id", RfidSaleTag.class)
				.setParameter("id", ticketId).getResultList()
				.forEach(r -> rows.put(r.getEpc(), r.getSalesLine() == null ? null : r.getSalesLine().getId())));
		return rows;
	}

	/** The tags' status and ticket number: epc → "SOLD T12" or "IN_STOCK". */
	private static Map<String, String> statusOf(String... epcs) {
		Map<String, String> status = new TreeMap<>();
		inTransaction((session, hooks) -> session
				.createQuery("select t from RfidTag t where t.epc in :epcs", RfidTag.class)
				.setParameter("epcs", Arrays.asList(epcs)).getResultList()
				.forEach(t -> status.put(t.getEpc(), t.getStatus() + (t.getTicketNumber() == null ? "" : " " + t.getTicketNumber()))));
		return status;
	}

	private static String numberOf(long ticketId) {
		String[] number = new String[1];
		inTransaction((session, hooks) -> number[0] = session.get(SalesHeader.class, ticketId).getSalesNumber());
		return number[0];
	}

	private static List<String> sorted(String... epcs) {
		List<String> list = new ArrayList<>(Arrays.asList(epcs));
		Collections.sort(list);
		return list;
	}

	@Test
	@DisplayName("A pending ticket paid with its own tags: each row kept once with the paid line, every tag SOLD")
	void pendingPaidWithTheSameTags() {
		long item = itemWithTags("A1", "A2", "A3");
		long ticket = pendingWith(item, "A1", "A2", "A3");
		assertEquals(sorted("A1", "A2", "A3"), new ArrayList<>(rowsOf(ticket).keySet()));

		pay(ticket, item, 2, 1, "A1", "A2", "A3");

		Map<String, Long> rows = rowsOf(ticket);
		assertEquals(sorted("A1", "A2", "A3"), new ArrayList<>(rows.keySet()), "paid 2 + free 1: the 3 tags");
		assertTrue(rows.values().stream().allMatch(line -> line != null), "with the paid line");
		String sold = "SOLD " + numberOf(ticket);
		assertEquals(Arrays.asList(sold, sold, sold), new ArrayList<>(statusOf("A1", "A2", "A3").values()));
	}

	@Test
	@DisplayName("A pending ticket paid with one tag more: the new one sold too")
	void pendingPaidWithOneTagMore() {
		long item = itemWithTags("B1", "B2", "B3");
		long ticket = pendingWith(item, "B1", "B2");

		pay(ticket, item, 3, 0, "B1", "B2", "B3");

		assertEquals(sorted("B1", "B2", "B3"), new ArrayList<>(rowsOf(ticket).keySet()));
		assertTrue(statusOf("B1", "B2", "B3").values().stream().allMatch(s -> s.startsWith("SOLD ")));
	}

	@Test
	@DisplayName("A pending ticket paid with one tag less: the tag left out stays in stock, no row for it")
	void pendingPaidWithOneTagLess() {
		long item = itemWithTags("C1", "C2", "C3");
		long ticket = pendingWith(item, "C1", "C2", "C3");

		pay(ticket, item, 2, 0, "C1", "C2");

		assertEquals(sorted("C1", "C2"), new ArrayList<>(rowsOf(ticket).keySet()));
		Map<String, String> status = statusOf("C1", "C2", "C3");
		assertEquals("IN_STOCK", status.get("C3"));
		assertTrue(status.get("C1").startsWith("SOLD ") && status.get("C2").startsWith("SOLD "));
	}

	@Test
	@DisplayName("A pending ticket saved again with its own tags (update-pending): held once, nothing sold")
	void pendingSavedAgain() {
		long item = itemWithTags("D1", "D2");
		long ticket = pendingWith(item, "D1", "D2");

		inTransaction((session, hooks) -> {
			SalesHeader pending = session.get(SalesHeader.class, ticket);
			linesOf(session, ticket).forEach(session::remove);
			SalesLine line = line(session, pending, item, 2);
			session.flush();
			hooks.ticketHeld(pending, Collections.singletonList(new LineTags(line, Arrays.asList("D1", "D2"))));
		});

		Map<String, Long> rows = rowsOf(ticket);
		assertEquals(sorted("D1", "D2"), new ArrayList<>(rows.keySet()));
		assertTrue(rows.values().stream().allMatch(line -> line == null));
		assertEquals(Arrays.asList("IN_STOCK", "IN_STOCK"), new ArrayList<>(statusOf("D1", "D2").values()));
	}

	@Test
	@DisplayName("A direct payment (no pending ticket): its tags sold, one row each")
	void directPayment() {
		long item = itemWithTags("E1", "E2");
		long[] ticket = new long[1];
		inTransaction((session, hooks) -> {
			SalesHeader sale = new SalesHeader();
			sale.setSalesNumber("T" + NEXT.getAndIncrement());
			sale.setStatus(TransactionStatus.COMPLETED);
			session.persist(sale);
			SalesLine line = line(session, sale, item, 2);
			session.flush();
			hooks.ticketCompleted(sale, Collections.singletonList(new LineTags(line, Arrays.asList("E1", "E2"))));
			ticket[0] = sale.getId();
		});

		assertEquals(sorted("E1", "E2"), new ArrayList<>(rowsOf(ticket[0]).keySet()));
		assertTrue(statusOf("E1", "E2").values().stream().allMatch(s -> s.equals("SOLD " + numberOf(ticket[0]))));
	}

	@Test
	@DisplayName("A pending ticket deleted: its rows gone, its tags in stock and free for another ticket")
	void pendingDeleted() {
		long item = itemWithTags("F1", "F2");
		long ticket = pendingWith(item, "F1", "F2");

		inTransaction((session, hooks) -> {
			SalesHeader pending = session.get(SalesHeader.class, ticket);
			pending.setStatus(TransactionStatus.CANCELLED);
			session.flush();
			hooks.ticketCancelled(pending);
		});

		assertEquals(Collections.emptyMap(), rowsOf(ticket));
		assertEquals(Arrays.asList("IN_STOCK", "IN_STOCK"), new ArrayList<>(statusOf("F1", "F2").values()));
		long other = pendingWith(item, "F1", "F2");
		assertEquals(sorted("F1", "F2"), new ArrayList<>(rowsOf(other).keySet()));
		assertNull(rowsOf(other).get("F1"));
		assertNotNull(numberOf(other));
	}
}
