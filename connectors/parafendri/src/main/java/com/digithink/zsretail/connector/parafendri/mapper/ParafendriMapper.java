package com.digithink.zsretail.connector.parafendri.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriBarcodeRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCustomerRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriLocationRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrder;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

/**
 * ParaFendri, step 2: the rows of the API to the Erp*DTO classes. The tree itemCategories, as ZEN's (same API family):
 * by indentation, 0 catégorie, 1 famille, 2 sous-famille, 3 section, the parent parentCategory; code and external id
 * the code, name and description the description, display order presentationOrder, active.
 * <ul>
 * <li>A famille keeps its catégorie (categoryExternalId) when its parent is a catégorie of the read, else it is kept
 * without one and noted ({@link #FAMILY_WITHOUT_CATEGORY}): its sous-familles and items keep their place.</li>
 * <li>Left out, each with its reason: a sous-famille whose parent is not a famille of the read, a section whose parent
 * is not a sous-famille of the read (the import would never save them), a blank code (counted at its level), and a row
 * without a level 0 to 3 (counted with the sections, the deepest level).</li>
 * </ul>
 * Each level's read counts the rows of that level only: a row of another level is not "left out" of it. Step 3: the
 * items of stockPoints ({@link #items}). Step 4: the barcodes of barCodes ({@link #barcodes}).
 */
public class ParafendriMapper {

	public static final String BLANK_CODE = "blank code";
	public static final String FAMILY_WITHOUT_CATEGORY = "famille whose parent is not a catégorie";
	public static final String PARENT_NOT_FAMILY = "parent not a famille of the read";
	public static final String PARENT_NOT_SUB_FAMILY = "parent not a sous-famille of the read";
	public static final String NO_INDENTATION = "no indentation";

	static final int CATEGORY = 0;
	static final int FAMILY = 1;
	static final int SUB_FAMILY = 2;
	static final int SECTION = 3;

	/** e.g. "indentation 4 (deeper than a section)": a row outside the four levels. */
	public static String outsideLevels(int indentation) {
		return "indentation " + indentation + (indentation > SECTION ? " (deeper than a section)" : " (not a level)");
	}

	/** The four levels of the tree from one read of the page. */
	public ErpCategories categories(List<ParafendriItemCategoryRow> rows) {
		Map<String, Integer> levels = new HashMap<>();
		for (ParafendriItemCategoryRow row : rows) {
			Integer level = row.getIndentation();
			if (!trimmed(row.getCode()).isEmpty() && level != null && level >= CATEGORY && level <= SECTION) {
				levels.putIfAbsent(trimmed(row.getCode()), level);
			}
		}
		List<ErpItemCategoryDTO> categories = new ArrayList<>();
		List<ErpItemFamilyDTO> families = new ArrayList<>();
		List<ErpItemSubFamilyDTO> subFamilies = new ArrayList<>();
		List<ErpItemSectionDTO> sections = new ArrayList<>();
		int[] read = new int[SECTION + 1];
		List<Map<String, Integer>> leftOut = new ArrayList<>();
		List<Map<String, Integer>> notes = new ArrayList<>();
		for (int level = CATEGORY; level <= SECTION; level++) {
			leftOut.add(new LinkedHashMap<>());
			notes.add(new LinkedHashMap<>());
		}
		for (ParafendriItemCategoryRow row : rows) {
			Integer level = row.getIndentation();
			if (level == null || level < CATEGORY || level > SECTION) {
				read[SECTION]++;
				count(leftOut.get(SECTION), level == null ? NO_INDENTATION : outsideLevels(level));
				continue;
			}
			read[level]++;
			String code = trimmed(row.getCode());
			if (code.isEmpty()) {
				count(leftOut.get(level), BLANK_CODE);
				continue;
			}
			String parent = trimmed(row.getParentCategory());
			Integer parentLevel = levels.get(parent);
			String name = trimmed(row.getDescription());
			switch (level) {
				case CATEGORY: {
					ErpItemCategoryDTO category = new ErpItemCategoryDTO();
					category.setExternalId(code);
					category.setCode(code);
					category.setName(name);
					category.setDescription(name);
					category.setDisplayOrder(row.getPresentationOrder());
					category.setActive(Boolean.TRUE);
					categories.add(category);
					break;
				}
				case FAMILY: {
					ErpItemFamilyDTO family = new ErpItemFamilyDTO();
					family.setExternalId(code);
					family.setCode(code);
					family.setName(name);
					family.setDescription(name);
					family.setDisplayOrder(row.getPresentationOrder());
					family.setActive(Boolean.TRUE);
					if (parentLevel != null && parentLevel == CATEGORY) {
						family.setCategoryExternalId(parent);
					} else {
						count(notes.get(FAMILY), FAMILY_WITHOUT_CATEGORY);
					}
					families.add(family);
					break;
				}
				case SUB_FAMILY: {
					if (parentLevel == null || parentLevel != FAMILY) {
						count(leftOut.get(SUB_FAMILY), PARENT_NOT_FAMILY);
						break;
					}
					ErpItemSubFamilyDTO subFamily = new ErpItemSubFamilyDTO();
					subFamily.setExternalId(code);
					subFamily.setCode(code);
					subFamily.setName(name);
					subFamily.setDescription(name);
					subFamily.setDisplayOrder(row.getPresentationOrder());
					subFamily.setActive(Boolean.TRUE);
					subFamily.setFamilyExternalId(parent);
					subFamilies.add(subFamily);
					break;
				}
				default: {
					if (parentLevel == null || parentLevel != SUB_FAMILY) {
						count(leftOut.get(SECTION), PARENT_NOT_SUB_FAMILY);
						break;
					}
					ErpItemSectionDTO section = new ErpItemSectionDTO();
					section.setExternalId(code);
					section.setCode(code);
					section.setName(name);
					section.setDescription(name);
					section.setDisplayOrder(row.getPresentationOrder());
					section.setActive(Boolean.TRUE);
					section.setSubFamilyExternalId(parent);
					sections.add(section);
					break;
				}
			}
		}
		return new ErpCategories(
				new ErpRead<>(categories, read[CATEGORY], leftOut.get(CATEGORY), notes.get(CATEGORY), null),
				new ErpRead<>(families, read[FAMILY], leftOut.get(FAMILY), notes.get(FAMILY), null),
				new ErpRead<>(subFamilies, read[SUB_FAMILY], leftOut.get(SUB_FAMILY), notes.get(SUB_FAMILY), null),
				new ErpRead<>(sections, read[SECTION], leftOut.get(SECTION), notes.get(SECTION), null));
	}

