package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Franchise: a single line item within a FranchiseSalesHeader.
 * Item name is stored as a snapshot to preserve historical accuracy
 * even if the item is renamed on the admin side later.
 */
@Entity
@Table(name = "franchise_sales_line")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class FranchiseSalesLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "header_id", nullable = false)
	@JsonIgnore // Breaks recursive serialization: FranchiseSalesHeader -> lines -> header -> lines -> ...
	private FranchiseSalesHeader header;

	/** Item code — used to cross-reference with the admin's item catalogue. */
	@Column(name = "item_code", nullable = false)
	private String itemCode;

	/** Snapshot of item name at time of sale (denormalized for dashboard accuracy). */
	@Column(name = "item_name")
	private String itemName;

	@Column(name = "quantity")
	private Integer quantity;

	@Column(name = "unit_price")
	private Double unitPrice;

	@Column(name = "discount_amount")
	private Double discountAmount;

	@Column(name = "total_amount")
	private Double totalAmount;
}
