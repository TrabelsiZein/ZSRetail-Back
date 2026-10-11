package com.digithink.zsretail.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class CustomerService extends _BaseService<Customer, Long> {

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private GeneralSetupService generalSetupService;

	// Cached default customer (volatile for thread safety)
	private volatile Customer cachedDefaultCustomer = null;

	@Override
	protected _BaseRepository<Customer, Long> getRepository() {
		return customerRepository;
	}

	/**
	 * Get CustomerRepository specifically (for accessing in controller)
	 */
	public CustomerRepository getCustomerRepository() {
		return customerRepository;
	}

	/**
	 * Override save to auto-generate customer code if not provided
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Customer save(Customer customer) throws Exception {
		// Validate required fields
		if (customer.getName() == null || customer.getName().trim().isEmpty()) {
			throw new IllegalArgumentException("Customer name is required");
		}
		if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
			throw new IllegalArgumentException("Customer phone is required");
		}

		// Generate customer code if not provided or empty (only for new customers)
		if (customer.getId() == null
				&& (customer.getCustomerCode() == null || customer.getCustomerCode().trim().isEmpty())) {
			customer.setCustomerCode(generateCustomerCode());
		} else if (customer.getId() == null && customer.getCustomerCode() != null
				&& !customer.getCustomerCode().trim().isEmpty()) {
			// Check if code already exists
			if (customerRepository.findByCustomerCode(customer.getCustomerCode()).isPresent()) {
				throw new IllegalArgumentException("Customer code already exists: " + customer.getCustomerCode());
			}
		}
		// For updates, keep existing code (don't regenerate)

		return super.save(customer);
	}

	/**
	 * Generate unique customer code: CUST-YYYYMMDD-XXX (incremental by day)
	 */
	private String generateCustomerCode() {
		// Count customers created today
		LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
		long count = customerRepository.countByCreatedAtGreaterThanEqual(todayStart);

		// Format: CUST-YYYYMMDD-XXX
		String dateStr = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
		String code = "CUST-" + dateStr + "-" + String.format("%03d", count + 1);

		log.info("Generated customer code: " + code);
		return code;
	}

	/**
	 * Search customers by name, code, phone, or email
	 */
	public java.util.List<Customer> searchCustomers(String searchTerm) {
		if (searchTerm == null || searchTerm.trim().isEmpty()) {
			return java.util.Collections.emptyList();
		}

		String searchPattern = "%" + searchTerm.toLowerCase() + "%";

		// Use JPA Specification to search across multiple fields
		org.springframework.data.jpa.domain.Specification<Customer> spec = (root, query, criteriaBuilder) -> {
			javax.persistence.criteria.Predicate namePredicate = criteriaBuilder
					.like(criteriaBuilder.lower(root.get("name")), searchPattern);
			javax.persistence.criteria.Predicate codePredicate = criteriaBuilder
					.like(criteriaBuilder.lower(root.get("customerCode")), searchPattern);
			javax.persistence.criteria.Predicate phonePredicate = criteriaBuilder
					.like(criteriaBuilder.lower(root.get("phone")), searchPattern);
			javax.persistence.criteria.Predicate emailPredicate = criteriaBuilder
					.like(criteriaBuilder.lower(root.get("email")), searchPattern);

			// Combine with OR
			return criteriaBuilder.or(namePredicate, codePredicate, phonePredicate, emailPredicate);
		};

		return customerRepository.findAll(spec);
	}

	/**
	 * Find customers with pagination, search, and active filter
	 * Only returns active customers for POS
	 */
	public org.springframework.data.domain.Page<Customer> findActiveCustomersPaginated(int page, int size, String searchTerm) {
		org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "name"));

		org.springframework.data.jpa.domain.Specification<Customer> spec = (root, query, criteriaBuilder) -> {
			java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

			// Always filter by active = true
			predicates.add(criteriaBuilder.equal(root.get("active"), true));

			// Add search criteria if provided
			if (searchTerm != null && !searchTerm.trim().isEmpty()) {
				String searchPattern = "%" + searchTerm.toLowerCase() + "%";
				javax.persistence.criteria.Predicate namePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("name")), searchPattern);
				javax.persistence.criteria.Predicate codePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("customerCode")), searchPattern);
				javax.persistence.criteria.Predicate phonePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("phone")), searchPattern);
				javax.persistence.criteria.Predicate emailPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("email")), searchPattern);
				javax.persistence.criteria.Predicate cityPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("city")), searchPattern);
				javax.persistence.criteria.Predicate countryPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("country")), searchPattern);
				javax.persistence.criteria.Predicate taxIdPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("taxId")), searchPattern);

				// Combine search predicates with OR
				predicates.add(criteriaBuilder.or(namePredicate, codePredicate, phonePredicate, emailPredicate,
						cityPredicate, countryPredicate, taxIdPredicate));
			}

			return criteriaBuilder.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
		};

		return customerRepository.findAll(spec, pageable);
	}

	/**
	 * Find customers with pagination, search, and status filter (for admin)
	 * Supports filtering by active/inactive status
	 */
	public org.springframework.data.domain.Page<Customer> findCustomersPaginated(int page, int size, String searchTerm, String statusFilter) {
		org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "name"));

		org.springframework.data.jpa.domain.Specification<Customer> spec = (root, query, criteriaBuilder) -> {
			java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

			// Filter by status if provided (all, active, inactive)
			if (statusFilter != null && !statusFilter.equals("all")) {
				if (statusFilter.equals("active")) {
					predicates.add(criteriaBuilder.equal(root.get("active"), true));
				} else if (statusFilter.equals("inactive")) {
					predicates.add(criteriaBuilder.equal(root.get("active"), false));
				}
			}

			// Add search criteria if provided
			if (searchTerm != null && !searchTerm.trim().isEmpty()) {
				String searchPattern = "%" + searchTerm.toLowerCase() + "%";
				javax.persistence.criteria.Predicate namePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("name")), searchPattern);
				javax.persistence.criteria.Predicate codePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("customerCode")), searchPattern);
				javax.persistence.criteria.Predicate phonePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("phone")), searchPattern);
				javax.persistence.criteria.Predicate emailPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("email")), searchPattern);
				javax.persistence.criteria.Predicate cityPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("city")), searchPattern);
				javax.persistence.criteria.Predicate countryPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("country")), searchPattern);
				javax.persistence.criteria.Predicate taxIdPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("taxId")), searchPattern);

				// Combine search predicates with OR
				predicates.add(criteriaBuilder.or(namePredicate, codePredicate, phonePredicate, emailPredicate,
						cityPredicate, countryPredicate, taxIdPredicate));
			}

			return criteriaBuilder.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
		};

		return customerRepository.findAll(spec, pageable);
	}

	@Transactional
	public Customer setAsDefault(Long customerId) {
		java.util.Optional<Customer> customerOpt = customerRepository.findById(customerId);
		if (!customerOpt.isPresent()) {
			throw new RuntimeException("Customer not found with id: " + customerId);
		}

		Customer customer = customerOpt.get();

		// Unset all other default customers
		customerRepository.findByIsDefaultTrue().ifPresent(currentDefault -> {
			if (!currentDefault.getId().equals(customerId)) {
				currentDefault.setIsDefault(false);
				customerRepository.save(currentDefault);
			}
		});

		// Set this customer as default
		customer.setIsDefault(true);
		Customer savedCustomer = customerRepository.save(customer);

		// Update GeneralSetup
		generalSetupService.updateValue("PASSENGER_CUSTOMER", customer.getCustomerCode());

		// Invalidate cache
		cachedDefaultCustomer = null;

		return savedCustomer;
	}

	/**
	 * Get the default customer (PASSENGER_CUSTOMER) from GeneralSetup Uses caching
	 * for performance
	 * 
	 * @return The default customer, or null if not found
	 */
	public Customer getDefaultCustomer() {
		// Double-check locking pattern for thread-safe lazy initialization
		if (cachedDefaultCustomer == null) {
			synchronized (this) {
				if (cachedDefaultCustomer == null) {
					String passengerCustomerCode = generalSetupService.findValueByCode("PASSENGER_CUSTOMER");
					if (passengerCustomerCode != null && !passengerCustomerCode.trim().isEmpty()) {
						cachedDefaultCustomer = customerRepository.findByCustomerCode(passengerCustomerCode)
								.orElse(null);
						if (cachedDefaultCustomer != null) {
							log.info("Loaded default customer from cache: " + passengerCustomerCode);
						} else {
							log.warn("PASSENGER_CUSTOMER code found in GeneralSetup but customer not found: "
									+ passengerCustomerCode);
						}
					} else {
						log.warn("PASSENGER_CUSTOMER not found in GeneralSetup");
					}
				}
			}
		}
		return cachedDefaultCustomer;
	}

	/**
	 * Invalidate the default customer cache (call when GeneralSetup changes)
	 */
	public void invalidateDefaultCustomerCache() {
		cachedDefaultCustomer = null;
	}
}
