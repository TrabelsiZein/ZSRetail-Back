package com.digithink.zsretail.connector.parafendri.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 6: a sales order as Business Central answers it (the POST's answer, or a row of the lookup by ticketNo): its id,
 * its number (the ticket's ERP number), the ticket it came from, its location and orderStatus (Entered, Posted, Error,
 * Double: set by Business Central) and amountIncludingVAT, the total TTC Business Central computed from the lines.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriSalesOrderRow {

	private String id;
	private String number;
	private String ticketNo;
	private String locationCode;
	private String orderStatus;
	private BigDecimal amountIncludingVAT;
}
