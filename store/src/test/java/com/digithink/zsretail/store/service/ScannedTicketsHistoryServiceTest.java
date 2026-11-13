package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.controller.ScannedTicketsHistoryAPI;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.service.ScannedTicketsHistoryService.Filters;

/**
 * 3.2.0, step 7: the history of the scanned tickets taken ({@link ScannedTicketsHistoryService}), its queries run as
 * they are with Hibernate over an in-memory H2 database. Four tickets: V-1 (yesterday 10:00, Amira) paid with a Servimax
 * 7.000 (counted 6.300) and a Sodexo 5.000 of which 3.700 applied (1.300 kept); V-2 (today 09:00, Karim) a gift cheque
 * 20.000; V-0 (40 days ago, Amira) a Servimax 7.000. Totals: 4 tickets, face 39.000, counted 37.600 (deduction 1.400),
 * applied 36.300, excess 1.300.
 */
class ScannedTicketsHistoryServiceTest {

	private static final LocalDate TODAY = LocalDate.now();

	private static SessionFactory sessions;
	private static Session session;
	private static ScannedTicketsHistoryService service;
	private static PaymentMethod meal;
	private static PaymentMethod gift;
	private static UserAccount amira;
	private static UserAccount karim;

	@BeforeAll
	static void database() throws Exception {
		Configuration configuration = new Configuration();
		ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		for (BeanDefinition entity : scan.findCandidateComponents("com.digithink.zsretail.core.model")) {
			configuration.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
		}
		configuration.addAnnotatedClass(ScannedTicketPayment.class);
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:scannedhistory;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		session = sessions.openSession();
		session.beginTransaction();

		meal = method("TR", "Ticket Restaurant", 3);
		gift = method("CC", "Chèque Cadeau", 4);
		amira = user("amira", "Amira Ben Salem");
		karim = user("karim", null);
		CashierSession till = new CashierSession();
		till.setSessionNumber("SESSION261113001");
		till.setCashier(amira);
		till.setOpenedAt(TODAY.minusDays(41).atStartOfDay());
		till.setOpeningCash(0.0);
		session.persist(till);
		AcceptedTicket servimax = line(meal, "031", "Servimax Repas");
		AcceptedTicket sodexo = line(meal, "041", "Sodexo Repas");
		AcceptedTicket cadeau = line(gift, "012", "Cadeau Plus");

		SalesHeader old = sale("V-0", TODAY.minusDays(40).atTime(12, 0), till);
		ticket(pay(old, meal, "7.000", amira, TODAY.minusDays(40).atTime(12, 0)), servimax,
				"0310070002600000000000000099", "7.000", "6.300", "6.300", "0.000");
		SalesHeader yesterday = sale("V-1", TODAY.minusDays(1).atTime(10, 0), till);
		ticket(pay(yesterday, meal, "6.300", amira, TODAY.minusDays(1).atTime(10, 0)), servimax,
				"0310070002600000000000000001", "7.000", "6.300", "6.300", "0.000");
		ticket(pay(yesterday, meal, "3.700", amira, TODAY.minusDays(1).atTime(10, 0).plusSeconds(1)), sodexo,
				"0410050002600000000000000002", "5.000", "5.000", "3.700", "1.300");
		SalesHeader today = sale("V-2", TODAY.atTime(9, 0), till);
		ticket(pay(today, gift, "20.000", karim, TODAY.atTime(9, 0)), cadeau, "0120200002600000000000000003", "20.000",
				"20.000", "20.000", "0.000");
		session.getTransaction().commit();

		service = new ScannedTicketsHistoryService();
		Field em = ScannedTicketsHistoryService.class.getDeclaredField("entityManager");
		em.setAccessible(true);
		em.set(service, session);
	}

	@AfterAll
	static void close() {
		session.close();
		sessions.close();
	}

	private static PaymentMethod method(String code, String name, int order) {
		PaymentMethod method = new PaymentMethod();
		method.setCode(code);
		method.setName(name);
		method.setDisplayOrder(order);
		method.setBehaviour(PaymentMethodBehaviour.SCANNED_TICKETS);
		session.persist(method);
		return method;
	}

