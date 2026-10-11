package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Copies down (step 3): the last change number of a domain. A change increments it inside its own transaction, so the
 * row stays locked until that transaction commits: changes of one domain get their numbers in commit order, and a
 * pull that reads the number reads a value whose changes are all committed. Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_down_sequence",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_down_sequence_domain", columnNames = "domain"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoDownSequence extends _BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DataDomain domain;

	@Column(name = "last_version", nullable = false)
	private long lastVersion;
}
