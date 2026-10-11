package com.digithink.zsretail.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Exception thrown when cash discrepancy is detected during session closure
 */
public class CashDiscrepancyException extends IllegalStateException {
	
	private static final long serialVersionUID = 1L;
	
	private final Map<String, Object> errorData;
	
	public CashDiscrepancyException(Double expectedAmount, Double actualAmount, Double discrepancyAmount) {
		super("Expected cash does not match counted cash");
		this.errorData = new HashMap<>();
		this.errorData.put("error", "CASH_DISCREPANCY");
		this.errorData.put("expectedAmount", expectedAmount);
		this.errorData.put("actualAmount", actualAmount);
		this.errorData.put("discrepancyAmount", discrepancyAmount);
		this.errorData.put("message", "Expected cash does not match counted cash");
	}
	
	public Map<String, Object> getErrorData() {
		return errorData;
	}
}
