package com.digithink.zsretail.inventory.service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.inventory.enumeration.InventoryCountStatus;
import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.inventory.model.InventoryCount;
import com.digithink.zsretail.inventory.model.InventoryCountLine;
import com.digithink.zsretail.inventory.repository.InventoryCountLineRepository;
import com.digithink.zsretail.inventory.repository.InventoryCountRepository;
import com.digithink.zsretail.inventory.repository.InventoryLineStore;
import com.digithink.zsretail.inventory.repository.InventoryLineStore.OkLine;
import com.digithink.zsretail.inventory.service.InventoryFileReader.FileRow;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;

import lombok.extern.log4j.Log4j2;

/**
 * Inventory count by Excel import (store only, the stock kept here; see docs/modules/inventory-count.md).
 * <p>
 * Import: the file is read before the transaction; the barcodes and the items are loaded once; the rows of one item are
 * merged into one line; every line is written in JDBC batches. Validation: one transaction; the count goes from DRAFT
 * to VALIDATED in one conditional update first (a second validation is refused), then the stock of each OK line is
 * read once, the difference (counted minus that stock) added through StockService, one INVENTORY_IN or INVENTORY_OUT
 * movement per difference, and the stock read and the difference stored on the line. Items not in the file are not
 * touched; NOT_FOUND, NOT_COUNTED and BAD_QUANTITY lines are never applied and never block the OK lines.
 */
@Service
@Log4j2
public class InventoryCountService {

	static final int TIMEOUT_SECONDS = 600;
	static final int DEFAULT_PAGE_SIZE = 50;
	static final int MAX_PAGE_SIZE = 500;
	/** Ids per IN list (SQL Server accepts 2,100 parameters). */
	static final int IDS_PER_QUERY = 2000;

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

