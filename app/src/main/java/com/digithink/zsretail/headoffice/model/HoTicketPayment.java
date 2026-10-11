package com.digithink.zsretail.headoffice.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

/** One payment of a {@link HoTicket} (task 2.3). Replaced with its ticket. */
@Entity
@Table(name = "ho_ticket_payment")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoTicketPayment extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "ticket_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoTicket ticket;

	/** 1, 2, 3... in the order received. */
	@Column(nullable = false)
	private Integer lineNo;

	private String paymentMethodCode;

	private String paymentMethodName;

	private Double amount;

	private LocalDateTime paymentDate;

	private String titleNumber;

	private LocalDate dueDate;
}
