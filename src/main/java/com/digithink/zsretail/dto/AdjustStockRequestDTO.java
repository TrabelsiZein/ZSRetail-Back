package com.digithink.zsretail.dto;

import java.math.BigDecimal;

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

	private BigDecimal delta; // 2.2.1: read as sent; a decimal is refused naming the item
	private String reason;
}
