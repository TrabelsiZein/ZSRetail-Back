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
	 * The instant of the first start with the module on: written at the first call, then only read. Turning the module
	 * off and on again does not move it; nothing older is ever picked.
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
