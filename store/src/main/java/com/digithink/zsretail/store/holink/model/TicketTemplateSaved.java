package com.digithink.zsretail.store.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.5.0, ticket templates step 5: a template of this store's own, kept aside when its head office started managing its
 * templates (ticket_template_saved, db/3.5.0): its row as it was, and when it was put aside. For a recovery by hand only:
 * the application writes this table and never reads it.
 */
@Entity
@Table(name = "ticket_template_saved")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class TicketTemplateSaved extends _BaseEntity {

	@Column(name = "document_type", nullable = false, length = 40)
	private String documentType;

	@Column(name = "layout", columnDefinition = "nvarchar(max)")
	private String layout;

	@Column(name = "blocks", columnDefinition = "nvarchar(max)")
	private String blocks;

	@Column(name = "format_version", nullable = false)
	private Integer formatVersion;

	@Column(name = "origin", nullable = false, length = 20)
	private String origin;

	/** The template's own last change (ticket_template.updated_at / updated_by). */
	@Column(name = "template_updated_at")
	private LocalDateTime templateUpdatedAt;

	@Column(name = "template_updated_by")
	private String templateUpdatedBy;

	/** When it was put aside (the head office's management turned on). */
	@Column(name = "saved_at", nullable = false)
	private LocalDateTime savedAt;
}
