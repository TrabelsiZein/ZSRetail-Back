package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 1: a row of the page locations of the company (the points de vente, PV-GREMDA...): its code, its name and when
 * Business Central last changed it, as the API gives it (ISO, UTC).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriLocationRow {

	private String code;
	private String name;
	private String lastModifiedDateTime;
}