	// ─── Items (step 3, stockPoints) ────────────────────────────

	public static final String BLANK_ITEM_NO = "blank itemNo";
	public static final String OTHER_LOCATION = "row of another location";
	public static final String SAME_ITEM = "same itemNo twice";
	public static final String NO_VAT = "no vatPercent";
	public static final String ZERO_PRICE = "null or zero unitPrice";
	public static final String VAT_NOT_WHOLE = "vatPercent not a whole number (rounded)";
	public static final String ITEM_BLOCKED = "itemBlocked";
	public static final String ITEM_SALES_BLOCKED = "itemSalesBlocked";
	public static final String STOCK_POINT_BLOCKED = "stockPointBlocked";
	public static final String NO_DATE = "no lastModifiedDateTime (0001-01-01)";
	public static final String CATEGORY_NOT_IN_TREE = "categoryCode not a catégorie of the tree";
	public static final String FAMILY_NOT_IN_TREE = "familyCode not a famille of the tree";
	public static final String SUB_FAMILY_NOT_IN_TREE = "subFamilyCode not a sous-famille of the tree";
	public static final String SECTION_NOT_IN_TREE = "sectionCode not a section of the tree";

	/**
	 * Step 3: the rows of one point de vente to items. Code and external id itemNo; name and description
	 * stockPointDescription, else itemDescription; unit baseUnitOfMeasure (blank: none); the price unitPrice as received
	 * (before VAT, never rounded: the till adds the VAT); the VAT vatPercent (a whole number; else rounded and noted);
	 * lastModifiedAt lastModifiedDateTime (0001-01-01: none, noted). Inactive, kept and noted under its reason: itemBlocked,
	 * itemSalesBlocked, stockPointBlocked, a null or zero unitPrice. The four links (categoryCode, familyCode,
	 * subFamilyCode, sectionCode): a blank code none; a code that is not a row the tree keeps at its level ({@link
	 * #categories}) none and noted, the item kept. Left out with their reason: a blank itemNo, a row of another location,
	 * no vatPercent, the same itemNo twice (the first kept). Not read: itemCategoryCode, customerNo.
	 */
	public ErpRead<ErpItemDTO> items(String locationCode, List<ParafendriStockPointRow> rows, ErpCategories tree) {
		Set<String> categories = codes(tree.getCategories().getRows(), ErpItemCategoryDTO::getCode);
		Set<String> families = codes(tree.getFamilies().getRows(), ErpItemFamilyDTO::getCode);
		Set<String> subFamilies = codes(tree.getSubFamilies().getRows(), ErpItemSubFamilyDTO::getCode);
		Set<String> sections = codes(tree.getSections().getRows(), ErpItemSectionDTO::getCode);
		String location = trimmed(locationCode);
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		List<ErpItemDTO> items = new ArrayList<>();
		for (ParafendriStockPointRow row : rows) {
			String itemNo = trimmed(row.getItemNo());
			if (itemNo.isEmpty()) {
				count(leftOut, BLANK_ITEM_NO);
				continue;
			}
			if (!trimmed(row.getLocationCode()).equalsIgnoreCase(location)) {
				count(leftOut, OTHER_LOCATION);
				continue;
			}
			if (row.getVatPercent() == null) {
				count(leftOut, NO_VAT);
				continue;
			}
			if (!seen.add(itemNo)) {
				count(leftOut, SAME_ITEM);
				continue;
			}
			ErpItemDTO item = new ErpItemDTO();
			item.setExternalId(itemNo);
			item.setCode(itemNo);
			String description = trimmed(row.getStockPointDescription());
			if (description.isEmpty()) {
				description = trimmed(row.getItemDescription());
			}
			item.setName(blankToNull(description));
			item.setDescription(blankToNull(description));
			item.setUnitOfMeasure(blankToNull(row.getBaseUnitOfMeasure()));
			BigDecimal vat = row.getVatPercent().setScale(0, RoundingMode.HALF_UP);
			if (vat.compareTo(row.getVatPercent()) != 0) {
				count(notes, VAT_NOT_WHOLE);
			}
			item.setDefaultVAT(vat.intValue());
			boolean active = true;
			BigDecimal price = row.getUnitPrice();
			if (price == null || price.signum() <= 0) {
				count(notes, ZERO_PRICE);
				price = price == null ? BigDecimal.ZERO : price;
				active = false;
			}
			item.setUnitPrice(price);
			if (Boolean.TRUE.equals(row.getItemBlocked())) {
				count(notes, ITEM_BLOCKED);
				active = false;
			}
			if (Boolean.TRUE.equals(row.getItemSalesBlocked())) {
				count(notes, ITEM_SALES_BLOCKED);
				active = false;
			}
			if (Boolean.TRUE.equals(row.getStockPointBlocked())) {
				count(notes, STOCK_POINT_BLOCKED);
				active = false;
			}
			item.setActive(active);
			item.setCategoryCode(link(row.getCategoryCode(), categories, notes, CATEGORY_NOT_IN_TREE));
			item.setFamilyExternalId(link(row.getFamilyCode(), families, notes, FAMILY_NOT_IN_TREE));
			item.setSubFamilyExternalId(link(row.getSubFamilyCode(), subFamilies, notes, SUB_FAMILY_NOT_IN_TREE));
			item.setSectionCode(link(row.getSectionCode(), sections, notes, SECTION_NOT_IN_TREE));
			OffsetDateTime modified = modifiedAt(row.getLastModifiedDateTime());
			if (modified == null) {
				count(notes, NO_DATE);
			}
			item.setLastModifiedAt(modified);
			items.add(item);
		}
		return new ErpRead<>(items, rows.size(), leftOut, notes, null);
	}

