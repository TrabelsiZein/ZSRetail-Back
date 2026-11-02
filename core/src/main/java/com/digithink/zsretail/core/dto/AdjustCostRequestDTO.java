package com.digithink.zsretail.core.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CMP: request body of POST /item/{id}/adjust-cost ("Ajuster le CMP").
 * costPrice: the new weighted average cost, 0 or more (rounded to 3 decimals).
 * reason: mandatory, kept in the COST_ADJUSTMENT movement.
 */
@Data
@NoArgsConstructor
public class AdjustCostRequestDTO {

	private Double costPrice;
	private String reason;
}
