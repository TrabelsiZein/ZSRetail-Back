package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.Entity;

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
import org.springframework.data.jpa.domain.Specification;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.dto.TicketsExportDTO;

/**
 * 3.1.2, the Excel export of the tickets history: {@link TicketsHistoryExport} over the list's own filters
 * ({@link SalesHeaderService#ticketsHistorySpec}), its queries run as they are with Hibernate over an in-memory H2
 * database (every entity of core). Three tickets: T-3 (today, two lines, cash and card, a customer), T-2 (yesterday, one
 * line, cash, a parfum) and T-1 (40 days ago, cancelled).
 * <ul>
 * <li>every ticket newest first, the lines in the tickets' order, the line count and quantity, the payments by method in
 * the methods' display order, the cashier;</li>
 * <li>the list's filters: the period, the status, the payment method, the search, the family;</li>
 * <li>the limit: the newest tickets, truncated, the count of all; the unit cost only with the right.</li>
 * </ul>
 */
class TicketsHistoryExportTest {

	private static final LocalDate TODAY = LocalDate.now();

	private static SessionFactory sessions;
	private static Session session;
	private static TicketsHistoryExport export;
	private static PaymentMethod card;
	private static ItemFamily parfums;

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
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:ticketsexport;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();

		parfums = new ItemFamily();
		parfums.setCode("F1");
		parfums.setName("Parfums");
		session.persist(parfums);
		Item alpha = item("A", "Alpha", parfums);
		Item bravo = item("B", "Bravo", null);
		// Card first in the display order, cash second: the columns follow the order, not the first ticket read
		card = method("Carte", 2, PaymentMethodType.CLIENT_TPE);
		PaymentMethod cash = method("Espèces", 1, PaymentMethodType.CLIENT_ESPECES);
		UserAccount cashier = new UserAccount();
		cashier.setUsername("cashier1");
		cashier.setPassword("-");
		cashier.setFullName("Caissier Un");
		session.persist(cashier);
		CashierSession till = new CashierSession();
		till.setSessionNumber("S-1");
		till.setCashier(cashier);
		till.setOpenedAt(TODAY.minusDays(40).atTime(8, 0));
		till.setOpeningCash(0.0);
		session.persist(till);
		Customer customer = new Customer();
		customer.setCustomerCode("C-9");
		customer.setName("Société Nour");
		customer.setPhone("-");
		session.persist(customer);

		SalesHeader old = ticket("T-1", TODAY.minusDays(40).atTime(10, 0), TransactionStatus.CANCELLED, 5.0, till);
		line(old, bravo, "1", 5.0, null);
		SalesHeader yesterday = ticket("T-2", TODAY.minusDays(1).atTime(18, 0), TransactionStatus.COMPLETED, 30.0, till);
		line(yesterday, alpha, "1", 30.0, "12.5");
		pay(yesterday, cash, 30.0);
		SalesHeader today = ticket("T-3", TODAY.atTime(9, 30), TransactionStatus.COMPLETED, 70.0, till);
		today.setCustomer(customer);
		line(today, bravo, "2", 40.0, null);
		line(today, bravo, "0.5", 30.0, null);
		pay(today, cash, 20.0);
		pay(today, card, 50.0);
		session.getTransaction().commit();

