package com.digithink.zsretail.headoffice.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.CommunicationEventAnswerDTO;
import com.digithink.zsretail.core.link.dto.CommunicationEventCopyDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.HoCommunicationReceiver;

/**
 * 3.3.0, communication step 6: POST /ho/communication/events, the events of a store whose loyalty is the head office's
 * (link job COMMUNICATION_PUSH). On every head office of 3.3.0, under the /ho/** chain (store key, then license; the
 * store is the principal set by StoreApiKeyFilter). {taken: false} while the module is off here; else one result per
 * event, by dedup key. An older head office has no such endpoint (404): the store pauses its push as well.
 */
@RestController
@RequestMapping("ho/communication")
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class HeadOfficeCommunicationAPI {

	private final HoCommunicationReceiver receiver;

	public HeadOfficeCommunicationAPI(HoCommunicationReceiver receiver) {
		this.receiver = receiver;
	}

	@PostMapping("/events")
	public CommunicationEventAnswerDTO events(@AuthenticationPrincipal Store store,
			@RequestBody List<CommunicationEventCopyDTO> copies) {
		return receiver.receive(store, copies);
	}
}
