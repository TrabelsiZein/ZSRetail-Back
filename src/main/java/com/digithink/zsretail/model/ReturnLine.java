package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Return line entity - line items for returns
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReturnLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "return_header_id", nullable = false)
	@JsonIgnore
	private ReturnHeader returnHeader;

	@ManyToOne
	@JoinColumn(name = "original_sales_line_id", nullable = false)
	@JsonIgnore
	private SalesLine originalSalesLine;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	@Column(nullable = false)
	private Integer quantity;

	@Column(nullable = false)
	private Double unitPrice; // HT (excluding VAT)

	@Column(nullable = false)
	private Double unitPriceIncludingVat; // TTC (including VAT)

	@Column(nullable = false)
	private Double lineTotal; // HT (excluding VAT)

	@Column(nullable = false)
	private Double lineTotalIncludingVat; // TTC (including VAT)

	private String notes;

	// ERP synchronization field
	private Boolean synched = false;
}

