package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.service.CustomerService;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("customer")
@Log4j2
public class CustomerAPI extends _BaseController<Customer, Long, CustomerService> {

	@Autowired
	private CustomerService customerService;

	@Autowired
	private GeneralSetupService generalSetupService;

	@Autowired
	private ApplicationModeService applicationModeService;

	/**
	 * Create customer. Only allowed in standalone mode (in ERP mode customers come from sync).
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Customer entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Customer creation is only available in standalone mode. In ERP mode customers are synchronized from the ERP."));
		}
		try {
			log.info("CustomerAPI::create");
			Customer created = customerService.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("CustomerAPI::create:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Search customers by name, code, phone, or email (for autocomplete - returns limited results)
	 */
	@GetMapping("/search")
	public ResponseEntity<?> searchCustomers(@RequestParam(required = false) String q) {
		try {
			log.info("CustomerAPI::searchCustomers: " + q);
			
			if (q == null || q.trim().isEmpty()) {
				return ResponseEntity.ok(java.util.Collections.emptyList());
			}
			
			List<Customer> customers = customerService.searchCustomers(q.trim());
			// Only return active customers
			List<Customer> activeCustomers = customers.stream()
				.filter(c -> c.getActive() != null && c.getActive())
				.limit(50) // Limit results for autocomplete
				.collect(java.util.stream.Collectors.toList());
			
			return ResponseEntity.ok(activeCustomers);
		} catch (Exception e) {
			log.error("CustomerAPI::searchCustomers:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get paginated list of active customers with search
	 * Used by POS CustomerList page
	 */
	@GetMapping("/paginated")
	public ResponseEntity<?> getCustomersPaginated(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String searchTerm,
			@RequestParam(required = false) Long selectedCustomerId) {
		try {
			log.info("CustomerAPI::getCustomersPaginated: page={}, size={}, searchTerm={}, selectedCustomerId={}", 
					page, size, searchTerm, selectedCustomerId);
			
			// Get paginated active customers
			Page<Customer> customerPage = customerService.findActiveCustomersPaginated(page, size, searchTerm);
			
			// Build response
			Map<String, Object> response = new HashMap<>();
			response.put("content", customerPage.getContent());
			response.put("totalElements", customerPage.getTotalElements());
			response.put("totalPages", customerPage.getTotalPages());
			response.put("number", customerPage.getNumber());
			response.put("size", customerPage.getSize());
			response.put("numberOfElements", customerPage.getNumberOfElements());
			response.put("first", customerPage.isFirst());
			response.put("last", customerPage.isLast());
			
			// If selectedCustomerId is provided and not in current page, fetch it separately
			if (selectedCustomerId != null) {
				boolean isInCurrentPage = customerPage.getContent().stream()
						.anyMatch(c -> c.getId().equals(selectedCustomerId));
				
				if (!isInCurrentPage) {
					// Selected customer is not in current page, fetch it
					java.util.Optional<Customer> selectedCustomer = customerService.findById(selectedCustomerId);
					if (selectedCustomer.isPresent() && selectedCustomer.get().getActive() != null 
							&& selectedCustomer.get().getActive()) {
						response.put("selectedCustomer", selectedCustomer.get());
					}
				}
			}
			
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("CustomerAPI::getCustomersPaginated:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get paginated list of customers with search and status filter
	 * Used by Admin CustomerManagement page
	 */
	@GetMapping("/admin/paginated")
	public ResponseEntity<?> getCustomersPaginatedForAdmin(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String searchTerm,
			@RequestParam(required = false, defaultValue = "all") String statusFilter) {
		try {
			log.info("CustomerAPI::getCustomersPaginatedForAdmin: page={}, size={}, searchTerm={}, statusFilter={}", 
					page, size, searchTerm, statusFilter);
			
			// Get paginated customers with status filter
			Page<Customer> customerPage = customerService.findCustomersPaginated(page, size, searchTerm, statusFilter);
			
			// Build response
			Map<String, Object> response = new HashMap<>();
			response.put("content", customerPage.getContent());
			response.put("totalElements", customerPage.getTotalElements());
			response.put("totalPages", customerPage.getTotalPages());
			response.put("number", customerPage.getNumber());
			response.put("size", customerPage.getSize());
			response.put("numberOfElements", customerPage.getNumberOfElements());
			response.put("first", customerPage.isFirst());
			response.put("last", customerPage.isLast());
			
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("CustomerAPI::getCustomersPaginatedForAdmin:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get passenger customer (default customer)
	 */
	@GetMapping("/passenger")
	public ResponseEntity<?> getPassengerCustomer() {
		try {
			log.info("CustomerAPI::getPassengerCustomer");
			
			// Get passenger customer code from GeneralSetup
			String passengerCode = generalSetupService.findValueByCode("PASSENGER_CUSTOMER");
			
			// Check if passenger code is configured
			if (passengerCode == null || passengerCode.trim().isEmpty()) {
				log.warn("PASSENGER_CUSTOMER not configured in GeneralSetup");
				return ResponseEntity.status(400).body(createErrorResponse("Passenger not configured"));
			}
			
			// Retrieve customer using the code from GeneralSetup
			java.util.Optional<Customer> passenger = customerService.getCustomerRepository()
				.findByCustomerCode(passengerCode.trim());
			
			if (passenger.isPresent()) {
				return ResponseEntity.ok(passenger.get());
			} else {
				log.warn("Passenger customer not found with code: " + passengerCode);
				return ResponseEntity.status(404).body(createErrorResponse("Passenger customer not found"));
			}
		} catch (Exception e) {
			log.error("CustomerAPI::getPassengerCustomer:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Delete customer by ID - override to prevent deletion of passenger customer
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		try {
			log.info("CustomerAPI::deleteById::" + id);
			
			// Get customer to check if it's the passenger customer
			java.util.Optional<Customer> customerOpt = customerService.findById(id);
			if (!customerOpt.isPresent()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse("Customer not found"));
			}
			
			Customer customer = customerOpt.get();
			
			// Prevent deletion of passenger customer
			if (customer.getCustomerCode() != null && customer.getCustomerCode().equals("PASSENGER")) {
				log.warn("Attempt to delete passenger customer blocked: " + id);
				return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(createErrorResponse("The passenger customer cannot be deleted as it is required by the system."));
			}
			
			// Proceed with deletion
			customerService.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("CustomerAPI::deleteById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Set customer as default
	 * Prevents setting inactive customers as default
	 */
	@PutMapping("/{id}/set-default")
	public ResponseEntity<?> setAsDefault(@PathVariable Long id) {
		try {
			log.info(this.getClass().getSimpleName() + "::setAsDefault::" + id);
			
			// Check if customer exists and is active
			java.util.Optional<Customer> customerOpt = customerService.findById(id);
			if (!customerOpt.isPresent()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(createErrorResponse("Customer not found with id: " + id));
			}
			
			Customer customer = customerOpt.get();
			if (customer.getActive() == null || !customer.getActive()) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST)
						.body(createErrorResponse("Cannot set inactive customer as default. Please activate the customer first."));
			}
			
			Customer updatedCustomer = customerService.setAsDefault(id);
			return ResponseEntity.ok(updatedCustomer);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::setAsDefault:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}

