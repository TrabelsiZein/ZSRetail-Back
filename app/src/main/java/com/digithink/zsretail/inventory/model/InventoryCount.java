package com.digithink.zsretail.inventory.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.inventory.enumeration.InventoryCountStatus;
import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * An inventory count imported from an Excel file (store only, the stock kept here): DRAFT until validated, then the
 * stock of each OK line becomes its counted quantity, with one INVENTORY_IN or INVENTORY_OUT movement per difference.
 * See docs/modules/inventory-count.md.
 */
@Entity
@Table(name = "inventory_count",
		uniqueConstraints = @UniqueConstraint(name = "uk_inventory_count_number", columnNames = "number"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class InventoryCount extends _BaseEntity {

	/** INV-YYYYMM-000001, like the purchase numbers. */
	@Column(name = "number", nullable = false, length = 30)
	private String number;

	@Column(name = "count_date", nullable = false)
	private LocalDate countDate;

	@Column(name = "note", length = 500)
	private String note;

	@Column(name = "file_name", length = 255)
	private String fileName;

	/** Rows of the file with a code or a quantity (header and empty rows not counted). */
	@Column(name = "rows_read")
	private Integer rowsRead;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InventoryCountStatus status = InventoryCountStatus.DRAFT;

	@Column(name = "validated_at")
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime validatedAt;

	@Column(name = "validated_by", length = 100)
	private String validatedBy;
}
