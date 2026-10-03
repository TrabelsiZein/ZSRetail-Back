package com.digithink.zsretail.headoffice.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.repository._BaseRepository;

public interface StoreRepository extends _BaseRepository<Store, Long> {

	Optional<Store> findByCodeIgnoreCase(String code);

	/**
	 * Heartbeat (task 1.4): writes lastContact and appVersion only, by id. A bulk update skips {@code @PreUpdate},
	 * so updatedAt is unchanged. Returns the number of rows updated.
	 */
	@Modifying
	@Query("update Store s set s.lastContact = :lastContact, s.appVersion = :appVersion where s.id = :id")
	int updateContact(@Param("id") Long id, @Param("lastContact") LocalDateTime lastContact,
			@Param("appVersion") String appVersion);
}
