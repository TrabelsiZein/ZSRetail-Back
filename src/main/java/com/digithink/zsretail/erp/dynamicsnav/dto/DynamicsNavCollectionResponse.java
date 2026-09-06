package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavCollectionResponse<T> {

	@JsonProperty("value")
	private List<T> value;

	@JsonProperty("@odata.nextLink")
	private String nextLink;
}

