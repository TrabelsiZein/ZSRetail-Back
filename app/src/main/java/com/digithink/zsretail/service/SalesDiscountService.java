package com.digithink.zsretail.service;

import javax.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.model.SalesDiscount;
import com.digithink.zsretail.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.model.enumeration.SalesDiscountType;
import com.digithink.zsretail.repository.SalesDiscountRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class SalesDiscountService extends _BaseService<SalesDiscount, Long> {

	@Autowired
	private SalesDiscountRepository salesDiscountRepository;

	@Override
	protected _BaseRepository<SalesDiscount, Long> getRepository() {
		return salesDiscountRepository;
	}

	/**
	 * Get SalesDiscountRepository specifically (for accessing in controller)
	 */
	public SalesDiscountRepository getSalesDiscountRepository() {
		return salesDiscountRepository;
	}

	/**
	 * Get paginated sales discounts with optional search criteria
	 */
	public Page<SalesDiscount> findAllPaginated(int page, int size, String searchTerm) {
		Pageable pageable = PageRequest.of(page, size);
		
		if (StringUtils.hasText(searchTerm)) {
			Specification<SalesDiscount> spec = buildSearchSpecification(searchTerm);
			return salesDiscountRepository.findAll(spec, pageable);
		}
		
		return salesDiscountRepository.findAll(pageable);
	}

	/**
	 * Build JPA Specification for search across multiple fields
	 */
	private Specification<SalesDiscount> buildSearchSpecification(String searchTerm) {
		return (root, query, criteriaBuilder) -> {
			String searchPattern = "%" + searchTerm.toLowerCase() + "%";
			
			// String fields only: LIKE cannot be used on enums (type, salesType) - would cause parameter type mismatch
			Predicate codePredicate = criteriaBuilder.like(
				criteriaBuilder.lower(root.get("code")), searchPattern);
			Predicate salesCodePredicate = criteriaBuilder.like(
				criteriaBuilder.lower(root.get("salesCode")), searchPattern);
			Predicate responsibilityCenterPredicate = criteriaBuilder.like(
				criteriaBuilder.lower(root.get("responsibilityCenter")), searchPattern);
			
			// type and salesType are enums: match only when search term equals an enum name or display name
			Predicate typePredicate = null;
			SalesDiscountType matchedType = SalesDiscountType.fromString(searchTerm.trim());
			if (matchedType != null) {
				typePredicate = criteriaBuilder.equal(root.get("type"), matchedType);
			}
			Predicate salesTypePredicate = null;
			SalesDiscountSalesType matchedSalesType = SalesDiscountSalesType.fromString(searchTerm.trim());
			if (matchedSalesType != null) {
				salesTypePredicate = criteriaBuilder.equal(root.get("salesType"), matchedSalesType);
			}
			
			// For numeric fields, try to parse and match
			Predicate lineDiscountPredicate = null;
			try {
				Double discount = Double.parseDouble(searchTerm);
				lineDiscountPredicate = criteriaBuilder.equal(root.get("lineDiscount"), discount);
			} catch (NumberFormatException e) {
				// Ignore if not a number
			}
			
			// Combine string predicates with OR
			Predicate combinedPredicate = criteriaBuilder.or(
				codePredicate,
				salesCodePredicate,
				responsibilityCenterPredicate
			);
			if (typePredicate != null) {
				combinedPredicate = criteriaBuilder.or(combinedPredicate, typePredicate);
			}
			if (salesTypePredicate != null) {
				combinedPredicate = criteriaBuilder.or(combinedPredicate, salesTypePredicate);
			}
			if (lineDiscountPredicate != null) {
				combinedPredicate = criteriaBuilder.or(combinedPredicate, lineDiscountPredicate);
			}
			
			return combinedPredicate;
		};
	}
}

