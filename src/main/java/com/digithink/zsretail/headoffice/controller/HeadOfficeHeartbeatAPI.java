package com.digithink.zsretail.headoffice.controller;

import java.time.OffsetDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatAnswerDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.StoreService;

/**
 * Head office plan, task 1.4: the store's heartbeat. Head office only. The store is the principal set by
 * {@link StoreApiKeyFilter}; lastContact (head office clock) and the version sent are written by id through
 * {@link StoreService#recordContact}, never by saving the principal. Answers like GET /ho/ping. Task 3.6: also what
 * the store owns (ownership, sales upstreams), in the same update; a store that sends nothing is unknown.
 */
@RestController
@RequestMapping("ho")
@ConditionalOnHeadOffice
public class HeadOfficeHeartbeatAPI {

	private final StoreService storeService;

	public HeadOfficeHeartbeatAPI(StoreService storeService) {
		this.storeService = storeService;
	}

	/** Step 4: the answer also carries the store's loyalty rights (null in a row made before step 4: false). */
	@PostMapping("/heartbeat")
	public HeadOfficeHeartbeatAnswerDTO heartbeat(@AuthenticationPrincipal Store store,
			@RequestBody(required = false) HeadOfficeHeartbeatDTO body) {
		OffsetDateTime now = OffsetDateTime.now();
		storeService.recordContact(store.getId(), body, now.toLocalDateTime());
		return new HeadOfficeHeartbeatAnswerDTO(store.getCode(), now.format(HeadOfficePingAPI.SERVER_TIME),
				Boolean.TRUE.equals(store.getCanEditMembers()), Boolean.TRUE.equals(store.getCanAdjustPoints()),
				Boolean.TRUE.equals(store.getRedeemRequiresOnline()), Boolean.TRUE.equals(store.getEnrolRequiresOnline()));
	}
}
