package com.digithink.zsretail.service;

/**
 * Head office plan, step 6: on a head office that sends its catalogue, a code longer than the copies down can carry (90
 * characters). Answered 400 by the item, family and sub-family APIs.
 */
public class CatalogueCodeTooLongException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public CatalogueCodeTooLongException(String message) {
		super(message);
	}
}
