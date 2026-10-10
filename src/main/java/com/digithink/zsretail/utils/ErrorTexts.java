package com.digithink.zsretail.utils;

import org.springframework.core.NestedExceptionUtils;

/**
 * The short text of an error for a log line, a summary or an answer of the link. Code cleanup step 5: was
 * SalesCopyFinder.cause (the store's sales copies), used by the head office too.
 */
public final class ErrorTexts {

	private ErrorTexts() {
	}

	/** The most specific cause, e.g. "SQLServerException: The query has timed out." */
	public static String cause(Exception e) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
		return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
	}
}
