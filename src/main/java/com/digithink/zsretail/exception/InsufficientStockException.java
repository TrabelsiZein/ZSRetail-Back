package com.digithink.zsretail.exception;

import java.math.BigDecimal;

import com.digithink.zsretail.utils.Quantities;

/**
 * Thrown when a stock decrement (e.g. for a sale) cannot be performed because
 * current stock is insufficient. Allows the caller to roll back the transaction
 * and return a clear message to the user. 2.2.1: quantities with up to 3 decimals,
 * written without trailing zeros (2, 0.2).
 */
public class InsufficientStockException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final Long itemId;
	private final BigDecimal requested;
	private final BigDecimal available;

	/** @param available the stock found, or null when unknown (the atomic update only says it was not enough) */
	public InsufficientStockException(Long itemId, BigDecimal requested, BigDecimal available) {
		super(available != null
				? String.format("Insufficient stock for item %d: requested=%s, available=%s", itemId,
						Quantities.plain(requested), Quantities.plain(available))
				: String.format("Insufficient stock for item %d: requested=%s", itemId, Quantities.plain(requested)));
		this.itemId = itemId;
		this.requested = requested;
		this.available = available;
	}

	public Long getItemId() {
		return itemId;
	}

	public BigDecimal getRequested() {
		return requested;
	}

	/** The stock found, or null when unknown. */
	public BigDecimal getAvailable() {
		return available;
	}
}
