package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoiceLine;
import com.digithink.zsretail.headoffice.repository.HoSupplyInvoiceRepository;

/**
 * Test support (step 7B): ho_supply_invoice in memory (its lines saved with it, as the cascade does), applying the rules
 * of the JPQL queries of {@link HoSupplyInvoiceRepository}.
 */
public final class InMemoryInvoices {

	public final Map<Long, HoSupplyInvoice> invoices = new LinkedHashMap<>();
	private long nextId = 700_000;

	public HoSupplyInvoice byNumber(String number) {
		return invoices.values().stream().filter(i -> i.getInvoiceNumber().equals(number)).findFirst().orElse(null);
	}

	public HoSupplyInvoiceRepository repository() {
		return proxy(HoSupplyInvoiceRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(invoices.get(args[0]));
				case "save": {
					HoSupplyInvoice invoice = (HoSupplyInvoice) args[0];
					if (invoice.getId() == null) {
						if (invoices.values().stream().anyMatch(i -> i.getInvoiceNumber().equals(invoice.getInvoiceNumber()))) {
							throw new IllegalStateException("uk_ho_supply_invoice_number");
						}
						invoice.setId(nextId++);
					}
					for (HoSupplyInvoiceLine line : invoice.getLines()) {
						if (line.getId() == null) {
							line.setId(nextId++);
						}
					}
					invoices.put(invoice.getId(), invoice);
					return invoice;
				}
				case "findFirstByOrderByInvoiceDateDescIdDesc":
					return invoices.values().stream().max(Comparator.comparing(HoSupplyInvoice::getInvoiceDate)
							.thenComparing(HoSupplyInvoice::getId));
				case "findOfStore":
					return invoices.values().stream().filter(i -> i.getStoreId().equals(args[0])
							&& ((Collection<?>) args[1]).contains(i.getInvoiceNumber())).collect(Collectors.toList());
				case "findTargets":
					return invoices.values().stream().map(i -> new Object[] { i.getInvoiceNumber(), i.getStoreId() })
							.collect(Collectors.toList());
				case "findPage": {
					long storeId = (Long) args[0];
					boolean any = (Long) args[1] == 1L;
					LocalDate from = (LocalDate) args[3];
					LocalDate to = (LocalDate) args[4];
					List<HoSupplyInvoice> rows = invoices.values().stream()
							.filter(i -> storeId == 0 || i.getStoreId() == storeId)
							.filter(i -> any || i.getPaid().equals(args[2]))
							.filter(i -> !i.getInvoiceDate().isBefore(from) && !i.getInvoiceDate().isAfter(to))
							.sorted(Comparator.comparing(HoSupplyInvoice::getId).reversed()).collect(Collectors.toList());
					Pageable page = (Pageable) args[5];
					int start = (int) Math.min(rows.size(), page.getOffset());
					int end = Math.min(rows.size(), start + page.getPageSize());
					return new PageImpl<>(new ArrayList<>(rows.subList(start, end)), page, rows.size());
				}
				case "balances": {
					Map<String, Object[]> groups = new LinkedHashMap<>();
					for (HoSupplyInvoice i : invoices.values()) {
						Object[] row = groups.computeIfAbsent(i.getStoreId() + "/" + i.getPaid(),
								k -> new Object[] { i.getStoreId(), i.getPaid(), 0L, 0.0 });
						row[2] = (Long) row[2] + 1;
						row[3] = (Double) row[3] + i.getTotalAmount();
					}
					return new ArrayList<>(groups.values());
				}
				default:
					return UNHANDLED;
			}
		});
	}
}
