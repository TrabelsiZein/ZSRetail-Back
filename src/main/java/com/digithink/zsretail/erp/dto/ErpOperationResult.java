package com.digithink.zsretail.erp.dto;

public class ErpOperationResult {

	private boolean success;
	private String externalReference;
	private String url; // The HTTP endpoint URL that was called
	private String message;
	private Object actualRequestPayload; // The actual request sent to ERP (e.g., NAV-specific DTO)
	private Object actualResponsePayload; // The actual response from ERP (e.g., HTTP response body)

	public static ErpOperationResult success(String externalReference) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(true);
		result.setExternalReference(externalReference);
		return result;
	}

	public static ErpOperationResult success(String externalReference, Object actualRequestPayload) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(true);
		result.setExternalReference(externalReference);
		result.setActualRequestPayload(actualRequestPayload);
		return result;
	}

	public static ErpOperationResult success(String externalReference, Object actualRequestPayload,
			Object actualResponsePayload) {
		return success(externalReference, actualRequestPayload, actualResponsePayload, null);
	}

	public static ErpOperationResult success(String externalReference, Object actualRequestPayload,
			Object actualResponsePayload, String url) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(true);
		result.setExternalReference(externalReference);
		result.setUrl(url);
		result.setActualRequestPayload(actualRequestPayload);
		result.setActualResponsePayload(actualResponsePayload);
		return result;
	}

	public static ErpOperationResult failure(String message) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(false);
		result.setMessage(message);
		return result;
	}

	public static ErpOperationResult failure(String message, Object actualRequestPayload) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(false);
		result.setMessage(message);
		result.setActualRequestPayload(actualRequestPayload);
		return result;
	}

	public static ErpOperationResult failure(String message, Object actualRequestPayload,
			Object actualResponsePayload) {
		return failure(message, actualRequestPayload, actualResponsePayload, null);
	}

	public static ErpOperationResult failure(String message, Object actualRequestPayload, Object actualResponsePayload,
			String url) {
		ErpOperationResult result = new ErpOperationResult();
		result.setSuccess(false);
		result.setMessage(message);
		result.setUrl(url);
		result.setActualRequestPayload(actualRequestPayload);
		result.setActualResponsePayload(actualResponsePayload);
		return result;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getExternalReference() {
		return externalReference;
	}

	public void setExternalReference(String externalReference) {
		this.externalReference = externalReference;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public Object getActualRequestPayload() {
		return actualRequestPayload;
	}

	public void setActualRequestPayload(Object actualRequestPayload) {
		this.actualRequestPayload = actualRequestPayload;
	}

	public Object getActualResponsePayload() {
		return actualResponsePayload;
	}

	public void setActualResponsePayload(Object actualResponsePayload) {
		this.actualResponsePayload = actualResponsePayload;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}
}
