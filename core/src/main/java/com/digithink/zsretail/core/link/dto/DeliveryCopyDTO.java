package com.digithink.zsretail.core.link.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
	public static final String KIND = "BL";

	/** Step 7B: the kind of SUPPLY record (BL or INVOICE); a record without a kind is a BL (step 7A). */
	private String kind = KIND;
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

	/** One line of a BL as it travels down. */
	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class DeliveryLineCopyDTO {
		private Integer lineNo;
		private String itemCode;
		private String itemName;
		private BigDecimal quantitySent; // 2.2.1: up to 3 decimals, 50 travels as 50
	}
}
