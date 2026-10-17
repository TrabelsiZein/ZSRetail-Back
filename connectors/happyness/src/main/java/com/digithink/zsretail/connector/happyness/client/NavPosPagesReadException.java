package com.digithink.zsretail.connector.happyness.client;

/**
 * A read of a page failed (HTTP error, timeout, no answer): the message names the page and the status. The client's
 * exception is its cause, so the catalogue engine reads the page again once after an I/O error or a timeout (3.1.0).
 */
public class NavPosPagesReadException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String page;

	public NavPosPagesReadException(String page, String message, Throwable cause) {
		super(message, cause);
		this.page = page;
	}

	public String getPage() {
		return page;
	}
}
