package com.digithink.zsretail.service;

import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.CatalogueKind;

/**
 * Head office plan, step 6: how the catalogue services call {@link CatalogueHeadOfficeHooks} around a save and a delete.
 * Without a hooks bean (every store, a head office with an ERP) the save and the delete run exactly as before.
 */
final class CatalogueHookCalls {

	private CatalogueHookCalls() {
	}

	/** A save that may throw, like _BaseService.save. */
	interface Save<T> {
		T save(T entity) throws Exception;
	}

	/** The hooks bean, or null (a provider missing in the tests that build the service by hand counts as none). */
	static CatalogueHeadOfficeHooks hooks(ObjectProvider<CatalogueHeadOfficeHooks> provider) {
		return provider == null ? null : provider.getIfAvailable();
	}

	/**
	 * beforeSave with the code in the database (null for a new record), the save, then afterSave. Called inside the
	 * service's transaction.
	 */
	static <T extends _BaseEntity> T save(CatalogueHeadOfficeHooks hooks, CatalogueKind kind, T entity,
			Function<Long, Optional<String>> storedCode, Save<T> save) throws Exception {
		if (hooks == null) {
			return save.save(entity);
		}
		String previousCode = entity.getId() == null ? null : storedCode.apply(entity.getId()).orElse(null);
		hooks.beforeSave(kind, previousCode, entity);
		T saved = save.save(entity);
		hooks.afterSave(kind, previousCode, saved);
		return saved;
	}
}
