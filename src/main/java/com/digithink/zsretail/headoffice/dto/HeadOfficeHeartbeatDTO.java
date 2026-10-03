package com.digithink.zsretail.headoffice.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /ho/heartbeat (task 1.4), sent by the store's HeadOfficeClient. Task 3.6: also what the store owns, so
 * the head office knows it; a store of an older version sends the version only (the head office shows it as unknown).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeadOfficeHeartbeatDTO {

	/** The store's application version (app.version). */
	private String appVersion;

	/** Task 3.6: DataDomain name to DataOwner name, every domain (GET /config "ownership"); absent from older stores. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Map<String, String> ownership;

	/** Task 3.6: SalesUpstream names, empty = nowhere (GET /config "salesUpstreams"); absent from older stores. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<String> salesUpstreams;

	/** The version only, as sent before task 3.6. */
	public HeadOfficeHeartbeatDTO(String appVersion) {
		this.appVersion = appVersion;
	}
}
