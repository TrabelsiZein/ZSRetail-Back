package com.digithink.zsretail.core.eventlog.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.4.0, event log: the last number given in each importance, one row per importance. A number is taken by adding 1
 * to the row in the transaction that writes the event (as ho_number_sequence): the row stays locked until it ends, two
 * events never get the same number, and an event not written gives its number back. Kept apart from the events: a row
 * deleted at the end of event_log still shows (the last number here is higher than the last row's).
 */
@Entity
@Table(name = "event_log_sequence",
		uniqueConstraints = @UniqueConstraint(name = "uk_event_log_sequence_importance", columnNames = "importance"))
@Data
@NoArgsConstructor
public class EventLogSequence {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "importance", nullable = false)
	private int importance;

	@Column(name = "last_value", nullable = false)
	private long lastValue;

	public EventLogSequence(int importance, long lastValue) {
		this.importance = importance;
		this.lastValue = lastValue;
	}
}
