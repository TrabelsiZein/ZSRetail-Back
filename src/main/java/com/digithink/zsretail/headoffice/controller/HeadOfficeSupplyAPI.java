package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.dto.StockReportDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.SupplyConfirmationReceiver;

/**
 * Head office plan, step 7A: what a store sends up about its supply, under the /ho/** chain (store key, then license).
 * Head office without an ERP only. The confirmations go to whichever {@link SupplyConfirmationReceiver} exists (the BLs,
 * or the ERP's invoices with headoffice.supply.source=ERP), the stock to {@link HoNetworkStockService}. The store is the
 * principal set by {@link StoreApiKeyFilter}: a store code in the body is ignored. The answer has one result per
 * document, in batch order.
 */
@RestController
@RequestMapping("ho/supply")
@ConditionalOnHeadOfficeWithoutErp
public class HeadOfficeSupplyAPI {

	private final SupplyConfirmationReceiver deliveries;
	private final HoNetworkStockService stock;

	public HeadOfficeSupplyAPI(SupplyConfirmationReceiver deliveries, HoNetworkStockService stock) {
		this.deliveries = deliveries;
		this.stock = stock;
	}

	/** Task 7A.5: one batch of the store's stock (items changed, codes removed); 400 {"error"} for a bad takenAt. */
	@PostMapping("/stock")
	public ResponseEntity<?> stock(@AuthenticationPrincipal Store store, @RequestBody StockReportDTO report) {
		try {
			return ResponseEntity.ok(stock.receive(store, report));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	/** Task 7A.4: the store's BL confirmations; one result per BL, by its number. */
	@PostMapping("/confirmations")
	public SalesCopyAnswerDTO confirmations(@AuthenticationPrincipal Store store,
			@RequestBody List<DeliveryConfirmationDTO> confirmations) {
		return new SalesCopyAnswerDTO(deliveries.receiveConfirmations(store, confirmations));
	}
}
