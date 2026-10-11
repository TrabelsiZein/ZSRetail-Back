package com.digithink.zsretail.headoffice.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.ReturnCopyDTO;
import com.digithink.zsretail.core.link.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.core.link.dto.SessionCopyDTO;
import com.digithink.zsretail.core.link.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.SalesCopyReceiver;

/**
 * Head office plan, task 2.3: receives the sales copies of a store, one batch per document type. Head office only.
 * The store is the principal set by {@link StoreApiKeyFilter}: a store code in the body is ignored. The answer has one
 * result per document, in batch order.
 */
@RestController
@RequestMapping("ho/sales")
@ConditionalOnInstallation(Installation.HEAD_OFFICE)
public class HeadOfficeSalesAPI {

	private final SalesCopyReceiver receiver;

	public HeadOfficeSalesAPI(SalesCopyReceiver receiver) {
		this.receiver = receiver;
	}

	@PostMapping("/tickets")
	public SalesCopyAnswerDTO tickets(@AuthenticationPrincipal Store store, @RequestBody List<TicketCopyDTO> copies) {
		return new SalesCopyAnswerDTO(receiver.receiveTickets(store, copies));
	}

	@PostMapping("/returns")
	public SalesCopyAnswerDTO returns(@AuthenticationPrincipal Store store, @RequestBody List<ReturnCopyDTO> copies) {
		return new SalesCopyAnswerDTO(receiver.receiveReturns(store, copies));
	}

	@PostMapping("/sessions")
	public SalesCopyAnswerDTO sessions(@AuthenticationPrincipal Store store, @RequestBody List<SessionCopyDTO> copies) {
		return new SalesCopyAnswerDTO(receiver.receiveSessions(store, copies));
	}
}
