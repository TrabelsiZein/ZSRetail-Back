package com.digithink.zsretail.store.holink.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: POST /store-order/draft/colis, the colis of one ERP item from the variants window: the number (0 removes the
 * row), and the head office's figures it showed: colisPossibles (the limit; null: none) and contenance (pieces per colis,
 * informative). See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreOrderColisInputDTO {

	private String navItemNo;
	private Integer nbColis;
	private Integer colisPossibles;
	private Integer contenance;
}
