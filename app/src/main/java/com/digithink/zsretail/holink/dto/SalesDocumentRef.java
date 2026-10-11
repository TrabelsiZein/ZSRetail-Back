package com.digithink.zsretail.holink.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.holink.enumeration.SalesCopyType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/** One document found by the search for new and changed documents (task 2.1): its key fields only. */
@Getter
@ToString
@AllArgsConstructor
public final class SalesDocumentRef {

	private final SalesCopyType type;

	private final Long localId;

	private final String documentNumber;

	/** Sales date, return date or session opening date. */
	private final LocalDateTime documentDate;

	/** Name of its TransactionStatus or SessionStatus; null when empty. */
	private final String status;

	/** updated_at of the document, or its date when updated_at is empty: the order of the search. */
	private final LocalDateTime changedAt;
}
