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
 * A store's session closing as copied to the head office (task 2.3). One row per store + session number; the copy
 * sent when the session is TERMINATED replaces the one sent when it was CLOSED, count lines included.
 */
@Entity
@Table(name = "ho_session",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_session_store_number", columnNames = { "store_id", "session_number" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoSession extends _BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "store_id", nullable = false)
	@JsonIgnore
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Store store;

	@Column(name = "session_number", nullable = false)
	private String sessionNumber;

	@Column(length = 20)
	private String status;

	private String cashierLogin;

	private String cashierName;

	private LocalDateTime openedAt;

	private LocalDateTime closedAt;

	private Double openingCash;

	private Double realCash;

	private Double posUserClosureCash;

	private Double responsibleClosureCash;

	private String verifiedByLogin;

	private String verifiedByName;

	private LocalDateTime verifiedAt;

	@Column(length = 1000)
	private String verificationNotes;

	@OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<HoSessionCount> counts = new ArrayList<>();
}
