package com.digithink.zsretail.controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.EligibleTicketDTO;
import com.digithink.zsretail.dto.InvoiceListDTO;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.InvoiceHeader;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.InvoiceService;
import com.digithink.zsretail.service.InvoiceService.InvoiceSnapshotData;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("admin/invoices")
@Log4j2
public class InvoiceAPI {

	@Autowired
	private InvoiceService service;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	/**
	 * DTO for creating invoices from multiple tickets.
	 */
	@Data
	public static class CreateInvoiceRequest {
		private Long customerId;
		private String invoiceDate; // ISO yyyy-MM-dd, optional
		private String notes;
		private List<Long> ticketIds;
		private InvoiceLineGroupingMode lineGroupingMode;
		// Editable snapshot of customer info for this invoice
		private String snapshotCustomerName;
		private String snapshotCustomerAddress;
		private String snapshotCustomerPhone;
		private String snapshotCustomerTaxRegNo;
	}

	/**
	 * List invoices for admin with basic filters.
	 */
	@GetMapping
	public ResponseEntity<?> listInvoices(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) Long customerId,
			@RequestParam(required = false) String invoiceNumber,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			log.info("InvoiceAPI::listInvoices from={}, to={}, customerId={}, invoiceNumber={}, page={}, size={}",
					from, to, customerId, invoiceNumber, page, size);

			LocalDate fromDate = (from != null && !from.trim().isEmpty()) ? LocalDate.parse(from) : null;
			LocalDate toDate = (to != null && !to.trim().isEmpty()) ? LocalDate.parse(to) : null;

			Page<InvoiceHeader> invoicePage = service.listInvoices(fromDate, toDate, customerId, invoiceNumber, page,
					size);

			List<InvoiceListDTO> content = invoicePage.getContent().stream()
					.map(InvoiceAPI::toListDTO)
					.collect(Collectors.toList());

