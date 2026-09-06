package com.digithink.zsretail.controller.franchise;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.franchise.FranchiseInvoiceDTO;
import com.digithink.zsretail.model.InvoiceHeader;
import com.digithink.zsretail.model.InvoiceLine;
import com.digithink.zsretail.repository.InvoiceHeaderRepository;
import com.digithink.zsretail.repository.InvoiceLineRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Exposes franchise supply invoices to franchise clients.
 * Clients pull unacknowledged invoices for their location code and create local purchase receptions.
 * Only active when franchise.admin=true. Secured by X-Franchise-Api-Key header.
 */
@RestController
@RequestMapping("franchise/invoices")
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.admin", havingValue = "true")
public class FranchiseInvoiceSyncController {

	private final InvoiceHeaderRepository invoiceHeaderRepository;
	private final InvoiceLineRepository invoiceLineRepository;

	/**
	 * Returns all invoices tagged for the given location code that have not yet been received.
	 */
	@GetMapping
	public ResponseEntity<List<FranchiseInvoiceDTO>> getPendingInvoices(
			@RequestParam String locationCode) {

		if (locationCode == null || locationCode.isBlank()) {
			return ResponseEntity.badRequest().build();
		}

		List<InvoiceHeader> invoices =
				invoiceHeaderRepository.findByFranchiseLocationCodeAndFranchiseReceivedAtIsNull(locationCode);

		List<FranchiseInvoiceDTO> dtos = invoices.stream()
				.map(this::toDTO)
				.collect(Collectors.toList());

		log.info("Franchise invoice sync: {} pending invoices for location '{}'", dtos.size(), locationCode);
		return ResponseEntity.ok(dtos);
	}

	/**
	 * Marks an invoice as received by the franchise client.
	 * Called after the client successfully creates the local purchase reception.
	 */
	@PostMapping("/{id}/acknowledge")
	public ResponseEntity<Void> acknowledgeInvoice(@PathVariable Long id) {
		InvoiceHeader invoice = invoiceHeaderRepository.findById(id)
				.orElse(null);

		if (invoice == null) {
			return ResponseEntity.notFound().build();
		}
		if (invoice.getFranchiseLocationCode() == null) {
			return ResponseEntity.badRequest().build();
		}

		invoice.setFranchiseReceivedAt(LocalDateTime.now());
		invoiceHeaderRepository.save(invoice);

		log.info("Franchise invoice {} acknowledged by client for location '{}'",
				invoice.getInvoiceNumber(), invoice.getFranchiseLocationCode());
		return ResponseEntity.ok().build();
	}

	private FranchiseInvoiceDTO toDTO(InvoiceHeader header) {
		List<InvoiceLine> lines = invoiceLineRepository.findByInvoice(header);

		List<FranchiseInvoiceDTO.FranchiseInvoiceLineDTO> lineDTOs = lines.stream()
				.filter(l -> l.getItem() != null)
				.map(this::toLineDTO)
				.collect(Collectors.toList());

		return new FranchiseInvoiceDTO(
				header.getId(),
				header.getInvoiceNumber(),
				header.getInvoiceDate(),
				header.getFranchiseLocationCode(),
				header.getSubtotal(),
				header.getTaxAmount(),
				header.getTotalAmount(),
				header.getNotes(),
				lineDTOs
		);
	}

	private FranchiseInvoiceDTO.FranchiseInvoiceLineDTO toLineDTO(InvoiceLine line) {
		String itemCode = line.getItem() != null ? line.getItem().getItemCode() : null;
		String itemName = line.getItem() != null ? line.getItem().getName() : line.getLineDescription();

		return new FranchiseInvoiceDTO.FranchiseInvoiceLineDTO(
				itemCode,
				itemName,
				line.getQuantity(),
				line.getUnitPrice(),
				line.getUnitPriceIncludingVat(),
				line.getSubtotal(),
				line.getTaxAmount(),
				line.getTotalAmount(),
				line.getVatPercent()
		);
	}
}
