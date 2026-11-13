package com.digithink.zsretail.store.posmachine.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.posmachine.model.SessionPosMachine;

/** The POS machine of each session that has one (3.2.0, POS machines step 2), by the session's id. */
public interface SessionPosMachineRepository extends JpaRepository<SessionPosMachine, Long> {

	/** The machines of these sessions (a page of a list), those that have one. */
	List<SessionPosMachine> findBySessionIdIn(Collection<Long> sessionIds);

	/** True when a session was opened on this machine (it cannot be deleted then). */
	boolean existsByPosMachine_Id(Long posMachineId);

	/** A session of this status on this machine (OPENED: the session that holds it). */
	Optional<SessionPosMachine> findFirstByPosMachine_IdAndSession_Status(Long posMachineId, SessionStatus status);
}