		export = new TicketsHistoryExport();
		Field em = TicketsHistoryExport.class.getDeclaredField("entityManager");
		em.setAccessible(true);
		em.set(export, session);
	}

	@AfterAll
	static void close() {
		session.close();
		sessions.close();
	}

	private static Item item(String code, String name, ItemFamily family) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name);
		item.setType(ItemType.PRODUCT);
		item.setItemFamily(family);
		session.persist(item);
		return item;
	}

	private static PaymentMethod method(String name, int order, PaymentMethodType type) {
		PaymentMethod method = new PaymentMethod();
		method.setCode(name.toUpperCase());
		method.setName(name);
		method.setDisplayOrder(order);
		method.setType(type);
		session.persist(method);
		return method;
	}

	private static SalesHeader ticket(String number, LocalDateTime at, TransactionStatus status, double total,
			CashierSession till) {
		SalesHeader ticket = new SalesHeader();
		ticket.setSalesNumber(number);
		ticket.setSalesDate(at);
		ticket.setStatus(status);
		ticket.setTotalAmount(total);
		ticket.setCashierSession(till);
		session.persist(ticket);
		return ticket;
	}

	private static void line(SalesHeader ticket, Item item, String quantity, double total, String cost) {
		SalesLine line = new SalesLine();
		line.setSalesHeader(ticket);
		line.setItem(item);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(total);
		line.setUnitPriceIncludingVat(total);
		line.setLineTotal(total);
		line.setLineTotalIncludingVat(total);
		line.setUnitCost(cost == null ? null : new BigDecimal(cost));
		session.persist(line);
	}

	private static void pay(SalesHeader ticket, PaymentMethod method, double amount) {
		Payment payment = new Payment();
		payment.setSalesHeader(ticket);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		session.persist(payment);
	}

	private static Specification<SalesHeader> filters(String dateFrom, String status, String paymentMethodId,
			String search, String familyId) {
		return SalesHeaderService.ticketsHistorySpec(dateFrom, null, status, null, paymentMethodId, search, null, null,
				familyId, null, null, null);
	}

	private static List<String> numbers(TicketsExportDTO answer) {
		return answer.getTickets().stream().map(TicketsExportDTO.Ticket::getSalesNumber).collect(Collectors.toList());
	}

	@Test
	@DisplayName("No filter: every ticket newest first, the lines in the tickets' order, counts, payments by method in"
			+ " display order, the session's cashier; no unit cost without the right")
	void everything() {
		TicketsExportDTO answer = export.export(filters(null, null, null, null, null), false, 100);
		assertEquals(Arrays.asList("T-3", "T-2", "T-1"), numbers(answer));
		assertEquals(3, answer.getTotalTickets());
		assertFalse(answer.isTruncated());
		assertEquals(Arrays.asList("Espèces", "Carte"), answer.getPaymentMethods());
		assertEquals(Arrays.asList("T-3", "T-3", "T-2", "T-1"),
				answer.getLines().stream().map(TicketsExportDTO.Line::getSalesNumber).collect(Collectors.toList()));
		TicketsExportDTO.Ticket today = answer.getTickets().get(0);
		assertEquals(2, today.getLineCount());
		assertEquals(0, new BigDecimal("2.5").compareTo(today.getQuantity()));
		assertEquals(20.0, today.getPayments().get("Espèces"), 0.0005);
		assertEquals(50.0, today.getPayments().get("Carte"), 0.0005);
		assertEquals("Caissier Un", today.getCashier());
		assertEquals("S-1", today.getSessionNumber());
		assertEquals("C-9", today.getCustomerCode());
		assertEquals("Société Nour", today.getCustomerName());
		assertEquals("COMPLETED", today.getStatus());
		TicketsExportDTO.Line parfum = answer.getLines().get(2);
		assertEquals("A", parfum.getItemCode());
		assertEquals("Parfums", parfum.getFamilyName());
		assertNull(parfum.getUnitCost(), "the unit cost only with the margins right");
		assertTrue(answer.getTickets().get(2).getPayments().isEmpty());
	}

	@Test
	@DisplayName("The list's filters: period, status, payment method, search on the customer, family")
	void filtersOfTheList() {
		String yesterday = TODAY.minusDays(1).toString();
		assertEquals(Arrays.asList("T-3", "T-2"), numbers(export.export(filters(yesterday, null, null, null, null), false, 100)));
		assertEquals(Arrays.asList("T-1"), numbers(export.export(filters(null, "CANCELLED", null, null, null), false, 100)));
		assertEquals(Arrays.asList("T-3"),
				numbers(export.export(filters(null, null, card.getId().toString(), null, null), false, 100)));
		assertEquals(Arrays.asList("T-3"), numbers(export.export(filters(null, null, null, "nour", null), false, 100)));
		assertEquals(Arrays.asList("T-2"),
				numbers(export.export(filters(null, null, null, null, parfums.getId().toString()), false, 100)));
		assertEquals(Arrays.asList("T-3", "T-2", "T-1"), numbers(export.export(filters(null, "all", "all", "", "all"), false, 100)));
	}

	@Test
	@DisplayName("The limit: the newest tickets and their lines only, truncated, the count of all; the unit cost with the"
			+ " right")
	void limitAndCost() {
		TicketsExportDTO answer = export.export(filters(null, null, null, null, null), true, 2);
		assertEquals(Arrays.asList("T-3", "T-2"), numbers(answer));
		assertTrue(answer.isTruncated());
		assertEquals(3, answer.getTotalTickets());
		assertEquals(3, answer.getLines().size());
		assertEquals(0, new BigDecimal("12.5").compareTo(answer.getLines().get(2).getUnitCost()));
		assertTrue(answer.isWithCost());
	}
}
