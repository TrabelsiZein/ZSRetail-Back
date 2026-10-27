package com.digithink.zsretail.store.holink.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.dto.ItemImageDTO;
import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.dto.NavItemFilter;
import com.digithink.zsretail.core.link.dto.SalesCopyResultDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.core.link.dto.VariantStockDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemImageRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.service.ItemBarcodeService;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.service.QuantityPolicy;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.dto.LiveAnswer;
import com.digithink.zsretail.store.holink.dto.StoreOrderCatalogueLineDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderVariantsDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderViewDTO;
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.holink.model.StoreOrderLine;
import com.digithink.zsretail.store.holink.repository.StoreOrderRepository;

/**
 * 3.1.1: the orders of the store to its head office ("Commandes centrale"), on a store whose goods come from the head
 * office. Online only: the variants' stock and the send are live questions to the head office (HeadOfficeClient, the
 * /ho/** chain with the store's key); without its answer nothing is shown or saved
 * ({@link HeadOfficeUnavailableException}, 503).
 * <p>
 * Rules: one DRAFT at a time, made by the first line added, its number given then (store code, year, sequence); one
 * line per item, adding it again replaces the quantity, 0 removes it; only an ERP item (with a NAV item number), active,
 * not a pack. Only the draft changes, is sent or is cancelled. The send holds the draft's row locked while the head office
 * answers (the draft's changes wait for it): accepted, the order is SENT; refused (409) or not answered (503), it stays
 * DRAFT, nothing saved. What the head office does with it then (POSTED, ERROR) comes down with the copies
 * ({@link StoreOrderStatusWriter}). See docs/modules/store-orders.md.
 * <p>
 * Errors: IllegalArgumentException (400), IllegalStateException (409), HeadOfficeUnavailableException (503); an unknown
 * order or no draft is an empty answer (404, or 204 for the draft).
 */
@Service
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
public class StoreOrderService {

	/** The edits of the draft: short statements. */
	static final int EDIT_TIMEOUT_SECONDS = 15;

	/** The send: the head office's answer (connect 2 s, read 15 s) inside the transaction, then the update. */
	static final int SEND_TIMEOUT_SECONDS = 30;

	static final int DEFAULT_PAGE_SIZE = 20;
	static final int MAX_PAGE_SIZE = 100;

	/** The items of a barcode searched, at most (as the item lookup). */
	static final int BARCODE_MATCHES_LIMIT = 1000;

	static final String NO_STORE_CODE = "DEFAULT_LOCATION is empty in the general setup: an order needs the store code";

	private final StoreOrderRepository orders;
	private final ItemRepository items;
	private final ItemBarcodeRepository barcodes;
	private final ItemImageRepository images;
	private final NavItemService navItems;
	private final HeadOfficeClient client;
	private final QuantityPolicy quantityPolicy;
	private final TransactionOperations edits;
	private final TransactionOperations sends;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public StoreOrderService(StoreOrderRepository orders, ItemRepository items, ItemBarcodeRepository barcodes,
			ItemImageRepository images, NavItemService navItems, HeadOfficeClient client, QuantityPolicy quantityPolicy,
			PlatformTransactionManager transactionManager) {
		this(orders, items, barcodes, images, navItems, client, quantityPolicy,
				timed(transactionManager, EDIT_TIMEOUT_SECONDS), timed(transactionManager, SEND_TIMEOUT_SECONDS),
				LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public StoreOrderService(StoreOrderRepository orders, ItemRepository items, ItemBarcodeRepository barcodes,
			ItemImageRepository images, NavItemService navItems, HeadOfficeClient client, QuantityPolicy quantityPolicy,
			TransactionOperations edits, TransactionOperations sends, Supplier<LocalDateTime> clock) {
		this.orders = orders;
		this.items = items;
		this.barcodes = barcodes;
		this.images = images;
		this.navItems = navItems;
		this.client = client;
		this.quantityPolicy = quantityPolicy;
		this.edits = edits;
		this.sends = sends;
		this.clock = clock;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager, int seconds) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(seconds);
		return template;
	}

	/** The head office did not answer, or answered with an error: nothing shown, nothing saved (503). */
	public static class HeadOfficeUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public HeadOfficeUnavailableException(String message) {
			super(message);
		}
	}

	// ─── Catalogue ───────────────────────────────────────────────