	// ─── Barcodes (step 4, barCodes) ────────────────────────────

	public static final String BLANK_BARCODE = "blank barCode";
	public static final String BLANK_BARCODE_ITEM = "blank itemNo";

	/**
	 * Step 4: the rows of barCodes to barcodes: the value barCode, the item itemNo (item.erp_external_id since step 3),
	 * the row's external id its id (the GUID: a barcode changed in Business Central replaces the old one; no id: the
	 * value, as the import does), lastModifiedAt lastModifiedDateTime (0001-01-01: none, noted). The primary flag is not
	 * set (as EMTOP's: the import leaves it). Left out with their reason: a blank barCode, a blank itemNo. A barcode of an
	 * item this store does not have is kept: the import leaves it out.
	 */
	public ErpRead<ErpItemBarcodeDTO> barcodes(List<ParafendriBarcodeRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		List<ErpItemBarcodeDTO> barcodes = new ArrayList<>();
		for (ParafendriBarcodeRow row : rows) {
			String value = trimmed(row.getBarCode());
			String itemNo = trimmed(row.getItemNo());
			if (value.isEmpty()) {
				count(leftOut, BLANK_BARCODE);
				continue;
			}
			if (itemNo.isEmpty()) {
				count(leftOut, BLANK_BARCODE_ITEM);
				continue;
			}
			ErpItemBarcodeDTO barcode = new ErpItemBarcodeDTO();
			String id = trimmed(row.getId());
			barcode.setExternalId(id.isEmpty() ? value : id);
			barcode.setBarcode(value);
			barcode.setItemExternalId(itemNo);
			OffsetDateTime modified = modifiedAt(row.getLastModifiedDateTime());
			if (modified == null) {
				count(notes, NO_DATE);
			}
			barcode.setLastModifiedAt(modified);
			barcodes.add(barcode);
		}
		return new ErpRead<>(barcodes, rows.size(), leftOut, notes, null);
	}

