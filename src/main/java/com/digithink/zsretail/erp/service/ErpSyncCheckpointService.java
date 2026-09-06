package com.digithink.zsretail.erp.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dto.ErpTimestamped;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpSyncCheckpointService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncCheckpointService.class);
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private static final Map<ErpSyncJobType, String> JOB_TYPE_TO_CODE;
	private static final Map<String, String> CODE_TO_DESCRIPTION;

	static {
		Map<ErpSyncJobType, String> jobToCode = new EnumMap<>(ErpSyncJobType.class);
		jobToCode.put(ErpSyncJobType.IMPORT_ITEM_FAMILIES, "ERP_SYNC_LAST_ITEM_FAMILY");
		jobToCode.put(ErpSyncJobType.IMPORT_ITEM_SUBFAMILIES, "ERP_SYNC_LAST_ITEM_SUBFAMILY");
		jobToCode.put(ErpSyncJobType.IMPORT_ITEMS, "ERP_SYNC_LAST_ITEM");
		jobToCode.put(ErpSyncJobType.IMPORT_ITEM_BARCODES, "ERP_SYNC_LAST_ITEM_BARCODE");
		jobToCode.put(ErpSyncJobType.IMPORT_LOCATIONS, "ERP_SYNC_LAST_LOCATION");
		jobToCode.put(ErpSyncJobType.IMPORT_CUSTOMERS, "ERP_SYNC_LAST_CUSTOMER");
		jobToCode.put(ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS, "a");
		jobToCode.put(ErpSyncJobType.SYNC_ERP_DELETIONS, "ERP_SYNC_LAST_DELETION_LOG");
		JOB_TYPE_TO_CODE = Collections.unmodifiableMap(jobToCode);

		Map<String, String> descriptions = new HashMap<>();
		descriptions.put("ERP_SYNC_LAST_ITEM_FAMILY", "Timestamp (Modified_At) of last synchronized item family");
		descriptions.put("ERP_SYNC_LAST_ITEM_SUBFAMILY", "Timestamp (Modified_At) of last synchronized item subfamily");
		descriptions.put("ERP_SYNC_LAST_ITEM", "Timestamp (Modified_At) of last synchronized item");
		descriptions.put("ERP_SYNC_LAST_ITEM_BARCODE", "Timestamp (Modified_At) of last synchronized item barcode");
		descriptions.put("ERP_SYNC_LAST_LOCATION", "Timestamp (Modified_At) of last synchronized location");
		descriptions.put("ERP_SYNC_LAST_CUSTOMER", "Timestamp (Modified_At) of last synchronized customer");
		descriptions.put("ERP_SYNC_LAST_SALES_PRICE", "Timestamp (Modified_At) of last synchronized sales price");
		descriptions.put("ERP_SYNC_LAST_SALES_DISCOUNT", "Timestamp (Modified_At) of last synchronized sales discount");
		descriptions.put("ERP_SYNC_LAST_DELETION_LOG", "Timestamp (Modified_At) of last processed deletion log entry");
		CODE_TO_DESCRIPTION = Collections.unmodifiableMap(descriptions);
	}

	private final GeneralSetupService generalSetupService;

	public ErpSyncFilter createFilterForJob(ErpSyncJobType jobType) {
		ErpSyncFilter filter = new ErpSyncFilter();
		resolveLastSync(jobType).ifPresent(filter::setUpdatedAfter);
		return filter;
	}

	public void updateLastSync(ErpSyncJobType jobType, Collection<?> payload) {
		if (payload == null || payload.isEmpty()) {
			return;
		}

		Optional<OffsetDateTime> maxTimestamp = payload.stream().filter(Objects::nonNull)
				.filter(ErpTimestamped.class::isInstance).map(ErpTimestamped.class::cast)
				.map(ErpTimestamped::getLastModifiedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());

		maxTimestamp.ifPresent(ts -> updateLastSync(jobType, ts));
	}

	public void updateLastSync(ErpSyncJobType jobType, OffsetDateTime timestamp) {
		if (timestamp == null) {
			return;
		}
		String code = JOB_TYPE_TO_CODE.get(jobType);
		if (code == null) {
			return;
		}
		GeneralSetup setup = generalSetupService.findByCode(code);
		if (setup == null) {
			LOGGER.warn("Missing general setup entry for ERP sync checkpoint code {}", code);
			return;
		}
		generalSetupService.updateValue(code, FORMATTER.format(timestamp));
	}

	public Optional<OffsetDateTime> resolveLastSync(ErpSyncJobType jobType) {
		String code = JOB_TYPE_TO_CODE.get(jobType);
		if (code == null) {
			return Optional.empty();
		}
		String value = generalSetupService.findValueByCode(code);
		if (value == null || value.trim().isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(OffsetDateTime.parse(value.trim()));
		} catch (DateTimeParseException ex) {
			LOGGER.warn("Invalid timestamp '{}' for ERP sync checkpoint '{}'", value, code);
			return Optional.empty();
		}
	}

	public static Map<ErpSyncJobType, String> getCheckpointCodes() {
		return JOB_TYPE_TO_CODE;
	}

	public static Map<String, String> getCheckpointDescriptions() {
		return CODE_TO_DESCRIPTION;
	}

	/**
	 * Update checkpoint for sales prices operation
	 */
	public void updateLastSyncForSalesPrices(OffsetDateTime timestamp) {
		if (timestamp == null) {
			return;
		}
		generalSetupService.updateValue("ERP_SYNC_LAST_SALES_PRICE", FORMATTER.format(timestamp));
	}

	/**
	 * Update checkpoint for sales prices operation from collection
	 */
	public void updateLastSyncForSalesPrices(Collection<?> payload) {
		if (payload == null || payload.isEmpty()) {
			return;
		}
		Optional<OffsetDateTime> maxTimestamp = payload.stream().filter(Objects::nonNull)
				.filter(ErpTimestamped.class::isInstance).map(ErpTimestamped.class::cast)
				.map(ErpTimestamped::getLastModifiedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());
		maxTimestamp.ifPresent(this::updateLastSyncForSalesPrices);
	}

	/**
	 * Update checkpoint for sales discounts operation
	 */
	public void updateLastSyncForSalesDiscounts(OffsetDateTime timestamp) {
		if (timestamp == null) {
			return;
		}
		generalSetupService.updateValue("ERP_SYNC_LAST_SALES_DISCOUNT", FORMATTER.format(timestamp));
	}

	/**
	 * Update checkpoint for sales discounts operation from collection
	 */
	public void updateLastSyncForSalesDiscounts(Collection<?> payload) {
		if (payload == null || payload.isEmpty()) {
			return;
		}
		Optional<OffsetDateTime> maxTimestamp = payload.stream().filter(Objects::nonNull)
				.filter(ErpTimestamped.class::isInstance).map(ErpTimestamped.class::cast)
				.map(ErpTimestamped::getLastModifiedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());
		maxTimestamp.ifPresent(this::updateLastSyncForSalesDiscounts);
	}

	/**
	 * Get last sync timestamp for sales prices
	 */
	public Optional<OffsetDateTime> resolveLastSyncForSalesPrices() {
		String value = generalSetupService.findValueByCode("ERP_SYNC_LAST_SALES_PRICE");
		if (value == null || value.trim().isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(OffsetDateTime.parse(value.trim()));
		} catch (DateTimeParseException ex) {
			LOGGER.warn("Invalid timestamp '{}' for ERP sync checkpoint '{}'", value, "ERP_SYNC_LAST_SALES_PRICE");
			return Optional.empty();
		}
	}

	/**
	 * Get last sync timestamp for sales discounts
	 */
	public Optional<OffsetDateTime> resolveLastSyncForSalesDiscounts() {
		String value = generalSetupService.findValueByCode("ERP_SYNC_LAST_SALES_DISCOUNT");
		if (value == null || value.trim().isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(OffsetDateTime.parse(value.trim()));
		} catch (DateTimeParseException ex) {
			LOGGER.warn("Invalid timestamp '{}' for ERP sync checkpoint '{}'", value, "ERP_SYNC_LAST_SALES_DISCOUNT");
			return Optional.empty();
		}
	}
}
