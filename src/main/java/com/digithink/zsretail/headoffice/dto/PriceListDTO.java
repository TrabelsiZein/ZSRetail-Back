package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 6.4: a selling price list as the admin API reads and writes it. lineCount and storeCount are
 * computed, never read from the client.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PriceListDTO {

	private Long id;
	private String code;
	private String name;
	private Boolean active;
	private Long lineCount;
	private Long storeCount;
}
