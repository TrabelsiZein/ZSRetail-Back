package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.model._BaseEntity;

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
