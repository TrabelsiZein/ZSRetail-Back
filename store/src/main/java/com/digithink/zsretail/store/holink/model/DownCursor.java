package com.digithink.zsretail.store.holink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.DataDomain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Copies down (step 3): the cursor of the last page applied, per domain. Made by the head office from its own data; the
 * store saves it as received and sends it back unchanged. Saved only after the page is applied: a page whose cursor
 * could not be saved is pulled and applied again, which changes nothing.
 */
@Entity
@Table(name = "hol_down_cursor", uniqueConstraints = @UniqueConstraint(name = "uk_hol_down_cursor_domain",
		columnNames = "domain"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class DownCursor extends _BaseEntity {

	public static final int CURSOR_LENGTH = 200;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DataDomain domain;

	@Column(name = "cursor_value", nullable = false, length = CURSOR_LENGTH)
	private String cursorValue;
}
