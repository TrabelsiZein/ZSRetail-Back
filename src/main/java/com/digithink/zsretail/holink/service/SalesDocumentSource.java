package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;
import java.util.List;

import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;

/**
 * Reads the store's documents for the sales copies (task 2.1). Read only: nothing in the selling services is called or
 * changed. Implemented with JPQL by {@code JpaSalesDocumentSource}; replaced by an in-memory list in the tests.
 */
public interface SalesDocumentSource {

	/**
	 * Documents of one type, of any status, whose change time (updated_at, or the document date when it is empty) is
	 * after the cursor ({@code afterChangedAt}, then {@code afterId}) and not after {@code until}, dated from
	 * {@code from} (sales date, return date, session opening date: never empty, so a tracked document is always read
	 * again after a change, whatever its status). In cursor order (change time, then id), at most {@code limit}.
	 */
	List<SalesDocumentRef> findChanged(SalesCopyType type, LocalDateTime from, LocalDateTime afterChangedAt, long afterId,
			LocalDateTime until, int limit);

	/**
	 * Task 2.4: the copy of one document as it is now ({@code TicketCopyDTO}, {@code ReturnCopyDTO} or
	 * {@code SessionCopyDTO}, built by {@link SalesCopyMapper}); null when the document no longer exists.
	 */
	Object loadCopy(SalesCopyType type, Long localId);
}
