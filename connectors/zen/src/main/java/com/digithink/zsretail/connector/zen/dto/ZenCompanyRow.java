package com.digithink.zsretail.connector.zen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Step 10: a row of the companies of the API (api-path/companies): its id and its name. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenCompanyRow {

	private String id;
	private String name;
}
