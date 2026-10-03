package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.HoLoyaltyAlias;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoLoyaltyAliasRepository extends _BaseRepository<HoLoyaltyAlias, Long> {

	Optional<HoLoyaltyAlias> findByCardNumber(String cardNumber);
}
