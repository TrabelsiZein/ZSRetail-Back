package com.digithink.zsretail.store.rfid.repository;

import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.rfid.model.PosMachine;

/** RFID step 3: the store's POS machines; exists only with rfid.enabled=true. */
@ConditionalOnInstallation(Installation.RFID)
public interface PosMachineRepository extends _BaseRepository<PosMachine, Long> {

	/** The machine of this name, any case. */
	Optional<PosMachine> findFirstByNameIgnoreCase(String name);

	/** The machine at this address (normalised, RfidAddresses.normalise). */
	Optional<PosMachine> findFirstByIpAddress(String ipAddress);

	/** The machines that read with this reader. */
	List<PosMachine> findByReader_Id(Long readerId);
}
