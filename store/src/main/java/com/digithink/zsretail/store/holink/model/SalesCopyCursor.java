package com.digithink.zsretail.store.holink.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.store.holink.enumeration.SalesCopyType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * How far the search for new and changed documents has read, per document type (task 2.1): the change time
 * (updated_at) and id of the last document read. The next search reads only what comes after, so a cycle with nothing
 * to send does not re-read the history.
 */
@Entity
@Table(name = "hol_sales_cursor",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_sales_cursor_type", columnNames = "document_type"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesCopyCursor extends _BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 10)
	private SalesCopyType documentType;

	@Column(name = "last_changed_at", nullable = false)
	private LocalDateTime lastChangedAt;

	@Column(name = "last_local_id", nullable = false)
	private Long lastLocalId;
}
