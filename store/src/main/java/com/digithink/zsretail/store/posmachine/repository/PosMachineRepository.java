package com.digithink.zsretail.store.posmachine.repository;

import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.posmachine.model.PosMachine;

/** The store's POS machines (RFID step 3; every store since 3.2.0). */
public interface PosMachineRepository extends _BaseRepository<PosMachine, Long> {

	/** The machine of this name, any case. */
	Optional<PosMachine> findFirstByNameIgnoreCase(String name);

	/** The machine at this address (normalised, PosMachineAddresses.normalise). */
	Optional<PosMachine> findFirstByIpAddress(String ipAddress);

	/** The machines that read with this RFID reader. */
	List<PosMachine> findByReader_Id(Long readerId);
}
