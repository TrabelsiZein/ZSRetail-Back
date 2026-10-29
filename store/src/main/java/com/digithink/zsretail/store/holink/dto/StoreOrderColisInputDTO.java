package com.digithink.zsretail.store.holink.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: POST /store-order/draft/colis, the colis of an ERP item (a colis is the whole item: one série of every colour,
 * ZEN): the number (0 removes the row), and from the variants window the head office's figures it showed: colisAvailable
 * (the limit; null: none) and the composition of one colis (each variant's pieces, ZEN's qteSerieType), kept as a
 * snapshot. The cart sends neither: the row keeps its own. See docs/modules/store-orders.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreOrderColisInputDTO {

	private String navItemNo;
	private Integer nbColis;
	private Integer colisAvailable;
	private List<Part> composition = new ArrayList<>();

	/** A variant (the store's item) and its pieces in one colis. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Part {
		private Long itemId;
		private Integer qtyPerColis;
	}
}
