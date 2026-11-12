package com.digithink.zsretail.connector.emtop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.digithink.zsretail.connector.emtop.client.DynamicsNavRestClient;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavProperties;
import com.digithink.zsretail.connector.emtop.connector.DynamicsNavConnector;
import com.digithink.zsretail.connector.emtop.dto.DynamicsNavPaymentLineDTO;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpPaymentLineDTO;

/**
 * 3.2.0, step 3b: the EMTOP connector builds NAV's payment line (codeunit WarehouseManagement_CreatePaymentLine) from the
 * line of a method the admin added, as from any non-cash method: the header's document number, the customer, the amount,
 * the session (fenceNo), the ticket, the title number, the due date as YYYY-MM-DD, the drawer. The REST client is a
 * mock: no network, nothing sent.
 */
class DynamicsNavPaymentLineTest {

	@Test
	@DisplayName("A line of CARTE_PLUXEE (30 TND, its slip number, due date, drawer): every field in NAV's form")
	void navLineOfAnAddedMethod() {
		DynamicsNavRestClient rest = mock(DynamicsNavRestClient.class);
		when(rest.createPaymentLine(org.mockito.ArgumentMatchers.any())).thenReturn(Collections.singletonMap("value", 1));
		DynamicsNavProperties properties = new DynamicsNavProperties();
		properties.setBaseUrl("http://nav.test/codeunits");
		DynamicsNavConnector connector = new DynamicsNavConnector(rest, null, properties);

		ErpPaymentLineDTO line = new ErpPaymentLineDTO();
		line.setDocNo("ENC-0042");
		line.setCustNo("PASSAGER");
		line.setAmount(new BigDecimal("30.000"));
		line.setFenceNo("SESSION261112001");
		line.setTicketNo("T-1");
		line.setTitleNo("SLIP-778812");
		line.setDueDate(LocalDate.of(2026, 11, 30));
		line.setDrawerName("Amira Ben Salem");
		ErpOperationResult result = connector.pushPaymentLine("ENC-0042", line);

		assertTrue(result.isSuccess());
		ArgumentCaptor<DynamicsNavPaymentLineDTO> sent = ArgumentCaptor.forClass(DynamicsNavPaymentLineDTO.class);
		verify(rest).createPaymentLine(sent.capture());
		DynamicsNavPaymentLineDTO nav = sent.getValue();
		assertEquals("ENC-0042", nav.getDocNo());
		assertEquals("PASSAGER", nav.getCustNo());
		assertEquals(30.0, nav.getAmount(), 1e-9);
		assertEquals("SESSION261112001", nav.getFenceNo());
		assertEquals("T-1", nav.getTicketNo());
		assertEquals("SLIP-778812", nav.getTitleNo());
		assertEquals("2026-11-30", nav.getDueDate());
		assertEquals("Amira Ben Salem", nav.getDrawerName());
		assertEquals("http://nav.test/codeunits/WarehouseManagement_CreatePaymentLine", result.getUrl());
	}

	@Test
	@DisplayName("A line without N° titre or due date (an optional slip left empty): titleNo and dueDate null")
	void navLineWithoutDetails() {
		DynamicsNavRestClient rest = mock(DynamicsNavRestClient.class);
		DynamicsNavConnector connector = new DynamicsNavConnector(rest, null, new DynamicsNavProperties());
		ErpPaymentLineDTO line = new ErpPaymentLineDTO();
		line.setCustNo("PASSAGER");
		line.setAmount(new BigDecimal("12.500"));
		connector.pushPaymentLine("ENC-0043", line);
		ArgumentCaptor<DynamicsNavPaymentLineDTO> sent = ArgumentCaptor.forClass(DynamicsNavPaymentLineDTO.class);
		verify(rest).createPaymentLine(sent.capture());
		assertNull(sent.getValue().getTitleNo());
		assertNull(sent.getValue().getDueDate());
		assertEquals(12.5, sent.getValue().getAmount(), 1e-9);
	}
}
