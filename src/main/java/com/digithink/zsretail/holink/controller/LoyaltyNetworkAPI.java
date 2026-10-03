package com.digithink.zsretail.holink.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.holink.service.StoreLoyaltyNetwork;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, step 4: GET /loyalty/network, what the loyalty pages of a store need when its loyalty is owned by
 * the head office: {ownedByHeadOffice: true, linkState, canEditMembers, canAdjustPoints, programEditable: false,
 * pointsAdjustable: false}. The rights are those of the last heartbeat answer, null before it. Exists only then: with
 * loyalty LOCAL this URL answers 404 and the pages work as before. JWT like the other APIs.
 */
@RestController
@RequestMapping("loyalty")
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
public class LoyaltyNetworkAPI {

	private final StoreLoyaltyNetwork network;

	public LoyaltyNetworkAPI(StoreLoyaltyNetwork network) {
		this.network = network;
	}

	@GetMapping("/network")
	public Map<String, Object> network() {
		return network.status();
	}
}
