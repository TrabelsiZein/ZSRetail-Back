package com.digithink.zsretail.erp.dto;

import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for a deletion log entry from ERP (e.g. Business Central Log API).
 * Used to remove from POS the records that were deleted in ERP.
 * Implements ErpTimestamped for checkpoint / incremental sync.
 */
@Getter
@Setter
public class ErpDeletionLogEntryDTO implements ErpTimestamped {

	private String sourceTable;
	private String itemNo;
	private String locationCode;
	private String variantCode;
	private String salesType;
	private String salesCode;
	private String startingDate;
	private String endingDate;
	private String responsibilityCenter;
	private String type;
	private String code;
	private String currencyCode;
	private String unitOfMeasureCode;
	private Double minimumQuantity;
	private String auxiliaryIndex1;
	private String auxiliaryIndex2;
	private String auxiliaryIndex3;
	private Integer auxiliaryIndex4;
	private OffsetDateTime modifiedAt;
	private String deletedBy;

	@Override
	public OffsetDateTime getLastModifiedAt() {
		return modifiedAt;
	}
}
