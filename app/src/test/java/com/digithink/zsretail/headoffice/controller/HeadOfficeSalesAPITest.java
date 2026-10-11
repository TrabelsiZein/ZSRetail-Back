package com.digithink.zsretail.headoffice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.SalesCopyReceiver;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 2.3: POST /ho/sales/tickets, /returns and /sessions pass the authenticated store and the
 * batch to the receiver and answer {"results":[{documentNumber, accepted, message}]}. Plain JUnit.
 */
class HeadOfficeSalesAPITest {

	private final Store store = new Store();

	private HeadOfficeSalesAPI api() {
		store.setId(7L);
		store.setCode("RS07");
		// The repositories are not reached: every document below is rejected by validation
		return new HeadOfficeSalesAPI(
				new SalesCopyReceiver(null, null, null, null, TransactionOperations.withoutTransaction()));
	}

	@Test
	@DisplayName("Each endpoint answers one result per document, as {results: [{documentNumber, accepted, message}]}")
	void answers() throws Exception {
		HeadOfficeSalesAPI api = api();
		TicketCopyDTO ticket = new TicketCopyDTO();
		ticket.setSalesNumber("T-1");
		ReturnCopyDTO ret = new ReturnCopyDTO();
		ret.setReturnNumber("R-1");
		ret.setReturnDate(LocalDateTime.of(2026, 10, 3, 9, 0));
		SessionCopyDTO session = new SessionCopyDTO();

		SalesCopyAnswerDTO tickets = api.tickets(store, Arrays.asList(ticket, null));
		SalesCopyAnswerDTO returns = api.returns(store, Arrays.asList(ret));
		SalesCopyAnswerDTO sessions = api.sessions(store, Arrays.asList(session));

		assertEquals("{\"results\":[{\"documentNumber\":\"T-1\",\"accepted\":false,\"message\":\"salesDate is required\"},"
				+ "{\"documentNumber\":null,\"accepted\":false,\"message\":\"empty document\"}]}",
				new ObjectMapper().writeValueAsString(tickets));
		assertEquals("status is required", returns.getResults().get(0).getMessage());
		assertEquals("sessionNumber is required", sessions.getResults().get(0).getMessage());
	}
}
