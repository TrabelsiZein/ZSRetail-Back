package com.digithink.zsretail.store.holink.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 3.1.1: the body of POST /store-order/draft/lines {itemId, quantity} and PUT /store-order/draft/note {note}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreOrderInputDTO {

	private Long itemId;

	/** 0 removes the line; up to 3 decimals where the store allows decimal quantities. */
	private BigDecimal quantity;

	private String note;
}