	private final InventoryCountRepository counts;
	private final InventoryCountLineRepository lineReads;
	private final InventoryLineStore lineStore;
	private final ItemRepository items;
	private final ItemBarcodeRepository barcodes;
	private final CashierSessionRepository sessions;
	private final StockService stock;
	private final StockMovementService movements;
	private final ApplicationModeService mode;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public InventoryCountService(InventoryCountRepository counts, InventoryCountLineRepository lineReads,
			InventoryLineStore lineStore, ItemRepository items, ItemBarcodeRepository barcodes,
			CashierSessionRepository sessions, StockService stock, StockMovementService movements,
			ApplicationModeService mode, PlatformTransactionManager transactionManager) {
		this(counts, lineReads, lineStore, items, barcodes, sessions, stock, movements, mode, timed(transactionManager),
				LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public InventoryCountService(InventoryCountRepository counts, InventoryCountLineRepository lineReads,
			InventoryLineStore lineStore, ItemRepository items, ItemBarcodeRepository barcodes,
			CashierSessionRepository sessions, StockService stock, StockMovementService movements,
			ApplicationModeService mode, TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this.counts = counts;
		this.lineReads = lineReads;
		this.lineStore = lineStore;
		this.items = items;
		this.barcodes = barcodes;
		this.sessions = sessions;
		this.stock = stock;
		this.movements = movements;
		this.mode = mode;
		this.transactions = transactions;
		this.clock = clock;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/** False on a head office and when the supply is the ERP's: every endpoint answers 403 then. */
	public boolean isAvailable() {
		return !mode.isHeadOffice() && !mode.isSupplyFromErp();
	}

	// ─── Reads ───────────────────────────────────────────────────

	/** A page {content, totalElements, totalPages, number, size} of the counts, newest first. */
	public Map<String, Object> list(Integer page, Integer size) {
		Page<InventoryCount> result = counts.findAllByOrderByIdDesc(PageRequest.of(pageNumber(page), pageSize(size)));
		return page(result.getContent().stream().map(InventoryCountService::view).collect(Collectors.toList()),
				result);
	}

	/** One count with its summary, or empty when unknown. */
	public Optional<Map<String, Object>> get(Long id) {
		return counts.findById(id).map(this::withSummary);
	}

	/**
	 * A page of the lines of one count: filter all, differences (OK lines whose counted quantity differs from the stock
	 * now, or whose applied difference is not 0 once validated) or problems (every line not OK); search on the code as
	 * read, the item code and the item name. Empty when the count is unknown.
	 */
	public Optional<Map<String, Object>> lines(Long id, String filter, String search, Integer page, Integer size) {
		Optional<InventoryCount> found = counts.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		boolean validated = found.get().getStatus() == InventoryCountStatus.VALIDATED;
		String pattern = search == null || search.trim().isEmpty() ? ""
				: "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		Page<Object[]> result = lineReads.findLines(id, filterOf(filter), validated ? 1 : 0, pattern,
				InventoryLineStatus.OK, PageRequest.of(pageNumber(page), pageSize(size)));
		return Optional.of(page(result.getContent().stream().map(row -> lineView(row, validated))
				.collect(Collectors.toList()), result));
	}

	// ─── Import ──────────────────────────────────────────────────

	/** Creates a draft from a file. IllegalArgumentException (400) for an unreadable or empty file. */
	public Map<String, Object> create(LocalDate countDate, String note, String fileName, InputStream file,
			String user) {
		requireAvailable();
		if (note != null && note.length() > 500) {
			throw new IllegalArgumentException("The note is longer than 500 characters.");
		}
		long start = System.nanoTime();
		List<FileRow> rows = readRows(file);
		InventoryCount saved = transactions.execute(status -> {
			InventoryCount count = new InventoryCount();
			count.setNumber(nextNumber());
			count.setCountDate(countDate == null ? clock.get().toLocalDate() : countDate);
			count.setNote(note == null || note.trim().isEmpty() ? null : note.trim());
			count.setStatus(InventoryCountStatus.DRAFT);
			count.setCreatedBy(user);
			count.setUpdatedBy(user);
			return writeLines(count, fileName, rows, user, start);
		});
		return withSummary(saved);
	}

	/** Imports the file again on a draft: its lines are replaced. Empty when unknown; 409 when validated. */
	public Optional<Map<String, Object>> reimport(Long id, String fileName, InputStream file, String user) {
		requireAvailable();
		long start = System.nanoTime();
		List<FileRow> rows = readRows(file);
		Optional<InventoryCount> saved = transactions.execute(status -> {
			Optional<InventoryCount> found = counts.findByIdForUpdate(id);
			if (!found.isPresent()) {
				return Optional.<InventoryCount>empty();
			}
			InventoryCount count = found.get();
			requireDraft(count, "imported again");
			lineStore.deleteByCount(count.getId());
			count.setUpdatedBy(user);
			return Optional.of(writeLines(count, fileName, rows, user, start));
		});
		return saved.map(this::withSummary);
	}

	private static List<FileRow> readRows(InputStream file) {
		if (file == null) {
			throw new IllegalArgumentException("No file.");
		}
		List<FileRow> rows = InventoryFileReader.read(file);
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("The file has no row to import.");
		}
		return rows;
	}

	/** Saves the header with the file name and the rows read, builds the lines and inserts them. */
	private InventoryCount writeLines(InventoryCount count, String fileName, List<FileRow> rows, String user,
			long start) {
		count.setFileName(fileName == null ? null : fileName.length() > 255 ? fileName.substring(0, 255) : fileName);
		count.setRowsRead(rows.size());
		InventoryCount saved = counts.save(count);
		List<InventoryCountLine> lines = buildLines(rows, loadCatalogue());
		lineStore.insert(saved.getId(), lines, user);

		Map<InventoryLineStatus, Integer> byStatus = new EnumMap<>(InventoryLineStatus.class);
		lines.forEach(line -> byStatus.merge(line.getStatus(), 1, Integer::sum));
		long differences = lines.stream().filter(line -> line.getStatus() == InventoryLineStatus.OK
				&& !line.getCountedQuantity().equals(line.getSystemQuantityAtImport())).count();
		log.info("Inventory count {} imported by {}: {} rows, {} lines ({} OK, {} not found, {} not counted,"
				+ " {} bad quantity), {} differences, {} ms", saved.getNumber(), user, rows.size(), lines.size(),
				byStatus.getOrDefault(InventoryLineStatus.OK, 0), byStatus.getOrDefault(InventoryLineStatus.NOT_FOUND, 0),
				byStatus.getOrDefault(InventoryLineStatus.NOT_COUNTED, 0),
				byStatus.getOrDefault(InventoryLineStatus.BAD_QUANTITY, 0), differences, millisSince(start));
		return saved;
	}

	/** The items and the active barcodes, read once (upper-case keys: SQL Server compares codes without case). */
	Catalogue loadCatalogue() {
		Catalogue catalogue = new Catalogue();
		for (Object[] row : items.findInventorySnapshot()) {
			ItemInfo item = new ItemInfo(((Number) row[0]).longValue(), (String) row[1], (ItemType) row[2],
					row[3] == null ? 0 : ((Number) row[3]).intValue());
			catalogue.byId.put(item.id, item);
			if (item.code != null) {
				catalogue.byCode.put(key(item.code), item);
			}
		}
		for (Object[] row : barcodes.findActiveBarcodeItemIds()) {
			ItemInfo item = row[1] == null ? null : catalogue.byId.get(((Number) row[1]).longValue());
			if (row[0] != null && item != null) {
				catalogue.byBarcode.put(key((String) row[0]), item);
			}
		}
		return catalogue;
	}

	/** The items and barcodes of this installation, by upper-case code. */
	static final class Catalogue {
		final Map<Long, ItemInfo> byId = new HashMap<>();
		final Map<String, ItemInfo> byCode = new HashMap<>();
		final Map<String, ItemInfo> byBarcode = new HashMap<>();

		/** A barcode first, then an item code; null when unknown. */
		ItemInfo find(String code) {
			if (code == null || code.isEmpty()) {
				return null;
			}
			String key = key(code);
			ItemInfo item = byBarcode.get(key);
			return item != null ? item : byCode.get(key);
		}

		/**
		 * A code read from a number cell and not found: Excel dropped the leading zeros of an EAN or UPC typed as a
		 * number. Tries it with zeros added in front, up to 14 digits, as a barcode and as an item code; the padded code
		 * when exactly one item matches, null otherwise (none, or several: never a guess).
		 */
		String findPadded(String code) {
			if (code == null || code.isEmpty() || code.length() >= MAX_PADDED_LENGTH
					|| !code.chars().allMatch(Character::isDigit)) {
				return null;
			}
			Map<Long, String> matches = new LinkedHashMap<>();
			StringBuilder padded = new StringBuilder(code);
			while (padded.length() < MAX_PADDED_LENGTH) {
				padded.insert(0, '0');
				String candidate = padded.toString();
				for (ItemInfo item : new ItemInfo[] { byBarcode.get(candidate), byCode.get(candidate) }) {
					if (item != null) {
						matches.putIfAbsent(item.id, candidate);
					}
				}
			}
			return matches.size() == 1 ? matches.values().iterator().next() : null;
		}
	}

	/** EAN-13, UPC-A (12) and GTIN-14: a number cell is padded with zeros up to this length at most. */
	static final int MAX_PADDED_LENGTH = 14;

	static final class ItemInfo {
		final long id;
		final String code;
		final ItemType type;
		final int stock;

		ItemInfo(long id, String code, ItemType type, int stock) {
			this.id = id;
			this.code = code;
			this.type = type;
			this.stock = stock;
		}
	}

	/** The rows merged by item (an unknown code by its value), in the order of their first row. */
	static List<InventoryCountLine> buildLines(List<FileRow> rows, Catalogue catalogue) {
		Map<String, Draft> drafts = new LinkedHashMap<>();
		for (FileRow row : rows) {
			ItemInfo found = catalogue.find(row.code);
			String padded = found == null && row.numericCode ? catalogue.findPadded(row.code) : null;
			ItemInfo item = padded != null ? catalogue.find(padded) : found;
			String groupKey = item != null ? "I" + item.id : "C" + key(row.code);
			Draft draft = drafts.computeIfAbsent(groupKey, k -> new Draft(row.code, item));
			draft.rows++;
			if (padded != null) {
				draft.foundAs.add(padded);
			}
			if (row.quantityError != null) {
				draft.errors.add("row " + row.rowNumber + ": " + row.quantityError);
			} else {
				draft.sum += row.quantity;
			}
		}
		return drafts.values().stream().map(Draft::toLine).collect(Collectors.toList());
	}

	private static final class Draft {
		final String code;
		final ItemInfo item;
		int rows;
		long sum;
		final List<String> errors = new ArrayList<>();
		/** The codes found with leading zeros added (a number cell). */
		final Set<String> foundAs = new LinkedHashSet<>();

		Draft(String code, ItemInfo item) {
			this.code = code;
			this.item = item;
		}

		InventoryCountLine toLine() {
			InventoryCountLine line = new InventoryCountLine();
			line.setCode(code.length() > 100 ? code.substring(0, 100) : code);
			line.setMergedRows(rows);
			boolean validSum = errors.isEmpty() && sum <= Integer.MAX_VALUE;
			if (item == null) {
				line.setStatus(InventoryLineStatus.NOT_FOUND);
				line.setCountedQuantity(validSum ? (int) sum : null);
				line.setMessage(code.isEmpty() ? "No code" : "Unknown barcode or item code");
				return line;
			}
			line.setItemId(item.id);
			line.setSystemQuantityAtImport(item.stock);
			if (item.type == ItemType.SERVICE || item.type == ItemType.DISCOUNT) {
				line.setStatus(InventoryLineStatus.NOT_COUNTED);
				line.setCountedQuantity(validSum ? (int) sum : null);
				line.setMessage("No stock for an item of type " + item.type);
			} else if (!errors.isEmpty()) {
				line.setStatus(InventoryLineStatus.BAD_QUANTITY);
				line.setMessage(message(String.join("; ", errors)));
			} else if (!validSum) {
				line.setStatus(InventoryLineStatus.BAD_QUANTITY);
				line.setMessage("Total quantity too large: " + sum);
			} else {
				line.setStatus(InventoryLineStatus.OK);
				line.setCountedQuantity((int) sum);
				if (!foundAs.isEmpty()) {
					line.setMessage(message("Found with leading zeros: " + String.join(", ", foundAs)));
				}
			}
			return line;
		}
	}

	private String nextNumber() {
		long next = counts.findTopByOrderByIdDesc().map(InventoryCount::getNumber).map(InventoryCountService::suffix)
				.orElse(0L) + 1;
		return "INV-" + clock.get().format(MONTH) + "-" + String.format("%06d", next);
	}

	private static long suffix(String number) {
		try {
			return Long.parseLong(number.substring(number.lastIndexOf('-') + 1));
		} catch (RuntimeException e) {
			return 0L;
		}
	}

	// ─── Validation ──────────────────────────────────────────────

	/**
	 * Applies a draft, all or nothing: empty when unknown; IllegalStateException (409) when it is not a draft (a second
	 * click finds it validated and changes nothing).
	 */
	public Optional<Map<String, Object>> validate(Long id, String user) {
		requireAvailable();
		long start = System.nanoTime();
		Optional<InventoryCount> validated = transactions.execute(status -> {
			Optional<InventoryCount> found = counts.findById(id);
			if (!found.isPresent()) {
				return Optional.<InventoryCount>empty();
			}
			String number = found.get().getNumber();
			if (counts.markValidated(id, clock.get(), user, InventoryCountStatus.DRAFT,
					InventoryCountStatus.VALIDATED) == 0) {
				if (!counts.findById(id).isPresent()) {
					return Optional.<InventoryCount>empty(); // deleted meanwhile
				}
				throw new IllegalStateException("This count is already validated: " + number + ".");
			}

			List<OkLine> lines = lineStore.okLines(id);
			Map<Long, Integer> stockNow = stockOf(lines);
			Map<Long, Integer> differences = new LinkedHashMap<>();
			long up = 0;
			long down = 0;
			for (OkLine line : lines) {
				if (!stockNow.containsKey(line.itemId)) {
					line.message = "Item deleted since the import: not applied";
					continue;
				}
				Integer current = stockNow.get(line.itemId);
				line.systemQuantity = current == null ? 0 : current;
				line.difference = line.counted - line.systemQuantity;
				differences.merge(line.itemId, line.difference, Integer::sum);
				up += Math.max(line.difference, 0);
				down += Math.max(-line.difference, 0);
			}
			stock.applyInventoryDifferences(differences);
			int written = movements.recordInventory(differences, id, number, user);
			lineStore.saveValidation(lines, user);
			log.info("Inventory count {} validated by {}: {} lines applied, {} differences (+{} / -{}), {} ms", number,
					user, lines.size(), written, up, down, millisSince(start));
			return counts.findById(id);
		});
		return validated.map(this::withSummary);
	}

	/** The stock now of the items of these lines (absent: the item was deleted), at most 2,000 ids per query. */
	private Map<Long, Integer> stockOf(List<OkLine> lines) {
		List<Long> ids = lines.stream().map(line -> line.itemId).distinct().collect(Collectors.toList());
		Map<Long, Integer> stockNow = new HashMap<>();
		for (int from = 0; from < ids.size(); from += IDS_PER_QUERY) {
			for (Object[] row : items.findStockByIds(ids.subList(from, Math.min(from + IDS_PER_QUERY, ids.size())))) {
				stockNow.put(((Number) row[0]).longValue(), row[1] == null ? null : ((Number) row[1]).intValue());
			}
		}
		return stockNow;
	}

	// ─── Delete ──────────────────────────────────────────────────

	/** Deletes a draft and its lines: false when unknown; IllegalStateException (409) when validated. */
	public boolean delete(Long id) {
		requireAvailable();
		Boolean deleted = transactions.execute(status -> {
			Optional<InventoryCount> found = counts.findByIdForUpdate(id);
			if (!found.isPresent()) {
				return false;
			}
			requireDraft(found.get(), "deleted");
			lineStore.deleteByCount(id);
			counts.delete(found.get());
			log.info("Inventory count {} deleted", found.get().getNumber());
			return true;
		});
		return Boolean.TRUE.equals(deleted);
	}

	// ─── Views ───────────────────────────────────────────────────

	private Map<String, Object> withSummary(InventoryCount count) {
		Map<String, Object> view = view(count);
		view.put("summary", summary(count));
		return view;
	}

	/**
	 * rowsRead, lines, linesOk, linesNotFound, linesNotCounted, linesBadQuantity, linesWithDifference, quantityUp,
	 * quantityDown (a draft against the stock now, a validated count from what was applied), openCashierSessions.
	 */
	private Map<String, Object> summary(InventoryCount count) {
		boolean validated = count.getStatus() == InventoryCountStatus.VALIDATED;
		List<Object[]> rows = validated ? lineReads.summaryOfValidated(count.getId())
				: lineReads.summaryOfDraft(count.getId());
		Map<InventoryLineStatus, Long> lines = new EnumMap<>(InventoryLineStatus.class);
		long withDifference = 0;
		long up = 0;
		long down = 0;
		for (Object[] row : rows) {
			InventoryLineStatus status = (InventoryLineStatus) row[0];
			lines.put(status, number(row[1]));
			if (status == InventoryLineStatus.OK) {
				withDifference = number(row[3]);
				up = validated ? number(row[4]) : number(row[4]) - number(row[5]);
				down = validated ? -number(row[5]) : number(row[6]) - number(row[7]);
			}
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("rowsRead", count.getRowsRead());
		summary.put("lines", lines.values().stream().mapToLong(Long::longValue).sum());
		summary.put("linesOk", lines.getOrDefault(InventoryLineStatus.OK, 0L));
		summary.put("linesNotFound", lines.getOrDefault(InventoryLineStatus.NOT_FOUND, 0L));
		summary.put("linesNotCounted", lines.getOrDefault(InventoryLineStatus.NOT_COUNTED, 0L));
		summary.put("linesBadQuantity", lines.getOrDefault(InventoryLineStatus.BAD_QUANTITY, 0L));
		summary.put("linesWithDifference", withDifference);
		summary.put("quantityUp", up);
		summary.put("quantityDown", down);
		summary.put("openCashierSessions", sessions.countByStatus(SessionStatus.OPENED));
		return summary;
	}

	static Map<String, Object> view(InventoryCount count) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", count.getId());
		view.put("number", count.getNumber());
		view.put("countDate", count.getCountDate() == null ? null : count.getCountDate().toString());
		view.put("note", count.getNote());
		view.put("fileName", count.getFileName());
		view.put("status", count.getStatus());
		view.put("rowsRead", count.getRowsRead());
		view.put("createdAt", text(count.getCreatedAt()));
		view.put("createdBy", count.getCreatedBy());
		view.put("validatedAt", text(count.getValidatedAt()));
		view.put("validatedBy", count.getValidatedBy());
		return view;
	}

	/** One line: a draft against the stock now, a validated count with the stock read and the difference applied. */
	private static Map<String, Object> lineView(Object[] row, boolean validated) {
		InventoryLineStatus status = (InventoryLineStatus) row[5];
		Integer counted = (Integer) row[6];
		Integer stockNow = row[12] == null ? (row[2] == null ? null : 0) : (Integer) row[12];
		Integer systemQuantity = validated ? (Integer) row[9] : stockNow;
		Integer difference = validated ? (Integer) row[10]
				: status == InventoryLineStatus.OK && counted != null && stockNow != null ? counted - stockNow : null;
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", row[0]);
		view.put("code", row[1]);
		view.put("itemId", row[2]);
		view.put("itemCode", row[3]);
		view.put("itemName", row[4]);
		view.put("status", status);
		view.put("countedQuantity", counted);
		view.put("mergedRows", row[7]);
		view.put("systemQuantityAtImport", row[8]);
		view.put("systemQuantity", systemQuantity);
		view.put("difference", difference);
		view.put("message", row[11]);
		return view;
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private void requireAvailable() {
		if (!isAvailable()) {
			throw new NotAvailableException();
		}
	}

	/** The message of the 403. */
	public static final String NOT_AVAILABLE = "Inventory counts exist only on a store that keeps its own stock.";

	/** A head office, or a store whose supply is the ERP's: the API answers 403. */
	public static final class NotAvailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public NotAvailableException() {
			super(NOT_AVAILABLE);
		}
	}

	private static void requireDraft(InventoryCount count, String action) {
		if (count.getStatus() != InventoryCountStatus.DRAFT) {
			throw new IllegalStateException("A validated count cannot be " + action + ": " + count.getNumber() + ".");
		}
	}

	private static String filterOf(String filter) {
		if (filter == null || filter.trim().isEmpty() || "all".equalsIgnoreCase(filter.trim())) {
			return "ALL";
		}
		if ("differences".equalsIgnoreCase(filter.trim())) {
			return "DIFFERENCES";
		}
		if ("problems".equalsIgnoreCase(filter.trim())) {
			return "PROBLEMS";
		}
		throw new IllegalArgumentException("Unknown filter: " + filter + " (all, differences or problems).");
	}

	private static Map<String, Object> page(List<Map<String, Object>> content, Page<?> result) {
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", content);
		answer.put("totalElements", result.getTotalElements());
		answer.put("totalPages", result.getTotalPages());
		answer.put("number", result.getNumber());
		answer.put("size", result.getSize());
		return answer;
	}

	private static int pageNumber(Integer page) {
		return page == null || page < 0 ? 0 : page;
	}

	private static int pageSize(Integer size) {
		return size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
	}

	private static String key(String code) {
		return code.toUpperCase(Locale.ROOT);
	}

	private static String message(String text) {
		return text.length() > 255 ? text.substring(0, 252) + "..." : text;
	}

	private static long number(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	private static String text(LocalDateTime time) {
		return time == null ? null : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}
}
