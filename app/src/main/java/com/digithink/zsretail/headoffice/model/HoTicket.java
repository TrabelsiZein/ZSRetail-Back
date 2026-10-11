package com.digithink.zsretail.headoffice.model;

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
 * A store's ticket as copied to the head office (task 2.3). Head office only: the store tables are not reused, a
 * ticket here points to its store and carries codes and names instead of the store's items, customers, users and
 * sessions. One row per store + sales number; a new copy replaces the content, lines and payments included.
 * createdAt is the first reception, updatedAt the last one.
 */
@Entity
@Table(name = "ho_ticket",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_ticket_store_number", columnNames = { "store_id", "sales_number" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoTicket extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "store_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Store store;

	@Column(name = "sales_number", nullable = false)
	private String salesNumber;

	private LocalDateTime salesDate;

	private LocalDateTime completedDate;

	@Column(length = 20)
	private String status;

	private Double subtotal;

	private Double taxAmount;

	private Double discountAmount;

	private Double discountPercentage;

	private Double totalAmount;

	private Double paidAmount;

	private Double changeAmount;

	@Column(length = 20)
	private String discountSource;

	private String promotionCode;

	private String promotionName;

	private String customerCode;

	private String customerName;

	private String cashierLogin;

	private String cashierName;

	private String sessionNumber;

	private String loyaltyCardNumber;

	private String loyaltyMemberName;

	private Integer loyaltyPointsEarned;

	private Integer loyaltyPointsRedeemed;

	private Double loyaltyDeductionAmount;

	private Boolean invoiced;

	private String invoiceNumber;

	private Integer tableNumber;

	@Column(length = 1000)
	private String notes;

	@OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoTicketLine> lines = new ArrayList<>();

	@OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoTicketPayment> payments = new ArrayList<>();
}
