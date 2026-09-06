package com.digithink.zsretail.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.repository._BaseRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * Base service implementation providing common CRUD operations
 * 
 * @param <T>  Entity type extending _BaseEntity
 * @param <ID> ID type (usually Long)
 */
public abstract class _BaseService<T extends _BaseEntity, ID> implements __BaseService<T, ID> {

	@Autowired
	protected CurrentUserProvider currentUserProvider;

	protected abstract _BaseRepository<T, ID> getRepository();

	public List<T> findAll() {
		return getRepository().findAllByOrderByUpdatedAtDesc();
	}

	public Optional<T> findById(ID id) {
		return getRepository().findById(id);
	}

	public List<T> findByField(String fieldName, String operation, Object value) {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		Specification<T> specification = (root, query, criteriaBuilder) -> {
			Predicate predicate = null;
			switch (operation) {
			case "=":
				predicate = criteriaBuilder.equal(root.get(fieldName), value);
				break;
			case ">":
				predicate = criteriaBuilder.greaterThan(root.get(fieldName), (Comparable) value);
				break;
			case "<":
				predicate = criteriaBuilder.lessThan(root.get(fieldName), (Comparable) value);
				break;
			case ">=":
				predicate = criteriaBuilder.greaterThanOrEqualTo(root.get(fieldName), (Comparable) value);
				break;
			case "<=":
				predicate = criteriaBuilder.lessThanOrEqualTo(root.get(fieldName), (Comparable) value);
				break;
			case "!=":
			case "<>":
				predicate = criteriaBuilder.notEqual(root.get(fieldName), value);
				break;
			case "LIKE":
				predicate = criteriaBuilder.like(root.get(fieldName).as(String.class), "%" + value + "%");
				break;
			default:
				throw new IllegalArgumentException("Invalid operation: " + operation);
			}
			return predicate;
		};
		return getRepository().findAll(specification, Sort.by(Sort.Direction.DESC, "updatedAt"));

	}

	@Transactional
	public T save(T entity) throws Exception {
		String username = currentUserProvider.getCurrentUserName();

		if (entity.getId() == null) {
			entity.setCreatedBy(username);
			entity.setCreatedAt(LocalDateTime.now());
		} else {
			entity.setUpdatedBy(username);
			entity.setUpdatedAt(LocalDateTime.now());
		}

		return getRepository().save(entity);
	}

	public void deleteById(ID id) {
		getRepository().deleteById(id);
	}

	public long count() {
		return getRepository().count();
	}
}
