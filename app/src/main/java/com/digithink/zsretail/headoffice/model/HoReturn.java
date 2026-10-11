package com.digithink.zsretail.headoffice.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A store's return as copied to the head office (task 2.3). One row per store + return number; a new copy replaces
 * the content, lines included. createdAt is the first reception, updatedAt the last one.
 */
@Entity
@Table(name = "ho_return",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_return_store_number", columnNames = { "store_id", "return_number" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoReturn extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "store_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Store store;

	@Column(name = "return_number", nullable = false)
	private String returnNumber;

	private LocalDateTime returnDate;

	@Column(length = 20)
	private String status;

	@Column(length = 20)
	private String returnType;

	private String originalSalesNumber;

	private Double totalReturnAmount;

	private Double discountPercentage;

	private String cashierLogin;

	private String cashierName;

	private String sessionNumber;

	private String voucherNumber;

	private Double voucherAmount;

	private LocalDate voucherExpiryDate;

	@Column(length = 1000)
	private String notes;

	@OneToMany(mappedBy = "returnCopy", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoReturnLine> lines = new ArrayList<>();
}
