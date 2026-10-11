package com.digithink.zsretail.holink.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;

/**
 * Head office plan, step 6: GET /catalogue/network, what the item pages of a store need when its catalogue is the head
 * office's: {fromHeadOffice: true, linkState, mayChangePrices, canPurchase, ownPriceCount,
 * salesPriceRowsOnHeadOfficeItems}. The rights are the saved values, null when never received (read as off). Exists only
 * then: on every other store this URL answers 404 and the pages work as before. JWT like
 * the other APIs.
 */
@RestController
@RequestMapping("catalogue")
@ConditionalOnInstallation(Installation.CATALOGUE_FROM_HEAD_OFFICE)
public class CatalogueNetworkAPI {

	private final StoreCatalogueGuard guard;

	public CatalogueNetworkAPI(StoreCatalogueGuard guard) {
		this.guard = guard;
	}

	@GetMapping("/network")
	public Map<String, Object> network() {
		return guard.status();
	}
}
