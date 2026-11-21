package com.digithink.zsretail.core.communication.service;

import java.time.LocalDateTime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.repository.CommunicationStateRepository;
import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.utils.ErrorTexts;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 8e: the one line of state written at a start with the module off (every installation):
 * the mark MODULE_ON of comm_state set to 0 when it said 1. The next start with the module on then moves the activation
 * instant to that moment ({@link CommunicationStates#startedOn}): nothing that happened while it was off is ever sent.
 * An installation that never had the module on writes nothing. A failure (no comm_state table) never stops the start.
 */
@Component
@Log4j2
public class CommunicationOffMark {

	private final java.util.function.BooleanSupplier moduleOn;
	private final CommunicationStateRepository states;

	@org.springframework.beans.factory.annotation.Autowired
	public CommunicationOffMark(ApplicationModeService mode, CommunicationStateRepository states) {
		this(mode::isCommunicationEnabled, states);
	}

	/** With the module's switch given: used by the tests. */
	public CommunicationOffMark(java.util.function.BooleanSupplier moduleOn, CommunicationStateRepository states) {
		this.moduleOn = moduleOn;
		this.states = states;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void markOff() {
		if (moduleOn.getAsBoolean()) {
			return;
		}
		try {
			states.findByCode(CommunicationStates.MODULE_ON).filter(mark -> Long.valueOf(1L).equals(mark.getIdValue()))
					.ifPresent(this::off);
		} catch (RuntimeException e) {
			log.debug("Communication: the off mark not written ({})", ErrorTexts.cause(e));
		}
	}

	private void off(CommunicationState mark) {
		mark.setIdValue(0L);
		mark.setTimeValue(LocalDateTime.now());
		states.save(mark);
		log.info("Communication: the module is off; switched on again, it will start from that moment");
	}
}
