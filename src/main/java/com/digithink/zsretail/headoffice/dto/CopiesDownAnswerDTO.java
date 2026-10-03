package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Answer of GET /ho/down/{domain} (step 3, copies down): the records of the domain changed for the calling store since
 * its cursor, the codes removed for it, the cursor to send back next time and whether more changes wait. A record is
 * the domain's copy (e.g. a promotion), by business codes only, never a database id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CopiesDownAnswerDTO {

	/** DataDomain name, e.g. PROMOTIONS. */
	private String domain;

	/** The records to save at the store, oldest change first. */
	private List<JsonNode> records = new ArrayList<>();

	/** Codes deleted at the head office, or no longer addressed to this store. */
	private List<String> removed = new ArrayList<>();

	/** Made by the head office from its own data; the store saves it and sends it back unchanged. */
	private String cursor;

	/** True when more changes wait after this page: the store pulls again at once. */
	private boolean more;
}
