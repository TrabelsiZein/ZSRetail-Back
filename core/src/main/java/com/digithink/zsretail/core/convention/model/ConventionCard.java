package com.digithink.zsretail.core.convention.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 3.6.0, conventions step 1 (docs/roadmap/conventions.md): a member's card. The number is unique in the whole
 * installation, not per convention (the till finds the card by its number alone; step 6 copies the cards down by
 * number). The holder's name, phone and employee number are optional information, never checked (decision 5). Never
 * deleted: blocked. A card has no origin of its own: it follows its convention.
 */
@Entity
@Table(name = "convention_card",
		uniqueConstraints = @UniqueConstraint(name = "uk_convention_card_number", columnNames = "card_number"),
		indexes = @Index(name = "ix_convention_card_convention", columnList = "convention_id, status"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ConventionCard extends _BaseEntity {

	/** Decision 6: A-Z, 0-9 and "-", trimmed and upper-cased by the service. */
	public static final int NUMBER_LENGTH = 40;
	public static final int HOLDER_NAME_LENGTH = 150;
	public static final int HOLDER_PHONE_LENGTH = 30;
	public static final int HOLDER_EMPLOYEE_NO_LENGTH = 50;

	/** Fixed once created. */
	@Nationalized
	@Column(name = "card_number", nullable = false, length = NUMBER_LENGTH)
	private String cardNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "convention_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_convention_card_convention"))
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Convention convention;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "nvarchar(20)")
	private ConventionCardStatus status = ConventionCardStatus.ACTIVE;

	@Nationalized
	@Column(name = "holder_name", length = HOLDER_NAME_LENGTH)
	private String holderName;

	/** Never written in an event's values (decision 15). */
	@Nationalized
	@Column(name = "holder_phone", length = HOLDER_PHONE_LENGTH)
	private String holderPhone;

	@Nationalized
	@Column(name = "holder_employee_no", length = HOLDER_EMPLOYEE_NO_LENGTH)
	private String holderEmployeeNo;

	/** The first export to Excel (decision 8: "only the cards never exported"); empty = never exported. */
	@Column(name = "exported_at")
	private LocalDateTime exportedAt;
}
