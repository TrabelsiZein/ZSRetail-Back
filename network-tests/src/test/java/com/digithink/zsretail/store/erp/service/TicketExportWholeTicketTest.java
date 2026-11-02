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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import com.digithink.zsretail.core.model.InvoiceHeader;
import com.digithink.zsretail.erp.api.dto.ErpInvoiceResult;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.support.Installations;

/**
 * 3.1.1 (ParaFendri, step 6): TicketExportService with a connector that posts a ticket whole
 * (ErpAbility.WHOLE_TICKET_EXPORT, parafendri): one pushTicket with every line, success = the ERP number, the ticket
 * and its lines synchronized at once; failure = nothing changed, retried next run, no status update. Any other
 * connector (emtop) or none: today's path, the header first (pushTicketHeader), never pushTicket. Step 7: a ticket in
 * the ERP is never posted again; prepared for an invoice, its invoice is asked once (requestInvoice), its number kept
 * and its PDF written under app.upload.dir/erp-invoices; a failure leaves it PARTIALLY_SYNCHED. EMTOP's invoice path
 * (updateTicketStatus with POS_Invoice) is unchanged.
 */
class TicketExportWholeTicketTest {

	private ErpSynchronizationManager manager;
	private SalesHeaderRepository headers;
	private SalesLineRepository lines;
	private GeneralSetupService setup;
	private ErpCommunicationService communications;
	private SalesHeader ticket;
	private List<SalesLine> ticketLines;
	/** Step 7: app.upload.dir of the test (the PDFs under erp-invoices/). */
	@TempDir
	Path uploads;

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
		return new TicketExportService(manager, headers, lines, setup, env, communications,
				new ErpInvoicePdfFiles(uploads.toString()));
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

