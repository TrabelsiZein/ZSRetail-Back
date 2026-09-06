package com.digithink.zsretail.erp.dynamicsnav.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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
import com.digithink.zsretail.erp.dynamicsnav.client.DynamicsNavRestClient;
import com.digithink.zsretail.erp.dynamicsnav.config.DynamicsNavProperties;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavLogEntryDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavPaymentHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavPaymentLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSessionDTO;
import com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper;
import com.digithink.zsretail.erp.spi.ErpConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(prefix = "erp.dynamicsnav", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DynamicsNavConnector implements ErpConnector {

	/**
	 * ThreadLocal to store pull operation metadata (URL and raw response) for
	 * logging. This is similar to how push operations return ErpOperationResult
	 * with metadata.
	 * 
	 * Thread-safety: ThreadLocal ensures each thread has its own copy, making this
	 * thread-safe for concurrent operations. The metadata is stored immediately
	 * after fetch and cleared after logging to prevent memory leaks.
	 */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL_RESULT = new ThreadLocal<>();

	private final DynamicsNavRestClient restClient;
	private final DynamicsNavMapper mapper;
	private final DynamicsNavProperties properties;

	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		try {
			List<ErpItemFamilyDTO> result = mapper.toItemFamilyDTOs(restClient.fetchItemFamilies());
			// Store metadata for logging (similar to how push operations return
			// ErpOperationResult)
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			// Store metadata even on exception for error logging
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		try {
			List<ErpItemSubFamilyDTO> result = mapper.toItemSubFamilyDTOs(restClient.fetchItemSubFamilies());
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		try {
			List<ErpItemDTO> result = mapper.toItemDTOs(restClient.fetchItems(filter));
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		try {
			List<ErpItemBarcodeDTO> result = mapper.toItemBarcodeDTOs(restClient.fetchItemBarcodes(filter));
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpLocationDTO> fetchLocations(ErpSyncFilter filter) {
		try {
			List<ErpLocationDTO> result = mapper.toLocationDTOs(restClient.fetchLocations());
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpCustomerDTO> fetchCustomers(ErpSyncFilter filter) {
		try {
			List<ErpCustomerDTO> result = mapper.toCustomerDTOs(restClient.fetchCustomers(filter));
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpSalesPriceDTO> fetchSalesPrices(ErpSyncFilter filter) {
		try {
			List<ErpSalesPriceDTO> result = mapper.toSalesPriceDTOs(restClient.fetchSalesPrices(filter));
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpSalesDiscountDTO> fetchSalesDiscounts(ErpSyncFilter filter) {
		try {
			List<ErpSalesDiscountDTO> result = mapper.toSalesDiscountDTOs(restClient.fetchSalesDiscounts(filter));
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	@Override
	public List<ErpDeletionLogEntryDTO> fetchDeletionLog(ErpSyncFilter filter) {
		try {
			List<DynamicsNavLogEntryDTO> navList = restClient.fetchDeletionLog(filter);
			List<ErpDeletionLogEntryDTO> result = mapper.toErpDeletionLogEntryDTOs(navList);
			storePullOperationMetadata(result);
			return result;
		} catch (Exception ex) {
			storePullOperationMetadata(null);
			throw ex;
		}
	}

	/**
	 * Store pull operation metadata (URL and raw response) for logging purposes.
	 * This is called after each fetch operation, similar to how push operations
	 * return ErpOperationResult with metadata.
	 */
	private void storePullOperationMetadata(Object mappedData) {
		String url = DynamicsNavRestClient.getLastFetchUrl();
		Object rawResponse = DynamicsNavRestClient.getLastFetchResponse();
		if (url != null || rawResponse != null) {
			LAST_PULL_RESULT
					.set(new PullOperationResult<>(mappedData != null ? (List<?>) mappedData : null, url, rawResponse));
			// Clean up rest client's ThreadLocal after capturing
			DynamicsNavRestClient.clearLastFetchUrl();
		}
	}

	@Override
	public PullOperationResult<?> getLastPullOperationResult() {
		return LAST_PULL_RESULT.get();
	}

	@Override
	public void clearLastPullOperationResult() {
		LAST_PULL_RESULT.remove();
	}

	@Override
	public ErpOperationResult pushCustomer(ErpCustomerDTO customer) {
		return ErpOperationResult.failure("Not implemented");
	}

	@Override
	public ErpOperationResult pushTicket(ErpTicketDTO ticket) {
		return ErpOperationResult.failure("Not implemented");
	}

	@Override
	public ErpOperationResult pushTicketHeader(ErpTicketDTO ticket) {
		// Convert to NAV-specific DTO
		DynamicsNavSalesOrderHeaderDTO headerDTO = mapper.toSalesOrderHeaderDTO(ticket);

		// Build URL for logging
		String url = buildSalesOrdersPosUrl();

		try {
			// Create header in NAV
			DynamicsNavSalesOrderHeaderDTO responseDto = restClient.createSalesOrderHeader(headerDTO);

			// Extract Document_No from response
			if (responseDto != null && responseDto.getDocumentNo() != null) {
				return ErpOperationResult.success(responseDto.getDocumentNo(), headerDTO, responseDto, url);
			} else {
				return ErpOperationResult.failure("Failed to get document number from NAV response", headerDTO, null,
						url);
			}
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, headerDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, headerDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create ticket header: " + errorMessage, headerDTO,
					responseBody, url);
		}
	}

	@Override
	public ErpOperationResult pushTicketLine(ErpTicketDTO ticket, String externalReference, ErpTicketLineDTO line) {
		// Convert to NAV-specific DTO
		DynamicsNavSalesOrderLineDTO lineDTO = mapper.toSalesOrderLineDTO(line, externalReference);

		// Build URL for logging
		String url = buildSalesOrdersPosSalesLinesUrl();

		try {
			// Create line in NAV
			DynamicsNavSalesOrderLineDTO responseDto = restClient.createSalesOrderLine(lineDTO);

			return ErpOperationResult.success(externalReference, lineDTO, responseDto, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, lineDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, lineDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create ticket line: " + errorMessage, lineDTO, responseBody,
					url);
		}
	}

	@Override
	public ErpOperationResult updateTicketStatus(String externalReference, boolean posOrder, Boolean posInvoice, String fiscalRegistration, String billToName2) {
		ObjectMapper objectMapper = new ObjectMapper();
		ObjectNode updatePayload = objectMapper.createObjectNode();
		updatePayload.put("POS_Order", posOrder);
		if (posInvoice != null) {
			updatePayload.put("POS_Invoice", posInvoice);
		}
		if (fiscalRegistration != null) {
			updatePayload.put("Fiscal_Registration", fiscalRegistration);
		}
		if (billToName2 != null) {
			updatePayload.put("Bill_to_Name_2", billToName2);
		}

		String escapedDocNo = externalReference != null ? externalReference.replace("'", "''") : "";
		String url = buildSalesOrdersPosUrl() + "(No='" + escapedDocNo + "',Document_Type='Order')";

		try {
			DynamicsNavSalesOrderHeaderDTO responseDto = restClient.updateSalesOrderHeaderStatus(externalReference,
					posOrder, posInvoice, fiscalRegistration, billToName2);
			return ErpOperationResult.success(externalReference, updatePayload, responseDto, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();
			return ErpOperationResult.failure(cleanErrorMessage, updatePayload, responseBody, url);
		} catch (Exception ex) {
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, updatePayload, responseBody, url);
			}
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();
			return ErpOperationResult.failure("Failed to update ticket status: " + errorMessage, updatePayload,
					responseBody, url);
		}
	}

	/**
	 * Extract clean error message from HTTP exception (without full response
	 * payload)
	 */
	private String extractCleanErrorMessage(HttpStatusCodeException ex) {
		try {
			String responseBody = ex.getResponseBodyAsString();
			if (responseBody != null && !responseBody.trim().isEmpty()) {
				// Try to parse JSON error response
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
	 * Extract HTTP exception from exception chain
	 */
	private HttpStatusCodeException extractHttpException(Exception ex) {
		if (ex instanceof HttpStatusCodeException) {
			return (HttpStatusCodeException) ex;
		}
		// Check if cause is HTTP exception
		Throwable cause = ex.getCause();
		if (cause instanceof HttpStatusCodeException) {
			return (HttpStatusCodeException) cause;
		}
		return null;
	}

	/**
	 * Extract response body from exception if it's an HTTP exception
	 */
	private String extractResponseBodyFromException(Exception ex) {
		HttpStatusCodeException httpEx = extractHttpException(ex);
		if (httpEx != null) {
			return httpEx.getResponseBodyAsString();
		}
		return null;
	}

	/**
	 * Build URL for SalesOrdersPos endpoint
	 */
	private String buildSalesOrdersPosUrl() {
		return buildCompanyEndpointUrl("SalesOrdersPos");
	}

	/**
	 * Build URL for SalesReturnPOS endpoint
	 */
	private String buildSalesReturnPosUrl() {
		return buildCompanyEndpointUrl("SalesReturnPOS");
	}

	/**
	 * Build URL for SalesReturnPOSSalesLines endpoint
	 */
	private String buildSalesReturnPosSalesLinesUrl() {
		return buildCompanyEndpointUrl("SalesReturnPOSSalesLines");
	}

	/**
	 * Build URL for SalesOrdersPosSalesLines endpoint
	 */
	private String buildSalesOrdersPosSalesLinesUrl() {
		return buildCompanyEndpointUrl("SalesOrdersPosSalesLines");
	}

	@Override
	public ErpOperationResult pushPaymentHeader(ErpPaymentHeaderDTO headerDTO) {
		// Convert to NAV-specific DTO
		DynamicsNavPaymentHeaderDTO navHeaderDTO = new DynamicsNavPaymentHeaderDTO();
		navHeaderDTO.setPaymentClass(headerDTO.getPaymentClass());
		// Format date as "YYYY-MM-DD" string
		if (headerDTO.getPostDate() != null) {
			navHeaderDTO.setPostdate(headerDTO.getPostDate().toString());
		}

		// Build URL for logging (code unit endpoint, not entity page)
		String url = buildCodeUnitEndpointUrl("WarehouseManagement_CreatePaymentHeader");

		try {
			// Create header in NAV
			JsonNode responseDto = restClient.createPaymentHeader(navHeaderDTO);

			if (responseDto.get("value").asText() != null && !responseDto.get("value").asText().isEmpty()) {
				return ErpOperationResult.success(responseDto.get("value").asText(), navHeaderDTO, responseDto, url);
			} else {
				return ErpOperationResult.failure("Failed to get document number from NAV payment header response",
						navHeaderDTO, null, url);
			}
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, navHeaderDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, navHeaderDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create payment header: " + errorMessage, navHeaderDTO,
					responseBody, url);
		}
	}

	@Override
	public ErpOperationResult pushPaymentLine(String paymentHeaderDocNo, ErpPaymentLineDTO lineDTO) {
		// Convert to NAV-specific DTO
		DynamicsNavPaymentLineDTO navLineDTO = new DynamicsNavPaymentLineDTO();
		navLineDTO.setDocNo(paymentHeaderDocNo);
		navLineDTO.setCustNo(lineDTO.getCustNo());
		navLineDTO.setAmount(lineDTO.getAmount() != null ? lineDTO.getAmount().doubleValue() : null);
		navLineDTO.setFenceNo(lineDTO.getFenceNo());
		navLineDTO.setTicketNo(lineDTO.getTicketNo());
		navLineDTO.setTitleNo(lineDTO.getTitleNo());
		// Format date as "YYYY-MM-DD" string or empty string if null
		if (lineDTO.getDueDate() != null && !lineDTO.getDueDate().toString().isEmpty()) {
			navLineDTO.setDueDate(lineDTO.getDueDate().toString());
		} else {
			navLineDTO.setDueDate(null);
		}
		navLineDTO.setDrawerName(lineDTO.getDrawerName());

		// Build URL for logging (code unit endpoint, not entity page)
		String url = buildCodeUnitEndpointUrl("WarehouseManagement_CreatePaymentLine");

		try {
			// Create line in NAV
			Object responseDto = restClient.createPaymentLine(navLineDTO);

			return ErpOperationResult.success(paymentHeaderDocNo, navLineDTO, responseDto, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, navLineDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, navLineDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create payment line: " + errorMessage, navLineDTO,
					responseBody, url);
		}
	}

	/**
	 * Build company endpoint URL
	 */
	private String buildCompanyEndpointUrl(String entityName) {
		String companySegment = properties.getCompanyUrlSegment();
		String path = (companySegment.isEmpty() ? "" : "/" + companySegment) + "/" + entityName;
		String baseUrl = properties.getBaseUrl();
		if (baseUrl == null || baseUrl.isEmpty()) {
			return "";
		}
		String baseUrlWithSlash = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
		return baseUrlWithSlash + path;
	}

	/**
	 * Build URL for code unit endpoints (not entity pages) Format:
	 * EndpointName?company=CompanyCode Note: baseUrl already includes ODataV4 path
	 */
	private String buildCodeUnitEndpointUrl(String endpointName) {
		String baseUrl = properties.getBaseUrl();
		if (baseUrl == null || baseUrl.isEmpty()) {
			return "";
		}
		String baseUrlWithSlash = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
		String url = baseUrlWithSlash + endpointName;

		// Add company as query parameter
		if (properties.getCompany() != null && !properties.getCompany().trim().isEmpty()) {
			url += "?company=" + properties.getCompany();
		}

		return url;
	}

	@Override
	public ErpOperationResult pushReturnHeader(ErpReturnDTO returnDTO) {
		// Convert to NAV-specific DTO
		DynamicsNavReturnHeaderDTO headerDTO = mapper.toReturnHeaderDTO(returnDTO);

		// Build URL for logging
		String url = buildSalesReturnPosUrl();

		try {
			// Create header in NAV
			DynamicsNavReturnHeaderDTO responseDto = restClient.createReturnHeader(headerDTO);

			// Extract Document_No from response
			if (responseDto != null && responseDto.getDocumentNo() != null) {
				return ErpOperationResult.success(responseDto.getDocumentNo(), headerDTO, responseDto, url);
			} else {
				return ErpOperationResult.failure("Failed to get document number from NAV response", headerDTO, null,
						url);
			}
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, headerDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, headerDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create return header: " + errorMessage, headerDTO,
					responseBody, url);
		}
	}

	@Override
	public ErpOperationResult pushReturnLine(ErpReturnDTO returnDTO, String externalReference,
			ErpReturnLineDTO lineDTO) {
		// Convert to NAV-specific DTO
		DynamicsNavReturnLineDTO navLineDTO = mapper.toReturnLineDTO(lineDTO, externalReference);

		// Build URL for logging
		String url = buildSalesReturnPosSalesLinesUrl();

		try {
			// Create line in NAV
			DynamicsNavReturnLineDTO responseDto = restClient.createReturnLine(navLineDTO);

			return ErpOperationResult.success(externalReference, navLineDTO, responseDto, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, navLineDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, navLineDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create return line: " + errorMessage, navLineDTO, responseBody,
					url);
		}
	}

	@Override
	public ErpOperationResult updateReturnStatus(String externalReference, boolean posOrder) {
		ObjectMapper objectMapper = new ObjectMapper();
		ObjectNode updatePayload = objectMapper.createObjectNode();
		updatePayload.put("POS_Order", posOrder);

		String escapedDocNo = externalReference != null ? externalReference.replace("'", "''") : "";
		String url = buildSalesReturnPosUrl() + "(No='" + escapedDocNo + "',Document_Type='Return Order')";

		try {
			DynamicsNavReturnHeaderDTO responseDto = restClient.updateReturnHeaderPosOrder(externalReference, posOrder);
			return ErpOperationResult.success(externalReference, updatePayload, responseDto, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();
			return ErpOperationResult.failure(cleanErrorMessage, updatePayload, responseBody, url);
		} catch (Exception ex) {
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, updatePayload, responseBody, url);
			}
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();
			return ErpOperationResult.failure("Failed to update return status: " + errorMessage, updatePayload,
					responseBody, url);
		}
	}

	@Override
	public ErpOperationResult pushSession(ErpSessionDTO sessionDTO) {
		// Convert to NAV-specific DTO
		DynamicsNavSessionDTO navSessionDTO = mapper.toSessionDTO(sessionDTO);

		// Build URL for logging (codeunit endpoint, not page endpoint)
		String url = buildCodeUnitEndpointUrl("WarehouseManagement_POSClosingEntries");

		try {
			// Create session in NAV (codeunit returns void, success is determined by HTTP
			// status)
			restClient.createSession(navSessionDTO);

			// NAV codeunit returns void, so success is determined by HTTP status code (200
			// OK)
			// Use session number as external reference since NAV doesn't return a document
			// number
			String externalReference = sessionDTO.getSessionNumber();

			// If we get here without exception, the request was successful
			return ErpOperationResult.success(externalReference, navSessionDTO, null, url);
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			// Extract clean error message and response body
			String cleanErrorMessage = extractCleanErrorMessage(ex);
			String responseBody = ex.getResponseBodyAsString();

			return ErpOperationResult.failure(cleanErrorMessage, navSessionDTO, responseBody, url);
		} catch (Exception ex) {
			// Check if exception or its cause is an HTTP exception
			HttpStatusCodeException httpEx = extractHttpException(ex);
			if (httpEx != null) {
				// Extract clean error message and response body from HTTP exception
				String cleanErrorMessage = extractCleanErrorMessage(httpEx);
				String responseBody = httpEx.getResponseBodyAsString();
				return ErpOperationResult.failure(cleanErrorMessage, navSessionDTO, responseBody, url);
			}

			// For non-HTTP exceptions, extract response body if available
			String responseBody = extractResponseBodyFromException(ex);
			String errorMessage = ex.getMessage();

			return ErpOperationResult.failure("Failed to create session: " + errorMessage, navSessionDTO, responseBody,
					url);
		}
	}
}
