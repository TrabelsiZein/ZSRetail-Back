package com.digithink.zsretail.headoffice.controller;

import java.time.OffsetDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.StoreService;

/**
 * Head office plan, task 1.4: the store's heartbeat. Head office only. The store is the principal set by
 * {@link StoreApiKeyFilter}; lastContact (head office clock) and the version sent are written by id through
 * {@link StoreService#recordContact}, never by saving the principal. Answers like GET /ho/ping.
 */
@RestController
@RequestMapping("ho")
@ConditionalOnHeadOffice
public class HeadOfficeHeartbeatAPI {

	private final StoreService storeService;

	public HeadOfficeHeartbeatAPI(StoreService storeService) {
		this.storeService = storeService;
	}

	@PostMapping("/heartbeat")
	public HeadOfficePingDTO heartbeat(@AuthenticationPrincipal Store store,
			@RequestBody(required = false) HeadOfficeHeartbeatDTO body) {
		OffsetDateTime now = OffsetDateTime.now();
		storeService.recordContact(store.getId(), body == null ? null : body.getAppVersion(), now.toLocalDateTime());
		return new HeadOfficePingDTO(store.getCode(), now.format(HeadOfficePingAPI.SERVER_TIME));
	}
}
