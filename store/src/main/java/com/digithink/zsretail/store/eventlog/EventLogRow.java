package com.digithink.zsretail.store.eventlog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 3.4.0, event log step 6: one line of the Journal page (and one row of its Excel file): an event, or a run of refused
 * logins shown as its last event with {@link #groupCount} and the run's first and last times. The amount is rounded to
 * 3 decimals (the column is a float: 21.899999...).
 */
@Data
public class EventLogRow {

	private static final String TIME = "yyyy-MM-dd'T'HH:mm:ss";

	private Long id;
	private int importance;
	private Long sequenceNo;
	private String code;
	/** The French label of the catalogue (EventCode); the screen names it in its own language. */
	private String codeLabel;
	private String subjectType;
	private String subjectId;
	private String subjectLabel;

	/** The event's own time (the till's clock of the gesture, else the server's): the page's order. */
	@JsonFormat(pattern = TIME)
	private LocalDateTime occurredAt;
	/** The server's clock when it was written (a till's event: when it arrived). */
	@JsonFormat(pattern = TIME)
	private LocalDateTime eventAt;
	/** The till's clock; null for a server's event. */
	@JsonFormat(pattern = TIME)
	private LocalDateTime frontAt;

	private Long posMachineId;
	private String posMachineName;
	private Long sessionId;
	private String sessionNumber;
	private String cartId;
	private Long userId;
	private String userLogin;
	private String userName;
	private String userRole;
	private Long authorisedById;
	private String authorisedByLogin;
	private String authorisedByName;
	private Long badgeOwnerId;
	private String badgeOwnerLogin;
	private String badgeOwnerName;
	private Long sentById;
	private String sentByLogin;
	private String clientAddress;
	private Long machineSequenceNo;
	private String installId;
	private String doubt;
	private String oldValue;
	private String newValue;
	private Double amount;
	private String details;
	private boolean imported;

	/** A run of refused logins: its key (GET /event-log?groupKey= gives its rows), how many, its first and last times. */
	private String groupKey;
	private long groupCount = 1;
	@JsonFormat(pattern = TIME)
	private LocalDateTime groupFirstAt;
	@JsonFormat(pattern = TIME)
	private LocalDateTime groupLastAt;

	/** 3 decimals, half up (21.899999999999999 is 21.9); null stays null. */
	public static Double rounded(Double value) {
		if (value == null || value.isNaN() || value.isInfinite()) {
			return value;
		}
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
