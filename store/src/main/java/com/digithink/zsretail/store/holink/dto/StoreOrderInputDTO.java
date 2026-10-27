package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1: the body of POST /store-order/draft/lines {itemId, quantity, availableQty?} and PUT /store-order/draft/note
 * {note}. availableQty is sent by the variants window (null: none known, no limit) and becomes the line's; the cart sends
 * none and the line keeps its own (availableQtySent tells the two apart).
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

	/** True when the body had availableQty, even null. */
	@JsonIgnore
	private boolean availableQtySent;

	public void setAvailableQty(BigDecimal availableQty) {
		this.availableQty = availableQty;
		this.availableQtySent = true;
	}
}
