package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Stock points, step 1: a point de stock of the head office whose catalogue comes from the ERP, one Location_Code of
 * the ERP's items page (FRANCHISE, a store's own location). A store has one point or none (ho_store.stock_point_id).
 * The points are read in list order (sortOrder): the first one where an item is active gives the item's default copy.
 * Head office only data (prefix ho_). {@code active} from {@link _BaseEntity}: an inactive point is not read; a point
 * used by a store cannot be deactivated or deleted. See docs/modules/head-office.md, "Stock points".
 */
@Entity
@Table(name = "ho_stock_point")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoStockPoint extends _BaseEntity {

	public static final int CODE_LENGTH = 20;

	/** The ERP's Location_Code: trimmed, uppercase, unique. Cannot be changed after creation. */
	@Column(nullable = false, unique = true, length = CODE_LENGTH)
	private String code;

	@Column(nullable = false)
	private String name;

	/** Place in the list, 1 first. */
	@Column(name = "sort_order", nullable = false)
	private Integer sortOrder;

	public static final int LAST_READ_SUMMARY_LENGTH = 255;

	/** Step 4: when the last items run read this point (not a dry run); null before the first one. */
	@Column(name = "last_read_at")
	private java.time.LocalDateTime lastReadAt;

	/** Step 4: OK, NO_ANSWER (no row: the point's rows kept) or FAILED; null before the first run. */
	@Column(name = "last_read_status", length = 20)
	private String lastReadStatus;

	/** Step 4: the counts of that read (read, new, changed, deactivated, written) or the error. */
	@Column(name = "last_read_summary", length = LAST_READ_SUMMARY_LENGTH)
	private String lastReadSummary;
}