	/**
	 * One page of the order catalogue: the NAV items with an active variant (NavItemService's grouped read), filtered and
	 * sorted as the filter says; the search also finds the NAV item of a barcode. Each line gets its first variant's
	 * price and the first image of its variants.
	 */
	public Page<StoreOrderCatalogueLineDTO> catalogue(NavItemFilter filter, int page, int size) {
		NavItemFilter asked = filter == null ? new NavItemFilter() : filter;
		asked.setActiveOnly(true);
		String like = NavItemService.likeOf(asked.getSearch());
		asked.setBarcodeItemIds(like == null ? new ArrayList<>()
				: barcodes.findItemIdsByBarcodeLike(like, PageRequest.of(0, BARCODE_MATCHES_LIMIT)));
		Page<NavItemDTO> lines = navItems.page(asked, page, size);
		Map<String, List<Item>> variants = navItems.activeVariantsOf(
				lines.getContent().stream().map(NavItemDTO::getNavItemNo).collect(Collectors.toList()));
		return lines.map(line -> catalogueLine(line, variants.getOrDefault(line.getNavItemNo(), Collections.emptyList())));
	}

	private static StoreOrderCatalogueLineDTO catalogueLine(NavItemDTO line, List<Item> variants) {
		StoreOrderCatalogueLineDTO dto = new StoreOrderCatalogueLineDTO();
		dto.setNavItemNo(line.getNavItemNo());
		dto.setName(line.getName());
		dto.setVariantCount(line.getVariants());
		dto.setFamilyName(line.getFamilyName());
		dto.setSubFamilyName(line.getSubFamilyName());
		dto.setCategoryCode(line.getCategoryCode());
		dto.setCategoryName(line.getCategoryName());
		dto.setSectionCode(line.getSectionCode());
		dto.setSectionName(line.getSectionName());
		if (!variants.isEmpty()) {
			Item first = variants.get(0);
			dto.setPrice(priceOf(first));
			dto.setVatRate(first.getDefaultVAT());
			variants.stream().filter(item -> hasText(item.getImageUrl())).findFirst().ifPresent(item -> {
				dto.setMainImage(item.getImageUrl());
				dto.setMainImageItemId(item.getId()); // the screen reads it at item-image/item/{id}
			});
		}
		return dto;
	}

	/** The public price before VAT: item.publicPrice (sent by the head office), else the item's own price. */
	static Double priceOf(Item item) {
		return item.getPublicPrice() != null ? item.getPublicPrice() : item.getUnitPrice();
	}

	// ─── Variants and their live stock ───────────────────────────

