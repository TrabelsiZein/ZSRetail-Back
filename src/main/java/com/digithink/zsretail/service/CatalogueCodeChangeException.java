package com.digithink.zsretail.service;

/**
 * Head office plan, step 6: on a head office that sends its catalogue, the code of an item, a family or a sub-family
 * cannot be changed once created (the stores keep stock and history by code). Answered 409 by the APIs.
 */
public class CatalogueCodeChangeException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public CatalogueCodeChangeException(String message) {
		super(message);
	}
}
