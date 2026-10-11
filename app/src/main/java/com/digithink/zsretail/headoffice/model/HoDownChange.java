package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Copies down (step 3): the last change of a record of a domain owned by the head office, for one store or for every
 * store (store_id null). One row per (domain, code, store), its change number moved at each change. A store pulls the
 * codes whose change number comes after its cursor, on its own row or the every-store row. A change of a record's
 * targets touches the rows of the stores it had and of the stores it has, so a store taken off gets the code too (as
 * removed). Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_down_change",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_down_change", columnNames = { "domain", "record_code",
				"store_id" }),
		indexes = @Index(name = "ix_ho_down_change_version", columnList = "domain,change_version"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoDownChange extends _BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DataDomain domain;

	/** Business code of the record, e.g. the promotion code. */
	@Column(name = "record_code", nullable = false, length = 100)
	private String recordCode;

	/** ho_store id; null for every store, including stores created later. */
	@Column(name = "store_id")
	private Long storeId;

	/** Change number from ho_down_sequence: the cursor a store sends back. */
	@Column(name = "change_version", nullable = false)
	private long changeVersion;
}
