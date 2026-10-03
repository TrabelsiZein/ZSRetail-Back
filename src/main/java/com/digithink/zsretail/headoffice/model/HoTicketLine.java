package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** One line of a {@link HoTicket} (task 2.3). Replaced with its ticket. */
@Entity
@Table(name = "ho_ticket_line")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoTicketLine extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "ticket_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoTicket ticket;

	@Column(nullable = false)
	private Integer lineNo;

	private String itemCode;

	private String itemName;

	private Integer quantity;

	private Double unitPrice;

	private Double unitPriceIncludingVat;

	private Integer vatPercent;

	private Double vatAmount;

	private Double discountPercentage;

	private Double discountAmount;

	@Column(length = 20)
	private String discountSource;

	private String promotionCode;

	private Double lineTotal;

	private Double lineTotalIncludingVat;
}
