package com.digithink.zsretail.controller.franchise;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.franchise.FranchiseSalesPayload;
import com.digithink.zsretail.model.FranchiseSalesHeader;
import com.digithink.zsretail.model.FranchiseSalesLine;
import com.digithink.zsretail.repository.FranchiseSalesHeaderRepository;
import com.digithink.zsretail.repository.FranchiseSalesLineRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Receives sales data pushed by franchise clients.
 * Stores them in dedicated franchise_sales_header / franchise_sales_line tables
 * for tracking and dashboards. Not linked to the admin's own sales flow.
 * Only active when franchise.admin=true. Secured by X-Franchise-Api-Key header.
 */
@RestController
@RequestMapping("franchise/sales")
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.admin", havingValue = "true")
public class FranchiseSalesReceiverController {

	private final FranchiseSalesHeaderRepository franchiseSalesHeaderRepository;
	private final FranchiseSalesLineRepository franchiseSalesLineRepository;

	/**
	 * Receives a sales record pushed by a franchise client.
	 * Idempotent: if a record with the same locationCode + externalSalesNumber already exists, it is skipped.
	 */
	@PostMapping
	public ResponseEntity<Void> receiveSale(@RequestBody FranchiseSalesPayload payload) {
		if (payload.getLocationCode() == null || payload.getExternalSalesNumber() == null) {
			return ResponseEntity.badRequest().build();
		}

		// Idempotency check: skip duplicates
		if (franchiseSalesHeaderRepository.existsByLocationCodeAndExternalSalesNumber(
				payload.getLocationCode(), payload.getExternalSalesNumber())) {
			log.debug("Franchise sale already received: location={}, number={}",
					payload.getLocationCode(), payload.getExternalSalesNumber());
			return ResponseEntity.ok().build();
		}

		FranchiseSalesHeader header = new FranchiseSalesHeader();
		header.setLocationCode(payload.getLocationCode());
		header.setExternalSalesNumber(payload.getExternalSalesNumber());
		header.setSalesDate(payload.getSalesDate());
		header.setReceivedAt(LocalDateTime.now());
		header.setCustomerName(payload.getCustomerName());
		header.setCashierName(payload.getCashierName());
		header.setTotalHT(payload.getTotalHT());
		header.setTotalTVA(payload.getTotalTVA());
		header.setTotalTTC(payload.getTotalTTC());
		header.setCreatedBy("FranchiseClient");
		header.setUpdatedBy("FranchiseClient");

		header = franchiseSalesHeaderRepository.save(header);

		if (payload.getLines() != null) {
			final FranchiseSalesHeader savedHeader = header;
			List<FranchiseSalesLine> lines = payload.getLines().stream().map(lp -> {
				FranchiseSalesLine line = new FranchiseSalesLine();
				line.setHeader(savedHeader);
				line.setItemCode(lp.getItemCode());
				line.setItemName(lp.getItemName());
				line.setQuantity(lp.getQuantity());
				line.setUnitPrice(lp.getUnitPrice());
				line.setDiscountAmount(lp.getDiscountAmount());
				line.setTotalAmount(lp.getTotalAmount());
				line.setCreatedBy("FranchiseClient");
				line.setUpdatedBy("FranchiseClient");
				return line;
			}).collect(Collectors.toList());
			franchiseSalesLineRepository.saveAll(lines);
		}

		log.info("Franchise sale received: location={}, number={}, total={}",
				payload.getLocationCode(), payload.getExternalSalesNumber(), payload.getTotalTTC());
		return ResponseEntity.ok().build();
	}

}

