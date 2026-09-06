package com.digithink.zsretail.model;

import java.time.LocalDateTime;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.OneToMany;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Franchise: stores sales data pushed by a franchise client to the admin.
 * Used for tracking and dashboards only — not linked to the admin's own sales flow.
 * Each record corresponds to one completed ticket on the franchise client side.
 */
@Entity
@Table(name = "franchise_sales_header")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class FranchiseSalesHeader extends _BaseEntity {

	/**
	 * Location code identifying the franchise client that sent this sale.
	 * Matches the client's DEFAULT_LOCATION GeneralSetup value.
	 */
	@Column(name = "location_code", nullable = false)
	private String locationCode;

	/**
	 * Original ticket/sales number on the franchise client system.
	 */
	@Column(name = "external_sales_number")
	private String externalSalesNumber;

	/**
	 * Date and time when the sale was completed on the franchise client side.
	 */
	@Column(name = "sales_date")
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime salesDate;

	/**
	 * Timestamp when the admin received this sale from the franchise client push.
	 */
	@Column(name = "received_at")
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime receivedAt = LocalDateTime.now();

	/** Customer name at time of sale on the franchise client (nullable — walk-in sales have no customer). */
	@Column(name = "customer_name")
	private String customerName;

	/** Cashier name on the franchise client side. */
	@Column(name = "cashier_name")
	private String cashierName;

	/** Total amount before tax (HT). */
	@Column(name = "total_ht")
	private Double totalHT;

	/** Total tax amount (TVA). */
	@Column(name = "total_tva")
	private Double totalTVA;

	/** Total amount including tax (TTC). */
	@Column(name = "total_ttc")
	private Double totalTTC;

	@OneToMany(mappedBy = "header", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private List<FranchiseSalesLine> lines;
}
