package com.digithink.zsretail.store.erp.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.core.erp.service.ErpCommunicationService;
import com.digithink.zsretail.core.erp.service.ErpExportJob;
import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.erp.api.ErpAbilities;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.dto.ErpInvoiceResult;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketExportService implements ErpExportJob {

	private static final Logger LOGGER = LoggerFactory.getLogger(TicketExportService.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final SalesHeaderRepository salesHeaderRepository;
	private final SalesLineRepository salesLineRepository;
	private final GeneralSetupService generalSetupService;
	/** 3.1.1: the connector in effect's abilities are read from it (ErpAbilities). */
	private final Environment environment;
	/** 3.1.1: the warnings of a run (a connector posting tickets whole), once each. */
	private final ErpCommunicationService communicationService;
	/** 3.1.1 (step 7): the ERP's invoices of the tickets, as PDF files. */
	private final ErpInvoicePdfFiles invoicePdfFiles;

	@Override
	public ErpSyncJobType jobType() {
		return ErpSyncJobType.EXPORT_TICKETS;
	}

	/** Through the proxy, in the same transaction the export method had when the runner called it. */
	@Override
	@Transactional
	public void export() {
		exportTickets();
	}

	/**
	 * Export tickets to Dynamics NAV
	 */
	@Transactional
	public void exportTickets() {
		LOGGER.info("Starting ticket export to ERP");

		// Find all completed tickets that are not totally synched
		List<SalesHeader> ticketsToSync = salesHeaderRepository.findByStatusAndSynchronizationStatusNot(
				TransactionStatus.COMPLETED, SynchronizationStatus.TOTALLY_SYNCHED);

		if (ticketsToSync.isEmpty()) {
			LOGGER.info("No tickets to export");
			return;
		}

		LOGGER.info("Found {} tickets to export", ticketsToSync.size());

		int successCount = 0;
		int errorCount = 0;

		// 3.1.1: the connector's warnings of this run, each written once at its end
		runWarnings.set(new LinkedHashMap<>());
		try {
			for (SalesHeader ticket : ticketsToSync) {
				try {
					exportTicket(ticket);
					successCount++;
				} catch (Exception ex) {
					LOGGER.error("Failed to export ticket {}: {}", ticket.getSalesNumber(), ex.getMessage(), ex);
					errorCount++;
				}
			}
		} finally {
			Map<String, ErpSyncOperation> warnings = runWarnings.get();
			runWarnings.remove();
			writeWarnings(warnings);
		}

		LOGGER.info("Ticket export completed. Success: {}, Errors: {}", successCount, errorCount);
	}

	/**
	 * 3.1.1: the connector's warnings of the current export run (exportTickets), each with the operation it came from;
	 * none outside a run.
	 */
	private final ThreadLocal<Map<String, ErpSyncOperation>> runWarnings = new ThreadLocal<>();

	/** 3.1.1: a result's warnings kept for the end of the run, or written now when the ticket is exported alone. */
	private void keepWarnings(ErpOperationResult result, ErpSyncOperation operation) {
		if (result.getWarnings().isEmpty()) {
			return;
		}
		Map<String, ErpSyncOperation> run = runWarnings.get();
		Map<String, ErpSyncOperation> these = run != null ? run : new LinkedHashMap<>();
		result.getWarnings().forEach(warning -> these.putIfAbsent(warning, operation));
		if (run == null) {
			writeWarnings(these);
		}
	}

	/**
	 * 3.1.1: each warning once, a WARN line and a WARNING row of the communications log (its operation: EXPORT_TICKET,
	 * REQUEST_INVOICE).
	 */
	private void writeWarnings(Map<String, ErpSyncOperation> warnings) {
		if (warnings == null) {
			return;
		}
		warnings.forEach((warning, operation) -> {
			LOGGER.warn("Ticket export: {}", warning);
			LocalDateTime now = LocalDateTime.now();
			communicationService.logOperation(operation, null, null, ErpCommunicationStatus.WARNING, null, warning, now,
					now);
		});
	}

	/**
	 * Export a single ticket to ERP
	 */
	@Transactional
	public void exportTicket(SalesHeader ticket) {
		LOGGER.info("Exporting ticket: {}", ticket.getSalesNumber());

		// 3.1.1: a connector that posts a ticket whole (ErpAbility.WHOLE_TICKET_EXPORT) gets it in one call
		if (ErpAbilities.has(environment, ErpAbility.WHOLE_TICKET_EXPORT)) {
			exportWholeTicket(ticket);
			return;
		}

		// If not synched at all, create header first
		if (ticket.getSynchronizationStatus() == SynchronizationStatus.NOT_SYNCHED) {
			exportTicketHeader(ticket);
		}

		// Export lines that are not synched
		exportTicketLines(ticket);

		// Check if all lines are synched, then update header status
		List<SalesLine> allLines = salesLineRepository.findBySalesHeader(ticket);
		boolean allLinesSynched = allLines.stream().allMatch(SalesLine::getSynched);

		if (allLinesSynched && !allLines.isEmpty()) {
			// Update POS_Order (and POS_Invoice, Fiscal_Registration when invoiced) in ERP
			if (ticket.getErpNo() != null) {
				try {
					Boolean posInvoice = Boolean.TRUE.equals(ticket.getInvoiced()) ? true : null;
					String fiscalRegistration = (ticket.getFiscalRegistration() != null
							&& !ticket.getFiscalRegistration().trim().isEmpty()) ? ticket.getFiscalRegistration().trim()
									: null;
					String billToName2 = (ticket.getInvoiceCustomerName() != null
							&& !ticket.getInvoiceCustomerName().trim().isEmpty())
									? ticket.getInvoiceCustomerName().trim()
									: null;
					ErpOperationResult result = synchronizationManager.updateTicketStatus(ticket.getErpNo(), true,
							posInvoice, fiscalRegistration, billToName2);
					if (result.isSuccess()) {
						// After POS_Order update succeeds, mark ticket as totally synchronized
						ticket.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
						salesHeaderRepository.save(ticket);
						LOGGER.info("Ticket {} fully synchronized", ticket.getSalesNumber());
					} else {
						LOGGER.error("Failed to update POS_Order for ticket {}: {}", ticket.getSalesNumber(),
								result.getMessage());
						// Don't update status - we'll try again next time
					}
				} catch (Exception ex) {
					LOGGER.error("Failed to update POS_Order for ticket {}: {}", ticket.getSalesNumber(),
							ex.getMessage(), ex);
					// Don't update status - we'll try again next time
				}
			} else {
				// No ERP document number, can't update status
				LOGGER.warn("Cannot update POS_Order for ticket {} - no ERP document number", ticket.getSalesNumber());
			}
		}
	}

	/**
	 * 3.1.1 (ErpAbility.WHOLE_TICKET_EXPORT): the ticket with all its lines in one call (pushTicket), all or nothing. A
	 * success gives the ERP's document number: the ticket and every line are synchronized at once. A failure leaves the
	 * ticket as it was (nothing partial, no status update afterwards), retried at the next run; it is counted as an error
	 * like a failed header. The connector makes sure a ticket is never posted twice (it looks it up first).
	 * <p>
	 * Step 7, the invoice: a ticket with its ERP number is never posted again. Prepared for an invoice (invoiced, no
	 * invoice number yet, no local invoice), its invoice is asked from the ERP ({@link #requestInvoice}); prepared before
	 * it went, it is asked right after the post, the ticket PARTIALLY_SYNCHED until the invoice is there. A ticket with
	 * its ERP number and nothing due (back to PARTIALLY_SYNCHED with no invoice to ask) is TOTALLY_SYNCHED again.
	 */
	private void exportWholeTicket(SalesHeader ticket) {
		if (notBlank(ticket.getErpNo())) {
			if (!invoiceDue(ticket)) {
				ticket.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
				salesHeaderRepository.save(ticket);
				LOGGER.info("Ticket {} already in the ERP as {}, nothing due: synchronized", ticket.getSalesNumber(),
						ticket.getErpNo());
				return;
			}
			if (!requestInvoice(ticket)) {
				// Counted as an error of the run; the ticket stays PARTIALLY_SYNCHED, asked again next run
				throw new RuntimeException("The invoice of ticket " + ticket.getSalesNumber() + " (" + ticket.getErpNo()
						+ " in the ERP) was not made: asked again next run");
			}
			return;
		}
		ErpOperationResult result = synchronizationManager.pushTicket(toErpTicketDTO(ticket));
		keepWarnings(result, ErpSyncOperation.EXPORT_TICKET);
		if (!result.isSuccess() || result.getExternalReference() == null) {
			String errorMsg = result.getMessage() != null ? result.getMessage()
					: "Failed to get document number from ERP response";
			LOGGER.error("Failed to export ticket {}: {}", ticket.getSalesNumber(), errorMsg);
			throw new RuntimeException(errorMsg);
		}
		for (SalesLine line : salesLineRepository.findBySalesHeader(ticket)) {
			if (!Boolean.TRUE.equals(line.getSynched())) {
				line.setSynched(true);
				salesLineRepository.save(line);
			}
		}
		ticket.setErpNo(result.getExternalReference());
		boolean invoiceDue = invoiceDue(ticket);
		ticket.setSynchronizationStatus(
				invoiceDue ? SynchronizationStatus.PARTIALLY_SYNCHED : SynchronizationStatus.TOTALLY_SYNCHED);
		salesHeaderRepository.save(ticket);
		LOGGER.info("Ticket {} exported whole to ERP with document number: {}", ticket.getSalesNumber(),
				result.getExternalReference());
		if (invoiceDue) {
			// Prepared for an invoice before it went: asked now; a failure keeps the post (not an error), asked next run
			requestInvoice(ticket);
		}
	}

	/**
	 * 3.1.1 (step 7): an invoice to ask from the ERP: the ticket prepared for one (prepareInvoice: invoiced, its fiscal
	 * registration) and none made yet, neither by the ERP (no invoice number) nor here (no local invoice).
	 */
	static boolean invoiceDue(SalesHeader ticket) {
		return Boolean.TRUE.equals(ticket.getInvoiced()) && !notBlank(ticket.getInvoiceNumber())
				&& ticket.getInvoice() == null;
	}

	/**
	 * 3.1.1 (step 7): the invoice of a ticket in the ERP asked (requestInvoice, its fiscal registration and invoice name
	 * given). A success: the PDF written ({@link ErpInvoicePdfFiles}, its place derived from the number), then the
	 * ticket's invoice number and TOTALLY_SYNCHED. Anything else (a refusal, no answer, the PDF not written) leaves the
	 * ticket PARTIALLY_SYNCHED, asked again next run (the connector answers an invoice already made without making
	 * another). True when the ticket has its invoice now.
	 */
	private boolean requestInvoice(SalesHeader ticket) {
		ErpInvoiceResult result;
		try {
			result = synchronizationManager.requestInvoice(ticket.getErpNo(),
					trimmedOrNull(ticket.getFiscalRegistration()), trimmedOrNull(ticket.getInvoiceCustomerName()));
		} catch (Exception ex) {
			LOGGER.error("Failed to request the invoice of ticket {} ({}): {}", ticket.getSalesNumber(),
					ticket.getErpNo(), ex.getMessage(), ex);
			return false;
		}
		keepWarnings(result, ErpSyncOperation.REQUEST_INVOICE);
		if (!result.isSuccess() || !notBlank(result.getInvoiceNo())) {
			LOGGER.error("Failed to request the invoice of ticket {} ({}): {}", ticket.getSalesNumber(),
					ticket.getErpNo(),
					result.getMessage() != null ? result.getMessage() : "no invoice number in the ERP's answer");
			return false;
		}
		String invoiceNo = result.getInvoiceNo().trim();
		if (result.getPdf() != null && result.getPdf().length > 0) {
			try {
				Path file = invoicePdfFiles.write(invoiceNo, result.getPdf());
				LOGGER.info("Ticket {}: invoice {} written to {}", ticket.getSalesNumber(), invoiceNo, file);
			} catch (IOException | RuntimeException ex) {
				LOGGER.error("Ticket {}: the PDF of invoice {} could not be written ({}): asked again next run",
						ticket.getSalesNumber(), invoiceNo, ex.getMessage(), ex);
				return false;
			}
		} else {
			LOGGER.warn("Ticket {}: the ERP made invoice {} without its PDF", ticket.getSalesNumber(), invoiceNo);
		}
		ticket.setInvoiceNumber(invoiceNo);
		ticket.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
		salesHeaderRepository.save(ticket);
		LOGGER.info("Ticket {} ({} in the ERP) invoiced by the ERP: {}", ticket.getSalesNumber(), ticket.getErpNo(),
				invoiceNo);
		return true;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String trimmedOrNull(String value) {
		return notBlank(value) ? value.trim() : null;
	}

	/**
	 * Export ticket header to ERP
	 */
	private void exportTicketHeader(SalesHeader ticket) {
		// Convert to ErpTicketDTO
		ErpTicketDTO ticketDTO = toErpTicketDTO(ticket);

		// Push header to ERP
		ErpOperationResult result = synchronizationManager.pushTicketHeader(ticketDTO);

		if (result.isSuccess() && result.getExternalReference() != null) {
			// Save external reference (document number) to ticket
			ticket.setErpNo(result.getExternalReference());
			ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
			salesHeaderRepository.save(ticket);

			LOGGER.info("Ticket header {} exported to ERP with document number: {}", ticket.getSalesNumber(),
					result.getExternalReference());
		} else {
			String errorMsg = result.getMessage() != null ? result.getMessage()
					: "Failed to get document number from ERP response";
			LOGGER.error("Failed to export ticket header {}: {}", ticket.getSalesNumber(), errorMsg);
			throw new RuntimeException(errorMsg);
		}
	}

	/**
	 * Export ticket lines to ERP
	 */
	private void exportTicketLines(SalesHeader ticket) {
		if (ticket.getErpNo() == null) {
			LOGGER.warn("Cannot export lines for ticket {} - no ERP document number", ticket.getSalesNumber());
			return;
		}

		List<SalesLine> lines = salesLineRepository.findBySalesHeader(ticket);
		List<SalesLine> unsynchedLines = lines.stream().filter(line -> !Boolean.TRUE.equals(line.getSynched()))
				.toList();

		if (unsynchedLines.isEmpty()) {
			LOGGER.info("All lines for ticket {} are already synched", ticket.getSalesNumber());
			return;
		}

		LOGGER.info("Exporting {} lines for ticket {}", unsynchedLines.size(), ticket.getSalesNumber());

		// Convert ticket to ErpTicketDTO (needed for pushTicketLine)
		ErpTicketDTO ticketDTO = toErpTicketDTO(ticket);

		for (SalesLine line : unsynchedLines) {
			try {
				// Convert line to ErpTicketLineDTO
				ErpTicketLineDTO lineDTO = toErpTicketLineDTO(line);

				// Push line to ERP
				ErpOperationResult result = synchronizationManager.pushTicketLine(ticketDTO, ticket.getErpNo(),
						lineDTO);

				if (result.isSuccess()) {
					// Mark line as synched
					line.setSynched(true);
					salesLineRepository.save(line);

					LOGGER.info("Exported line {} for ticket {}", line.getId(), ticket.getSalesNumber());
				} else {
					LOGGER.error("Failed to export line {} for ticket {}: {}", line.getId(), ticket.getSalesNumber(),
							result.getMessage());
					// Continue with next line
				}
			} catch (Exception ex) {
				LOGGER.error("Failed to export line {} for ticket {}: {}", line.getId(), ticket.getSalesNumber(),
						ex.getMessage(), ex);
				// Continue with next line
			}
		}
	}

	/**
	 * Convert SalesHeader to ErpTicketDTO
	 */
	private ErpTicketDTO toErpTicketDTO(SalesHeader ticket) {
		ErpTicketDTO dto = new ErpTicketDTO();
		dto.setTicketNumber(ticket.getSalesNumber());
		dto.setSaleDate(ticket.getSalesDate());
		dto.setExternalId(ticket.getErpNo());

		// Set customer external ID
		if (ticket.getCustomer() != null) {
			dto.setCustomerExternalId(ticket.getCustomer().getCustomerCode());
		}

		// Set responsibility center and location from GeneralSetup
		String responsibilityCenter = generalSetupService.findValueByCode("RESPONSIBILITY_CENTER");
		if (responsibilityCenter != null) {
			dto.setResponsibilityCenter(responsibilityCenter);
		}

		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode != null) {
			dto.setLocationExternalId(locationCode);
		}

		// Set cashier session ID
		if (ticket.getCashierSession() != null) {
			dto.setCashierSessionId(ticket.getCashierSession().getSessionNumber());
		}

		List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(ticket);

		// Set discount percentage — combines header discount + loyalty deduction into
		// a single Discount_Percent so NAV can reconcile: gross × (1 − pct/100) =
		// Ticket_Amount. I-48: gross = the lines' TTC (stamp included), what the till's
		// subtotal + VAT were; since 3.1.0 those carry the reduction on a reduced ticket
		double gross = salesLines.stream()
				.mapToDouble(l -> l.getLineTotalIncludingVat() != null ? l.getLineTotalIncludingVat() : 0.0).sum();
		double totalDeduction = (ticket.getDiscountAmount() != null ? ticket.getDiscountAmount() : 0.0)
				+ (ticket.getLoyaltyDeductionAmount() != null ? ticket.getLoyaltyDeductionAmount() : 0.0);
		if (totalDeduction > 0 && gross > 0) {
			dto.setDiscountPercentage(totalDeduction / gross * 100.0);
		}
		// 3.1.1 (a ticket posted whole): the same reduction as an amount including VAT
		if (totalDeduction > 0) {
			dto.setTicketDiscountAmount(BigDecimal.valueOf(totalDeduction));
		}

		// Set total amount
		if (ticket.getTotalAmount() != null) {
			dto.setTotalAmount(BigDecimal.valueOf(ticket.getTotalAmount()));
		}
		// 3.1.1 (a ticket posted whole): the total excluding VAT, after the ticket-wide reduction (I-48)
		if (ticket.getSubtotal() != null) {
			dto.setTotalAmountExcludingVat(BigDecimal.valueOf(ticket.getSubtotal()));
		}

		// Invoice fields for NAV (POS_Invoice, Fiscal_Registration)
		dto.setPosInvoice(Boolean.TRUE.equals(ticket.getInvoiced()));
		if (ticket.getFiscalRegistration() != null && !ticket.getFiscalRegistration().trim().isEmpty()) {
			dto.setFiscalRegistration(ticket.getFiscalRegistration().trim());
		}

		// Optional invoice customer name (sent to NAV Bill_to_Name_2)
		if (ticket.getInvoiceCustomerName() != null && !ticket.getInvoiceCustomerName().trim().isEmpty()) {
			dto.setInvoiceCustomerName(ticket.getInvoiceCustomerName().trim());
		}

		// Convert lines (3.1.1: each its position from 1, for a ticket posted whole)
		for (SalesLine salesLine : salesLines) {
			ErpTicketLineDTO lineDTO = toErpTicketLineDTO(salesLine);
			lineDTO.setLineNumber(dto.getLines().size() + 1);
			dto.getLines().add(lineDTO);
		}

		return dto;
	}

	/**
	 * Convert SalesLine to ErpTicketLineDTO
	 */
	private ErpTicketLineDTO toErpTicketLineDTO(SalesLine line) {
		ErpTicketLineDTO dto = new ErpTicketLineDTO();

		if (line.getItem() != null) {
			// Tax stamp item: export using ERP code from GeneralSetup so ERP recognizes the
			// line
			if ("TAX_STAMP".equals(line.getItem().getItemCode())) {
				String erpCode = generalSetupService.findValueByCode("TAX_STAMP_ERP_ITEM_CODE");
				dto.setItemExternalId(erpCode != null && !erpCode.isEmpty() ? erpCode : "TAX_STAMP");
			} else {
				dto.setItemExternalId(line.getItem().getItemCode());
			}
		}

		dto.setQuantity(line.getQuantity()); // 2.2.1: normalized, 2 stays 2 (scale 0) as with BigDecimal.valueOf(int)
		dto.setUnitPrice(BigDecimal.valueOf(line.getUnitPrice()));

		// Use stored discount % when available; otherwise derive it from lineTotal vs
		// grossHT (fixed-amount promotions store discount_amount only, % stays null)
		if (line.getDiscountPercentage() != null) {
			dto.setDiscountPercentage(BigDecimal.valueOf(line.getDiscountPercentage()));
		} else if (line.getDiscountAmount() != null && line.getDiscountAmount() > 0 && line.getUnitPrice() != null
				&& line.getUnitPrice() > 0 && line.getQuantity() != null && line.getQuantity().signum() > 0
				&& line.getLineTotal() != null) {
			double grossHT = line.getUnitPrice() * line.getQuantity().doubleValue();
			double pct = (1.0 - line.getLineTotal() / grossHT) * 100.0;
			if (pct > 0 && pct <= 100) {
				dto.setDiscountPercentage(BigDecimal.valueOf(pct));
			}
		}

		if (line.getDiscountAmount() != null) {
			dto.setDiscountAmount(BigDecimal.valueOf(line.getDiscountAmount()));
		}

		if (line.getLineTotal() != null) {
			dto.setTotalAmount(BigDecimal.valueOf(line.getLineTotal()));
		}
		// 3.1.1 (a ticket posted whole): the line's TTC and its promotion's code
		if (line.getLineTotalIncludingVat() != null) {
			dto.setTotalAmountIncludingVat(BigDecimal.valueOf(line.getLineTotalIncludingVat()));
		}
		if (line.getPromotion() != null) {
			dto.setPromotionCode(line.getPromotion().getCode());
		}

		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode != null) {
			dto.setLocationCode(locationCode);
		}

		return dto;
	}

}
