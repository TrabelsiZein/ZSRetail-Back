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

/** One cash count line of a {@link HoSession} (task 2.3). Replaced with its session. */
@Entity
@Table(name = "ho_session_count")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoSessionCount extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoSession session;

	@Column(nullable = false)
	private Integer lineNo;

	@Column(length = 20)
	private String counterType;

	private String paymentMethodCode;

	private String paymentMethodName;

	private Double denominationValue;

	private Integer quantity;

	private Double lineTotal;

	private String referenceNumber;
}
