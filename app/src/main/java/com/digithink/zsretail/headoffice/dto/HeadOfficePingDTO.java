package com.digithink.zsretail.headoffice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Answer of GET /ho/ping: the calling store's code and the head office time. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeadOfficePingDTO {

	private String storeCode;

	/** ISO-8601 with milliseconds and the offset, e.g. 2026-10-02T23:15:04.123+01:00. */
	private String serverTime;
}
