package com.digithink.zsretail.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for stock adjustment (standalone mode).
 * delta: positive to add, negative to subtract.
 * reason: COUNT, CORRECTION, DAMAGE.
 */
@Data
@NoArgsConstructor
public class AdjustStockRequestDTO {

	private Integer delta;
	private String reason;
}
