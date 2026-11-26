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
 * 3.5.0, ticket templates step 2: the template of one document type (docs/modules/ticket-templates.md). No row = the
 * built-in default, today's ticket; "Réinitialiser" deletes the row. Levels only, never a CSS value, so that a printer's
 * renderer (phase 2) reads the same row. Core's: a store and a head office have the table (the head office sends its
 * templates down at step 5). Created by db/3.5.0/update.sql. Read and written by TicketTemplateService only.
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

	/** 58 or 80 (mm); a POS machine's own width wins when it has one. */
	@Column(name = "paper_width", nullable = false)
	private Integer paperWidth;

	/** SMALL, NORMAL (today's) or LARGE. */
	@Column(name = "font_size", nullable = false, length = 10)
	private String fontSize;

	/** The gaps removed (the renderer's spacing). */
	@Column(name = "compact", nullable = false)
	private Boolean compact;

	/** The internal tickets of step 4: the rows at zero hidden. False on a customer ticket. */
	@Column(name = "hide_zero_rows", nullable = false)
	private Boolean hideZeroRows;

	/** JSON: [{"code", "on", "elements": [{"code", "on"}]}], the sections in print order (TicketBlocks). */
	@Column(name = "blocks", columnDefinition = "nvarchar(max)")
	private String blocks;

	/** The JSON's format (1), so that a later version reads an older row. */
	@Column(name = "format_version", nullable = false)
	private Integer formatVersion;

	/** LOCAL, or HEAD_OFFICE for a copy received (step 5). */
	@Column(name = "origin", nullable = false, length = 20)
	private String origin;
}
