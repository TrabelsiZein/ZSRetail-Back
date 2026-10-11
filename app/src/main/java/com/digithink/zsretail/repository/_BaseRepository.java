package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import com.digithink.zsretail.model._BaseEntity;

/**
 * Base repository interface providing common methods for all entities
 * @param <T> Entity type extending _BaseEntity
 * @param <ID> ID type (usually Long)
 */
@NoRepositoryBean
public interface _BaseRepository<T extends _BaseEntity, ID>
		extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

	List<T> findAllByOrderByUpdatedAtDesc();
	
	List<T> findAllByOrderByCreatedAtDesc();
	
	List<T> findByActiveTrue();
	
	List<T> findByActiveFalse();
	
	Optional<T> findByIdAndActiveTrue(ID id);
	
	long countByActiveTrue();
	
	long countByActiveFalse();
	
	List<T> findByCreatedBy(String createdBy);
	
	List<T> findByUpdatedBy(String updatedBy);
	
	List<T> findByCreatedAtAfter(LocalDateTime date);
	
	List<T> findByCreatedAtBefore(LocalDateTime date);
}
