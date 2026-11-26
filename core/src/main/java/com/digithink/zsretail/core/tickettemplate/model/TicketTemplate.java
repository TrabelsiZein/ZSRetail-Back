package com.digithink.zsretail.core.tickettemplate.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.5.0, ticket templates (docs/modules/ticket-templates.md): the template of one document type. No row = the built-in
 * default, today's ticket; "Réinitialiser" deletes the row. Format 2 (step 2b): two JSON columns, levels only, never a
 * CSS value, so that a printer's renderer (phase 2) reads the same row. Core's: a store and a head office have the
 * table (the head office sends its templates down at step 5). Created by db/3.5.0/update.sql. Read and written by
 * TicketTemplateService only.
 */
@Entity
@Table(name = "ticket_template", uniqueConstraints = @UniqueConstraint(name = "uk_ticket_template_document_type",
		columnNames = "document_type"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class TicketTemplate extends _BaseEntity {

	/** The document (TicketDocumentType's name): SALE at step 2. */
	@Column(name = "document_type", nullable = false, length = 40)
	private String documentType;

	/**
	 * JSON: the page's layout, {"paperWidth", "fontSize", "spacing", "feedLines", "separator"} (TicketBlocks.layout of
	 * the document). Format 1 rows had columns; db/3.5.0 moved them here.
	 */
	@Column(name = "layout", columnDefinition = "nvarchar(max)")
	private String layout;

	/** JSON: [{"code", "on", "elements": [{"code", "on", "options": {...}}]}], the sections in print order. */
	@Column(name = "blocks", columnDefinition = "nvarchar(max)")
	private String blocks;

	/** The JSON's format (2 since step 2b), so that a later version reads an older row. */
	@Column(name = "format_version", nullable = false)
	private Integer formatVersion;

	/** LOCAL, or HEAD_OFFICE for a copy received (step 5). */
	@Column(name = "origin", nullable = false, length = 20)
	private String origin;
}
