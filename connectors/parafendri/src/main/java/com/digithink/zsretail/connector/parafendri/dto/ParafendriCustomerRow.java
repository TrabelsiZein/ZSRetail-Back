package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of the page customers of the company: the account of a point de vente (4110001...), its name, its block
 * ("_x0020_", Business Central's blank option, when not blocked; Ship, Invoice, All) and when Business Central last
 * changed it (ISO, UTC).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriCustomerRow {

	private String number;
	@JsonAlias("displayName")
	private String name;
	private String blocked;
	private String lastModifiedDateTime;
}