		// Alone (not in a run): its warnings written at once (a ticket not posted yet: one with its erpNo is never
		// posted again, step 7)
		second.setErpNo(null);
		second.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		service("parafendri").exportTicket(second);
		verify(communications, org.mockito.Mockito.times(2)).logOperation(any(), any(), any(),
				org.mockito.ArgumentMatchers.eq(ErpCommunicationStatus.WARNING), any(), any(), any(), any());
	}

	// ─── Step 7: the invoice ────────────

	private static final String PDF = "%PDF-1.4\n%test\n%%EOF\n";

	/** The ticket in the ERP (step 6 done), then prepared for an invoice (prepareInvoice: back to PARTIALLY_SYNCHED). */
	private void preparedInERP() {
		ticket.setErpNo("CV-000001");
		ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		prepared();
	}

	private void prepared() {
		ticket.setInvoiced(true);
		ticket.setFiscalRegistration("1234567A/M/000");
		ticket.setInvoiceCustomerName("Société Test");
	}

	private static ErpInvoiceResult invoice(String number) {
		return ErpInvoiceResult.invoiced(number, PDF.getBytes(StandardCharsets.UTF_8), null, null, null);
	}

	@Test
	@DisplayName("Prepared after the post: the invoice asked once (never posted again), its number, its PDF written")
	void invoiceRequestedOnce() throws IOException {
		preparedInERP();
		when(manager.requestInvoice("CV-000001", "1234567A/M/000", "Société Test")).thenReturn(invoice("FV-000001"));
		service("parafendri").exportTicket(ticket);

		verify(manager, never()).pushTicket(any());
		verify(manager).requestInvoice("CV-000001", "1234567A/M/000", "Société Test");
		assertEquals("FV-000001", ticket.getInvoiceNumber());
		assertNull(ticket.getInvoice(), "no local invoice");
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
		Path pdf = uploads.resolve("erp-invoices").resolve("FV-000001.pdf");
		assertEquals(PDF, new String(Files.readAllBytes(pdf), StandardCharsets.UTF_8));
		assertEquals(pdf, new ErpInvoicePdfFiles(uploads.toString()).pathOf("FV-000001"), "derived from the number");
		verify(headers).save(ticket);

		// Exported again (e.g. picked up by mistake): the invoice is there, nothing asked, nothing posted
		ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		service("parafendri").exportTicket(ticket);
		verify(manager, org.mockito.Mockito.times(1)).requestInvoice(anyString(), any(), any());
		verify(manager, never()).pushTicket(any());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
	}

	@Test
	@DisplayName("Prepared before the post: posted whole, then the invoice asked at once with its ERP number")
	void invoicedBeforeExport() {
		prepared();
		when(manager.pushTicket(any())).thenReturn(ErpOperationResult.success("CV-000002"));
		when(manager.requestInvoice("CV-000002", "1234567A/M/000", "Société Test")).thenReturn(invoice("FV-000002"));
		service("parafendri").exportTicket(ticket);

		verify(manager).pushTicket(any());
		verify(manager).requestInvoice("CV-000002", "1234567A/M/000", "Société Test");
		assertEquals("CV-000002", ticket.getErpNo());
		assertEquals("FV-000002", ticket.getInvoiceNumber());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
		assertTrue(Files.exists(uploads.resolve("erp-invoices").resolve("FV-000002.pdf")));
	}

	@Test
	@DisplayName("The invoice refused: PARTIALLY_SYNCHED kept, no invoice number, never posted again; an error only when nothing else was done")
	void invoiceRefused() {
		preparedInERP();
		when(manager.requestInvoice(anyString(), any(), any()))
				.thenReturn(ErpInvoiceResult.of(ErpOperationResult.failure("parafendri: ... failed: HTTP 400")));
		assertThrows(RuntimeException.class, () -> service("parafendri").exportTicket(ticket));
		verify(manager, never()).pushTicket(any());
		assertNull(ticket.getInvoiceNumber());
		assertEquals("CV-000001", ticket.getErpNo());
		assertEquals(SynchronizationStatus.PARTIALLY_SYNCHED, ticket.getSynchronizationStatus());
		assertFalse(Files.exists(uploads.resolve("erp-invoices")));

		// Just posted, then refused: the post kept (no error thrown), asked again next run
		SalesHeader fresh = new SalesHeader();
		fresh.setSalesNumber("PV-GREMDA251031003");
		fresh.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		fresh.setInvoiced(true);
		fresh.setFiscalRegistration("1234567A/M/000");
		when(lines.findBySalesHeader(fresh)).thenReturn(ticketLines);
		when(manager.pushTicket(any())).thenReturn(ErpOperationResult.success("CV-000003"));
		service("parafendri").exportTicket(fresh);
		assertEquals("CV-000003", fresh.getErpNo());
		assertNull(fresh.getInvoiceNumber());
		assertEquals(SynchronizationStatus.PARTIALLY_SYNCHED, fresh.getSynchronizationStatus());
	}

	@Test
	@DisplayName("In the ERP, nothing due (no invoice asked, or a local invoice): synchronized, never posted again, nothing asked")
	void nothingDue() {
		ticket.setErpNo("CV-000001");
		ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		service("parafendri").exportTicket(ticket);
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());

		ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		ticket.setInvoiced(true);
		ticket.setInvoice(new InvoiceHeader());
		service("parafendri").exportTicket(ticket);
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
		verify(manager, never()).pushTicket(any());
		verify(manager, never()).requestInvoice(any(), any(), any());
	}

	@Test
	@DisplayName("A run of two invoices with the fiscal warning: one WARNING row, under REQUEST_INVOICE")
	void fiscalWarningOncePerRun() {
		preparedInERP();
		SalesHeader second = new SalesHeader();
		second.setSalesNumber("PV-GREMDA251031002");
		second.setErpNo("CV-000002");
		second.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		second.setInvoiced(true);
		second.setFiscalRegistration("7654321B/M/000");
		when(headers.findByStatusAndSynchronizationStatusNot(TransactionStatus.COMPLETED,
				SynchronizationStatus.TOTALLY_SYNCHED)).thenReturn(Arrays.asList(ticket, second));
		String fiscal = "shipAndInvoice has no fiscal fields — ask the BC contact";
		when(manager.requestInvoice(anyString(), any(), any()))
				.thenAnswer(call -> invoice("FV-" + call.getArgument(0)).addWarning(fiscal));

		service("parafendri").exportTickets();
		verify(manager, org.mockito.Mockito.times(2)).requestInvoice(anyString(), any(), any());
		verify(communications, org.mockito.Mockito.times(1)).logOperation(org.mockito.ArgumentMatchers.eq(
				ErpSyncOperation.REQUEST_INVOICE), any(), any(), org.mockito.ArgumentMatchers.eq(
						ErpCommunicationStatus.WARNING), any(), org.mockito.ArgumentMatchers.eq(fiscal), any(), any());
		assertEquals("FV-CV-000002", second.getInvoiceNumber());
	}

	@Test
	@DisplayName("EMTOP: an invoiced ticket goes through updateTicketStatus (POS_Invoice) as before, never requestInvoice")
	void emtopInvoiceUnchanged() {
		preparedInERP();
		ticketLines.forEach(line -> line.setSynched(true));
		when(manager.updateTicketStatus("CV-000001", true, true, "1234567A/M/000", "Société Test"))
				.thenReturn(ErpOperationResult.success("CV-000001"));
		service("emtop").exportTicket(ticket);
		verify(manager).updateTicketStatus("CV-000001", true, true, "1234567A/M/000", "Société Test");
		verify(manager, never()).requestInvoice(any(), any(), any());
		verify(manager, never()).pushTicket(any());
		assertNull(ticket.getInvoiceNumber());
		assertEquals(SynchronizationStatus.TOTALLY_SYNCHED, ticket.getSynchronizationStatus());
	}

	@Test
	@DisplayName("The seeding question: a connector posting tickets whole (parafendri) gets returns and sessions disabled")
	void seeding() {
		assertTrue(TestModes.of(withConnector("parafendri")).isWholeTicketExport());
		assertFalse(TestModes.of(withConnector("emtop")).isWholeTicketExport());
		assertFalse(TestModes.of(withConnector("none")).isWholeTicketExport());
	}
}
