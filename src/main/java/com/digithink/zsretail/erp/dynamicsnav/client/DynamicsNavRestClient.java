package com.digithink.zsretail.erp.dynamicsnav.client;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dynamicsnav.config.DynamicsNavProperties;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavBarcodeDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavCollectionResponse;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavCustomerDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavFamilyDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavLocationDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavLogEntryDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavPaymentHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavPaymentLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavReturnLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesDiscountDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderHeaderDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesOrderLineDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSalesPriceDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSessionDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavStockKeepingUnitDTO;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavSubFamilyDTO;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LocationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Component
@ConditionalOnProperty(prefix = "erp.dynamicsnav", name = "enabled", havingValue = "true")
public class DynamicsNavRestClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(DynamicsNavRestClient.class);

	/**
	 * ThreadLocal to store the URL used in the current fetch operation for logging.
	 * Thread-safety: ThreadLocal ensures each thread has its own copy.
	 */
	private static final ThreadLocal<String> CURRENT_FETCH_URL = new ThreadLocal<>();

	/**
	 * ThreadLocal to store the raw NAV response for logging. Thread-safety:
	 * ThreadLocal ensures each thread has its own copy.
	 */
	private static final ThreadLocal<Object> CURRENT_FETCH_RESPONSE = new ThreadLocal<>();

	private final RestTemplate dynamicsNavRestTemplate;
	private final DynamicsNavProperties properties;
	private final GeneralSetupService generalSetupService;
	private final LocationService locationService;
	private final ObjectMapper objectMapper;

	public DynamicsNavRestClient(@Qualifier("dynamicsNavRestTemplate") RestTemplate dynamicsNavRestTemplate,
			DynamicsNavProperties properties, GeneralSetupService generalSetupService,
			LocationService locationService) {
		this.dynamicsNavRestTemplate = dynamicsNavRestTemplate;
		this.properties = properties;
		this.generalSetupService = generalSetupService;
		this.locationService = locationService;
		// Configure ObjectMapper to exclude null fields (like Postman does)
		// and serialize LocalDate as ISO date string (YYYY-MM-DD)
		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new JavaTimeModule());
		this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
	}

	/**
	 * Fetch all items from Dynamics NAV with pagination support.
	 * Follows @odata.nextLink until all pages are retrieved. Stores the initial URL
	 * in ThreadLocal for logging purposes.
	 */
	private <T> List<T> fetchAllWithPagination(String initialUrl,
			ParameterizedTypeReference<DynamicsNavCollectionResponse<T>> typeRef) {
		// Store the initial URL in ThreadLocal for logging
		CURRENT_FETCH_URL.set(initialUrl);
		List<T> allItems = new ArrayList<>();
		String nextUrl = initialUrl;
		boolean firstPage = true;

		try {
			while (nextUrl != null) {
				ResponseEntity<DynamicsNavCollectionResponse<T>> response;
				if (firstPage) {
					// First page may contain unencoded spaces; let RestTemplate handle encoding
					// from String
					response = dynamicsNavRestTemplate.exchange(nextUrl, HttpMethod.GET, null, typeRef);
					firstPage = false;
				} else {
					// Next links are already encoded by NAV; avoid re-encoding by using URI
					URI nextUri = URI.create(nextUrl);
					response = dynamicsNavRestTemplate.exchange(nextUri, HttpMethod.GET, null, typeRef);
				}

				DynamicsNavCollectionResponse<T> body = response.getBody();
				if (body != null && body.getValue() != null) {
					allItems.addAll(body.getValue());
					nextUrl = body.getNextLink();

					// Store raw response (only on first page) for logging
					if (CURRENT_FETCH_RESPONSE.get() == null) {
						CURRENT_FETCH_RESPONSE.set(body.getValue());
					}

					LOGGER.debug("Fetched {} items, total so far: {}", body.getValue().size(), allItems.size());
				} else {
					nextUrl = null;
				}
			}
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to fetch paginated data from Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage,
					ex);
			throw new RuntimeException("Failed to fetch paginated data: " + ex.getStatusCode() + " " + errorMessage,
					ex);
		} catch (RestClientException ex) {
			LOGGER.error("Failed to fetch paginated data from Dynamics NAV: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to fetch paginated data: " + ex.getMessage(), ex);
		}
		// Note: We don't remove the URL here - it will be retrieved by
		// DynamicsNavConnector
		// and cleaned up by ErpSynchronizationManager after logging

		return allItems;
	}

	/**
	 * Get the URL used in the last fetch operation (for logging purposes). This
	 * should be called immediately after a fetch operation within the same thread.
	 */
	public static String getLastFetchUrl() {
		return CURRENT_FETCH_URL.get();
	}

	/**
	 * Get the raw NAV response from the last fetch operation (for logging
	 * purposes).
	 */
	public static Object getLastFetchResponse() {
		return CURRENT_FETCH_RESPONSE.get();
	}

	/**
	 * Clear the stored fetch URL and response (should be called after retrieving
	 * them).
	 */
	public static void clearLastFetchUrl() {
		CURRENT_FETCH_URL.remove();
		CURRENT_FETCH_RESPONSE.remove();
	}

	public List<DynamicsNavFamilyDTO> fetchItemFamilies() {
		try {
			String url = buildCompanyEndpointUrl("FamilyList");
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavFamilyDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch item families from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavSubFamilyDTO> fetchItemSubFamilies() {
		try {
			String url = buildCompanyEndpointUrl("SubFamilyList");
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavSubFamilyDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch item subfamilies from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavLocationDTO> fetchLocations() {
		try {
			String url = buildCompanyEndpointUrl("LocationList");
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavLocationDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch locations from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavStockKeepingUnitDTO> fetchItems() {
		return fetchItems(null);
	}

	public List<DynamicsNavStockKeepingUnitDTO> fetchItems(ErpSyncFilter filter) {
		try {
			String defaultLocation = resolveDefaultLocation();
			if (defaultLocation == null || defaultLocation.isBlank()) {
				throw new ErpSyncWarningException("DEFAULT_LOCATION is not configured");
			}

			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("StockkeepingUnitList");
			appendSkuFilters(builder, filter, defaultLocation);
			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavStockKeepingUnitDTO>>() {
					});
		} catch (ErpSyncWarningException warning) {
			throw warning;
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch items from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavBarcodeDTO> fetchItemBarcodes() {
		return fetchItemBarcodes(null);
	}

	public List<DynamicsNavBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		try {
			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("BarCodeList");
			buildUpdatedAfterFilter(filter, "Modified_At").ifPresent(value -> builder.queryParam("$filter", value));
			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavBarcodeDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch item barcodes from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavCustomerDTO> fetchCustomers(ErpSyncFilter filter) {
		try {
			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("CustomerList");
			buildUpdatedAfterFilter(filter, "Modified_At").ifPresent(value -> builder.queryParam("$filter", value));
			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavCustomerDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch customers from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavSalesPriceDTO> fetchSalesPrices(ErpSyncFilter filter) {
		try {
			List<Location> defLocations = locationService.findByField("isDefault", "=", true);
			if (defLocations.size() == 0)
				throw new ErpSyncWarningException("DEFAULT_LOCATION is not configured");

			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("SalesPrice");

			Optional<String> dateFilter = buildUpdatedAfterFilter(filter, "Modified_At");

			if (dateFilter.isPresent()) {
				builder.queryParam("$filter", dateFilter.get() + " and (Responsibility_Center eq '"
						+ defLocations.get(0).getResponsibilityCenter() + "' or Responsibility_Center eq '')");
			} else {
				builder.queryParam("$filter", "Responsibility_Center eq '"
						+ defLocations.get(0).getResponsibilityCenter() + "' or Responsibility_Center eq ''");
			}

			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavSalesPriceDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch sales prices from Dynamics NAV", ex);
			throw ex;
		}
	}

	public List<DynamicsNavSalesDiscountDTO> fetchSalesDiscounts(ErpSyncFilter filter) {
		try {
			List<Location> defLocations = locationService.findByField("isDefault", "=", true);
			if (defLocations.size() == 0)
				throw new ErpSyncWarningException("DEFAULT_LOCATION is not configured");

			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("SalesDiscount");

			Optional<String> dateFilter = buildUpdatedAfterFilter(filter, "Modified_At");

			if (dateFilter.isPresent()) {
				builder.queryParam("$filter", dateFilter.get() + " and (Responsibility_Center eq '"
						+ defLocations.get(0).getResponsibilityCenter() + "' or Responsibility_Center eq '')");
			} else {
				builder.queryParam("$filter", "Responsibility_Center eq '"
						+ defLocations.get(0).getResponsibilityCenter() + "' or Responsibility_Center eq ''");
			}

			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavSalesDiscountDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch sales discounts from Dynamics NAV", ex);
			throw ex;
		}
	}

	/**
	 * Fetches deletion log entries from Business Central Log API (company/Log).
	 * Supports optional filter by Modified_At for incremental sync.
	 */
	public List<DynamicsNavLogEntryDTO> fetchDeletionLog(ErpSyncFilter filter) {
		try {
			UriComponentsBuilder builder = buildCompanyEndpointUriBuilder("Log");
			Optional<String> dateFilter = buildUpdatedAfterFilter(filter, "Modified_At");
			if (dateFilter.isPresent()) {
				builder.queryParam("$filter", dateFilter.get());
			}
			String url = builder.build(false).toUriString();
			return fetchAllWithPagination(url,
					new ParameterizedTypeReference<DynamicsNavCollectionResponse<DynamicsNavLogEntryDTO>>() {
					});
		} catch (RuntimeException ex) {
			LOGGER.error("Failed to fetch deletion log from Dynamics NAV", ex);
			throw ex;
		}
	}

	private String buildCompanyEndpointUrl(String entityName) {
		return buildCompanyEndpointUriBuilder(entityName).build(false).toUriString();
	}

	private UriComponentsBuilder buildCompanyEndpointUriBuilder(String entityName) {
		String companySegment = properties.getCompanyUrlSegment();
		String path = (companySegment.isEmpty() ? "" : "/" + companySegment) + "/" + entityName;

		return UriComponentsBuilder.fromHttpUrl(ensureTrailingSlash(properties.getBaseUrl())).path(path);
	}

	private String ensureTrailingSlash(String baseUrl) {
		if (baseUrl == null || baseUrl.isEmpty()) {
			return "";
		}
		return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
	}

	private Optional<String> buildUpdatedAfterFilter(ErpSyncFilter filter, String fieldName) {
		if (filter == null || filter.getUpdatedAfter() == null) {
			return Optional.empty();
		}
		String formatted = filter.getUpdatedAfter().withOffsetSameInstant(ZoneOffset.UTC)
				.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		return Optional.of(fieldName + " gt " + formatted);
	}

	private void appendSkuFilters(UriComponentsBuilder builder, ErpSyncFilter filter, String defaultLocation) {
		Optional<String> dateFilter = buildUpdatedAfterFilter(filter, "Modified_At");
		String locationFilter = buildLocationFilter(defaultLocation);

		if (dateFilter.isPresent() && locationFilter != null) {
			builder.queryParam("$filter", dateFilter.get() + " and " + locationFilter);
		} else if (dateFilter.isPresent()) {
			builder.queryParam("$filter", dateFilter.get());
		} else if (locationFilter != null) {
			builder.queryParam("$filter", locationFilter);
		}
	}

	private String buildLocationFilter(String location) {
		if (location == null || location.isBlank()) {
			return null;
		}
		String escaped = location.replace("'", "''");
		return "Location_Code eq '" + escaped + "'";
	}

	private String resolveDefaultLocation() {
		return generalSetupService.findValueByCode("DEFAULT_LOCATION");
	}

	/**
	 * Create a sales order header in Dynamics NAV
	 */
	public DynamicsNavSalesOrderHeaderDTO createSalesOrderHeader(DynamicsNavSalesOrderHeaderDTO header) {
		try {
			String url = buildCompanyEndpointUrl("SalesOrdersPos");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavSalesOrderHeaderDTO> request = new HttpEntity<>(header, headers);

			ResponseEntity<DynamicsNavSalesOrderHeaderDTO> response = dynamicsNavRestTemplate.postForEntity(url,
					request, DynamicsNavSalesOrderHeaderDTO.class);

			// Extract Document_No from response (it's read-only so it will be in the
			// response)
			DynamicsNavSalesOrderHeaderDTO responseDto = response.getBody();
//			if (responseDto != null && responseDto.getDocumentNo() != null) {
//				header.setDocumentNo(responseDto.getDocumentNo());
//			}

			return responseDto;
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create sales order header in Dynamics NAV: {} - {}", ex.getStatusCode(),
					errorMessage, ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create sales order header in Dynamics NAV: {}", ex.getMessage(), ex);
			throw ex;
		}
	}

	/**
	 * Create a sales order line in Dynamics NAV
	 */
	public DynamicsNavSalesOrderLineDTO createSalesOrderLine(DynamicsNavSalesOrderLineDTO line) {
		try {
			String url = buildCompanyEndpointUrl("SalesOrdersPosSalesLines");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavSalesOrderLineDTO> request = new HttpEntity<>(line, headers);

			ResponseEntity<DynamicsNavSalesOrderLineDTO> response = dynamicsNavRestTemplate.postForEntity(url, request,
					DynamicsNavSalesOrderLineDTO.class);

			return response.getBody();
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create sales order line in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage,
					ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create sales order line in Dynamics NAV: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to create sales order line: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Update POS_Order field to true in Dynamics NAV header Note: This uses PATCH
	 * method to update only the POS_Order field
	 */
	public DynamicsNavSalesOrderHeaderDTO updateSalesOrderHeaderPosOrder(String documentNo, boolean posOrder) {
		return updateSalesOrderHeaderStatus(documentNo, posOrder, null, null, null);
	}

	/**
	 * Update ticket header fields in Dynamics NAV (POS_Order, POS_Invoice,
	 * Fiscal_Registration). Only non-null invoice fields are included in the PATCH.
	 */
	public DynamicsNavSalesOrderHeaderDTO updateSalesOrderHeaderStatus(String documentNo, boolean posOrder,
			Boolean posInvoice, String fiscalRegistration, String billToName2) {
		try {
			// Build URL for specific document - escape the document number for URL
			String escapedDocNo = documentNo.replace("'", "''");
			String url = buildCompanyEndpointUrl("SalesOrdersPos") + "(No='" + escapedDocNo
					+ "',Document_Type='Order')";

			com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
			node.put("POS_Order", posOrder);
			if (posInvoice != null) {
				node.put("POS_Invoice", posInvoice);
			}
			if (fiscalRegistration != null) {
				node.put("Fiscal_Registration", fiscalRegistration);
			}
			if (billToName2 != null) {
				node.put("Bill_to_Name_2", billToName2);
			}
			String jsonPayload = objectMapper.writeValueAsString(node);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.setIfMatch("*"); // Required for OData PATCH operations in Dynamics NAV
			HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);

			// Use exchange with PATCH method since RestTemplate doesn't have patch method
			ResponseEntity<DynamicsNavSalesOrderHeaderDTO> responseDto = dynamicsNavRestTemplate.exchange(url,
					HttpMethod.PATCH, request, DynamicsNavSalesOrderHeaderDTO.class);
			LOGGER.info("Updated POS_Order to {} for document {}", posOrder, documentNo);
			return responseDto.getBody();
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to update POS_Order for document {}: {} - {}", documentNo, ex.getStatusCode(),
					errorMessage, ex);
			throw new RuntimeException("Failed to update POS_Order: " + ex.getStatusCode() + " " + errorMessage, ex);
		} catch (RestClientException ex) {
			LOGGER.error("Failed to update POS_Order for document {}: {}", documentNo, ex.getMessage(), ex);
			throw new RuntimeException("Failed to update POS_Order: " + ex.getMessage(), ex);
		} catch (Exception ex) {
			LOGGER.error("Failed to update POS_Order for document {}: {}", documentNo, ex.getMessage(), ex);
			throw new RuntimeException("Failed to update POS_Order: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Extract detailed error message from HTTP exception response body
	 */
	private String extractErrorMessage(HttpStatusCodeException ex) {
		try {
			String responseBody = ex.getResponseBodyAsString();
			if (responseBody != null && !responseBody.trim().isEmpty()) {
				// Try to parse JSON error response
				JsonNode errorNode = objectMapper.readTree(responseBody);

				// Handle array of errors: [{"error": {...}}]
				if (errorNode.isArray() && errorNode.size() > 0) {
					JsonNode firstError = errorNode.get(0);
					if (firstError.has("error")) {
						JsonNode error = firstError.get("error");
						if (error.has("message")) {
							return error.get("message").asText();
						}
						if (error.has("code")) {
							return error.get("code").asText();
						}
					}
				}

				// Handle single error object: {"error": {...}}
				if (errorNode.has("error")) {
					JsonNode error = errorNode.get("error");
					if (error.has("message")) {
						return error.get("message").asText();
					}
					if (error.has("code")) {
						return error.get("code").asText();
					}
				}

				// If no structured error found, return the raw response body
				return responseBody;
			}
		} catch (Exception parseEx) {
			LOGGER.debug("Failed to parse error response body: {}", parseEx.getMessage());
		}

		// Fallback to status text or exception message
		return ex.getStatusText() != null ? ex.getStatusText() : ex.getMessage();
	}

	/**
	 * Build URL for code unit endpoints (not entity pages) Format:
	 * EndpointName?company=CompanyCode Note: baseUrl already includes ODataV4 path
	 */
	private String buildCodeUnitEndpointUrl(String endpointName) {
		String baseUrl = ensureTrailingSlash(properties.getBaseUrl());
		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(endpointName);

		// Add company as query parameter
		if (properties.getCompany() != null && !properties.getCompany().trim().isEmpty()) {
			builder.queryParam("company", properties.getCompany());
		}

		return builder.build(false).toUriString();
	}

	/**
	 * Create a payment header in Dynamics NAV Returns the document number from the
	 * response
	 */
	public JsonNode createPaymentHeader(DynamicsNavPaymentHeaderDTO header) {
		try {
			String url = buildCodeUnitEndpointUrl("WarehouseManagement_CreatePaymentHeader");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavPaymentHeaderDTO> request = new HttpEntity<>(header, headers);

			// Response is in format: {"@odata.context": "...", "value": "ESP-005-25-09919"}
			ResponseEntity<JsonNode> response = dynamicsNavRestTemplate.postForEntity(url, request, JsonNode.class);

			JsonNode responseBody = response.getBody();
			if (responseBody != null && responseBody.has("value")) {
//				return responseBody.get("value").asText();
				return responseBody;
			}

			throw new RuntimeException("Failed to get document number from NAV payment header response");
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create payment header in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage,
					ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create payment header in Dynamics NAV: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to create payment header: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Create a payment line in Dynamics NAV Returns the response (usually just a
	 * success indicator)
	 */
	public Object createPaymentLine(DynamicsNavPaymentLineDTO line) {
		try {
			String url = buildCodeUnitEndpointUrl("WarehouseManagement_CreatePaymentLine");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavPaymentLineDTO> request = new HttpEntity<>(line, headers);

			ResponseEntity<Object> response = dynamicsNavRestTemplate.postForEntity(url, request, Object.class);

			return response.getBody();
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create payment line in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage,
					ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create payment line in Dynamics NAV: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to create payment line: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Create a return header in Dynamics NAV
	 */
	public DynamicsNavReturnHeaderDTO createReturnHeader(DynamicsNavReturnHeaderDTO header) {
		try {
			String url = buildCompanyEndpointUrl("SalesReturnPOS");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavReturnHeaderDTO> request = new HttpEntity<>(header, headers);

			ResponseEntity<DynamicsNavReturnHeaderDTO> response = dynamicsNavRestTemplate.postForEntity(url, request,
					DynamicsNavReturnHeaderDTO.class);

			// Extract Document_No from response (it's read-only so it will be in the
			// response)
			DynamicsNavReturnHeaderDTO responseDto = response.getBody();

			return responseDto;
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create return header in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage,
					ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create return header in Dynamics NAV: {}", ex.getMessage(), ex);
			throw ex;
		}
	}

	/**
	 * Create a return line in Dynamics NAV
	 */
	public DynamicsNavReturnLineDTO createReturnLine(DynamicsNavReturnLineDTO line) {
		try {
			String url = buildCompanyEndpointUrl("SalesReturnPOSSalesLines");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavReturnLineDTO> request = new HttpEntity<>(line, headers);

			ResponseEntity<DynamicsNavReturnLineDTO> response = dynamicsNavRestTemplate.postForEntity(url, request,
					DynamicsNavReturnLineDTO.class);

			return response.getBody();
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create return line in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage, ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create return line in Dynamics NAV: {}", ex.getMessage(), ex);
			throw ex;
		}
	}

	/**
	 * Update POS_Order field on return header in Dynamics NAV (PATCH).
	 * Call after all return lines are synched.
	 */
	public DynamicsNavReturnHeaderDTO updateReturnHeaderPosOrder(String documentNo, boolean posOrder) {
		try {
			String escapedDocNo = documentNo.replace("'", "''");
			String url = buildCompanyEndpointUrl("SalesReturnPOS") + "(No='" + escapedDocNo
					+ "',Document_Type='Return Order')";

			com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
			node.put("POS_Order", posOrder);
			String jsonPayload = objectMapper.writeValueAsString(node);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.setIfMatch("*");
			HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);

			ResponseEntity<DynamicsNavReturnHeaderDTO> responseDto = dynamicsNavRestTemplate.exchange(url,
					HttpMethod.PATCH, request, DynamicsNavReturnHeaderDTO.class);
			LOGGER.info("Updated POS_Order to {} for return document {}", posOrder, documentNo);
			return responseDto.getBody();
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to update POS_Order for return document {}: {} - {}", documentNo, ex.getStatusCode(),
					errorMessage, ex);
			throw new RuntimeException("Failed to update return POS_Order: " + ex.getStatusCode() + " " + errorMessage, ex);
		} catch (RestClientException ex) {
			LOGGER.error("Failed to update POS_Order for return document {}: {}", documentNo, ex.getMessage(), ex);
			throw new RuntimeException("Failed to update return POS_Order: " + ex.getMessage(), ex);
		} catch (Exception ex) {
			LOGGER.error("Failed to update POS_Order for return document {}: {}", documentNo, ex.getMessage(), ex);
			throw new RuntimeException("Failed to update return POS_Order: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Create a POS closing entry in Dynamics NAV The codeunit returns void, so we
	 * just check the HTTP status code for success
	 */
	public void createSession(DynamicsNavSessionDTO session) {
		try {
			String url = buildCodeUnitEndpointUrl("WarehouseManagement_POSClosingEntries");
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<DynamicsNavSessionDTO> request = new HttpEntity<>(session, headers);

			// Codeunit returns void, so we use Void.class and check status code
			ResponseEntity<Void> response = dynamicsNavRestTemplate.postForEntity(url, request, Void.class);

			// Check if response is successful (2xx status code)
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new RuntimeException("Unexpected status code: " + response.getStatusCode());
			}

			LOGGER.info("Session created successfully in Dynamics NAV");
		} catch (HttpClientErrorException | HttpServerErrorException ex) {
			String errorMessage = extractErrorMessage(ex);
			LOGGER.error("Failed to create session in Dynamics NAV: {} - {}", ex.getStatusCode(), errorMessage, ex);
			// Re-throw the original exception so parent can extract response body
			throw ex;
		} catch (RestClientException ex) {
			LOGGER.error("Failed to create session in Dynamics NAV: {}", ex.getMessage(), ex);
			throw ex;
		}
	}
}
