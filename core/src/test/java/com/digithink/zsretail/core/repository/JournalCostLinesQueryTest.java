package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.persistence.Entity;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

/**
 * 3.1.2, the Journal's gains: the two line projections of the day list (SalesLineRepository.findCostLinesOfFinishedBetween,
 * ReturnLineRepository.findCostLinesOfReturnsBetween) run as they are with Hibernate over an in-memory H2 database (every
 * entity of core, Spring's naming strategy): the finished tickets of the range only (not a pending or a cancelled one,
 * not another day), the returns of the range, every column in its place (the item's type as the enum).
 */
class JournalCostLinesQueryTest {

	private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 3, 0, 0);
	private static final LocalDateTime TO = FROM.plusDays(1);
	private static final List<TransactionStatus> EXCLUDED = Arrays.asList(TransactionStatus.PENDING,
			TransactionStatus.CANCELLED);
	private static final AtomicLong NEXT = new AtomicLong(1);

	private static SessionFactory sessions;
	private static Long completed;
	private static Long ret;

	@BeforeAll
	static void database() throws Exception {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition entity : scan.findCandidateComponents("com.digithink.zsretail.core.model")) {
			configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
		}
		configuration.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:journalcosts;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "com.digithink.zsretail.core.support.H2SqlServerModeDialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item product = item(session, "A", ItemType.PRODUCT);
			Item service = item(session, "S", ItemType.SERVICE);
			SalesHeader done = sale(session, TransactionStatus.COMPLETED, FROM.plusHours(10), 29.85);
			line(session, done, product, "2", 23.8, 20.0, 19, new BigDecimal("6.000"));
			line(session, done, service, "1", 5.95, 5.0, 19, null);
			completed = done.getId();
			line(session, sale(session, TransactionStatus.PENDING, FROM.plusHours(11), 11.9), product, "1", 11.9, 10.0, 19,
					new BigDecimal("6.000"));
			line(session, sale(session, TransactionStatus.CANCELLED, FROM.plusHours(12), 11.9), product, "1", 11.9, 10.0, 19,
					null);
			line(session, sale(session, TransactionStatus.COMPLETED, FROM.minusHours(1), 11.9), product, "1", 11.9, 10.0, 19,
					null);
			ReturnHeader back = new ReturnHeader();
			back.setReturnNumber("R" + NEXT.getAndIncrement());
			back.setOriginalSalesHeader(done);
			back.setReturnDate(FROM.plusHours(13));
			back.setTotalReturnAmount(11.9);
			session.persist(back);
			ReturnLine line = new ReturnLine();
			line.setReturnHeader(back);
			line.setOriginalSalesLine(session.createQuery("FROM SalesLine", SalesLine.class).setMaxResults(1).getSingleResult());
			line.setItem(product);
			line.setQuantity(BigDecimal.ONE);
			line.setUnitPrice(10.0);
			line.setUnitPriceIncludingVat(11.9);
			line.setLineTotal(10.0);
			line.setLineTotalIncludingVat(11.9);
			line.setUnitCost(new BigDecimal("6.000"));
			session.persist(line);
			ret = back.getId();
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code, ItemType type) {
		Item item = new Item();
		item.setItemCode(code + NEXT.getAndIncrement());
		item.setName(code);
		item.setType(type);
		session.persist(item);
		return item;
	}

	private static SalesHeader sale(Session session, TransactionStatus status, LocalDateTime date, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setSalesNumber("T" + NEXT.getAndIncrement());
		sale.setStatus(status);
		sale.setSalesDate(date);
		sale.setTotalAmount(total);
		session.persist(sale);
		return sale;
	}

	private static void line(Session session, SalesHeader sale, Item item, String quantity, double ttc, double ht, int vat,
			BigDecimal cost) {
		SalesLine line = new SalesLine();
		line.setSalesHeader(sale);
		line.setItem(item);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(ht);
		line.setLineTotal(ht);
		line.setUnitPriceIncludingVat(ttc);
		line.setLineTotalIncludingVat(ttc);
		line.setVatPercent(vat);
		line.setUnitCost(cost);
		session.persist(line);
	}

	private static String query(Class<?> repository, String method, Class<?>... types) throws Exception {
		return repository.getMethod(method, types).getAnnotation(Query.class).value();
	}

	@Test
	@DisplayName("Tickets: the finished ticket of the range only, one row per line, every column in place")
	void tickets() throws Exception {
		String jpql = query(SalesLineRepository.class, "findCostLinesOfFinishedBetween", LocalDateTime.class,
				LocalDateTime.class, List.class);
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = session.createQuery(jpql, Object[].class).setParameter("from", FROM).setParameter("to", TO)
					.setParameter("excluded", EXCLUDED).getResultList();
			assertEquals(2, rows.size(), "the pending, the cancelled and the other day left out");
			rows.forEach(row -> assertEquals(completed, ((Number) row[0]).longValue()));
			Object[] product = rows.stream().filter(r -> r[4] == ItemType.PRODUCT).findFirst().get();
			assertEquals(FROM.plusHours(10), product[1]);
			assertEquals(29.85, ((Number) product[2]).doubleValue(), 0.0);
			assertEquals(23.8, ((Number) product[5]).doubleValue(), 0.0);
			assertEquals(20.0, ((Number) product[6]).doubleValue(), 0.0);
			assertEquals(19, ((Number) product[7]).intValue());
			assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) product[8]));
			assertEquals(0, new BigDecimal("6.000").compareTo((BigDecimal) product[9]));
			Object[] service = rows.stream().filter(r -> r[4] == ItemType.SERVICE).findFirst().get();
			assertEquals(null, service[9], "no cost: null as stored");
		}
	}

	@Test
	@DisplayName("Returns: the return of the range, one row per line, every column in place")
	void returns() throws Exception {
		String jpql = query(ReturnLineRepository.class, "findCostLinesOfReturnsBetween", LocalDateTime.class,
				LocalDateTime.class);
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = session.createQuery(jpql, Object[].class).setParameter("from", FROM).setParameter("to", TO)
					.getResultList();
			assertEquals(Arrays.asList(ret), rows.stream().map(r -> ((Number) r[0]).longValue()).collect(Collectors.toList()));
			Object[] row = rows.get(0);
			assertEquals(11.9, ((Number) row[2]).doubleValue(), 0.0);
			assertEquals(ItemType.PRODUCT, row[4]);
			assertEquals(11.9, ((Number) row[5]).doubleValue(), 0.0);
			assertEquals(10.0, ((Number) row[6]).doubleValue(), 0.0);
			assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) row[7]));
			assertEquals(0, new BigDecimal("6.000").compareTo((BigDecimal) row[8]));
			assertEquals(0, session.createQuery(jpql, Object[].class).setParameter("from", TO).setParameter("to", TO.plusDays(1))
					.getResultList().size(), "another day: none");
		}
	}
}
