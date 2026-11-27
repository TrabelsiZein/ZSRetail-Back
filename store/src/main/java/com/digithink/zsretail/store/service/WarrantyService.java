package com.digithink.zsretail.store.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.model.Warranty;
import com.digithink.zsretail.store.repository.WarrantyRepository;

@Service
public class WarrantyService extends _BaseService<Warranty, Long> {

	@Autowired
	private WarrantyRepository warrantyRepository;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Override
	protected com.digithink.zsretail.core.repository._BaseRepository<Warranty, Long> getRepository() {
		return warrantyRepository;
	}

	/**
	 * 3.5.0 (ticket templates, the warranty ticket): the share of a sold line's TTC the customer really paid after the
	 * sale's ticket discount, the returns screen's rule (ReturnProducts.returnDiscountFactor, ReturnHeaderService): a
	 * ticket paid partly with loyalty points, the ticket discount without the points (loyaltyHeaderFactor: the points
	 * are a payment, as the returns screen shows them apart); else the ticket's percentage; else a ticket discount amount,
	 * the ticket's total over its lines' TTC. Null when the ticket has no discount (the warranty prints as before). The
	 * sale's lines are read only for an amount or points.
	 */
	public static Double ticketDiscountFactor(SalesHeader ticket, Supplier<List<SalesLine>> ticketLines) {
		if (ticket == null) {
			return null;
		}
		Double factor = null;
		Double deduction = ticket.getLoyaltyDeductionAmount();
		Double pct = ticket.getDiscountPercentage();
		Double amount = ticket.getDiscountAmount();
		if (deduction != null && deduction > 0) {
			factor = ReturnHeaderService.loyaltyHeaderFactor(ticket, ticketLines.get());
		} else if (pct != null && pct > 0) {
			factor = 1.0 - pct / 100.0;
		} else if (amount != null && amount > 0 && ticket.getTotalAmount() != null && ticket.getTotalAmount() >= 0) {
			double linesTtc = ticketLines.get().stream()
					.mapToDouble(l -> l.getLineTotalIncludingVat() != null ? l.getLineTotalIncludingVat() : 0.0).sum();
			if (linesTtc > 0) {
				factor = ticket.getTotalAmount() / linesTtc;
			}
		}
		return factor != null && factor >= 0 && factor < 1 ? factor : null;
	}

	/** The ticket discount factor of a warranty's sale (ticketDiscountFactor), its lines read from the database. */
	public Double ticketDiscountFactor(SalesHeader ticket) {
		return ticketDiscountFactor(ticket, () -> salesLineRepository.findBySalesHeader(ticket));
	}

	public Optional<SalesHeader> findSalesHeaderByTicketNumber(String ticketNumber) {
		return salesHeaderRepository.findBySalesNumber(ticketNumber.trim());
	}

	public List<SalesLine> getSalesLinesForTicket(String ticketNumber) {
		return findSalesHeaderByTicketNumber(ticketNumber)
				.map(salesLineRepository::findBySalesHeader)
				.orElse(List.of());
	}

	public List<Warranty> findBySalesHeader(SalesHeader salesHeader) {
		return warrantyRepository.findBySalesHeader(salesHeader);
	}

	public List<Warranty> findByTicketNumber(String ticketNumber) {
		return findSalesHeaderByTicketNumber(ticketNumber)
				.map(warrantyRepository::findBySalesHeader)
				.orElse(List.of());
	}

	/**
	 * Sum of quantity covered by existing warranties for a sales line.
	 */
	public int getQuantityAlreadyCoveredForLine(SalesLine line) {
		return warrantyRepository.findBySalesLine(line).stream()
				.mapToInt(w -> w.getQuantityCovered() != null ? w.getQuantityCovered() : 0)
				.sum();
	}

	/**
	 * Create a warranty for a sales line. Sales header and item are derived from the line.
	 * Validates: quantityCovered <= line quantity and total covered (existing + new) <= line quantity.
	 */
	public Warranty createForSalesLine(Long salesLineId, java.time.LocalDate startDate, java.time.LocalDate endDate,
			Integer quantityCovered, String notes) throws Exception {
		SalesLine line = salesLineRepository.findById(salesLineId)
				.orElseThrow(() -> new IllegalArgumentException("Sales line not found: " + salesLineId));
		if (startDate == null || endDate == null) {
			throw new IllegalArgumentException("Start date and end date are required");
		}
		if (endDate.isBefore(startDate)) {
			throw new IllegalArgumentException("End date must be on or after start date");
		}
		int qty = quantityCovered != null && quantityCovered > 0 ? quantityCovered : 1;
		// 2.2.1: a warranty covers whole units; a line sold with decimals (0.2 of a bulk item) has none
		if (!Quantities.isWhole(line.getQuantity())) {
			throw new IllegalArgumentException("A warranty covers whole units: the sales line quantity "
					+ Quantities.plain(line.getQuantity()) + " is not a whole number");
		}
		int lineQty = line.getQuantity() != null ? line.getQuantity().intValueExact() : 0;
		if (qty > lineQty) {
			throw new IllegalArgumentException("Quantity covered (" + qty + ") cannot exceed sales line quantity (" + lineQty + ")");
		}
		int alreadyCovered = getQuantityAlreadyCoveredForLine(line);
		if (alreadyCovered + qty > lineQty) {
			throw new IllegalArgumentException("Total quantity covered would exceed sales line quantity (" + lineQty + "). Already covered: " + alreadyCovered + ", remaining: " + (lineQty - alreadyCovered));
		}
		Warranty w = new Warranty();
		w.setSalesHeader(line.getSalesHeader());
		w.setSalesLine(line);
		w.setItem(line.getItem());
		w.setStartDate(startDate);
		w.setEndDate(endDate);
		w.setQuantityCovered(qty);
		w.setNotes(notes);
		return save(w);
	}

	/** Compute warranty status: USED if used, else EXPIRED if endDate < today, else ACTIVE. */
	public static String computeStatus(Warranty w) {
		if (w == null) return "ACTIVE";
		if (Boolean.TRUE.equals(w.getUsed())) return "USED";
		if (w.getEndDate() == null) return "ACTIVE";
		return w.getEndDate().isBefore(java.time.LocalDate.now()) ? "EXPIRED" : "ACTIVE";
	}

	public Warranty markAsUsed(Long warrantyId) throws Exception {
		Warranty w = findById(warrantyId).orElseThrow(() -> new IllegalArgumentException("Warranty not found: " + warrantyId));
		w.setUsed(true);
		return save(w);
	}
}
