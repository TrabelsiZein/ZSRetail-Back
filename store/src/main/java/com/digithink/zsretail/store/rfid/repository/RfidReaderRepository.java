package com.digithink.zsretail.store.rfid.repository;

import java.util.Optional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.rfid.model.RfidReader;

/** RFID step 3: the store's RFID readers; exists only with rfid.enabled=true. */
@ConditionalOnInstallation(Installation.RFID)
public interface RfidReaderRepository extends _BaseRepository<RfidReader, Long> {

	/** The reader of this name, any case. */
	Optional<RfidReader> findFirstByNameIgnoreCase(String name);

	/** The reader at this address and port. */
	Optional<RfidReader> findFirstByIpAddressAndPort(String ipAddress, Integer port);
}
