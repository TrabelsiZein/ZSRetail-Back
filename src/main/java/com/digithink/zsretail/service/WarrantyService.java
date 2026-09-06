package com.digithink.zsretail.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.Warranty;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.repository.WarrantyRepository;

@Service
public class WarrantyService extends _BaseService<Warranty, Long> {

	@Autowired
	private WarrantyRepository warrantyRepository;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Override
	protected com.digithink.zsretail.repository._BaseRepository<Warranty, Long> getRepository() {
		return warrantyRepository;
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
		int lineQty = line.getQuantity() != null ? line.getQuantity() : 0;
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