	private static UserAccount user(String username, String fullName) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
		user.setPassword("-");
		user.setFullName(fullName);
		session.persist(user);
		return user;
	}

	private static AcceptedTicket line(PaymentMethod method, String code, String issuer) {
		AcceptedTicket line = new AcceptedTicket();
		line.setPaymentMethod(method);
		line.setTicketCode(code);
		line.setIssuerName(issuer);
		session.persist(line);
		return line;
	}

	private static SalesHeader sale(String number, LocalDateTime at, CashierSession till) {
		SalesHeader sale = new SalesHeader();
		sale.setSalesNumber(number);
		sale.setSalesDate(at);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setTotalAmount(0.0);
		sale.setCashierSession(till);
		session.persist(sale);
		return sale;
	}

	private static Payment pay(SalesHeader sale, PaymentMethod method, String amount, UserAccount cashier,
			LocalDateTime at) {
		Payment payment = new Payment();
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(Double.valueOf(amount));
		payment.setCreatedByUser(cashier);
		payment.setPaymentDate(at);
		session.persist(payment);
		return payment;
	}

	private static void ticket(Payment payment, AcceptedTicket line, String barcode, String face, String counted,
			String applied, String excess) {
		ScannedTicketPayment ticket = new ScannedTicketPayment();
		ticket.setPayment(payment);
		ticket.setAcceptedTicket(line);
		ticket.setBarcode(barcode);
		ticket.setLiveBarcode(barcode);
		ticket.setIssuerName(line.getIssuerName());
		ticket.setTicketCode(line.getTicketCode());
		ticket.setPrintedYear(2026);
		ticket.setFaceValue(new BigDecimal(face));
		ticket.setDeductionPercent(new BigDecimal(face).compareTo(new BigDecimal(counted)) == 0 ? BigDecimal.ZERO
				: BigDecimal.TEN);
		ticket.setCountedValue(new BigDecimal(counted));
		ticket.setAppliedAmount(new BigDecimal(applied));
		ticket.setExcessAmount(new BigDecimal(excess));
		session.persist(ticket);
	}

	private static Filters filters(String dateFrom, String dateTo, String issuer, String methodId, String cashierId,
			String barcode) {
		return Filters.of(dateFrom, dateTo, issuer, methodId, cashierId, barcode);
	}

	private static Filters none() {
		return filters(null, null, null, null, null, null);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> content(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("content");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> totals(Map<String, Object> answer) {
		return (Map<String, Object>) answer.get("totals");
	}

	private static List<String> barcodesEnd(List<Map<String, Object>> rows) {
		return rows.stream().map(r -> ((String) r.get("barcode")).substring(26)).collect(Collectors.toList());
	}

	private static String amounts(Map<String, Object> totals) {
		return totals.get("ticketsCount") + " " + totals.get("faceTotal") + " " + totals.get("deduction") + " "
				+ totals.get("counted") + " " + totals.get("applied") + " " + totals.get("excess");
	}

	@Test
	@DisplayName("No filter: every ticket newest first, the row's fields, the totals of all")
	void everyTicket() {
		Map<String, Object> page = service.page(none(), 0, 20);
		List<Map<String, Object>> rows = content(page);
		assertEquals(Arrays.asList("03", "02", "01", "99"), barcodesEnd(rows), "newest first (the payment's time)");
		Map<String, Object> sodexo = rows.get(1);
		assertEquals("V-1", sodexo.get("salesNumber"));
		assertEquals("SESSION261113001", sodexo.get("sessionNumber"));
		assertEquals("Amira Ben Salem", sodexo.get("cashierName"));
		assertEquals("Ticket Restaurant", sodexo.get("paymentMethodName"));
		assertEquals("Sodexo Repas", sodexo.get("issuerName"));
		assertEquals("041", sodexo.get("ticketCode"));
		assertEquals("0410050002600000000000000002", sodexo.get("barcode"));
		assertEquals(2026, sodexo.get("printedYear"));
		assertEquals(new BigDecimal("5.000"), sodexo.get("faceValue"));
		assertEquals(new BigDecimal("0.000"), sodexo.get("deduction"));
		assertEquals(new BigDecimal("5.000"), sodexo.get("countedValue"));
		assertEquals(new BigDecimal("3.700"), sodexo.get("appliedAmount"));
		assertEquals(new BigDecimal("1.300"), sodexo.get("excessAmount"));
		assertEquals(new BigDecimal("0.700"), rows.get(2).get("deduction"), "7.000 - 6.300");
		assertEquals("karim", rows.get(0).get("cashierName"), "no full name: the user name");
		assertEquals(4L, page.get("totalElements"));
		assertEquals("4 39.000 1.400 37.600 36.300 1.300", amounts(totals(page)));
	}

	@Test
	@DisplayName("The period (the payment's time), as the tickets history sends it or as days")
	void period() {
		String from = TODAY.minusDays(1).atStartOfDay().toString().substring(0, 16);
		assertEquals(Arrays.asList("03", "02", "01"), barcodesEnd(content(service.page(filters(from, null, null, null,
				null, null), 0, 20))));
		Map<String, Object> yesterday = service.page(filters(TODAY.minusDays(1).toString(), TODAY.minusDays(1).toString(),
				null, null, null, null), 0, 20);
		assertEquals(Arrays.asList("02", "01"), barcodesEnd(content(yesterday)));
		assertEquals("2 12.000 0.700 11.300 10.000 1.300", amounts(totals(yesterday)));
	}

	@Test
	@DisplayName("The issuer, the payment method and the cashier")
	void issuerMethodCashier() {
		assertEquals(Arrays.asList("01", "99"), barcodesEnd(content(service.page(
				filters(null, null, "Servimax Repas", null, null, null), 0, 20))));
		assertEquals(Arrays.asList("03"), barcodesEnd(content(service.page(
				filters(null, null, null, gift.getId().toString(), null, null), 0, 20))));
		assertEquals(Arrays.asList("02", "01", "99"), barcodesEnd(content(service.page(
				filters(null, null, null, null, amira.getId().toString(), null), 0, 20))));
		assertEquals(Arrays.asList("03", "02", "01", "99"), barcodesEnd(content(service.page(
				filters(null, null, null, "all", "x", null), 0, 20))), "'all' or unreadable: no filter");
	}

	@Test
	@DisplayName("The barcode: whole or its last digits; the period left out; nothing for the middle of a barcode")
	void barcode() {
		String today = TODAY.toString();
		assertEquals(Arrays.asList("99"), barcodesEnd(content(service.page(
				filters(today, today, null, null, null, "0310070002600000000000000099"), 0, 20))), "whole, 40 days ago");
		assertEquals(Arrays.asList("99"), barcodesEnd(content(service.page(filters(today, today, null, null, null, " 099 "),
				0, 20))), "the last digits, trimmed");
		assertEquals(0L, service.page(filters(null, null, null, null, null, "0070002"), 0, 20).get("totalElements"));
		assertEquals(0L, service.page(filters(null, null, null, null, null, "%"), 0, 20).get("totalElements"),
				"no wildcard");
		assertEquals("1 7.000 0.700 6.300 6.300 0.000",
				amounts(totals(service.page(filters(null, null, null, null, null, "99"), 0, 20))));
	}

	@Test
	@DisplayName("Paging on the server: the page's rows, the totals and count of the whole result")
	void paging() {
		Map<String, Object> second = service.page(none(), 1, 3);
		assertEquals(Arrays.asList("99"), barcodesEnd(content(second)));
		assertEquals(4L, second.get("totalElements"));
		assertEquals(2L, second.get("totalPages"));
		assertEquals(1, second.get("number"));
		assertEquals("4 39.000 1.400 37.600 36.300 1.300", amounts(totals(second)));
	}

	@Test
	@DisplayName("The export: every row of the filters, newest first; cut at the limit, said; the totals of all")
	@SuppressWarnings("unchecked")
	void export() {
		Map<String, Object> all = service.export(none(), ScannedTicketsHistoryService.MAX_EXPORT);
		assertEquals(4, ((List<Object>) all.get("rows")).size());
		assertEquals(false, all.get("truncated"));
		Map<String, Object> cut = service.export(none(), 3);
		assertEquals(Arrays.asList("03", "02", "01"), barcodesEnd((List<Map<String, Object>>) cut.get("rows")));
		assertEquals(true, cut.get("truncated"));
		assertEquals(4L, cut.get("total"));
		assertEquals(3, cut.get("limit"));
		assertEquals("4 39.000 1.400 37.600 36.300 1.300", amounts(totals(cut)));
	}

	@Test
	@DisplayName("The issuers met, for the filter, sorted")
	void issuers() {
		assertEquals(Arrays.asList("Cadeau Plus", "Servimax Repas", "Sodexo Repas"), service.issuers());
	}

	@Test
	@DisplayName("Dates: a datetime-local, a day (start or end), nothing or unreadable")
	void dates() {
		assertEquals(LocalDateTime.of(2026, 11, 13, 8, 30), ScannedTicketsHistoryService.dateTime("2026-11-13T08:30", false));
		assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), ScannedTicketsHistoryService.dateTime("2026-11-13", false));
		assertEquals(LocalDateTime.of(2026, 11, 13, 23, 59, 59), ScannedTicketsHistoryService.dateTime("2026-11-13", true));
		assertNull(ScannedTicketsHistoryService.dateTime(" ", false));
		assertNull(ScannedTicketsHistoryService.dateTime("13/11/2026", false));
	}

	@Test
	@DisplayName("The API: 403 without read:admin-scanned-tickets, the page with it")
	void permission() throws Exception {
		CurrentUserProvider without = new CurrentUserProvider() {
			@Override
			public boolean currentUserHasPermission(String permission) {
				return false;
			}
		};
		CurrentUserProvider with = new CurrentUserProvider() {
			@Override
			public boolean currentUserHasPermission(String permission) {
				return "read:admin-scanned-tickets".equals(permission);
			}
		};
		ScannedTicketsHistoryAPI refusing = api(without);
		assertEquals(403, refusing.page(null, null, null, null, null, null, 0, 20).getStatusCodeValue());
		assertEquals(403, refusing.export(null, null, null, null, null, null).getStatusCodeValue());
		assertEquals(403, refusing.issuers().getStatusCodeValue());
		ResponseEntity<?> allowed = api(with).page(null, null, null, null, null, "99", 0, 20);
		assertEquals(200, allowed.getStatusCodeValue());
		assertEquals(1L, ((Map<?, ?>) allowed.getBody()).get("totalElements"));
	}

	private static ScannedTicketsHistoryAPI api(CurrentUserProvider user) throws Exception {
		ScannedTicketsHistoryAPI api = new ScannedTicketsHistoryAPI();
		for (Object[] field : new Object[][] { { "service", service }, { "currentUserProvider", user } }) {
			Field f = ScannedTicketsHistoryAPI.class.getDeclaredField((String) field[0]);
			f.setAccessible(true);
			f.set(api, field[1]);
		}
		return api;
	}
}
