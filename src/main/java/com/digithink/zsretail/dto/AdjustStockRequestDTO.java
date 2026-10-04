package com.digithink.zsretail.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for stock adjustment (without an ERP).
 * delta: positive to add, negative to subtract.
 * reason: COUNT, CORRECTION, DAMAGE.
 */
@Data
@NoArgsConstructor
public class AdjustStockRequestDTO {

	private Integer delta;
	private String reason;
}
