package com.digithink.zsretail.store.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.holink.model.DownCursor;

public interface DownCursorRepository extends _BaseRepository<DownCursor, Long> {

	Optional<DownCursor> findByDomain(DataDomain domain);
}