			Map<String, Object> response = new HashMap<>();
			response.put("content", content);
			response.put("totalElements", invoicePage.getTotalElements());
			response.put("totalPages", invoicePage.getTotalPages());
			response.put("number", invoicePage.getNumber());
			response.put("size", invoicePage.getSize());
			response.put("numberOfElements", invoicePage.getNumberOfElements());
			response.put("first", invoicePage.isFirst());
			response.put("last", invoicePage.isLast());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("InvoiceAPI::listInvoices:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Get eligible tickets (completed, non-invoiced) for a customer & date range.
	 */
	@GetMapping("/eligible-tickets")
	public ResponseEntity<?> getEligibleTickets(
			@RequestParam Long customerId,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo) {
		try {
			log.info("InvoiceAPI::getEligibleTickets customerId={}, dateFrom={}, dateTo={}", customerId, dateFrom,
					dateTo);

			if (!applicationModeService.isStandalone()) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
						.body(createErrorResponse("Invoice creation from POS is only available in standalone mode."));
			}

			LocalDate from = (dateFrom != null && !dateFrom.trim().isEmpty()) ? LocalDate.parse(dateFrom) : null;
			LocalDate to = (dateTo != null && !dateTo.trim().isEmpty()) ? LocalDate.parse(dateTo) : null;

			java.util.List<SalesHeader> tickets = service.findEligibleTickets(customerId, from, to);
			java.util.List<EligibleTicketDTO> dtos = tickets.stream()
					.map(t -> new EligibleTicketDTO(t.getId(), t.getSalesNumber(), t.getSalesDate(), t.getTotalAmount()))
					.collect(Collectors.toList());
			return ResponseEntity.ok(dtos);
		} catch (IllegalArgumentException e) {
			log.error("InvoiceAPI::getEligibleTickets: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("InvoiceAPI::getEligibleTickets:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Create invoice from selected tickets.
	 */
	@PostMapping
	public ResponseEntity<?> createInvoice(@RequestBody CreateInvoiceRequest request) {
		try {
			log.info("InvoiceAPI::createInvoice customerId={}, tickets={}",
					request.getCustomerId(),
					request.getTicketIds() != null ? request.getTicketIds().size() : 0);

			if (!applicationModeService.isStandalone()) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
						.body(createErrorResponse("Invoice creation from POS is only available in standalone mode."));
			}

			if (request.getCustomerId() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Customer is mandatory"));
			}
			if (request.getTicketIds() == null || request.getTicketIds().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("At least one ticket must be selected"));
			}

			LocalDate invoiceDate = (request.getInvoiceDate() != null && !request.getInvoiceDate().trim().isEmpty())
					? LocalDate.parse(request.getInvoiceDate())
					: LocalDate.now();

			InvoiceSnapshotData snapshot = new InvoiceSnapshotData();
			snapshot.setName(request.getSnapshotCustomerName());
			snapshot.setAddress(request.getSnapshotCustomerAddress());
			snapshot.setPhone(request.getSnapshotCustomerPhone());
			snapshot.setTaxRegNo(request.getSnapshotCustomerTaxRegNo());

			InvoiceHeader created = service.createInvoice(
					request.getCustomerId(),
					request.getTicketIds(),
					invoiceDate,
					request.getNotes(),
					request.getLineGroupingMode(),
					currentUserProvider.getCurrentUser(),
					snapshot);

			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (IllegalArgumentException e) {
			log.error("InvoiceAPI::createInvoice: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("InvoiceAPI::createInvoice:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Create invoice directly from a ticket (used by ticket details popup).
	 */
	@PostMapping("/from-ticket/{ticketId}")
	public ResponseEntity<?> createInvoiceFromTicket(@PathVariable Long ticketId,
			@RequestBody(required = false) Map<String, Object> body) {
		try {
			log.info("InvoiceAPI::createInvoiceFromTicket ticketId={}", ticketId);

			if (!applicationModeService.isStandalone()) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
						.body(createErrorResponse("Invoice creation from POS is only available in standalone mode."));
			}

			String notes = null;
			String invoiceDateStr = null;
			InvoiceSnapshotData snapshot = new InvoiceSnapshotData();
			if (body != null) {
				Object n = body.get("notes");
				if (n instanceof String) notes = ((String) n).trim();
				Object d = body.get("invoiceDate");
				if (d instanceof String) invoiceDateStr = ((String) d).trim();
				Object sn = body.get("snapshotCustomerName");
				if (sn instanceof String) snapshot.setName((String) sn);
				Object sa = body.get("snapshotCustomerAddress");
				if (sa instanceof String) snapshot.setAddress((String) sa);
				Object sp = body.get("snapshotCustomerPhone");
				if (sp instanceof String) snapshot.setPhone((String) sp);
				Object st = body.get("snapshotCustomerTaxRegNo");
				if (st instanceof String) snapshot.setTaxRegNo((String) st);
			}

			LocalDate invoiceDate = (invoiceDateStr != null && !invoiceDateStr.isEmpty())
					? LocalDate.parse(invoiceDateStr)
					: null;

			InvoiceHeader created = service.createInvoiceFromTicket(
					ticketId,
					invoiceDate,
					notes,
					currentUserProvider.getCurrentUser(),
					snapshot);

			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (IllegalArgumentException e) {
			log.error("InvoiceAPI::createInvoiceFromTicket: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("InvoiceAPI::createInvoiceFromTicket:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Get invoice details (header, lines, tickets) for viewing/printing.
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getInvoiceDetails(@PathVariable Long id) {
		try {
			log.info("InvoiceAPI::getInvoiceDetails id={}", id);
			Map<String, Object> details = service.getInvoiceDetails(id);
			return ResponseEntity.ok(details);
		} catch (IllegalArgumentException e) {
			log.error("InvoiceAPI::getInvoiceDetails: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("InvoiceAPI::getInvoiceDetails:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Map entity to list DTO without touching lazy relations (e.g. lines) to avoid
	 * Jackson circular reference and lazy-load issues.
	 */
	private static InvoiceListDTO toListDTO(InvoiceHeader h) {
		InvoiceListDTO.CustomerSummary customerSummary = null;
		Customer c = h.getCustomer();
		if (c != null) {
			customerSummary = new InvoiceListDTO.CustomerSummary(c.getId(), c.getName(), c.getCustomerCode());
		}
		return new InvoiceListDTO(
				h.getId(),
				h.getInvoiceNumber(),
				h.getInvoiceDate(),
				customerSummary,
				h.getSubtotal(),
				h.getTaxAmount(),
				h.getDiscountAmount(),
				h.getTotalAmount(),
				h.getNotes(),
				h.getLineGroupingMode(),
				h.getFranchiseLocationCode(),
				h.getFranchiseReceivedAt());
	}

	protected String getDetailedMessage(Throwable e) {
		Throwable cause = e.getCause();
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause != null ? cause.getLocalizedMessage() : e.getLocalizedMessage();
	}

	protected Object createErrorResponse(String message) {
		return message;
	}
}

