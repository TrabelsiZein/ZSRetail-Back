package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoTicketRepository extends _BaseRepository<HoTicket, Long> {

	Optional<HoTicket> findByStoreIdAndSalesNumber(Long storeId, String salesNumber);
}
