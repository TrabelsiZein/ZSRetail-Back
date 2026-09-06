package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.Warranty;
import com.digithink.zsretail.service.WarrantyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("warranty")
@Log4j2
public class WarrantyAPI {

	@Autowired
	private WarrantyService warrantyService;

	/**
	 * Get ticket details by ticket number (for warranty registration). Returns sale and lines; optionally existing warranties for this ticket.
	 */
	@GetMapping("/ticket-details")
	public ResponseEntity<?> getTicketDetails(@RequestParam String ticketNumber) {
		try {
			log.info("WarrantyAPI::getTicketDetails: ticketNumber={}", ticketNumber);
			SalesHeader salesHeader = warrantyService.findSalesHeaderByTicketNumber(ticketNumber)
					.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));
			List<SalesLine> salesLines = warrantyService.getSalesLinesForTicket(ticketNumber);
			List<Warranty> existingWarranties = warrantyService.findBySalesHeader(salesHeader);

			List<Map<String, Object>> linesData = salesLines.stream().map(line -> {
				Map<String, Object> m = new HashMap<>();
				m.put("id", line.getId());
				m.put("item", line.getItem());
				m.put("quantity", line.getQuantity());
				m.put("unitPrice", line.getUnitPrice());
				m.put("lineTotal", line.getLineTotal());
				m.put("lineTotalIncludingVat", line.getLineTotalIncludingVat());
				List<Warranty> lineWarranties = existingWarranties.stream().filter(w -> w.getSalesLine().getId().equals(line.getId())).collect(Collectors.toList());
				m.put("warrantyCount", (long) lineWarranties.size());
				int quantityAlreadyCovered = lineWarranties.stream().mapToInt(w -> w.getQuantityCovered() != null ? w.getQuantityCovered() : 0).sum();
				m.put("quantityAlreadyCovered", quantityAlreadyCovered);
				return m;
			}).collect(Collectors.toList());

			Map<String, Object> response = new HashMap<>();
			response.put("ticket", salesHeader);
			response.put("salesLines", linesData);
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("WarrantyAPI::getTicketDetails: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(e.getMessage()));
		}
	}

	/**
	 * Create a warranty (manual registration). Body: salesLineId, startDate, endDate, quantityCovered (optional), notes (optional).
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
		try {
			Long salesLineId = body.get("salesLineId") != null ? Long.valueOf(body.get("salesLineId").toString()) : null;
			if (salesLineId == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Sales line ID is required"));
			}
			String startStr = body.get("startDate") != null ? body.get("startDate").toString() : null;
			String endStr = body.get("endDate") != null ? body.get("endDate").toString() : null;
			if (startStr == null || endStr == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Start date and end date are required"));
			}
			java.time.LocalDate startDate = java.time.LocalDate.parse(startStr);
			java.time.LocalDate endDate = java.time.LocalDate.parse(endStr);
			Integer quantityCovered = null;
			if (body.get("quantityCovered") != null) {
				quantityCovered = Integer.valueOf(body.get("quantityCovered").toString());
			}
			String notes = body.get("notes") != null ? body.get("notes").toString().trim() : null;
			Warranty w = warrantyService.createForSalesLine(salesLineId, startDate, endDate, quantityCovered, notes);
			return ResponseEntity.status(HttpStatus.CREATED).body(w);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("WarrantyAPI::create: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(e.getMessage()));
		}
	}

	/**
	 * List warranties. Optional: ticketNumber, status (ACTIVE | EXPIRED). Each item includes computed "status".
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) String ticketNumber,
			@RequestParam(required = false) String status) {
		try {
			List<Warranty> list = ticketNumber != null && !ticketNumber.trim().isEmpty()
					? warrantyService.findByTicketNumber(ticketNumber.trim())
					: warrantyService.findAll();
			String statusFilter = (status != null && !status.trim().isEmpty()) ? status.trim().toUpperCase() : null;
			List<Map<String, Object>> result = list.stream()
					.map(w -> {
						String st = WarrantyService.computeStatus(w);
						if (statusFilter != null && !statusFilter.equals("ALL") && !st.equals(statusFilter)) {
							return null;
						}
						Map<String, Object> m = new HashMap<>();
						m.put("id", w.getId());
						m.put("salesHeader", w.getSalesHeader());
						m.put("salesLine", w.getSalesLine());
						m.put("item", w.getItem());
						m.put("startDate", w.getStartDate());
						m.put("endDate", w.getEndDate());
						m.put("quantityCovered", w.getQuantityCovered());
						m.put("notes", w.getNotes());
						m.put("status", st);
						return m;
					})
					.filter(m -> m != null)
					.collect(Collectors.toList());
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("WarrantyAPI::list: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(e.getMessage()));
		}
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> getById(@PathVariable Long id) {
		return warrantyService.findById(id)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Mark warranty as used (customer used the warranty). Status becomes USED.
	 */
	@PatchMapping("/{id}/use")
	public ResponseEntity<?> markAsUsed(@PathVariable Long id) {
		try {
			Warranty w = warrantyService.markAsUsed(id);
			return ResponseEntity.ok(w);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			log.error("WarrantyAPI::markAsUsed: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(e.getMessage()));
		}
	}

	private String createErrorResponse(String message) {
		return message;
	}
}
