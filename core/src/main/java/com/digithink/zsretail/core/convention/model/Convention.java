package com.digithink.zsretail.core.convention.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.enumeration.ConventionState;
import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.6.0, conventions step 1 (docs/roadmap/conventions.md): an agreement with an organisation whose members get a
 * benefit at the till (the benefit itself is a promotion reserved to the convention, step 2). Core: a store and a head
 * office both have the table (db/3.6.0/update.sql, in Hibernate's form: ConventionTableTest).
 * <p>
 * Every text column is nvarchar (the driver sends nvarchar parameters: a varchar column would turn a lookup into a scan).
 * {@code status} is the truth: the inherited {@code active} is written true and never read. Never deleted once it has a
 * card: it is suspended.
 */
@Entity
@Table(name = "convention", uniqueConstraints = @UniqueConstraint(name = "uk_convention_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Convention extends _BaseEntity {

	public static final int CODE_LENGTH = 30;
	public static final int NAME_LENGTH = 150;
	public static final int PREFIX_LENGTH = 30;
	/** Decision 6: a generated number is the prefix, "-" and 5 digits. */
	public static final int DEFAULT_CARD_DIGITS = 5;

	/** A-Z, 0-9 and "-" (checked by the service); fixed once created: the copies down go by code (step 6). */
	@Nationalized
	@Column(name = "code", nullable = false, length = CODE_LENGTH)
	private String code;

	/** The convention's own label. */
	@Nationalized
	@Column(name = "name", nullable = false, length = NAME_LENGTH)
	private String name;

	/** The organisation that signed (decision 10: required). */
	@Nationalized
	@Column(name = "organisation", nullable = false, length = NAME_LENGTH)
	private String organisation;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	/** Empty: no end. */
	@Column(name = "end_date")
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "nvarchar(20)")
	private ConventionStatus status = ConventionStatus.ACTIVE;

	@Enumerated(EnumType.STRING)
	@Column(name = "recognition", nullable = false, columnDefinition = "nvarchar(20)")
	private ConventionRecognition recognition = ConventionRecognition.CARDS;

	/** Decision 12, counted per card (per member number with NO_CARD) from the tickets; empty = no limit. */
	@Column(name = "max_uses_per_day")
	private Integer maxUsesPerDay;

	@Column(name = "max_uses_per_month")
	private Integer maxUsesPerMonth;

	/** The total paid of the validated tickets carrying the card, returns given back; empty = no limit. */
	@Column(name = "max_amount_per_month")
	private Double maxAmountPerMonth;

	/** The generated numbers' prefix; empty = the code. Fixed once the convention has a card. */
	@Nationalized
	@Column(name = "card_prefix", length = PREFIX_LENGTH)
	private String cardPrefix;

	/** The digits of a generated number after the prefix and "-". Fixed once the convention has a card. */
	@Column(name = "card_digits", nullable = false)
	private Integer cardDigits = DEFAULT_CARD_DIGITS;

	/**
	 * The generation counter: the last sequence used, taken under a row lock in the generation's transaction (never read
	 * from the highest card number). Written by the generation only, never read from a request.
	 */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@Column(name = "last_card_seq", nullable = false)
	private Long lastCardSeq = 0L;

	/** LOCAL, or HEAD_OFFICE for a copy received (step 6). Never read from a request. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@Enumerated(EnumType.STRING)
	@Column(name = "origin", nullable = false, columnDefinition = "nvarchar(20)")
	private RecordOrigin origin = RecordOrigin.LOCAL;

	/** Part B4: the prefix of a generated number, the card prefix or, empty, the code. Not a property. */
	public String prefixInEffect() {
		return cardPrefix == null || cardPrefix.trim().isEmpty() ? code : cardPrefix;
	}

	/**
	 * Part B3: what the convention is on this day (the server's date, never the browser's): SUSPENDED as stored, else
	 * UPCOMING before its start, EXPIRED after its end, ACTIVE within them (both days included). Not a property: never
	 * stored, never read from a request.
	 */
	public ConventionState stateOn(LocalDate day) {
		if (status == ConventionStatus.SUSPENDED) {
			return ConventionState.SUSPENDED;
		}
		if (startDate != null && day.isBefore(startDate)) {
			return ConventionState.UPCOMING;
		}
		if (endDate != null && day.isAfter(endDate)) {
			return ConventionState.EXPIRED;
		}
		return ConventionState.ACTIVE;
	}
}
