package com.digithink.zsretail.core.link.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): what became of a store's order at the head office, as it travels down to that store only
 * (domain SUPPLY, record ORD:&lt;number&gt;, kind STORE_ORDER). The number is orderNumber, never "number": a store that
 * does not know this kind reads no BL number in it and refuses the record. See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreOrderStatusCopyDTO {

	public static final String KIND = "STORE_ORDER";
	public static final String RECORD_PREFIX = "ORD:";

	private String kind = KIND;
	private String orderNumber;
	/** SENT (at the head office, not in NAV yet), POSTED (in NAV, navOrderNo set) or ERROR (errorMessage set). */
	private String status;
	/** The NAV document number once posted. */
	private String navOrderNo;
	/** Why the head office could not post it into NAV. */
	private String errorMessage;
	/** yyyy-MM-ddTHH:mm:ss, head office clock of the status. */
	private String statusAt;

	public static String recordCode(String orderNumber) {
		return RECORD_PREFIX + orderNumber;
	}
}
