package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.InvoiceDetailsDTO;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.InvoiceHeader;
import com.digithink.zsretail.model.InvoiceLine;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.InvoiceHeaderRepository;
import com.digithink.zsretail.repository.InvoiceLineRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class InvoiceService extends _BaseService<InvoiceHeader, Long> {

	@Autowired
	private InvoiceHeaderRepository invoiceHeaderRepository;

	@Autowired
	private InvoiceLineRepository invoiceLineRepository;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private ApplicationModeService applicationModeService;

	/** Snapshot data passed by caller before invoice creation (editable customer info). */
	@Data
	public static class InvoiceSnapshotData {
		private String name;
		private String address;
		private String phone;
		private String taxRegNo;
	}

	@Override
	protected _BaseRepository<InvoiceHeader, Long> getRepository() {
		return invoiceHeaderRepository;
	}

	/**
	 * Find completed, non-invoiced tickets for a customer in a date range.
	 */
	public List<SalesHeader> findEligibleTickets(Long customerId, LocalDate from, LocalDate to) {
		Customer customer = customerRepository.findById(customerId)
				.orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

		LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : LocalDate.MIN.atStartOfDay();
		LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : LocalDate.MAX.atTime(LocalTime.MAX);

		List<SalesHeader> tickets = salesHeaderRepository
				.findBySalesDateBetweenAndStatus(fromDateTime, toDateTime, TransactionStatus.COMPLETED);

		return tickets.stream()
				.filter(t -> t.getCustomer() != null && t.getCustomer().getId().equals(customer.getId()))
				.filter(t -> t.getInvoice() == null && !Boolean.TRUE.equals(t.getInvoiced()))
				.sorted(Comparator.comparing(SalesHeader::getSalesDate))
				.collect(Collectors.toList());
	}

	/**
	 * Create an invoice from a set of ticket ids with optional snapshot data.
	 */
	@Transactional(rollbackFor = Exception.class)
	public InvoiceHeader createInvoice(Long customerId, List<Long> ticketIds, LocalDate invoiceDate, String notes,
			InvoiceLineGroupingMode lineGroupingMode, UserAccount createdByUser, InvoiceSnapshotData snapshot) throws Exception {

		if (ticketIds == null || ticketIds.isEmpty()) {
			throw new IllegalArgumentException("At least one ticket must be selected to create an invoice");
		}

		Customer customer = customerRepository.findById(customerId)
				.orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

		List<SalesHeader> tickets = salesHeaderRepository.findAllById(ticketIds);
		if (tickets.size() != ticketIds.size()) {
			throw new IllegalArgumentException("Some tickets were not found");
		}

		// Validate tickets all belong to customer, are completed, and not yet invoiced
		for (SalesHeader ticket : tickets) {
			if (ticket.getCustomer() == null || !ticket.getCustomer().getId().equals(customer.getId())) {
				throw new IllegalArgumentException("All tickets must belong to the same customer");
			}
			if (ticket.getStatus() != TransactionStatus.COMPLETED) {
				throw new IllegalArgumentException("All tickets must be completed to be invoiced");
			}
			if (ticket.getInvoice() != null || Boolean.TRUE.equals(ticket.getInvoiced())) {
				throw new IllegalArgumentException("Ticket " + ticket.getSalesNumber() + " is already invoiced");
			}
		}

		InvoiceLineGroupingMode effectiveMode = lineGroupingMode != null ? lineGroupingMode
				: InvoiceLineGroupingMode.BY_ITEM;

		String invoiceNumber = generateNextInvoiceNumber(invoiceDate != null ? invoiceDate : LocalDate.now());

		InvoiceHeader invoice = new InvoiceHeader();
		invoice.setInvoiceNumber(invoiceNumber);
		invoice.setInvoiceDate(invoiceDate != null ? invoiceDate : LocalDate.now());
		invoice.setCustomer(customer);
		invoice.setCreatedByUser(createdByUser);
		invoice.setLineGroupingMode(effectiveMode);
		invoice.setNotes(notes);

		// Franchise admin: auto-tag invoice with the customer's default location code
		// so franchise clients can pull it via the sync API. No impact in normal mode.
		if (applicationModeService.isFranchiseAdmin()
				&& customer.getDefaultLocation() != null
				&& !customer.getDefaultLocation().isBlank()) {
			invoice.setFranchiseLocationCode(customer.getDefaultLocation());
		}

		// Apply snapshot data (fall back to customer FK data)
		if (snapshot != null) {
			invoice.setSnapshotCustomerName(notBlank(snapshot.getName()) ? snapshot.getName() : customer.getName());
			invoice.setSnapshotCustomerAddress(notBlank(snapshot.getAddress()) ? snapshot.getAddress() : customer.getAddress());
			invoice.setSnapshotCustomerPhone(notBlank(snapshot.getPhone()) ? snapshot.getPhone() : customer.getPhone());
			invoice.setSnapshotCustomerTaxRegNo(notBlank(snapshot.getTaxRegNo()) ? snapshot.getTaxRegNo() : customer.getTaxRegistrationNo());
		} else {
			invoice.setSnapshotCustomerName(customer.getName());
			invoice.setSnapshotCustomerAddress(customer.getAddress());
			invoice.setSnapshotCustomerPhone(customer.getPhone());
			invoice.setSnapshotCustomerTaxRegNo(customer.getTaxRegistrationNo());
		}

		invoice = save(invoice);

		// Build invoice lines based on selected tickets and grouping mode
		List<SalesLine> allLines = new ArrayList<>();
		for (SalesHeader ticket : tickets) {
			List<SalesLine> ticketLines = salesLineRepository.findBySalesHeader(ticket);
			allLines.addAll(ticketLines);
		}

		List<InvoiceLine> invoiceLines = buildInvoiceLines(invoice, allLines, effectiveMode);

		// Compute totals
		double subtotal = invoiceLines.stream().mapToDouble(l -> l.getSubtotal() != null ? l.getSubtotal() : 0.0)
				.sum();
		double taxAmount = invoiceLines.stream().mapToDouble(l -> l.getTaxAmount() != null ? l.getTaxAmount() : 0.0)
				.sum();
		double total = invoiceLines.stream().mapToDouble(l -> l.getTotalAmount() != null ? l.getTotalAmount() : 0.0)
				.sum();

		invoice.setSubtotal(subtotal);
		invoice.setTaxAmount(taxAmount);
		invoice.setTotalAmount(total);

		// Persist header and lines
		invoice = save(invoice);
		for (InvoiceLine line : invoiceLines) {
			invoiceLineRepository.save(line);
		}

		// Link tickets and mark as invoiced
		for (SalesHeader ticket : tickets) {
			ticket.setInvoice(invoice);
			ticket.setInvoiceNumber(invoice.getInvoiceNumber());
			ticket.setInvoiced(true);
			salesHeaderRepository.save(ticket);
		}

		return invoice;
	}

	/** Backward-compatible overload without snapshot data. */
	@Transactional(rollbackFor = Exception.class)
	public InvoiceHeader createInvoice(Long customerId, List<Long> ticketIds, LocalDate invoiceDate, String notes,
			InvoiceLineGroupingMode lineGroupingMode, UserAccount createdByUser) throws Exception {
		return createInvoice(customerId, ticketIds, invoiceDate, notes, lineGroupingMode, createdByUser, null);
	}

	/**
	 * Helper: create invoice for a single ticket, defaulting to BY_ITEM grouping.
	 */
	@Transactional(rollbackFor = Exception.class)
	public InvoiceHeader createInvoiceFromTicket(Long ticketId, LocalDate invoiceDate, String notes,
			UserAccount createdByUser, InvoiceSnapshotData snapshot) throws Exception {

		SalesHeader ticket = salesHeaderRepository.findById(ticketId)
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

		if (ticket.getCustomer() == null) {
			throw new IllegalArgumentException("Ticket has no customer and cannot be invoiced");
		}

		List<Long> singleTicket = new ArrayList<>();
		singleTicket.add(ticketId);

		return createInvoice(ticket.getCustomer().getId(), singleTicket,
				invoiceDate != null ? invoiceDate : LocalDate.now(), notes,
				InvoiceLineGroupingMode.BY_ITEM, createdByUser, snapshot);
	}

	/** Backward-compatible overload without snapshot data. */
	@Transactional(rollbackFor = Exception.class)
	public InvoiceHeader createInvoiceFromTicket(Long ticketId, LocalDate invoiceDate, String notes,
			UserAccount createdByUser) throws Exception {
		return createInvoiceFromTicket(ticketId, invoiceDate, notes, createdByUser, null);
	}

	/**
	 * Builds invoice lines according to the configured grouping mode.
	 */
	protected List<InvoiceLine> buildInvoiceLines(InvoiceHeader invoice, List<SalesLine> lines,
			InvoiceLineGroupingMode mode) {

		List<InvoiceLine> result = new ArrayList<>();

		switch (mode) {
		case BY_ITEM:
			result.addAll(buildLinesGroupedByItem(invoice, lines));
			break;
		case BY_FAMILY:
			result.addAll(buildLinesGroupedByFamily(invoice, lines));
			break;
		case BY_SUBFAMILY:
			result.addAll(buildLinesGroupedBySubFamily(invoice, lines));
			break;
		case NO_GROUPING:
		default:
			result.addAll(buildLinesWithoutGrouping(invoice, lines));
			break;
		}

		return result;
	}

	private List<InvoiceLine> buildLinesGroupedByItem(InvoiceHeader invoice, List<SalesLine> lines) {
		Map<Item, AggregatedAmounts> byItem = new HashMap<>();
		for (SalesLine sl : lines) {
			Item item = sl.getItem();
			if (item == null) {
				continue;
			}
			AggregatedAmounts agg = byItem.computeIfAbsent(item, k -> new AggregatedAmounts());
			agg.add(sl);
		}

		List<InvoiceLine> result = new ArrayList<>();
		for (Map.Entry<Item, AggregatedAmounts> entry : byItem.entrySet()) {
			Item item = entry.getKey();
			AggregatedAmounts agg = entry.getValue();

			InvoiceLine il = new InvoiceLine();
			il.setInvoice(invoice);
			il.setItem(item);
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.averageUnitPrice());
			il.setUnitPriceIncludingVat(agg.averageUnitPriceIncludingVat());
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);

			result.add(il);
		}
		return result;
	}

	private List<InvoiceLine> buildLinesGroupedByFamily(InvoiceHeader invoice, List<SalesLine> lines) {
		Map<ItemFamily, AggregatedAmounts> byFamily = new HashMap<>();
		for (SalesLine sl : lines) {
			Item item = sl.getItem();
			if (item == null) {
				continue;
			}
			ItemFamily family = item.getItemFamily();
			AggregatedAmounts agg = byFamily.computeIfAbsent(family, k -> new AggregatedAmounts());
			agg.add(sl);
		}

		List<InvoiceLine> result = new ArrayList<>();
		for (Map.Entry<ItemFamily, AggregatedAmounts> entry : byFamily.entrySet()) {
			ItemFamily family = entry.getKey();
			AggregatedAmounts agg = entry.getValue();

			InvoiceLine il = new InvoiceLine();
			il.setInvoice(invoice);
			il.setItemFamily(family);
			il.setLineDescription(family != null ? family.getName() : "Non classé");
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.quantity > 0 ? agg.subtotal / agg.quantity : 0.0);
			il.setUnitPriceIncludingVat(agg.quantity > 0 ? agg.totalAmount / agg.quantity : 0.0);
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);

			result.add(il);
		}
		return result;
	}

	private List<InvoiceLine> buildLinesGroupedBySubFamily(InvoiceHeader invoice, List<SalesLine> lines) {
		Map<ItemSubFamily, AggregatedAmounts> bySubFamily = new HashMap<>();
		for (SalesLine sl : lines) {
			Item item = sl.getItem();
			if (item == null) {
				continue;
			}
			ItemSubFamily subFamily = item.getItemSubFamily();
			AggregatedAmounts agg = bySubFamily.computeIfAbsent(subFamily, k -> new AggregatedAmounts());
			agg.add(sl);
		}

		List<InvoiceLine> result = new ArrayList<>();
		for (Map.Entry<ItemSubFamily, AggregatedAmounts> entry : bySubFamily.entrySet()) {
			ItemSubFamily subFamily = entry.getKey();
			AggregatedAmounts agg = entry.getValue();

			InvoiceLine il = new InvoiceLine();
			il.setInvoice(invoice);
			il.setItemSubFamily(subFamily);
			il.setLineDescription(subFamily != null ? subFamily.getName() : "Non classé");
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.quantity > 0 ? agg.subtotal / agg.quantity : 0.0);
			il.setUnitPriceIncludingVat(agg.quantity > 0 ? agg.totalAmount / agg.quantity : 0.0);
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);

			result.add(il);
		}
		return result;
	}

	private List<InvoiceLine> buildLinesWithoutGrouping(InvoiceHeader invoice, List<SalesLine> lines) {
		List<InvoiceLine> result = new ArrayList<>();
		for (SalesLine sl : lines) {
			InvoiceLine il = new InvoiceLine();
			il.setInvoice(invoice);
			il.setItem(sl.getItem());
			il.setQuantity(sl.getQuantity());
			il.setUnitPrice(sl.getUnitPrice());
			il.setUnitPriceIncludingVat(sl.getUnitPriceIncludingVat());
			il.setSubtotal(sl.getLineTotal());
			il.setTaxAmount(sl.getVatAmount());
			il.setTotalAmount(sl.getLineTotalIncludingVat());
			il.setLineTotalIncludingVat(sl.getLineTotalIncludingVat());
			il.setVatPercent(sl.getVatPercent());
			result.add(il);
		}
		return result;
	}

	/**
	 * DTO-like helper aggregating monetary values while grouping lines.
	 */
	private static class AggregatedAmounts {
		int quantity = 0;
		double subtotal = 0.0;
		double taxAmount = 0.0;
		double totalAmount = 0.0;
		Integer vatPercent = null;
		double unitPriceSum = 0.0;
		double unitPriceIncludingVatSum = 0.0;
		int unitPriceCount = 0;

		void add(SalesLine line) {
			if (line.getQuantity() != null) {
				quantity += line.getQuantity();
			}
			if (line.getLineTotal() != null) {
				subtotal += line.getLineTotal();
			}
			if (line.getVatAmount() != null) {
				taxAmount += line.getVatAmount();
			}
			if (line.getLineTotalIncludingVat() != null) {
				totalAmount += line.getLineTotalIncludingVat();
			}
			if (line.getVatPercent() != null) {
				vatPercent = line.getVatPercent();
			}
			if (line.getUnitPrice() != null) {
				unitPriceSum += line.getUnitPrice();
				unitPriceCount++;
			}
			if (line.getUnitPriceIncludingVat() != null) {
				unitPriceIncludingVatSum += line.getUnitPriceIncludingVat();
			}
		}

		double averageUnitPrice() {
			return unitPriceCount > 0 ? unitPriceSum / unitPriceCount : 0.0;
		}

		double averageUnitPriceIncludingVat() {
			return unitPriceCount > 0 ? unitPriceIncludingVatSum / unitPriceCount : 0.0;
		}
	}

	/**
	 * Simple invoice number generation: YEAR-XXXXXX sequential per year.
	 */
	protected String generateNextInvoiceNumber(LocalDate invoiceDate) {
		int year = invoiceDate.getYear();
		String prefix = year + "-";

		List<InvoiceHeader> sameYear = invoiceHeaderRepository.findAll().stream()
				.filter(i -> i.getInvoiceDate() != null && i.getInvoiceDate().getYear() == year)
				.collect(Collectors.toList());

		int maxSeq = sameYear.stream().map(InvoiceHeader::getInvoiceNumber)
				.filter(n -> n != null && n.startsWith(prefix))
				.map(n -> n.substring(prefix.length()))
				.filter(s -> s.matches("\\d+"))
				.mapToInt(Integer::parseInt)
				.max()
				.orElse(0);

		int nextSeq = maxSeq + 1;
		return prefix + String.format("%06d", nextSeq);
	}

	/**
	 * Paged listing of invoices for admin UI.
	 */
	public Page<InvoiceHeader> listInvoices(LocalDate from, LocalDate to, Long customerId, String invoiceNumber,
			int page, int size) {
		Pageable pageable = PageRequest.of(page, size);

		LocalDate fromDate = from != null ? from : LocalDate.MIN;
		LocalDate toDate = to != null ? to : LocalDate.MAX;

		Page<InvoiceHeader> basePage;

		if (customerId != null) {
			Customer customer = customerRepository.findById(customerId)
					.orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
			basePage = invoiceHeaderRepository.findByCustomerAndInvoiceDateBetween(customer, fromDate, toDate,
					pageable);
		} else {
			basePage = invoiceHeaderRepository.findByInvoiceDateBetween(fromDate, toDate, pageable);
		}

		if (invoiceNumber == null || invoiceNumber.trim().isEmpty()) {
			return basePage;
		}

		List<InvoiceHeader> filtered = basePage.getContent().stream()
				.filter(i -> i.getInvoiceNumber() != null
						&& i.getInvoiceNumber().toLowerCase().contains(invoiceNumber.toLowerCase()))
				.collect(Collectors.toList());

		return new PageImpl<>(filtered, pageable, filtered.size());
	}

	/**
	 * Load invoice with its lines and linked tickets for detail/print.
	 */
	public Map<String, Object> getInvoiceDetails(Long id) {
		InvoiceHeader invoice = invoiceHeaderRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id));

		List<InvoiceLine> lines = invoiceLineRepository.findByInvoice(invoice);

		List<SalesHeader> tickets = salesHeaderRepository.findByCustomer(invoice.getCustomer()).stream()
				.filter(t -> invoice.equals(t.getInvoice()))
				.sorted(Comparator.comparing(SalesHeader::getSalesDate))
				.collect(Collectors.toList());

		InvoiceDetailsDTO.InvoiceHeaderDetail invoiceDto = toHeaderDetail(invoice);
		List<InvoiceDetailsDTO.InvoiceLineDetail> lineDtos = lines.stream()
				.map(this::toLineDetail)
				.collect(Collectors.toList());
		List<InvoiceDetailsDTO.TicketSummary> ticketDtos = tickets.stream()
				.map(this::toTicketSummary)
				.collect(Collectors.toList());

		Map<String, Object> result = new HashMap<>();
		result.put("invoice", invoiceDto);
		result.put("lines", lineDtos);
		result.put("tickets", ticketDtos);
		return result;
	}

	private InvoiceDetailsDTO.InvoiceHeaderDetail toHeaderDetail(InvoiceHeader h) {
		InvoiceDetailsDTO.CustomerSummary customerSummary = null;
		Customer c = h.getCustomer();
		if (c != null) {
			customerSummary = new InvoiceDetailsDTO.CustomerSummary(
					c.getId(), c.getName(), c.getCustomerCode(),
					c.getAddress(), c.getPhone(), c.getTaxRegistrationNo());
		}
		InvoiceDetailsDTO.UserSummary userSummary = null;
		UserAccount u = h.getCreatedByUser();
		if (u != null) {
			userSummary = new InvoiceDetailsDTO.UserSummary(u.getId(), u.getUsername(), u.getFullName());
		}
		return new InvoiceDetailsDTO.InvoiceHeaderDetail(
				h.getId(),
				h.getInvoiceNumber(),
				h.getInvoiceDate(),
				customerSummary,
				userSummary,
				h.getLineGroupingMode(),
				h.getSubtotal(),
				h.getTaxAmount(),
				h.getDiscountAmount(),
				h.getTotalAmount(),
				h.getNotes(),
				h.getSnapshotCustomerName(),
				h.getSnapshotCustomerAddress(),
				h.getSnapshotCustomerPhone(),
				h.getSnapshotCustomerTaxRegNo());
	}

	private InvoiceDetailsDTO.InvoiceLineDetail toLineDetail(InvoiceLine line) {
		InvoiceDetailsDTO.ItemSummary itemSummary = null;
		if (line.getItem() != null) {
			Item i = line.getItem();
			itemSummary = new InvoiceDetailsDTO.ItemSummary(i.getId(), i.getName(), i.getItemCode());
		}
		InvoiceDetailsDTO.ItemFamilySummary familySummary = null;
		if (line.getItemFamily() != null) {
			ItemFamily f = line.getItemFamily();
			familySummary = new InvoiceDetailsDTO.ItemFamilySummary(f.getId(), f.getName());
		}
		InvoiceDetailsDTO.ItemSubFamilySummary subFamilySummary = null;
		if (line.getItemSubFamily() != null) {
			ItemSubFamily s = line.getItemSubFamily();
			subFamilySummary = new InvoiceDetailsDTO.ItemSubFamilySummary(s.getId(), s.getName());
		}
		return new InvoiceDetailsDTO.InvoiceLineDetail(
				line.getId(),
				itemSummary,
				familySummary,
				subFamilySummary,
				line.getLineDescription(),
				line.getQuantity(),
				line.getUnitPrice(),
				line.getUnitPriceIncludingVat(),
				line.getSubtotal(),
				line.getTaxAmount(),
				line.getTotalAmount(),
				line.getLineTotalIncludingVat(),
				line.getVatPercent());
	}

	private InvoiceDetailsDTO.TicketSummary toTicketSummary(SalesHeader t) {
		return new InvoiceDetailsDTO.TicketSummary(
				t.getId(),
				t.getSalesNumber(),
				t.getSalesDate(),
				t.getTotalAmount());
	}

	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}
