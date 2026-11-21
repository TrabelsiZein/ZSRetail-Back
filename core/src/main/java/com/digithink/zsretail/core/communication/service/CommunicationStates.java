package com.digithink.zsretail.core.communication.service;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.repository.CommunicationStateRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

/**
 * 3.3.0, communication module, step 2: the activation instant and the finders' cursors (comm_state). Only with the
 * module on. See docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
public class CommunicationStates {

	public static final String ACTIVE_SINCE = "ACTIVE_SINCE";

	private final CommunicationStateRepository states;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationStates(CommunicationStateRepository states) {
		this(states, LocalDateTime::now);
	}

	/** With a given clock: used by the tests. */
	public CommunicationStates(CommunicationStateRepository states, Supplier<LocalDateTime> clock) {
		this.states = states;
		this.clock = clock;
	}

	/**
	 * Step 8e: the module's state at the last start, idValue 1 on, 0 off (written by {@link #startedOn} and, with the
	 * module off, by CommunicationOffMark). Absent: never started with this version.
	 */
	public static final String MODULE_ON = "MODULE_ON";

	/**
	 * Step 8e, at each start with the module on: when the last start was off (or none is known: the first start, or the
	 * first with this version), the activation instant moves to now: the finders and the head office's receiver read
	 * nothing older, so nothing of an off time is ever sent. A start after a start with the module on keeps catching up.
	 * Returns the activation instant.
	 */
	public synchronized LocalDateTime startedOn() {
		CommunicationState mark = states.findByCode(MODULE_ON).orElse(null);
		if (mark != null && Long.valueOf(1L).equals(mark.getIdValue())) {
			return activeSince();
		}
		LocalDateTime now = clock.get();
		CommunicationState since = states.findByCode(ACTIVE_SINCE).orElseGet(() -> {
			CommunicationState state = new CommunicationState();
			state.setCode(ACTIVE_SINCE);
			return state;
		});
		since.setTimeValue(now);
		states.save(since);
		if (mark == null) {
			mark = new CommunicationState();
			mark.setCode(MODULE_ON);
		}
		mark.setIdValue(1L);
		mark.setTimeValue(now);
		states.save(mark);
		return now;
	}

	/**
	 * The instant the module was switched on (step 8e: at the last start after an off one; before: the first start).
	 * Written at the first call when absent; nothing older is ever picked.
	 */
	public synchronized LocalDateTime activeSince() {
		return states.findByCode(ACTIVE_SINCE).map(CommunicationState::getTimeValue).orElseGet(() -> {
			CommunicationState state = new CommunicationState();
			state.setCode(ACTIVE_SINCE);
			state.setTimeValue(clock.get());
			return states.save(state).getTimeValue();
		});
	}

	/** A finder's cursor; a new one starts at {@code start} (the activation instant), id 0. Saved by {@link #save}. */
	public CommunicationState cursor(String code, LocalDateTime start) {
		return states.findByCode(code).orElseGet(() -> {
			CommunicationState state = new CommunicationState();
			state.setCode(code);
			state.setTimeValue(start);
			state.setIdValue(0L);
			return state;
		});
	}

	public void save(CommunicationState state) {
		states.save(state);
	}
}