	// ─── Locations and the point de vente's customer ───────────

	public static final String SAME_CODE = "same code twice";
	public static final String BLANK_NUMBER = "blank number";
	public static final String OTHER_CUSTOMER = "another customer than the point de vente's";
	/** Business Central's blank option of blocked (not blocked), as the API writes it. */
	public static final String NOT_BLOCKED = "_x0020_";

	/**
	 * Every location of the company (the points de vente), code and name; a blank code, or a code read twice (its case
	 * ignored), left out. None is marked as the default: the admin picks it on the Emplacements page (DEFAULT_LOCATION).
	 */
	public ErpRead<ErpLocationDTO> locations(List<ParafendriLocationRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		List<ErpLocationDTO> locations = new ArrayList<>();
		for (ParafendriLocationRow row : rows) {
			String code = trimmed(row.getCode());
			if (code.isEmpty()) {
				count(leftOut, BLANK_CODE);
				continue;
			}
			if (!seen.add(code.toUpperCase(Locale.ROOT))) {
				count(leftOut, SAME_CODE);
				continue;
			}
			ErpLocationDTO location = new ErpLocationDTO();
			location.setExternalId(code);
			location.setCode(code);
			location.setName(trimmed(row.getName()));
			locations.add(location);
		}
		return new ErpRead<>(locations, rows.size(), leftOut, new LinkedHashMap<>(), null);
	}

	/**
	 * The point de vente's customer only (customerNo of its stockPoints rows): the first row of that number (its case
	 * ignored), code and ERP id its number, active unless blocked; any other row (another point de vente's account, when
	 * the page was read whole) left out, never imported.
	 */
	public ErpRead<ErpCustomerDTO> customer(String customerNo, List<ParafendriCustomerRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		List<ErpCustomerDTO> customers = new ArrayList<>();
		String wanted = trimmed(customerNo);
		for (ParafendriCustomerRow row : rows) {
			String number = trimmed(row.getNumber());
			if (number.isEmpty()) {
				count(leftOut, BLANK_NUMBER);
				continue;
			}
			if (!number.equalsIgnoreCase(wanted) || !customers.isEmpty()) {
				count(leftOut, number.equalsIgnoreCase(wanted) ? SAME_CODE : OTHER_CUSTOMER);
				continue;
			}
			ErpCustomerDTO customer = new ErpCustomerDTO();
			customer.setExternalId(number);
			customer.setCode(number);
			customer.setName(trimmed(row.getName()));
			customer.setActive(!blocked(row.getBlocked()));
			OffsetDateTime modified = modifiedAt(row.getLastModifiedDateTime());
			if (modified == null) {
				count(notes, NO_DATE);
			}
			customer.setLastModifiedAt(modified);
			customers.add(customer);
		}
		return new ErpRead<>(customers, rows.size(), leftOut, notes, null);
	}

	/** Blocked unless blank or {@link #NOT_BLOCKED} (Ship, Invoice and All all block the till's sale). */
	static boolean blocked(String value) {
		String text = trimmed(value);
		return !text.isEmpty() && !NOT_BLOCKED.equals(text);
	}

	// ─── Tickets (step 6, salesOrders) ──────────────────────────

	/** Business Central's line numbers: 10000, 20000, ... (the step of its own pages). */
	public static final int LINE_NO_STEP = 10000;
	/** Decimals of a percentage sent (Business Central keeps 5 on Line Discount %; the amounts and prices as stored). */
	static final int PERCENT_DECIMALS = 5;

	/**
	 * The item code TicketExportService gives the tax stamp when the General Setup TAX_STAMP_ERP_ITEM_CODE is empty (the
	 * local item's code, unknown to Business Central).
	 */
	public static final String LOCAL_TAX_STAMP = "TAX_STAMP";
	public static final String NO_TAX_STAMP_ITEM = "no tax stamp line sent: the General Setup TAX_STAMP_ERP_ITEM_CODE is"
			+ " empty (set it to the stamp's item in BC)";

