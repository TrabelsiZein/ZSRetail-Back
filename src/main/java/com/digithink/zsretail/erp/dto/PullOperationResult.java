package com.digithink.zsretail.erp.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result wrapper for pull operations, similar to ErpOperationResult for push operations.
 * Contains the fetched data along with metadata (URL and raw response) for logging purposes.
 */
@Getter
@AllArgsConstructor
public class PullOperationResult<T> {
	private List<T> data;
	private String url;
	private Object rawResponse;
}

