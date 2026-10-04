package com.digithink.zsretail.headoffice.dto;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 7A: a BL as it travels down to its store (domain SUPPLY, record BL:&lt;number&gt;), by codes
 * only, never a database id. Dates as ISO strings. No price (step 7B).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliveryCopyDTO {

	public static final String RECORD_PREFIX = "BL:";

	private String number;
	/** yyyy-MM-dd. */
	private String documentDate;
	/** yyyy-MM-ddTHH:mm:ss, head office clock. */
	private String sentAt;
	private String status;
	private String note;
	private List<DeliveryLineCopyDTO> lines = new ArrayList<>();

	public static String recordCode(String number) {
		return RECORD_PREFIX + number;
	}

	/** The BL number of a record code; null when it is not one. */
	public static String numberOf(String recordCode) {
		return recordCode != null && recordCode.startsWith(RECORD_PREFIX) && recordCode.length() > RECORD_PREFIX.length()
				? recordCode.substring(RECORD_PREFIX.length())
				: null;
	}

	public static DeliveryCopyDTO of(HoDelivery delivery) {
		DeliveryCopyDTO copy = new DeliveryCopyDTO();
		copy.number = delivery.getNumber();
		copy.documentDate = delivery.getDocumentDate() == null ? null : delivery.getDocumentDate().toString();
		copy.sentAt = delivery.getSentAt() == null ? null
				: delivery.getSentAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		copy.status = delivery.getStatus() == null ? null : delivery.getStatus().name();
		copy.note = delivery.getNote();
		for (HoDeliveryLine line : delivery.getLines()) {
			DeliveryLineCopyDTO lineCopy = new DeliveryLineCopyDTO();
			lineCopy.setLineNo(line.getLineNo());
			lineCopy.setItemCode(line.getItemCode());
			lineCopy.setItemName(line.getItemName());
			lineCopy.setQuantitySent(line.getQuantitySent());
			copy.lines.add(lineCopy);
		}
		copy.lines.sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		return copy;
	}

	/** One line of a BL as it travels down. */
	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class DeliveryLineCopyDTO {
		private Integer lineNo;
		private String itemCode;
		private String itemName;
		private Integer quantitySent;
	}
}
