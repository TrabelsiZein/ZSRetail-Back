package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: the body of POST /store-order/draft/lines {itemId, quantity, availableQty?, vracAllowed?} and PUT
 * /store-order/draft/note {note}. availableQty and vracAllowed are sent by the variants window (null: none known, no limit
 * / no rule) and become the line's; the cart sends neither and the line keeps its own (availableQtySent tells the two
 * apart).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreOrderInputDTO {

	private Long itemId;

	/** 0 removes the line; up to 3 decimals where the store allows decimal quantities. */
	private BigDecimal quantity;

	private String note;

	/** The quantity available the variants window showed for the item (null: no limit); only when sent. */
	private BigDecimal availableQty;

	/** 3.1.1: the variant's vrac rule the window showed (ZEN's vracAutorise; null: no rule); with availableQty. */
	private Boolean vracAllowed;

	/** True when the body had availableQty, even null. */
	@JsonIgnore
	private boolean availableQtySent;

	public void setAvailableQty(BigDecimal availableQty) {
		this.availableQty = availableQty;
		this.availableQtySent = true;
	}
}
