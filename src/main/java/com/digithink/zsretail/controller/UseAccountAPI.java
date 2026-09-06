package com.digithink.zsretail.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.CreateUserRequestDTO;
import com.digithink.zsretail.dto.UserAccountDTO;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.service.UserAccountService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("user")
@Log4j2
public class UseAccountAPI extends _BaseController<UserAccount, Long, UserAccountService> {

	/**
	 * Get all users as DTOs
	 */
	@GetMapping("all")
	public ResponseEntity<?> getAllUsers() {
		try {
			log.info("UseAccountAPI::getAllUsers");
			return ResponseEntity.ok(service.getAllUsers());
		} catch (Exception e) {
			log.error("UseAccountAPI::getAllUsers:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	/**
	 * Get user by ID as DTO
	 */
	@GetMapping("detail/{id}")
	public ResponseEntity<?> getUserDetail(@PathVariable Long id) {
		try {
			log.info("UseAccountAPI::getUserDetail::" + id);
			return ResponseEntity.ok(service.getUserById(id));
		} catch (Exception e) {
			log.error("UseAccountAPI::getUserDetail:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	/**
	 * Create a new user with role
	 */
	@PostMapping("create")
	public ResponseEntity<?> createUser(@RequestBody CreateUserRequestDTO request) {
		try {
			log.info("UseAccountAPI::createUser::" + request.getUsername());
			UserAccountDTO createdUser = service.createUser(request);
			return ResponseEntity.ok(createdUser);
		} catch (Exception e) {
			log.error("UseAccountAPI::createUser:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	/**
	 * Update user role
	 */
	@PutMapping("{id}/role")
	public ResponseEntity<?> updateUserRole(@PathVariable Long id, @RequestBody Role role) {
		try {
			log.info("UseAccountAPI::updateUserRole::" + id + " -> " + role);
			UserAccountDTO updatedUser = service.updateUserRole(id, role);
			return ResponseEntity.ok(updatedUser);
		} catch (Exception e) {
			log.error("UseAccountAPI::updateUserRole:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}

	/**
	 * Toggle user active status
	 */
	@PutMapping("{id}/toggle-status")
	public ResponseEntity<?> toggleUserStatus(@PathVariable Long id) {
		try {
			log.info("UseAccountAPI::toggleUserStatus::" + id);
			UserAccountDTO updatedUser = service.toggleUserStatus(id);
			return ResponseEntity.ok(updatedUser);
		} catch (Exception e) {
			log.error("UseAccountAPI::toggleUserStatus:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(e.getMessage());
		}
	}
}
