package com.digithink.zsretail.store.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.core.erp.service.ErpCommunicationService;
import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.erp.api.ErpAbilities;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.support.Installations;

/**
 * 3.1.1 (ParaFendri, step 6): TicketExportService with a connector that posts a ticket whole
 * (ErpAbility.WHOLE_TICKET_EXPORT, parafendri): one pushTicket with every line, success = the ERP number, the ticket
 * and its lines synchronized at once; failure = nothing changed, retried next run, no status update. Any other
 * connector (emtop) or none: today's path, the header first (pushTicketHeader), never pushTicket.
 */
class TicketExportWholeTicketTest {

	private ErpSynchronizationManager manager;
	private SalesHeaderRepository headers;
	private SalesLineRepository lines;
	private GeneralSetupService setup;
	private ErpCommunicationService communications;
	private SalesHeader ticket;
	private List<SalesLine> ticketLines;

	@BeforeEach
	void setUp() {
		manager = mock(ErpSynchronizationManager.class);
		headers = mock(SalesHeaderRepository.class);
		lines = mock(SalesLineRepository.class);
		setup = mock(GeneralSetupService.class);
		communications = mock(ErpCommunicationService.class);
		when(setup.findValueByCode("DEFAULT_LOCATION")).thenReturn("PV-GREMDA");
		ticket = new SalesHeader();
		ticket.setSalesNumber("PV-GREMDA251031001");
		ticket.setSalesDate(LocalDateTime.of(2026, 10, 31, 10, 15));
		ticket.setTotalAmount(72.0);
		ticket.setSubtotal(60.504);
		ticket.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		Promotion promotion = new Promotion();
		promotion.setCode("PROMO10");
		ticketLines = Arrays.asList(line("COS-COF-PAR-0001", 18.90756, 45.0, null),
				line("COS-PAR-HOM-0007", 25.21008, 27.0, promotion));
		when(lines.findBySalesHeader(ticket)).thenReturn(ticketLines);
	}

	private static SalesLine line(String itemCode, double unitPrice, double ttc, Promotion promotion) {
		Item item = new Item();
		item.setItemCode(itemCode);
		SalesLine line = new SalesLine();
		line.setItem(item);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(unitPrice);
		line.setLineTotal(ttc / 1.19);
		line.setLineTotalIncludingVat(ttc);
		line.setPromotion(promotion);
		line.setSynched(false);
		return line;
	}

	private TicketExportService service(String connector) {
		MockEnvironment env = Installations.type("store");
		env.setProperty("erp.connector", connector);
		return new TicketExportService(manager, headers, lines, setup, env, communications);
	}

	@Test
	@DisplayName("The ability: parafendri has WHOLE_TICKET_EXPORT, emtop and none do not")
	void ability() {
		assertTrue(ErpAbilities.has(withConnector("parafendri"), ErpAbility.WHOLE_TICKET_EXPORT));
		assertFalse(ErpAbilities.has(withConnector("emtop"), ErpAbility.WHOLE_TICKET_EXPORT));
		assertFalse(ErpAbilities.has(withConnector("none"), ErpAbility.WHOLE_TICKET_EXPORT));
	}

	private static MockEnvironment withConnector(String connector) {
		MockEnvironment env = Installations.type("store");
		env.setProperty("erp.connector", connector);
		return env;
	}

	@Test
	@DisplayName("A whole ticket, accepted: one pushTicket with its lines, the ERP number, everything synchronized at once")
	void wholeAccepted() {
		when(manager.pushTicket(any())).thenReturn(ErpOperationResult.success("CV-000001"));
		service("parafendri").exportTicket(ticket);

		ArgumentCaptor<ErpTicketDTO> sent = ArgumentCaptor.forClass(ErpTicketDTO.class);
		verify(manager).pushTicket(sent.capture());
		verify(manager, never()).pushTicketHeader(any());
		verify(manager, never()).pushTicketLine(any(), any(), any());
		verify(manager, never()).updateTicketStatus(anyString(), anyBoolean(), any(), any(), any());
		assertEquals("CV-000001", ticket.getErpNo());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
		assertTrue(ticketLines.stream().allMatch(SalesLine::getSynched), "every line at once");
		verify(headers).save(ticket);

		ErpTicketDTO dto = sent.getValue();
		assertEquals(2, dto.getLines().size());
		assertEquals(Integer.valueOf(1), dto.getLines().get(0).getLineNumber());
		assertEquals(Integer.valueOf(2), dto.getLines().get(1).getLineNumber());
		assertEquals(new BigDecimal("60.504"), dto.getTotalAmountExcludingVat());
		assertEquals(new BigDecimal("45.0"), dto.getLines().get(0).getTotalAmountIncludingVat());
		assertNull(dto.getLines().get(0).getPromotionCode());
		assertEquals("PROMO10", dto.getLines().get(1).getPromotionCode());
		assertEquals(new BigDecimal("18.90756"), dto.getLines().get(0).getUnitPrice(), "as stored");
	}

