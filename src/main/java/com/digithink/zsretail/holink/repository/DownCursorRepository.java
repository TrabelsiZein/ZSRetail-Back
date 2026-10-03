package com.digithink.zsretail.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.holink.model.DownCursor;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository._BaseRepository;

public interface DownCursorRepository extends _BaseRepository<DownCursor, Long> {

	Optional<DownCursor> findByDomain(DataDomain domain);
}
