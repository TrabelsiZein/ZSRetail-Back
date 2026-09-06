package com.digithink.zsretail.erp.service;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.erp.dto.ErpOperationResult;
import com.digithink.zsretail.erp.dto.ErpReturnDTO;
import com.digithink.zsretail.erp.dto.ErpReturnLineDTO;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.ReturnLineRepository;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReturnExportService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReturnExportService.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final ReturnHeaderRepository returnHeaderRepository;
	private final ReturnLineRepository returnLineRepository;
	private final GeneralSetupService generalSetupService;

	/**
	 * Export returns to Dynamics NAV
	 */
	@Transactional
	public void exportReturns() {
		LOGGER.info("Starting return export to ERP");

		// Find all completed returns that are not totally synched
		List<ReturnHeader> returnsToSync = returnHeaderRepository.findByStatusAndSynchronizationStatusNot(
				TransactionStatus.COMPLETED, SynchronizationStatus.TOTALLY_SYNCHED);

		if (returnsToSync.isEmpty()) {
			LOGGER.info("No returns to export");
			return;
		}

		LOGGER.info("Found {} returns to export", returnsToSync.size());

		int successCount = 0;
		int errorCount = 0;

		for (ReturnHeader returnHeader : returnsToSync) {
			try {
				exportReturn(returnHeader);
				successCount++;
			} catch (Exception ex) {
				LOGGER.error("Failed to export return {}: {}", returnHeader.getReturnNumber(), ex.getMessage(), ex);
				errorCount++;
			}
		}

		LOGGER.info("Return export completed. Success: {}, Errors: {}", successCount, errorCount);
	}

	/**
	 * Export a single return to ERP
	 */
	@Transactional
	public void exportReturn(ReturnHeader returnHeader) {
		LOGGER.info("Exporting return: {}", returnHeader.getReturnNumber());

		// If not synched at all, create header first
		if (returnHeader.getSynchronizationStatus() == SynchronizationStatus.NOT_SYNCHED) {
			exportReturnHeader(returnHeader);
		}

		// Export lines that are not synched
		exportReturnLines(returnHeader);

		// Check if all lines are synched, then update header POS_Order in ERP and status
		List<ReturnLine> allLines = returnLineRepository.findByReturnHeader(returnHeader);
		boolean allLinesSynched = allLines.stream().allMatch(line -> Boolean.TRUE.equals(line.getSynched()));

		if (allLinesSynched && !allLines.isEmpty()) {
			// Update POS_Order to true in ERP (like updateTicketStatus for tickets)
			if (returnHeader.getErpNo() != null) {
				try {
					ErpOperationResult result = synchronizationManager.updateReturnStatus(returnHeader.getErpNo(), true);
					if (result.isSuccess()) {
						returnHeader.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
						returnHeaderRepository.save(returnHeader);
						LOGGER.info("Return {} fully synchronized", returnHeader.getReturnNumber());
					} else {
						LOGGER.error("Failed to update POS_Order for return {}: {}", returnHeader.getReturnNumber(),
								result.getMessage());
						returnHeader.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
						returnHeaderRepository.save(returnHeader);
					}
				} catch (Exception ex) {
					LOGGER.error("Failed to update POS_Order for return {}: {}", returnHeader.getReturnNumber(),
							ex.getMessage(), ex);
					returnHeader.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
					returnHeaderRepository.save(returnHeader);
				}
			} else {
				returnHeader.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
				returnHeaderRepository.save(returnHeader);
				LOGGER.info("Return {} fully synchronized (no ERP document number)", returnHeader.getReturnNumber());
			}
		} else if (!allLines.isEmpty()) {
			returnHeader.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
			returnHeaderRepository.save(returnHeader);
			LOGGER.info("Return {} partially synchronized", returnHeader.getReturnNumber());
		}
	}

	/**
	 * Export return header to ERP
	 */
	private void exportReturnHeader(ReturnHeader returnHeader) {
		// Convert to ErpReturnDTO
		ErpReturnDTO returnDTO = toErpReturnDTO(returnHeader);

		// Push header to ERP
		ErpOperationResult result = synchronizationManager.pushReturnHeader(returnDTO);

		if (result.isSuccess() && result.getExternalReference() != null) {
			// Save external reference (document number) to return header
			returnHeader.setErpNo(result.getExternalReference());
			returnHeader.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
			returnHeaderRepository.save(returnHeader);

			LOGGER.info("Return header {} exported to ERP with document number: {}", returnHeader.getReturnNumber(),
					result.getExternalReference());
		} else {
			String errorMsg = result.getMessage() != null ? result.getMessage()
					: "Failed to get document number from ERP response";
			LOGGER.error("Failed to export return header {}: {}", returnHeader.getReturnNumber(), errorMsg);
			throw new RuntimeException(errorMsg);
		}
	}

	/**
	 * Export return lines to ERP
	 */
	private void exportReturnLines(ReturnHeader returnHeader) {
		if (returnHeader.getErpNo() == null) {
			LOGGER.warn("Cannot export lines for return {} - no ERP document number", returnHeader.getReturnNumber());
			return;
		}

		List<ReturnLine> lines = returnLineRepository.findByReturnHeader(returnHeader);
		List<ReturnLine> unsynchedLines = lines.stream().filter(line -> !Boolean.TRUE.equals(line.getSynched()))
				.toList();

		if (unsynchedLines.isEmpty()) {
			LOGGER.info("All lines for return {} are already synched", returnHeader.getReturnNumber());
			return;
		}

		LOGGER.info("Exporting {} lines for return {}", unsynchedLines.size(), returnHeader.getReturnNumber());

		// Convert return to ErpReturnDTO (needed for pushReturnLine)
		ErpReturnDTO returnDTO = toErpReturnDTO(returnHeader);

		for (ReturnLine line : unsynchedLines) {
			try {
				// Convert line to ErpReturnLineDTO
				ErpReturnLineDTO lineDTO = toErpReturnLineDTO(line);

				// Push line to ERP
				ErpOperationResult result = synchronizationManager.pushReturnLine(returnDTO, returnHeader.getErpNo(),
						lineDTO);

				if (result.isSuccess()) {
					// Mark line as synched
					line.setSynched(true);
					returnLineRepository.save(line);

					LOGGER.info("Exported line {} for return {}", line.getId(), returnHeader.getReturnNumber());
				} else {
					LOGGER.error("Failed to export line {} for return {}: {}", line.getId(),
							returnHeader.getReturnNumber(), result.getMessage());
					// Continue with next line
				}
			} catch (Exception ex) {
				LOGGER.error("Failed to export line {} for return {}: {}", line.getId(), returnHeader.getReturnNumber(),
						ex.getMessage(), ex);
				// Continue with next line
			}
		}
	}

	/**
	 * Convert ReturnHeader to ErpReturnDTO
	 */
	private ErpReturnDTO toErpReturnDTO(ReturnHeader returnHeader) {
		ErpReturnDTO dto = new ErpReturnDTO();
		dto.setReturnNumber(returnHeader.getReturnNumber());
		dto.setReturnDate(returnHeader.getReturnDate());
		dto.setExternalId(returnHeader.getErpNo());

		// Set customer external ID from original sales header
		if (returnHeader.getOriginalSalesHeader() != null
				&& returnHeader.getOriginalSalesHeader().getCustomer() != null) {
			dto.setCustomerExternalId(returnHeader.getOriginalSalesHeader().getCustomer().getCustomerCode());
			dto.setOriginalSalesNumber(returnHeader.getOriginalSalesHeader().getSalesNumber());
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
		if (returnHeader.getCashierSession() != null) {
			dto.setCashierSessionId(returnHeader.getCashierSession().getSessionNumber());
		}

		// Set total amount
		if (returnHeader.getTotalReturnAmount() != null) {
			dto.setTotalReturnAmount(BigDecimal.valueOf(returnHeader.getTotalReturnAmount()));
		}

		dto.setDiscountPercentage(returnHeader.getDiscountPercentage());

		// Convert lines
		List<ReturnLine> returnLines = returnLineRepository.findByReturnHeader(returnHeader);
		for (ReturnLine returnLine : returnLines) {
			dto.getLines().add(toErpReturnLineDTO(returnLine));
		}

		return dto;
	}

	/**
	 * Convert ReturnLine to ErpReturnLineDTO
	 */
	private ErpReturnLineDTO toErpReturnLineDTO(ReturnLine line) {
		ErpReturnLineDTO dto = new ErpReturnLineDTO();

		if (line.getItem() != null) {
			dto.setItemExternalId(line.getItem().getItemCode());
		}

		dto.setQuantity(BigDecimal.valueOf(line.getQuantity()));
		dto.setUnitPrice(BigDecimal.valueOf(line.getUnitPrice()));
		dto.setUnitPriceIncludingVat(BigDecimal.valueOf(line.getUnitPriceIncludingVat()));
		dto.setLineTotal(BigDecimal.valueOf(line.getLineTotal()));
		dto.setLineTotalIncludingVat(BigDecimal.valueOf(line.getLineTotalIncludingVat()));

		// Set discount percentage from original sales line if available
		if (line.getOriginalSalesLine() != null && line.getOriginalSalesLine().getDiscountPercentage() != null) {
			dto.setDiscountPercentage(BigDecimal.valueOf(line.getOriginalSalesLine().getDiscountPercentage()));
		}

		// Set original sales line number if available
		if (line.getOriginalSalesLine() != null && line.getOriginalSalesLine().getSalesHeader() != null) {
			dto.setOriginalSalesLineNumber(line.getOriginalSalesLine().getSalesHeader().getSalesNumber());
		}

		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode != null) {
			dto.setLocationCode(locationCode);
		}

		return dto;
	}
}