	/**
	 * The active variants of a NAV item number with NAV's stock of each at this store's location, asked to the head office
	 * now; the draft's quantity of each. Empty for a number no active item carries (nothing asked to the head office).
	 */
	public Optional<StoreOrderVariantsDTO> variants(String navItemNo) {
		if (!hasText(navItemNo)) {
			throw new IllegalArgumentException("navItemNo is required");
		}
		String number = navItemNo.trim();
		List<Item> local = navItems.activeVariantsOf(Collections.singletonList(number)).getOrDefault(number,
				Collections.emptyList());
		if (local.isEmpty()) {
			return Optional.empty();
		}
		LiveAnswer<VariantStockDTO[]> answer = client.variantStock(number);
		if (answer.isAnswered() && answer.getStatus() == 409) {
			// The head office will not answer this store as it is (no ERP customer number): its reason, as for a send
			throw new IllegalStateException("The head office refused the stock of " + number + ": "
					+ (hasText(answer.getError()) ? answer.getError() : "no reason given"));
		}
		if (!answer.isOk()) {
			throw new HeadOfficeUnavailableException(unavailable("read the stock of " + number, answer));
		}
		Map<String, BigDecimal> stock = new HashMap<>();
		Map<String, VariantStockDTO> rows = new HashMap<>();
		for (VariantStockDTO row : answer.getBody()) {
			if (row != null && row.getItemNo() != null && number.equalsIgnoreCase(row.getItemNo().trim())) {
				String key = variantKey(row.getVariantCode());
				if (row.getQuantity() != null) {
					stock.merge(key, row.getQuantity(), BigDecimal::add);
				}
				rows.putIfAbsent(key, row); // its details and size order: the first row's
			}
		}
		Map<Long, List<ItemImageDTO>> imagesByItem = new HashMap<>();
		for (ItemImage image : images.findOfItems(local.stream().map(Item::getId).collect(Collectors.toList()))) {
			imagesByItem.computeIfAbsent(image.getItemId(), id -> new ArrayList<>()).add(ItemImageDTO.of(image));
		}
		Map<Long, BigDecimal> drafted = draftQuantities();
		StoreOrderVariantsDTO dto = new StoreOrderVariantsDTO();
		dto.setNavItemNo(number);
		dto.setName(local.get(0).getName());
		for (Item item : local) {
			StoreOrderVariantsDTO.Variant variant = new StoreOrderVariantsDTO.Variant();
			variant.setItemId(item.getId());
			variant.setItemCode(item.getItemCode());
			variant.setVariantCode(item.getVariantCode());
			variant.setVariantDescription(item.getVariantDescription());
			variant.setSizeCode(item.getSizeCode());
			variant.setSizeDescription(item.getSizeDescription());
			variant.setColorCode(item.getColorCode());
			variant.setColorDescription(item.getColorDescription());
			variant.setChoiceCode(item.getChoiceCode());
			variant.setChoiceDescription(item.getChoiceDescription());
			variant.setPrice(priceOf(item));
			variant.setVatRate(item.getDefaultVAT());
			variant.setMainImage(hasText(item.getImageUrl()) ? item.getImageUrl() : null);
			variant.setImages(imagesByItem.getOrDefault(item.getId(), new ArrayList<>()));
			BigDecimal inNav = stock.get(variantKey(item.getVariantCode()));
			variant.setStock(inNav == null ? null : Quantities.normalize(inNav)); // null: NAV gave no row (or no quantity)
			VariantStockDTO row = rows.get(variantKey(item.getVariantCode()));
			variant.setDetails(row == null ? null : row.getDetails());
			variant.setSizeOrder(row == null ? null : row.getSizeOrder());
			variant.setDraftQuantity(drafted.get(item.getId()));
			dto.getVariants().add(variant);
		}
		return Optional.of(dto);
	}

	private static String variantKey(String variantCode) {
		return variantCode == null ? "" : variantCode.trim().toUpperCase(Locale.ROOT);
	}

	private Map<Long, BigDecimal> draftQuantities() {
		Map<Long, BigDecimal> quantities = edits.execute(status -> {
			Map<Long, BigDecimal> found = new HashMap<>();
			currentDraft().ifPresent(draft -> draft.getLines().forEach(line -> found.put(line.getItemId(), line.getQuantity())));
			return found;
		});
		return quantities == null ? new HashMap<>() : quantities;
	}

	// ─── The draft ───────────────────────────────────────────────

	/** The store's draft with its lines; empty when there is none. */
	public Optional<StoreOrderViewDTO> draft() {
		return Optional.ofNullable(edits.execute(status -> currentDraft().map(StoreOrderViewDTO::of).orElse(null)));
	}

	/**
	 * Adds the item to the draft with this quantity, or replaces its quantity; 0 removes its line. A missing draft is made
	 * (not for a 0). Answers the draft; empty when a 0 found no draft.
	 */
	public Optional<StoreOrderViewDTO> putLine(Long itemId, BigDecimal quantity, String user) {
		return putLine(itemId, quantity, KEEP_AVAILABLE, user);
	}

	/**
	 * The same from the variants window, with the quantity available it showed (null: none, no limit): it becomes the
	 * line's, and the quantity may not exceed it (409).
	 */
	public Optional<StoreOrderViewDTO> putLine(Long itemId, BigDecimal quantity, BigDecimal available, String user) {
		return putLine(itemId, quantity, new Availability(available), user);
	}

	/** The availability a line is put with: the one given (null: no limit), or the line's own (KEEP_AVAILABLE). */
	private static final class Availability {
		private final BigDecimal quantity;

		private Availability(BigDecimal quantity) {
			this.quantity = quantity == null ? null : Quantities.normalize(quantity);
		}
	}

	private static final Availability KEEP_AVAILABLE = new Availability(null);

