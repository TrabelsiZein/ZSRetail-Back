package com.digithink.zsretail.headoffice.model;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.2.0: what the store expected for one scanned-tickets method of a {@link HoSession} (SessionScannedCopyDTO): the
 * number of tickets, their face total and their net. Replaced with its session. A session copied without them (a store
 * before 3.2.0, or sent before) has none: its scanned-ticket differences are not shown.
 */
@Entity
@Table(name = "ho_session_scanned")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoSessionScanned extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ho_session_scanned_session"))
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private HoSession session;

	private String paymentMethodCode;

	private Integer ticketCount;

	private Double faceTotal;

	private Double netTotal;
}
