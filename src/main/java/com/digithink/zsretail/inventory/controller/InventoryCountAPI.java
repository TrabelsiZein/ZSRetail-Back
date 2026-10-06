package com.digithink.zsretail.inventory.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digithink.zsretail.inventory.service.InventoryCountService;
import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * Inventory count by Excel import (JWT, like the other admin APIs). Only on a store that keeps its own stock: on a head
 * office or when the supply is the ERP's every URL answers 403. Errors {"error"}: 400 invalid file or parameter, 403
 * not available here, 404 unknown count, 409 a validated count (validate again, import again, delete). See
 * docs/modules/inventory-count.md.
 */
@RestController
@RequestMapping("admin/inventory-counts")
@Log4j2
public class InventoryCountAPI {

	private static final String NOT_FOUND = "Not found";

	private final InventoryCountService service;
	private final CurrentUserProvider currentUser;

	public InventoryCountAPI(InventoryCountService service, CurrentUserProvider currentUser) {
		this.service = service;
		this.currentUser = currentUser;
	}

	/** A page {content, totalElements, totalPages, number, size} of the counts, newest first. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.list(page, size)));
	}

	/** Multipart: file (.xlsx or .xls), countDate (yyyy-MM-dd, today when absent), note. 200 the draft with its summary. */
	@PostMapping
	public ResponseEntity<?> create(@RequestPart("file") MultipartFile file,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate countDate,
			@RequestParam(required = false) String note) {
		return answer(() -> ResponseEntity
				.ok(service.create(countDate, note, file.getOriginalFilename(), stream(file), userName())));
	}

	/** One count with its summary. */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.get(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** Multipart: file. Imports the file again on a draft: its lines are replaced. */
	@PostMapping("/{id}/file")
	public ResponseEntity<?> reimport(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
		return answer(() -> service.reimport(id, file.getOriginalFilename(), stream(file), userName())
				.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** A page of the lines; filter all (default), differences or problems; search on code and item name. */
	@GetMapping("/{id}/lines")
	public ResponseEntity<?> lines(@PathVariable Long id, @RequestParam(required = false) String filter,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> service.lines(id, filter, search, page, size).<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(this::notFound));
	}

	/** Applies a draft to the stock. 200 the validated count with its summary. */
	@PostMapping("/{id}/validate")
	public ResponseEntity<?> validate(@PathVariable Long id) {
		return answer(() -> service.validate(id, userName()).<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(this::notFound));
	}

	/** Deletes a draft and its lines. 204. */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		return answer(() -> service.delete(id) ? ResponseEntity.noContent().build() : notFound());
	}

	private static InputStream stream(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("No file.");
		}
		try {
			return file.getInputStream();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private String userName() {
		try {
			return currentUser.getCurrentUserName();
		} catch (RuntimeException e) {
			return "System";
		}
	}

	private ResponseEntity<?> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		if (!service.isAvailable()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(InventoryCountService.NOT_AVAILABLE));
		}
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (InventoryCountService.NotAvailableException e) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("InventoryCountAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
