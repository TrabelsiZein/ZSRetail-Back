package com.digithink.zsretail.core.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.UserAccount;

public interface UserAccountRepository extends _BaseRepository<UserAccount, Long> {

	Optional<UserAccount> findByUsername(String username);

	Optional<UserAccount> findByBadgeCode(String badgeCode);

}
