package com.digithink.zsretail.headoffice.dto;

import com.digithink.zsretail.headoffice.enumeration.StoreStatus;
import com.digithink.zsretail.headoffice.model.Store;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A store in GET /admin/headoffice/stores (task 1.5): the store's own JSON, unchanged (the key hash stays out), then
 * the status and the age of the last contact, both computed with the head office clock.
 */
@Getter
@AllArgsConstructor
public class StoreListItemDTO {

	@JsonUnwrapped
	private final Store store;

	private final StoreStatus status;

	/** Whole seconds since lastContact (head office clock, never negative); null before the first contact. */
	private final Long secondsSinceContact;
}
