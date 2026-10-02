package com.digithink.zsretail.headoffice.controller;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;

/**
 * Head office plan, task 1.3: proves the store key on /ho/**. Head office only. The store is the principal set by
 * {@link StoreApiKeyFilter}. Writes nothing: lastContact is updated by the heartbeat (task 1.4).
 */
@RestController
@RequestMapping("ho")
@ConditionalOnHeadOffice
public class HeadOfficePingAPI {

	static final DateTimeFormatter SERVER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

	@GetMapping("/ping")
	public HeadOfficePingDTO ping(@AuthenticationPrincipal Store store) {
		return new HeadOfficePingDTO(store.getCode(), OffsetDateTime.now().format(SERVER_TIME));
	}
}
