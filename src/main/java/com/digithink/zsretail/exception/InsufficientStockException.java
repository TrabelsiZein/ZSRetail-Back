package com.digithink.zsretail.exception;

/**
 * Thrown when a stock decrement (e.g. for a sale) cannot be performed because
 * current stock is insufficient. Allows the caller to roll back the transaction
 * and return a clear message to the user.
 */
public class InsufficientStockException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final Long itemId;
	private final int requested;
	private final int available;

	public InsufficientStockException(Long itemId, int requested, int available) {
		super(available >= 0
				? String.format("Insufficient stock for item %d: requested=%d, available=%d", itemId, requested, available)
				: String.format("Insufficient stock for item %d: requested=%d", itemId, requested));
		this.itemId = itemId;
		this.requested = requested;
		this.available = available;
	}

	public Long getItemId() {
		return itemId;
	}

	public int getRequested() {
		return requested;
	}

	public int getAvailable() {
		return available;
	}
}