	/**
	 * Step 6: one ticket to the body of POST salesOrders, its lines inside (all to confirm with Sayf, ParaFendri's BC
	 * contact). The header: ticketNo the ticket number; customerNo the point de vente's customer (customerNo of its
	 * stockPoints rows); locationCode DEFAULT_LOCATION; orderDate and postingDate the ticket's date; ticketAmount the total
	 * excluding VAT after the ticket-wide reduction (SalesHeader.subtotal); amountIncludingVAT never sent, header or line
	 * (Business Central computes it: read-only, its own in the answer); discountPercent the ticket-wide reduction (discount and loyalty points) as a % of the lines' TTC, the %
	 * EMTOP sends as Discount_Percent, 0 without one; invoiceDiscountAmount and invoiceDiscountPercent 0; fenceNo the
	 * cashier session number, as EMTOP's Fence_No. Each line: lineNo 10000 × its position among the lines sent; itemNo the
	 * item's ERP code (the tax stamp's TAX_STAMP_ERP_ITEM_CODE, as for EMTOP; without it no stamp line, warned once:
	 * {@link #NO_TAX_STAMP_ITEM}); quantity (decimals kept); unitPrice before VAT as stored, never rounded;
	 * lineDiscountPercent (a free line: 100, its price kept, as EMTOP's); promotionNo the promotion's code, else "". A
	 * missing amount or percentage is 0. warnings (may be null) gets what the run should say once.
	 */
	public ParafendriSalesOrder salesOrder(ErpTicketDTO ticket, String customerNo, String locationCode,
			List<String> warnings) {
		ParafendriSalesOrder order = new ParafendriSalesOrder();
		order.setTicketNo(trimmed(ticket.getTicketNumber()));
		order.setCustomerNo(trimmed(customerNo));
		order.setLocationCode(trimmed(locationCode));
		String date = ticket.getSaleDate() == null ? null : ticket.getSaleDate().toLocalDate().toString();
		order.setOrderDate(date);
		order.setPostingDate(date);
		order.setTicketAmount(orZero(ticket.getTotalAmountExcludingVat()));
		order.setDiscountPercent(percent(ticket.getDiscountPercentage() == null ? null
				: BigDecimal.valueOf(ticket.getDiscountPercentage())));
		order.setInvoiceDiscountAmount(BigDecimal.ZERO);
		order.setInvoiceDiscountPercent(BigDecimal.ZERO);
		order.setFenceNo(blankToNull(ticket.getCashierSessionId()));
		int position = 0;
		for (ErpTicketLineDTO line : ticket.getLines()) {
			if (LOCAL_TAX_STAMP.equals(trimmed(line.getItemExternalId()))) {
				// The stamp without its item in Business Central (General Setup TAX_STAMP_ERP_ITEM_CODE empty): no line
				if (warnings != null && !warnings.contains(NO_TAX_STAMP_ITEM)) {
					warnings.add(NO_TAX_STAMP_ITEM);
				}
				continue;
			}
			position++;
			ParafendriSalesOrder.Line orderLine = new ParafendriSalesOrder.Line();
			orderLine.setLineNo(position * LINE_NO_STEP);
			orderLine.setItemNo(trimmed(line.getItemExternalId()));
			orderLine.setQuantity(line.getQuantity());
			orderLine.setUnitPrice(orZero(line.getUnitPrice()));
			orderLine.setLineDiscountPercent(percent(line.getDiscountPercentage()));
			orderLine.setPromotionNo(trimmed(line.getPromotionCode()));
			order.getSalesOrderLines().add(orderLine);
		}
		return order;
	}

	private static BigDecimal orZero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	/** A percentage at {@link #PERCENT_DECIMALS} decimals, trailing zeros dropped; none: 0. */
	private static BigDecimal percent(BigDecimal value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal rounded = value.setScale(PERCENT_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros();
		return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
	}

	/** A blank code: none; a code the tree does not keep at its level: none, noted; else the code. */
	private static String link(String value, Set<String> tree, Map<String, Integer> notes, String reason) {
		String code = trimmed(value);
		if (code.isEmpty()) {
			return null;
		}
		if (!tree.contains(code)) {
			count(notes, reason);
			return null;
		}
		return code;
	}

	/** lastModifiedDateTime, null when blank, Business Central's empty date (0001-01-01) or not readable. */
	public static OffsetDateTime modifiedAt(String value) {
		String text = trimmed(value);
		if (text.isEmpty() || text.startsWith("0001-01-01")) {
			return null;
		}
		try {
			return OffsetDateTime.parse(text);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static <T> Set<String> codes(List<T> rows, Function<T, String> code) {
		Set<String> codes = new HashSet<>();
		for (T row : rows) {
			codes.add(code.apply(row));
		}
		return codes;
	}

	private static String blankToNull(String value) {
		String text = trimmed(value);
		return text.isEmpty() ? null : text;
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static void count(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}
}
