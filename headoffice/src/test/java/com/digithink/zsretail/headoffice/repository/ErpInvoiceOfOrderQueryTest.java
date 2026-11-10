package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;

/**
 * 3.1.1: the invoices of an ERP order number (HoErpInvoiceRepository.OF_ORDER, native, the number cast to varchar so SQL
 * Server seeks ix_ho_erp_invoice_order), run as it is with Hibernate over an in-memory H2 database (no Spring context),
 * its rows read by OrderInvoice.fromRows as the repository's invoicesOfOrder reads them.
 */
class ErpInvoiceOfOrderQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(HoErpInvoice.class).addAnnotatedClass(HoErpInvoiceLine.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:erpinvoiceorder;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.persist(invoice("FVE2600012", "CV26000004", LocalDate.of(2026, 10, 29)));
			session.persist(invoice("FVE2600010", "CV26000004", null));
			session.persist(invoice("FVE2600011", "CV26000005", LocalDate.of(2026, 10, 28)));
			session.persist(invoice("FVE2600009", null, LocalDate.of(2026, 10, 27)));
			session.getTransaction().commit();
		}
	}

	private static HoErpInvoice invoice(String number, String orderNo, LocalDate postingDate) {
		HoErpInvoice invoice = new HoErpInvoice();
		invoice.setBcNumber(number);
		invoice.setOrderNo(orderNo);
		invoice.setPostingDate(postingDate);
		invoice.setCustomerNo("4110001");
		invoice.setStatus(ErpInvoiceStatus.READ);
		invoice.setHeld(false);
		return invoice;
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	@SuppressWarnings("unchecked")
	private static List<Object[]> rows(String orderNo) {
		try (Session session = sessions.openSession()) {
			return session.createNativeQuery(HoErpInvoiceRepository.OF_ORDER).setParameter("orderNo", orderNo).list();
		}
	}

	/** The rows of the query read as the repository's invoicesOfOrder reads them. */
	private static List<HoErpInvoiceRepository.OrderInvoice> invoicesOfOrder(String orderNo) {
		return HoErpInvoiceRepository.OrderInvoice.fromRows(rows(orderNo));
	}

	@Test
	@DisplayName("The number cast to varchar(50) (a seek on SQL Server), the invoices by number")
	void cast() {
		assertTrue(HoErpInvoiceRepository.OF_ORDER.contains("order_no = cast(:orderNo as varchar(50))"));
		assertTrue(HoErpInvoiceRepository.OF_ORDER.endsWith("order by bc_number"));
	}

	@Test
	@DisplayName("The invoices of that order number only, by number, their posting date a LocalDate (none when absent)")
	void invoicesOfTheOrder() {
		List<HoErpInvoiceRepository.OrderInvoice> found = invoicesOfOrder("CV26000004");
		assertEquals(java.util.Arrays.asList("FVE2600010", "FVE2600012"),
				found.stream().map(HoErpInvoiceRepository.OrderInvoice::getNumber).collect(Collectors.toList()));
		assertEquals(null, found.get(0).getPostingDate());
		assertEquals(LocalDate.of(2026, 10, 29), found.get(1).getPostingDate());
		assertEquals("4110001", found.get(1).getCustomerNo());
		assertEquals(1, invoicesOfOrder("CV26000005").size());
		assertTrue(invoicesOfOrder("CV26000099").isEmpty());
	}
}
