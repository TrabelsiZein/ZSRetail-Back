package com.digithink.zsretail.headoffice.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeStandalone;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;

/**
 * Head office plan, step 7A: what a store sends up about its supply, under the /ho/** chain (store key, then license).
 * Head office without an ERP only. The store is the principal set by {@link StoreApiKeyFilter}: a store code in the
 * body is ignored. The answer has one result per document, in batch order.
 */
@RestController
@RequestMapping("ho/supply")
@ConditionalOnHeadOfficeStandalone
public class HeadOfficeSupplyAPI {

	private final HoDeliveryService deliveries;

	public HeadOfficeSupplyAPI(HoDeliveryService deliveries) {
		this.deliveries = deliveries;
	}

	/** Task 7A.4: the store's BL confirmations; one result per BL, by its number. */
	@PostMapping("/confirmations")
	public SalesCopyAnswerDTO confirmations(@AuthenticationPrincipal Store store,
			@RequestBody List<DeliveryConfirmationDTO> confirmations) {
		return new SalesCopyAnswerDTO(deliveries.receiveConfirmations(store, confirmations));
	}
}
