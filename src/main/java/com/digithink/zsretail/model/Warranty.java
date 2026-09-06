package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Warranty entity - manual warranty registration per sold item (sales line).
 * Start date, end date, linked to ticket (SalesHeader) and item line (SalesLine).
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Warranty extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "sales_header_id", nullable = false)
	private SalesHeader salesHeader;

	@ManyToOne
	@JoinColumn(name = "sales_line_id", nullable = false)
	private SalesLine salesLine;

	@ManyToOne
	@JoinColumn(name = "item_id", nullable = false)
	private Item item;

	@Column(nullable = false)
	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate startDate;

	@Column(nullable = false)
	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate endDate;

	@Column(nullable = false)
	private Integer quantityCovered = 1;

	private String notes;

	/** When true, customer has used this warranty (e.g. for repair). Status becomes USED. */
	@Column(nullable = false)
	private Boolean used = false;
}
