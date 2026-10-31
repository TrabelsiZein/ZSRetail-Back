package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Step 0: a row of the companies of the API (api-path/companies): its id, its name and its display name. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriCompanyRow {

	private String id;
	private String name;
	private String displayName;
}
