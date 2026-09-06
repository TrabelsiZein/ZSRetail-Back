package com.digithink.zsretail.erp.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import com.digithink.zsretail.erp.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.dto.ErpOperationResult;
import com.digithink.zsretail.erp.dto.ErpPaymentHeaderDTO;
import com.digithink.zsretail.erp.dto.ErpPaymentLineDTO;
import com.digithink.zsretail.erp.dto.ErpReturnDTO;
import com.digithink.zsretail.erp.dto.ErpReturnLineDTO;
import com.digithink.zsretail.erp.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.dto.ErpSalesPriceDTO;
import com.digithink.zsretail.erp.dto.ErpSessionDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.dto.ErpTicketLineDTO;
import com.digithink.zsretail.erp.dto.PullOperationResult;
import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.spi.ErpConnector;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpSynchronizationManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSynchronizationManager.class);

	private final ErpConnector erpConnector;
	private final ErpCommunicationService communicationService;

	public List<ErpItemFamilyDTO> pullItemFamilies(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_ITEM_FAMILIES, filter,
				() -> erpConnector.fetchItemFamilies(filter));
	}

	public List<ErpItemSubFamilyDTO> pullItemSubFamilies(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES, filter,
				() -> erpConnector.fetchItemSubFamilies(filter));
	}

	public List<ErpItemDTO> pullItems(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_ITEMS, filter, () -> erpConnector.fetchItems(filter));
	}

	public List<ErpItemBarcodeDTO> pullItemBarcodes(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_ITEM_BARCODES, filter,
				() -> erpConnector.fetchItemBarcodes(filter));
	}

	public List<ErpLocationDTO> pullLocations(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_LOCATIONS, filter,
				() -> erpConnector.fetchLocations(filter));
	}

	public List<ErpCustomerDTO> pullCustomers(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_CUSTOMERS, filter,
				() -> erpConnector.fetchCustomers(filter));
	}

	public List<ErpSalesPriceDTO> pullSalesPrices(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_SALES_PRICES, filter,
				() -> erpConnector.fetchSalesPrices(filter));
	}

	public List<ErpSalesDiscountDTO> pullSalesDiscounts(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.IMPORT_SALES_DISCOUNTS, filter,
				() -> erpConnector.fetchSalesDiscounts(filter));
	}

	public List<ErpDeletionLogEntryDTO> pullDeletionLog(ErpSyncFilter filter) {
		return executePullOperation(ErpSyncOperation.SYNC_ERP_DELETIONS, filter,
				() -> erpConnector.fetchDeletionLog(filter));
	}

	public ErpOperationResult pushCustomer(ErpCustomerDTO customerDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_CUSTOMER, customerDTO,
				() -> erpConnector.pushCustomer(customerDTO));
	}

	public ErpOperationResult pushTicket(ErpTicketDTO ticketDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_TICKET, ticketDTO,
				() -> erpConnector.pushTicket(ticketDTO));
	}

	public ErpOperationResult pushTicketHeader(ErpTicketDTO ticketDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_TICKET, ticketDTO,
				() -> erpConnector.pushTicketHeader(ticketDTO));
	}

	public ErpOperationResult pushTicketLine(ErpTicketDTO ticketDTO, String externalReference,
			ErpTicketLineDTO lineDTO) {
		// Create a payload object that includes both ticket and line info for logging
		Object payload = new Object() {
			public ErpTicketDTO getTicket() {
				return ticketDTO;
			}

			public ErpTicketLineDTO getLine() {
				return lineDTO;
			}

			public String getExternalReference() {
				return externalReference;
			}
		};

		return executePushOperation(ErpSyncOperation.EXPORT_TICKET_LINE, payload,
				() -> erpConnector.pushTicketLine(ticketDTO, externalReference, lineDTO));
	}

	public ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder) {
		return updateTicketStatus(externalReference, posOrder, null, null, null);
	}

	public ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice,
			String fiscalRegistration, String billToName2) {
		Object payload = new Object() {
			public String getExternalReference() {
				return externalReference;
			}

			public boolean getPosOrder() {
				return posOrder;
			}

			public Boolean getPosInvoice() {
				return posInvoice;
			}

			public String getFiscalRegistration() {
				return fiscalRegistration;
			}

			public String getBillToName2() {
				return billToName2;
			}
		};
		return executePushOperation(ErpSyncOperation.UPDATE_TICKET, payload,
				() -> erpConnector.updateTicketStatus(externalReference, posOrder, posInvoice, fiscalRegistration, billToName2));
	}

	public ErpOperationResult pushPaymentHeader(ErpPaymentHeaderDTO headerDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_PAYMENT_HEADER, headerDTO,
				() -> erpConnector.pushPaymentHeader(headerDTO));
	}

	public ErpOperationResult pushPaymentLine(String paymentHeaderDocNo, ErpPaymentLineDTO lineDTO) {
		// Create a payload object that includes both header doc no and line info for
		// logging
		Object payload = new Object() {
			public String getPaymentHeaderDocNo() {
				return paymentHeaderDocNo;
			}

			public ErpPaymentLineDTO getLine() {
				return lineDTO;
			}
		};

		return executePushOperation(ErpSyncOperation.EXPORT_PAYMENT_LINE, payload,
				() -> erpConnector.pushPaymentLine(paymentHeaderDocNo, lineDTO));
	}

	public ErpOperationResult pushReturnHeader(ErpReturnDTO returnDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_RETURN, returnDTO,
				() -> erpConnector.pushReturnHeader(returnDTO));
	}

	public ErpOperationResult pushReturnLine(ErpReturnDTO returnDTO, String externalReference,
			ErpReturnLineDTO lineDTO) {
		// Create a payload object that includes both return and line info for logging
		Object payload = new Object() {
			public ErpReturnDTO getReturn() {
				return returnDTO;
			}

			public ErpReturnLineDTO getLine() {
				return lineDTO;
			}

			public String getExternalReference() {
				return externalReference;
			}
		};

		return executePushOperation(ErpSyncOperation.EXPORT_RETURN_LINE, payload,
				() -> erpConnector.pushReturnLine(returnDTO, externalReference, lineDTO));
	}

	public ErpOperationResult updateReturnStatus(String externalReference, boolean posOrder) {
		Object payload = new Object() {
			public String getExternalReference() {
				return externalReference;
			}

			public boolean getPosOrder() {
				return posOrder;
			}
		};
		return executePushOperation(ErpSyncOperation.UPDATE_RETURN, payload,
				() -> erpConnector.updateReturnStatus(externalReference, posOrder));
	}

	public ErpOperationResult pushSession(ErpSessionDTO sessionDTO) {
		return executePushOperation(ErpSyncOperation.EXPORT_SESSION, sessionDTO,
				() -> erpConnector.pushSession(sessionDTO));
	}

	/**
	 * Execute a pull operation (fetch from ERP) with proper logging and
	 * thread-safety.
	 * 
	 * Thread-safety: This method uses ThreadLocal in the connector to store
	 * operation metadata. Each thread has its own ThreadLocal copy, ensuring
	 * thread-safety for concurrent operations. The metadata is read immediately
	 * after the fetch and cleaned up in a finally block to prevent memory leaks.
	 */
	private <T> List<T> executePullOperation(ErpSyncOperation operation, ErpSyncFilter filter,
			OperationExecutor<List<T>> executor) {
		LocalDateTime start = LocalDateTime.now();
		try {
			List<T> result = executor.execute();

			// Get metadata from connector (similar to how push operations get
			// ErpOperationResult)
			// This maintains abstraction - synchronization manager only talks to
			// ErpConnector interface
			PullOperationResult<?> pullResult = erpConnector.getLastPullOperationResult();

			// For GET requests (pull operations), there is NO request payload/body
			// The URL is already stored in the 'url' field, so requestPayload should be
			// null
			String url = pullResult != null ? pullResult.getUrl() : null;
			// Remove quotes from URL if present (JSON serialization adds them)
			String cleanUrl = url != null ? url.replaceAll("^\"|\"$", "") : null;
			// GET requests don't have request bodies - set to null
			Object requestPayload = null;

			// Use raw ERP response instead of mapped result for logging
			Object responsePayload = pullResult != null ? pullResult.getRawResponse() : null;
			if (responsePayload == null) {
				responsePayload = result; // Fallback to mapped result if raw response not available
			}

			communicationService.logOperation(operation, requestPayload, responsePayload,
					ErpCommunicationStatus.SUCCESS, cleanUrl, null, start, LocalDateTime.now());

			return result;
		} catch (Exception ex) {
			LOGGER.error("ERP pull operation {} failed: {}", operation, ex.getMessage(), ex);

			// Extract error response body if available (from HTTP exceptions)
			Object errorResponse = extractErrorResponseBody(ex);

			// Try to get URL from connector metadata first, then from exception
			PullOperationResult<?> pullResult = erpConnector.getLastPullOperationResult();
			String url = pullResult != null ? pullResult.getUrl() : null;
			if (url == null) {
				url = extractUrlFromException(ex);
			}
			// Remove quotes from URL if present
			String cleanUrl = url != null ? url.replaceAll("^\"|\"$", "") : null;

			// For GET requests, there is NO request payload/body
			// The URL is already stored in the 'url' field, so requestPayload should be
			// null
			Object requestPayload = null;

			communicationService.logOperation(operation, requestPayload, errorResponse, ErpCommunicationStatus.ERROR,
					cleanUrl, ex.getMessage(), start, LocalDateTime.now());

			throw ex;
		} finally {
			// Always clean up ThreadLocal to prevent memory leaks and ensure thread-safety
			// This ensures cleanup happens even if an exception occurs during logging
			erpConnector.clearLastPullOperationResult();
		}
	}

	private ErpOperationResult executePushOperation(ErpSyncOperation operation, Object payload,
			OperationExecutor<ErpOperationResult> executor) {
		LocalDateTime start = LocalDateTime.now();
		try {
			ErpOperationResult result = executor.execute();
			ErpCommunicationStatus status = result.isSuccess() ? ErpCommunicationStatus.SUCCESS
					: ErpCommunicationStatus.ERROR;

			// Use actual request payload from result if available, otherwise use the
			// abstract payload
			Object requestPayload = result.getActualRequestPayload() != null ? result.getActualRequestPayload()
					: payload;

			// Use actual response payload from result (ERP HTTP response), not the
			// ErpOperationResult object
			Object responsePayload = result.getActualResponsePayload();

			communicationService.logOperation(operation, requestPayload, responsePayload, status, result.getUrl(),
					result.getMessage(), start, LocalDateTime.now());
			return result;
		} catch (Exception ex) {
			LOGGER.error("ERP push operation {} failed: {}", operation, ex.getMessage(), ex);

			// Extract error response body if available (from HTTP exceptions)
			Object errorResponse = extractErrorResponseBody(ex);

			// Extract clean error message (without full payload)
			String cleanErrorMessage = extractCleanErrorMessage(ex);

			// Use the abstract payload as fallback for request
			// Try to extract URL from exception if it's an HTTP exception
			String url = extractUrlFromException(ex);
			communicationService.logOperation(operation, payload, errorResponse, ErpCommunicationStatus.ERROR, url,
					cleanErrorMessage, start, LocalDateTime.now());
			throw ex;
		}
	}

	/**
	 * Extract error response body from exception if it's an HTTP exception
	 */
	private Object extractErrorResponseBody(Exception ex) {
		// Check if the exception itself is an HttpStatusCodeException
		if (ex instanceof HttpStatusCodeException) {
			String responseBody = ((HttpStatusCodeException) ex).getResponseBodyAsString();
			if (responseBody != null && !responseBody.trim().isEmpty()) {
				return responseBody;
			}
		}

		// Check if the cause is an HttpStatusCodeException (common when wrapped in
		// RuntimeException)
		Throwable cause = ex.getCause();
		if (cause instanceof HttpStatusCodeException) {
			String responseBody = ((HttpStatusCodeException) cause).getResponseBodyAsString();
			if (responseBody != null && !responseBody.trim().isEmpty()) {
				return responseBody;
			}
		}

		// No error response body available
		return null;
	}

	/**
	 * Extract clean error message from exception (without full response payload)
	 */
	private String extractCleanErrorMessage(Exception ex) {
		// Check if the exception itself is an HttpStatusCodeException
		if (ex instanceof HttpStatusCodeException) {
			return extractCleanErrorMessageFromHttpException((HttpStatusCodeException) ex);
		}

		// Check if the cause is an HttpStatusCodeException
		Throwable cause = ex.getCause();
		if (cause instanceof HttpStatusCodeException) {
			return extractCleanErrorMessageFromHttpException((HttpStatusCodeException) cause);
		}

		// For other exceptions, check if message contains JSON and extract clean
		// message
		String message = ex.getMessage();
		if (message != null && (message.contains("{") || message.contains("["))) {
			// Try to extract clean message by removing JSON part
			// Check if message contains JSON array or object
			int jsonStart = message.indexOf('[');
			if (jsonStart == -1) {
				jsonStart = message.indexOf('{');
			}
			if (jsonStart > 0) {
				// Extract the part before JSON
				return message.substring(0, jsonStart).trim();
			}
		}

		// Fallback to original message
		return message != null ? message : "Unknown error";
	}

	/**
	 * Extract clean error message from HTTP exception
	 */
	private String extractCleanErrorMessageFromHttpException(HttpStatusCodeException ex) {
		try {
			String responseBody = ex.getResponseBodyAsString();
			if (responseBody != null && !responseBody.trim().isEmpty()) {
				com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
				com.fasterxml.jackson.databind.JsonNode errorNode = objectMapper.readTree(responseBody);

				// Handle array of errors: [{"error": {...}}]
				if (errorNode.isArray() && errorNode.size() > 0) {
					com.fasterxml.jackson.databind.JsonNode firstError = errorNode.get(0);
					if (firstError.has("error")) {
						com.fasterxml.jackson.databind.JsonNode error = firstError.get("error");
						if (error.has("message")) {
							return ex.getStatusCode() + " " + error.get("message").asText();
						}
						if (error.has("code")) {
							return ex.getStatusCode() + " " + error.get("code").asText();
						}
					}
				}

				// Handle single error object: {"error": {...}}
				if (errorNode.has("error")) {
					com.fasterxml.jackson.databind.JsonNode error = errorNode.get("error");
					if (error.has("message")) {
						return ex.getStatusCode() + " " + error.get("message").asText();
					}
					if (error.has("code")) {
						return ex.getStatusCode() + " " + error.get("code").asText();
					}
				}
			}
		} catch (Exception parseEx) {
			// If parsing fails, fall through to default
		}

		// Fallback to status code and status text
		return ex.getStatusCode() + " " + (ex.getStatusText() != null ? ex.getStatusText() : "Unknown error");
	}

	/**
	 * Extract URL from exception if it's an HTTP exception (from request URI)
	 */
	private String extractUrlFromException(Exception ex) {
		if (ex instanceof HttpStatusCodeException) {
			// HttpStatusCodeException doesn't have getRequestURI, but we can't get it from
			// here
			// Return null as we don't have access to the request URL from the exception
			return null;
		}
		Throwable cause = ex.getCause();
		if (cause instanceof HttpStatusCodeException) {
			return null;
		}
		return null;
	}

	@FunctionalInterface
	private interface OperationExecutor<T> {
		T execute();
	}
}
