package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stock points, step 1: a point de stock as the admin API reads and writes it. sortOrder is changed only by
 * PUT .../order; itemCount and storeCount are computed, never read from the client.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockPointDTO {

	private Long id;
	private String code;
	private String name;
	private Boolean active;
	private Integer sortOrder;
	private Long itemCount;
	private Long storeCount;
}
