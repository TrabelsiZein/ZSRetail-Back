package com.digithink.zsretail.connector.parafendri.client;

/**
 * A read of a page failed (HTTP error, timeout, no answer) or did not give what was asked: the message names the page
 * and the HTTP status. No retry.
 */
public class ParafendriReadException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String page;
	/** The HTTP status of the answer; null when there was none (timeout, no answer) or the answer was a 200. */
	private final Integer status;

	public ParafendriReadException(String page, Integer status, String message, Throwable cause) {
		super(message, cause);
		this.page = page;
		this.status = status;
	}

	public String getPage() {
		return page;
	}

	public Integer getStatus() {
		return status;
	}
}
