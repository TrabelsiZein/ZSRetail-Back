package com.digithink.zsretail.erp.service;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.erp.dto.ErpOperationResult;
import com.digithink.zsretail.erp.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.dto.ErpTicketLineDTO;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TicketExportService {

	private static final Logger LOGGER = LoggerFactory.getLogger(TicketExportService.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final SalesHeaderRepository salesHeaderRepository;
	private final SalesLineRepository salesLineRepository;
	private final GeneralSetupService generalSetupService;

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

		for (SalesHeader ticket : ticketsToSync) {
			try {
				exportTicket(ticket);
				successCount++;
			} catch (Exception ex) {
				LOGGER.error("Failed to export ticket {}: {}", ticket.getSalesNumber(), ex.getMessage(), ex);
				errorCount++;
			}
		}

		LOGGER.info("Ticket export completed. Success: {}, Errors: {}", successCount, errorCount);
	}

	/**
	 * Export a single ticket to ERP
	 */
	@Transactional
	public void exportTicket(SalesHeader ticket) {
		LOGGER.info("Exporting ticket: {}", ticket.getSalesNumber());

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

		// Set discount percentage — combines header discount + loyalty deduction into
		// a single Discount_Percent so NAV can reconcile: gross × (1 − pct/100) =
		// Ticket_Amount
		double gross = (ticket.getSubtotal() != null ? ticket.getSubtotal() : 0.0)
				+ (ticket.getTaxAmount() != null ? ticket.getTaxAmount() : 0.0);
		double totalDeduction = (ticket.getDiscountAmount() != null ? ticket.getDiscountAmount() : 0.0)
				+ (ticket.getLoyaltyDeductionAmount() != null ? ticket.getLoyaltyDeductionAmount() : 0.0);
		if (totalDeduction > 0 && gross > 0) {
			dto.setDiscountPercentage(totalDeduction / gross * 100.0);
		}

		// Set total amount
		if (ticket.getTotalAmount() != null) {
			dto.setTotalAmount(BigDecimal.valueOf(ticket.getTotalAmount()));
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

		// Convert lines
		List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(ticket);
		for (SalesLine salesLine : salesLines) {
			dto.getLines().add(toErpTicketLineDTO(salesLine));
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

		dto.setQuantity(BigDecimal.valueOf(line.getQuantity()));
		dto.setUnitPrice(BigDecimal.valueOf(line.getUnitPrice()));

		// Use stored discount % when available; otherwise derive it from lineTotal vs
		// grossHT (fixed-amount promotions store discount_amount only, % stays null)
		if (line.getDiscountPercentage() != null) {
			dto.setDiscountPercentage(BigDecimal.valueOf(line.getDiscountPercentage()));
		} else if (line.getDiscountAmount() != null && line.getDiscountAmount() > 0 && line.getUnitPrice() != null
				&& line.getUnitPrice() > 0 && line.getQuantity() != null && line.getQuantity() > 0
				&& line.getLineTotal() != null) {
			double grossHT = line.getUnitPrice() * line.getQuantity();
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

		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode != null) {
			dto.setLocationCode(locationCode);
		}

		return dto;
	}

}