	@Test
	@DisplayName("A whole ticket, refused: nothing changed (retried next run), counted as an error, no status update")
	void wholeRefused() {
		when(manager.pushTicket(any()))
				.thenReturn(ErpOperationResult.failure("parafendri: posting the ticket ... failed: HTTP 400"));
		RuntimeException failed = assertThrows(RuntimeException.class, () -> service("parafendri").exportTicket(ticket));
		assertEquals("parafendri: posting the ticket ... failed: HTTP 400", failed.getMessage());
		assertNull(ticket.getErpNo());
		assertEquals(SynchronizationStatus.NOT_SYNCHED, ticket.getSynchronizationStatus());
		assertFalse(ticketLines.stream().anyMatch(SalesLine::getSynched), "nothing partial");
		verify(headers, never()).save(any());
		verify(lines, never()).save(any());
		verify(manager, never()).updateTicketStatus(anyString(), anyBoolean(), any(), any(), any());
	}

	@Test
	@DisplayName("EMTOP (and no connector): today's path, the header first, never pushTicket")
	void otherConnectors() {
		for (String connector : new String[] { "emtop", "none" }) {
			when(manager.pushTicketHeader(any())).thenReturn(ErpOperationResult.failure("stop after the header"));
			assertThrows(RuntimeException.class, () -> service(connector).exportTicket(ticket), connector);
			verify(manager, never()).pushTicket(any());
		}
		verify(manager, org.mockito.Mockito.times(2)).pushTicketHeader(any());
	}

	@Test
	@DisplayName("A run of two tickets with the same connector warning: one WARN, one WARNING row of the log, at the run's end")
	void oneWarningPerRun() {
		SalesHeader second = new SalesHeader();
		second.setSalesNumber("PV-GREMDA251031002");
		second.setSalesDate(LocalDateTime.of(2026, 10, 31, 10, 20));
		second.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		when(lines.findBySalesHeader(second)).thenReturn(ticketLines);
		when(headers.findByStatusAndSynchronizationStatusNot(TransactionStatus.COMPLETED,
				SynchronizationStatus.TOTALLY_SYNCHED)).thenReturn(Arrays.asList(ticket, second));
		String guard = "no-double guard unavailable: ticketNo is not filterable in BC";
		when(manager.pushTicket(any())).thenAnswer(call -> ErpOperationResult.success("CV-1").addWarning(guard));

		service("parafendri").exportTickets();
		verify(manager, org.mockito.Mockito.times(2)).pushTicket(any());
		verify(communications, org.mockito.Mockito.times(1)).logOperation(org.mockito.ArgumentMatchers.eq(
				ErpSyncOperation.EXPORT_TICKET), any(), any(), org.mockito.ArgumentMatchers.eq(ErpCommunicationStatus.WARNING),
				any(), org.mockito.ArgumentMatchers.eq(guard), any(), any());

		// Alone (not in a run): its warnings written at once
		service("parafendri").exportTicket(second);
		verify(communications, org.mockito.Mockito.times(2)).logOperation(any(), any(), any(),
				org.mockito.ArgumentMatchers.eq(ErpCommunicationStatus.WARNING), any(), any(), any(), any());
	}

	@Test
	@DisplayName("The seeding question: a connector posting tickets whole (parafendri) gets returns and sessions disabled")
	void seeding() {
		assertTrue(TestModes.of(withConnector("parafendri")).isWholeTicketExport());
		assertFalse(TestModes.of(withConnector("emtop")).isWholeTicketExport());
		assertFalse(TestModes.of(withConnector("none")).isWholeTicketExport());
	}
}
