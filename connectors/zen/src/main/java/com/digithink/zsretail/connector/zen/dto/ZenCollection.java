package com.digithink.zsretail.connector.zen.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/** One OData V4 answer of a page of the API: its rows and, when there are more, the next link. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenCollection<T> {

	@JsonProperty("value")
	private List<T> value;

	@JsonProperty("@odata.nextLink")
	private String nextLink;
}
