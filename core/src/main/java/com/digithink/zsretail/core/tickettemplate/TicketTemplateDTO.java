package com.digithink.zsretail.core.tickettemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.5.0, ticket templates step 2: a document's template as the page and the till read it, and as the page sends it
 * (PUT /ticket-templates/{type}): the paper width, the size level, compact, the sections in print order with their
 * elements' switches. Every section and element of the catalogue is there, in order (TicketTemplateService.normalise).
 */
@Data
@NoArgsConstructor
public class TicketTemplateDTO {

	private String documentType;
	private Integer paperWidth;
	private String fontSize;
	private Boolean compact;
	private Boolean hideZeroRows;
	private List<SectionDTO> sections = new ArrayList<>();
	/** Read only: LOCAL, or HEAD_OFFICE for a copy received (step 5). */
	private String origin;
	/** Read only: when and by whom the row was last saved; null for the default. */
	@JsonFormat(pattern = "yyyy-MM-dd | HH:mm:ss")
	private LocalDateTime updatedAt;
	private String updatedBy;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class SectionDTO {
		private String code;
		private Boolean on;
		private List<ElementDTO> elements = new ArrayList<>();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ElementDTO {
		private String code;
		private Boolean on;
	}
}
