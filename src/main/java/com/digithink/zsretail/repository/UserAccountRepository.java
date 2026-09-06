package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.UserAccount;

public interface UserAccountRepository extends _BaseRepository<UserAccount, Long> {

	Optional<UserAccount> findByUsername(String username);

	Optional<UserAccount> findByBadgeCode(String badgeCode);

}
