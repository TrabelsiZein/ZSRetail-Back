package com.digithink.zsretail.erp.navpospages.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/** One OData V4 answer of a page: its rows and, when there are more, the next link. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NavPosCollection<T> {

	@JsonProperty("value")
	private List<T> value;

	@JsonProperty("@odata.nextLink")
	private String nextLink;
}
