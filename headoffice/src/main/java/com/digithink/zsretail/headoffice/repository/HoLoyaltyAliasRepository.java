package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoLoyaltyAlias;

public interface HoLoyaltyAliasRepository extends _BaseRepository<HoLoyaltyAlias, Long> {

	Optional<HoLoyaltyAlias> findByCardNumber(String cardNumber);
}
