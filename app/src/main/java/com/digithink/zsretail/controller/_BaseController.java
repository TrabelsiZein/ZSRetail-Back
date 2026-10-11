package com.digithink.zsretail.controller;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.service.__BaseService;

import lombok.extern.log4j.Log4j2;

/**
 * Base controller providing common CRUD operations for all entities
 * @param <T> Entity type extending _BaseEntity
 * @param <ID> ID type (usually Long)
 * @param <S> Service type extending __BaseService
 */
@RestController
@Log4j2
public abstract class _BaseController<T extends _BaseEntity, ID, S extends __BaseService<T, ID>> {

	@Autowired
	protected S service;

	/**
	 * Get all entities
	 */
	@GetMapping
	public ResponseEntity<?> getAll() {
		try {
			log.info(this.getClass().getSimpleName() + "::getAll");
			return ResponseEntity.ok(service.findAll());
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::getAll:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Get entity by ID
	 */
	@GetMapping("/{id}")
	public ResponseEntity<?> getById(@PathVariable ID id) {
		try {
			log.info(this.getClass().getSimpleName() + "::getById::" + id);
			Optional<T> entity = service.findById(id);
			return entity.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::getById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Find entities by field
	 */
	@GetMapping("/findByField")
	public ResponseEntity<?> findByField(@RequestParam String fieldName, @RequestParam String operation,
			@RequestParam Object value) {
		try {
			log.info(this.getClass().getSimpleName() + "::findByField::" + fieldName + operation + value.toString());
			return ResponseEntity.ok(service.findByField(fieldName, operation, value));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::findByField:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Create new entity
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody T entity) {
		try {
			log.info(this.getClass().getSimpleName() + "::create");
			T createdEntity = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(createdEntity);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::create:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Update existing entity
	 */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable ID id, @RequestBody T entity) {
		try {
			log.info(this.getClass().getSimpleName() + "::update::" + id);
			Optional<T> existingEntity = service.findById(id);
			if (!existingEntity.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			entity.setId(existingEntity.get().getId());
			T updatedEntity = service.save(entity);
			return ResponseEntity.ok(updatedEntity);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::update:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Delete entity by ID
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable ID id) {
		try {
			log.info(this.getClass().getSimpleName() + "::deleteById::" + id);
			service.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::deleteById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Check if entity exists by ID
	 */
	@GetMapping("/{id}/exists")
	public ResponseEntity<?> existsById(@PathVariable ID id) {
		try {
			log.info(this.getClass().getSimpleName() + "::existsById::" + id);
			Optional<T> entity = service.findById(id);
			return ResponseEntity.ok(entity.isPresent());
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::existsById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Get count of all entities
	 */
	@GetMapping("/count")
	public ResponseEntity<?> count() {
		try {
			log.info(this.getClass().getSimpleName() + "::count");
			return ResponseEntity.ok(service.count());
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::count:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Extract detailed error message from exception
	 */
	protected String getDetailedMessage(Throwable e) {
		Throwable cause = e.getCause();
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause != null ? cause.getLocalizedMessage() : e.getLocalizedMessage();
	}

	/**
	 * Create error response object
	 */
	protected Object createErrorResponse(String message) {
		return message;
	}
}