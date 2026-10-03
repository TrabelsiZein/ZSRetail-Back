package com.digithink.zsretail.headoffice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of POST /ho/heartbeat (task 1.4), sent by the store's HeadOfficeClient. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeadOfficeHeartbeatDTO {

	/** The store's application version (app.version). */
	private String appVersion;
}
