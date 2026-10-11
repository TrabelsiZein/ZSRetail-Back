package com.digithink.zsretail.erp.navpospages.client;

/** A read of a page failed (HTTP error, timeout, no answer): the message names the page and the status. No retry. */
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
