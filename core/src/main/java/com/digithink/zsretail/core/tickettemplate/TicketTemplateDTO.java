package com.digithink.zsretail.core.tickettemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.5.0, ticket templates: a document's template as the page and the till read it, and as the page sends it
 * (PUT /ticket-templates/{type}). Format 2 (step 2b): the layout ({"paperWidth", "fontSize", "spacing", "feedLines",
 * "separator"}) and the sections in print order with their elements' switches and options. Every layout option, section,
 * element and element option of the catalogue is there, in order (TicketTemplateService.normalise).
 */
@Data
@NoArgsConstructor
public class TicketTemplateDTO {

	private String documentType;
	/** Read only: the format (2). */
	private Integer formatVersion;
	private Map<String, Object> layout = new LinkedHashMap<>();
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
		/** Step 2b: the element's options by key (none: omitted). */
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		private Map<String, Object> options = new LinkedHashMap<>();

		public ElementDTO(String code, Boolean on) {
			this(code, on, new LinkedHashMap<>());
		}
	}
}
