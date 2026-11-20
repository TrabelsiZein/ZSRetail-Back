package com.digithink.zsretail.core.link.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication step 6: the head office's answer to a batch of events. taken false: the head office takes no
 * events (its communication module is off); the store pauses its push, nothing is counted. Otherwise one result per
 * event, by dedup key: accepted (stored, or already there) or refused with a message.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommunicationEventAnswerDTO {

	private boolean taken;
	private List<Result> results = new ArrayList<>();

	/** One event's answer. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Result {
		private String dedupKey;
		private boolean accepted;
		/** Why it was refused; for an accepted event, e.g. "already at the head office" or null. */
		private String message;
	}

	public static CommunicationEventAnswerDTO notTaken() {
		return new CommunicationEventAnswerDTO(false, new ArrayList<>());
	}
}
