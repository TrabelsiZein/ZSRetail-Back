package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

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
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1, the unit cost frozen on the ticket lines (sales_line.unit_cost, return_line.unit_cost), with Hibernate over an
 * in-memory H2 database in SQL Server mode (every entity of core, Spring's naming strategy; no Spring context):
 * <ul>
 * <li>the column is DECIMAL(19,3), as db/3.1.1/sales-line-unit-cost.sql adds it;</li>
 * <li>it is written on the insert only: a later save of the line (another value, or none, as a whole body sent to the
 * generic PUT /sales-line/{id}) leaves it, while the other columns change;</li>
 * <li>it is never in JSON, and a body that sends one is not read.</li>
 * </ul>
 */
class LineUnitCostColumnTest {

	private static SessionFactory sessions;
	private static final AtomicLong NEXT = new AtomicLong(1);

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition entity : scan.findCandidateComponents("com.digithink.zsretail.core.model")) {
			try {
				configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:lineunitcost;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	@Test
	@DisplayName("sales_line.unit_cost and return_line.unit_cost: DECIMAL(19,3), nullable")
	void columnType() {
		for (String table : new String[] { "SALES_LINE", "RETURN_LINE" }) {
			try (Session session = sessions.openSession()) {
				Object[] column = (Object[]) session.createNativeQuery("SELECT NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE "
						+ "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = :table AND COLUMN_NAME = 'UNIT_COST'")
						.setParameter("table", table).getSingleResult();
				assertEquals(19, ((Number) column[0]).intValue(), table);
				assertEquals(3, ((Number) column[1]).intValue(), table);
				assertEquals("YES", column[2], table);
			}
		}
	}

	@Test
	@DisplayName("A sales line keeps the cost of its insert: a save with another cost, or with none (a whole body), changes the other columns only")
	void salesLineCostNeverUpdated() {
		Long id;
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			SalesLine line = salesLine(session, new BigDecimal("7.124"));
			session.persist(line);
			session.getTransaction().commit();
			id = line.getId();
		}
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			SalesLine line = session.get(SalesLine.class, id);
			line.setUnitCost(new BigDecimal("9.000"));
			line.setSynched(true);
			session.getTransaction().commit();
		}
		SalesLine detached = read(SalesLine.class, id);
		detached.setUnitCost(null); // the body of a PUT: the cost is never in JSON
		detached.setUnitPrice(11.0);
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.merge(detached);
			session.getTransaction().commit();
		}
		SalesLine stored = read(SalesLine.class, id);
		assertEquals(new BigDecimal("7.124"), stored.getUnitCost());
		assertEquals(Boolean.TRUE, stored.getSynched());
		assertEquals(11.0, stored.getUnitPrice(), 0.0);
	}

	@Test
	@DisplayName("A return line keeps the cost of its insert; a null cost stays null")
	void returnLineCostNeverUpdated() {
		Long costed;
		Long unknown;
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			SalesLine sold = salesLine(session, new BigDecimal("4.500"));
			session.persist(sold);
			ReturnHeader header = new ReturnHeader();
			header.setReturnNumber("R" + NEXT.getAndIncrement());
			header.setOriginalSalesHeader(sold.getSalesHeader());
			session.persist(header);
			ReturnLine withCost = returnLine(header, sold, sold.getUnitCost());
			ReturnLine withoutCost = returnLine(header, sold, null);
			session.persist(withCost);
			session.persist(withoutCost);
			session.getTransaction().commit();
			costed = withCost.getId();
			unknown = withoutCost.getId();
		}
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.get(ReturnLine.class, costed).setUnitCost(BigDecimal.ONE);
			ReturnLine later = session.get(ReturnLine.class, unknown);
			later.setUnitCost(new BigDecimal("6.000")); // today's CMP never reaches a written line
			later.setSynched(true);
			session.getTransaction().commit();
		}
		assertEquals(new BigDecimal("4.500"), read(ReturnLine.class, costed).getUnitCost());
		assertNull(read(ReturnLine.class, unknown).getUnitCost());
		assertEquals(Boolean.TRUE, read(ReturnLine.class, unknown).getSynched());
	}

	@Test
	@DisplayName("JSON: no unitCost in a sales or a return line; a body that sends one is not read")
	void neverInJson() throws Exception {
		ObjectMapper json = new ObjectMapper().findAndRegisterModules();
		SalesLine line = new SalesLine();
		line.setUnitPrice(10.0);
		line.setUnitCost(new BigDecimal("7.124"));
		ReturnLine back = new ReturnLine();
		back.setUnitCost(new BigDecimal("7.124"));
		assertFalse(json.writeValueAsString(line).contains("unitCost"), json.writeValueAsString(line));
		assertFalse(json.writeValueAsString(back).contains("unitCost"), json.writeValueAsString(back));

		assertNull(json.readValue("{\"unitPrice\": 10, \"unitCost\": 1.5}", SalesLine.class).getUnitCost());
		assertNull(json.readValue("{\"unitCost\": 1.5}", ReturnLine.class).getUnitCost());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static SalesLine salesLine(Session session, BigDecimal cost) {
		Item item = new Item();
		item.setItemCode("I" + NEXT.getAndIncrement());
		item.setName("Item");
		session.persist(item);
		SalesHeader ticket = new SalesHeader();
		ticket.setSalesNumber("T" + NEXT.getAndIncrement());
		ticket.setStatus(TransactionStatus.COMPLETED);
		session.persist(ticket);
		SalesLine line = new SalesLine();
		line.setSalesHeader(ticket);
		line.setItem(item);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(10.0);
		line.setLineTotal(10.0);
		line.setUnitPriceIncludingVat(10.0);
		line.setLineTotalIncludingVat(10.0);
		line.setUnitCost(cost);
		return line;
	}

	private static ReturnLine returnLine(ReturnHeader header, SalesLine sold, BigDecimal cost) {
		ReturnLine line = new ReturnLine();
		line.setReturnHeader(header);
		line.setOriginalSalesLine(sold);
		line.setItem(sold.getItem());
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(10.0);
		line.setUnitPriceIncludingVat(10.0);
		line.setLineTotal(10.0);
		line.setLineTotalIncludingVat(10.0);
		line.setUnitCost(cost);
		return line;
	}

	private static <T> T read(Class<T> type, Long id) {
		try (Session session = sessions.openSession()) {
			return session.get(type, id);
		}
	}
}
