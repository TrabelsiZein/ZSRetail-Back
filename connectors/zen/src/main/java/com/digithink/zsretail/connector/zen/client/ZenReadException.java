package com.digithink.zsretail.connector.zen.client;

/** A read of a page failed (HTTP error, timeout, no answer): the message names the page and the status. No retry. */
public class ZenReadException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String page;

	public ZenReadException(String page, String message, Throwable cause) {
		super(message, cause);
		this.page = page;
	}

	public String getPage() {
		return page;
	}
}