	private Optional<StoreOrderViewDTO> putLine(Long itemId, BigDecimal quantity, Availability available, String user) {
		if (itemId == null) {
			throw new IllegalArgumentException("itemId is required");
		}
		if (quantity == null || quantity.signum() < 0) {
			throw new IllegalArgumentException("quantity is required, 0 or more");
		}
		try {
			return Optional.ofNullable(edits.execute(status -> applyLine(itemId, quantity, available, user)));
		} catch (DataIntegrityViolationException e) {
			// Two first lines at the same time: the other request made the draft (ux_hol_order_draft); this one goes in it
			return Optional.ofNullable(edits.execute(status -> applyLine(itemId, quantity, available, user)));
		}
	}

	private StoreOrderViewDTO applyLine(Long itemId, BigDecimal quantity, Availability available, String user) {
		if (quantity.signum() == 0) {
			Optional<StoreOrder> draft = lockedDraft();
			if (!draft.isPresent()) {
				return null;
			}
			return removeLine(draft.get(), itemId, user);
		}
		Item item = items.findById(itemId).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + itemId));
		checkOrderable(item);
		quantityPolicy.check(quantity, item.getItemCode());
		StoreOrder draft = lockedDraft().orElseGet(() -> newDraft(user));
		StoreOrderLine line = draft.getLines().stream().filter(l -> itemId.equals(l.getItemId())).findFirst()
				.orElse(null);
		// The window's availability when it gave one, else the line's own (the cart); none: no limit
		BigDecimal limit = available != KEEP_AVAILABLE ? available.quantity : line == null ? null : line.getAvailableQty();
		checkAvailable(quantity, limit, Quantities.itemLabel(item.getItemCode(), item.getName()));
		if (line == null) {
			line = new StoreOrderLine();
			line.setOrder(draft);
			line.setItemId(item.getId());
			line.setItemCode(item.getItemCode());
			line.setNavItemNo(item.getNavItemNo());
			line.setVariantCode(item.getVariantCode());
			line.setDescription(item.getName());
			line.setVariantDescription(item.getVariantDescription());
			line.setCreatedBy(user == null ? line.getCreatedBy() : user);
			draft.getLines().add(line);
		}
		line.setQuantity(Quantities.normalize(quantity));
		line.setAvailableQty(limit);
		line.setUpdatedBy(user);
		return save(draft, user);
	}

	/** A quantity above the availability (none: no limit) is refused (409), the figures named. */
	static void checkAvailable(BigDecimal quantity, BigDecimal available, String label) {
		if (available != null && quantity.compareTo(available) > 0) {
			throw new IllegalStateException("Item " + label + ": " + Quantities.normalize(quantity).toPlainString()
					+ " asked but only " + Quantities.normalize(available).toPlainString()
					+ " available at the head office: lower the quantity");
		}
	}

	/** Only an active ERP item that is not a pack is ordered (400 otherwise, by its code). */
	static void checkOrderable(Item item) {
		String label = Quantities.itemLabel(item.getItemCode(), item.getName());
		if (Boolean.FALSE.equals(item.getActive())) {
			throw new IllegalArgumentException("Item " + label + " is inactive: it cannot be ordered");
		}
		if (!hasText(item.getNavItemNo())) {
			throw new IllegalArgumentException(
					"Item " + label + " has no ERP item number: only the head office's ERP items are ordered");
		}
		if (item.getType() == ItemType.PACKAGE) {
			throw new IllegalArgumentException("Item " + label + " is a pack: it cannot be ordered");
		}
	}

	/** Removes the item's line from the draft (none: unchanged). Empty when there is no draft. */
	public Optional<StoreOrderViewDTO> removeLine(Long itemId, String user) {
		return Optional.ofNullable(
				edits.execute(status -> lockedDraft().map(draft -> removeLine(draft, itemId, user)).orElse(null)));
	}

	private StoreOrderViewDTO removeLine(StoreOrder draft, Long itemId, String user) {
		boolean removed = draft.getLines().removeIf(line -> line.getItemId().equals(itemId));
		return removed ? save(draft, user) : StoreOrderViewDTO.of(draft);
	}

	/** The draft's note (blank: none). Empty when there is no draft. */
	public Optional<StoreOrderViewDTO> setNote(String note, String user) {
		String text = hasText(note) ? note.trim() : null;
		if (text != null && text.length() > StoreOrder.NOTE_LENGTH) {
			throw new IllegalArgumentException("note: at most " + StoreOrder.NOTE_LENGTH + " characters");
		}
		return Optional.ofNullable(edits.execute(status -> lockedDraft().map(draft -> {
			draft.setNote(text);
			return save(draft, user);
		}).orElse(null)));
	}

	/** Cancels the draft. 409 for an order that is no longer a draft; empty for an unknown one. */
	public Optional<StoreOrderViewDTO> cancel(Long id, String user) {
		return Optional.ofNullable(edits.execute(status -> orders.findForUpdate(id).map(order -> {
			requireDraft(order, "cancelled");
			order.setStatus(StoreOrderStatus.CANCELLED);
			return save(order, user);
		}).orElse(null)));
	}

	/**
	 * Sends the draft to the head office and waits for its answer, the row locked meanwhile. Accepted: SENT, sent_at.
	 * Refused by the head office: 409 with its reason; not answered: 503. Either way the order stays a draft, nothing
	 * saved. 409 for an order that is no longer a draft or has no line; empty for an unknown one.
	 */
	public Optional<StoreOrderViewDTO> send(Long id, String user) {
		return Optional.ofNullable(sends.execute(status -> orders.findForUpdate(id).map(order -> {
			requireDraft(order, "sent");
			if (order.getLines().isEmpty()) {
				throw new IllegalStateException("Order " + order.getNumber() + " has no line: nothing to send");
			}
			for (StoreOrderLine line : order.getLines()) {
				if (line.getAvailableQty() != null && line.getQuantity().compareTo(line.getAvailableQty()) > 0) {
					throw new IllegalStateException("Order " + order.getNumber() + " is not sent: item "
							+ Quantities.itemLabel(line.getItemCode(), line.getDescription()) + " asks "
							+ line.getQuantity().toPlainString() + " but only " + line.getAvailableQty().toPlainString()
							+ " are available at the head office: lower the quantity");
				}
			}
			String storeCode = client.readStoreCode();
			if (storeCode == null) {
				throw new IllegalStateException(NO_STORE_CODE);
			}
			LocalDateTime at = clock.get();
			LiveAnswer<SalesCopyResultDTO> answer = client.sendOrder(upOf(order, storeCode, at));
			if (!answer.isOk()) {
				throw new HeadOfficeUnavailableException(unavailable("send order " + order.getNumber(), answer));
			}
			SalesCopyResultDTO result = answer.getBody();
			if (!order.getNumber().equals(result.getDocumentNumber())) {
				throw new HeadOfficeUnavailableException("The head office's answer names another order ("
						+ result.getDocumentNumber() + "): order " + order.getNumber() + " is not sent");
			}
			if (!result.isAccepted()) {
				throw new IllegalStateException("The head office refused order " + order.getNumber() + ": "
						+ (hasText(result.getMessage()) ? result.getMessage() : "no reason given"));
			}
			order.setStatus(StoreOrderStatus.SENT);
			order.setSentAt(at);
			order.setErrorMessage(null);
			return save(order, user);
		}).orElse(null)));
	}

	/** The order as it travels up: by codes, dates as ISO strings, the store code as its NAV location. */
	static StoreOrderDTO upOf(StoreOrder order, String storeCode, LocalDateTime sentAt) {
		StoreOrderDTO up = new StoreOrderDTO();
		up.setNumber(order.getNumber());
		up.setLocationCode(storeCode);
		up.setNote(order.getNote());
		up.setCreatedAt(order.getCreatedAt() == null ? null : order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		up.setSentAt(sentAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		for (StoreOrderLine line : order.getLines()) {
			up.getLines().add(new StoreOrderDTO.Line(line.getItemCode(), line.getNavItemNo(), line.getVariantCode(),
					line.getDescription(), line.getVariantDescription(), line.getQuantity()));
		}
		return up;
	}

	private static void requireDraft(StoreOrder order, String action) {
		if (order.getStatus() != StoreOrderStatus.DRAFT) {
			throw new IllegalStateException(
					"Order " + order.getNumber() + " is " + order.getStatus() + ": only a draft is " + action);
		}
	}

	/** Why a live question has no usable answer, for the 503. */
	static String unavailable(String what, LiveAnswer<?> answer) {
		String why;
		if (!answer.isAnswered()) {
			why = answer.getState() + ": " + answer.getMessage();
		} else if (answer.getStatus() == 200) {
			why = "empty answer";
		} else {
			why = "HTTP " + answer.getStatus() + (hasText(answer.getError()) ? ": " + answer.getError() : "");
		}
		return "The head office is needed to " + what + " and did not answer (" + why + "). Try again when it is online.";
	}

	// ─── Reads ───────────────────────────────────────────────────

	/**
	 * A page {content, totalElements, totalPages, number, size} of orders, newest first, without their lines. status:
	 * one of {@link StoreOrderStatus}, blank or ALL for every one; from / to: the creation day, both included; search:
	 * in the order number, the head office's order number, a line's item code, ERP item number or name (blank: every
	 * order).
	 */
	public Map<String, Object> list(String status, LocalDate from, LocalDate to, String search, Integer page,
			Integer size) {
		StoreOrderStatus wanted = statusOf(status);
		String text = ItemBarcodeService.containsPattern(search);
		int number = page == null ? 0 : Math.max(page, 0);
		int rows = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		Map<String, Object> answer = edits.execute(tx -> {
			Page<StoreOrder> found = orders.findPage(wanted == null ? 1L : 0L, wanted,
					from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(),
					text == null ? 1L : 0L, text == null ? "%" : text, PageRequest.of(number, rows));
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("content", found.getContent().stream().map(StoreOrderViewDTO::row).collect(Collectors.toList()));
			result.put("totalElements", found.getTotalElements());
			result.put("totalPages", found.getTotalPages());
			result.put("number", found.getNumber());
			result.put("size", found.getSize());
			return result;
		});
		return answer;
	}

	private static StoreOrderStatus statusOf(String status) {
		if (!hasText(status) || "ALL".equalsIgnoreCase(status.trim())) {
			return null;
		}
		try {
			return StoreOrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown status: " + status);
		}
	}

	/** One order with its lines. */
	public Optional<StoreOrderViewDTO> get(Long id) {
		return Optional.ofNullable(edits.execute(status -> orders.findById(id).map(StoreOrderViewDTO::of).orElse(null)));
	}

	// ─── Inside a transaction ────────────────────────────────────

	/** The draft, not locked; empty when there is none. */
	private Optional<StoreOrder> currentDraft() {
		return orders.findByStatusOrderByIdAsc(StoreOrderStatus.DRAFT).stream().findFirst();
	}

	/** The draft, its row locked; empty when there is none (or it was sent or cancelled while waiting for the lock). */
	private Optional<StoreOrder> lockedDraft() {
		return currentDraft().flatMap(draft -> orders.findForUpdate(draft.getId()))
				.filter(draft -> draft.getStatus() == StoreOrderStatus.DRAFT);
	}

	private StoreOrder newDraft(String user) {
		String storeCode = client.readStoreCode();
		if (storeCode == null) {
			throw new IllegalStateException(NO_STORE_CODE);
		}
		StoreOrder draft = new StoreOrder();
		draft.setNumber(nextNumber(storeCode, clock.get().getYear()));
		draft.setStatus(StoreOrderStatus.DRAFT);
		draft.setCreatedAt(clock.get());
		draft.setCreatedBy(user == null ? draft.getCreatedBy() : user);
		return draft;
	}

	/** Store code, year and the next sequence of that year, 6 digits: S01-2026-000012. */
	String nextNumber(String storeCode, int year) {
		String prefix = storeCode + "-" + year + "-";
		if (prefix.length() + 6 > StoreOrder.NUMBER_LENGTH) {
			throw new IllegalStateException("Store code " + storeCode + " is too long for an order number");
		}
		String last = orders.findLastNumber(likePrefix(prefix));
		long next = 1;
		if (last != null && last.startsWith(prefix)) {
			try {
				next = Long.parseLong(last.substring(prefix.length())) + 1;
			} catch (NumberFormatException e) {
				throw new IllegalStateException("Order number " + last + " does not end with its sequence");
			}
		}
		return prefix + String.format(Locale.ROOT, "%06d", next);
	}

	/** The prefix as a LIKE pattern: its % and _ (a store code may hold them) read as themselves. */
	static String likePrefix(String prefix) {
		return prefix.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]") + "%";
	}

	private StoreOrderViewDTO save(StoreOrder order, String user) {
		order.recount();
		if (user != null) {
			order.setUpdatedBy(user);
		}
		return StoreOrderViewDTO.of(orders.save(order));
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
