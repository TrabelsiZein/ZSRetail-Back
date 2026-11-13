package com.digithink.zsretail.core.link.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0: one scanned-tickets method of a {@link SessionCopyDTO}, what the store's session details expect for it: the
 * number of tickets scanned in the session's sales and their face total (what the cashier counts on the papers), and
 * their net (the parts applied, what entered the sales). A method declared at the closing without a scanned ticket is
 * sent with zeros.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionScannedCopyDTO {

	private String paymentMethodCode;

	private Integer ticketCount;

	private Double faceTotal;

	private Double netTotal;
}
