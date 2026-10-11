package com.digithink.zsretail.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.InvoiceHeaderRepository;
import com.digithink.zsretail.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.VendorRepository;

/**
 * L2 of step 6: without a date filter, the invoice and purchase invoice lists and the eligible tickets and purchases
 * asked SQL Server for LocalDate.MIN / MAX, outside the range of its date and datetime2 types (500). The bounds are now
 * 0001-01-01 and 9999-12-31; a date given is used as before. Repository stubs record the bounds; no database.
 */
class InvoiceDateBoundsTest {

	private static final LocalDate FIRST = LocalDate.of(1, 1, 1);
	private static final LocalDate LAST = LocalDate.of(9999, 12, 31);

	private final List<Object> bounds = new ArrayList<>();

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private InvoiceService invoices() throws Exception {
		InvoiceService service = new InvoiceService();
		set(service, InvoiceService.class, "invoiceHeaderRepository", proxy(InvoiceHeaderRepository.class, (m, a) -> {
			if ("findByInvoiceDateBetween".equals(m)) {
				bounds.add(a[0]);
				bounds.add(a[1]);
				return new PageImpl<>(Collections.emptyList());
			}
			return UNHANDLED;
		}));
		set(service, InvoiceService.class, "salesHeaderRepository", proxy(SalesHeaderRepository.class, (m, a) -> {
			if ("findBySalesDateBetweenAndStatus".equals(m)) {
				bounds.add(a[0]);
				bounds.add(a[1]);
				return Collections.emptyList();
			}
			return UNHANDLED;
		}));
		set(service, InvoiceService.class, "customerRepository", proxy(CustomerRepository.class,
				(m, a) -> "findById".equals(m) ? Optional.of(new Customer()) : UNHANDLED));
		return service;
	}

	private PurchaseInvoiceService purchaseInvoices() throws Exception {
		PurchaseInvoiceService service = new PurchaseInvoiceService();
		set(service, PurchaseInvoiceService.class, "purchaseInvoiceHeaderRepository",
				proxy(PurchaseInvoiceHeaderRepository.class, (m, a) -> {
					if ("findByInvoiceDateBetween".equals(m)) {
						bounds.add(a[0]);
						bounds.add(a[1]);
						return new PageImpl<>(Collections.emptyList());
					}
					return UNHANDLED;
				}));
		set(service, PurchaseInvoiceService.class, "purchaseHeaderRepository", proxy(PurchaseHeaderRepository.class, (m, a) -> {
			if ("findByVendorAndPurchaseDateBetweenAndStatus".equals(m)) {
				bounds.add(a[1]);
				bounds.add(a[2]);
				return Collections.emptyList();
			}
			return UNHANDLED;
		}));
		set(service, PurchaseInvoiceService.class, "vendorRepository", proxy(VendorRepository.class,
				(m, a) -> "findById".equals(m) ? Optional.of(new Vendor()) : UNHANDLED));
		return service;
	}

	@Test
	@DisplayName("Invoices: the list and the eligible tickets without dates use 0001-01-01 and 9999-12-31; dates given are kept")
	void invoicesWithoutDates() throws Exception {
		InvoiceService service = invoices();
		service.listInvoices(null, null, null, null, 0, 10);
		assertEquals(FIRST, bounds.get(0));
		assertEquals(LAST, bounds.get(1));
		service.findEligibleTickets(1L, null, null);
		assertEquals(FIRST.atStartOfDay(), bounds.get(2));
		assertEquals(LAST.atStartOfDay(), bounds.get(3));
		LocalDate day = LocalDate.of(2026, 10, 4);
		service.listInvoices(day, day, null, null, 0, 10);
		assertEquals(day, bounds.get(4));
		assertEquals(day, bounds.get(5));
		service.findEligibleTickets(1L, day, day);
		assertEquals(day.atStartOfDay(), bounds.get(6));
		assertEquals(LocalDateTime.of(2026, 10, 4, 23, 59, 59, 999999999), bounds.get(7));
	}

	@Test
	@DisplayName("Purchase invoices (be626d3): the list and the eligible purchases without dates use the same bounds")
	void purchaseInvoicesWithoutDates() throws Exception {
		PurchaseInvoiceService service = purchaseInvoices();
		service.listPurchaseInvoices(null, null, null, null, 0, 10);
		assertEquals(FIRST, bounds.get(0));
		assertEquals(LAST, bounds.get(1));
		service.findEligiblePurchases(1L, null, null);
		assertEquals(FIRST.atStartOfDay(), bounds.get(2));
		assertEquals(LAST.atStartOfDay(), bounds.get(3));
	}
}
