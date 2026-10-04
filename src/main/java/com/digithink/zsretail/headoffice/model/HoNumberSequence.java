package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Step 7A: a document sequence of the head office, one row per code (BL). A number is taken by incrementing the row
 * inside the caller's transaction: the row stays locked until that transaction ends, so two numbers are never the same,
 * and a transaction that rolls back gives its number back. Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_number_sequence",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_number_sequence_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoNumberSequence extends _BaseEntity {

	@Column(nullable = false, length = 20)
	private String code;

	@Column(name = "last_value", nullable = false)
	private long lastValue;
}
