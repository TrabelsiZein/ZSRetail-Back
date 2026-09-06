package com.digithink.zsretail.service;

import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.model._BaseEntity;

/**
 * Base service interface providing common CRUD operations
 * @param <T> Entity type extending _BaseEntity
 * @param <ID> ID type (usually Long)
 */
public interface __BaseService<T extends _BaseEntity, ID> {

	public List<T> findAll();

	public Optional<T> findById(ID id);

	public List<T> findByField(String fieldName, String operation, Object value);

	public T save(T entity) throws Exception;

	public void deleteById(ID id);
	
	public long count();
}
