package com.digithink.zsretail.headoffice.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoLoyaltyMovementRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.headoffice.service.ConsolidatedSalesService.HistoryQuery;
import com.digithink.zsretail.model.LoyaltyMember;

/**
 * Head office plan, step 5: the overspend report. A store spends against its own balance, never blocked by default;
 * when one of its removals (points spent, or removed after a return) reaches the head office with the balance already
 * lower, the balance stops at zero and the points missing are kept on the movement (ho_loyalty_movement
 * .overspend_points). This lists them, and counts them for the home page. Head office only.
 */
@Service
@ConditionalOnHeadOffice
public class HoLoyaltyReportService {

	private final HoLoyaltyMovementRepository movements;
	private final StoreRepository stores;

	public HoLoyaltyReportService(HoLoyaltyMovementRepository movements, StoreRepository stores) {
		this.movements = movements;
		this.stores = stores;
	}

	/**
	 * GET /admin/headoffice/loyalty/overspends: {content, totalElements, totalPages, number, size}, newest first. Filters
	 * as the consolidated sales lists: storeId, dateFrom, dateTo (yyyy-MM-dd or yyyy-MM-ddTHH:mm), search (card,
	 * member name, sale or return number, contains, any case). IllegalArgumentException (400) on a bad date or page.
	 */
	public Map<String, Object> overspends(Integer page, Integer size, Long storeId, String dateFrom, String dateTo,
			String search) {
		HistoryQuery query = HistoryQuery.of(page, size, storeId, dateFrom, dateTo, search, null, null);
		Page<Object[]> rows = movements.findOverspends(query.getStoreId(), query.getDateFrom(), query.getDateTo(),
				query.getNumber(), PageRequest.of(query.getPage(), query.getSize()));
		Set<Long> storeIds = new TreeSet<>();
		for (Object[] row : rows.getContent()) {
			storeIds.add(((HoLoyaltyMovement) row[0]).getStoreId());
		}
		Map<Long, Store> byId = new HashMap<>();
		if (!storeIds.isEmpty()) {
			stores.findAllById(storeIds).forEach(store -> byId.put(store.getId(), store));
		}
		List<Map<String, Object>> content = new ArrayList<>();
		for (Object[] row : rows.getContent()) {
			HoLoyaltyMovement movement = (HoLoyaltyMovement) row[0];
			LoyaltyMember member = (LoyaltyMember) row[1];
			Store store = byId.get(movement.getStoreId());
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", movement.getId());
			item.put("receivedAt", movement.getCreatedAt());
			item.put("storeDate", movement.getStoreDate());
			item.put("storeId", movement.getStoreId());
			item.put("storeCode", store == null ? null : store.getCode());
			item.put("storeName", store == null ? null : store.getName());
			item.put("cardNumber", movement.getCardNumber());
			item.put("memberCardNumber", member.getCardNumber());
			item.put("memberName", (member.getFirstName() + " " + member.getLastName()).trim());
			item.put("type", movement.getType());
			item.put("salesNumber", movement.getSalesNumber());
			item.put("returnNumber", movement.getReturnNumber());
			item.put("points", movement.getPoints());
			item.put("overspendPoints", movement.getOverspendPoints());
			item.put("transactionId", movement.getTransactionId());
			content.add(item);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", content);
		answer.put("totalElements", rows.getTotalElements());
		answer.put("totalPages", rows.getTotalPages());
		answer.put("number", query.getPage());
		answer.put("size", query.getSize());
		return answer;
	}

	/** GET /admin/headoffice/loyalty/overspends/count: {count, points} in the period (every period by default). */
	public Map<String, Object> count(String dateFrom, String dateTo) {
		HistoryQuery query = HistoryQuery.of(0, 1, null, dateFrom, dateTo, null, null, null);
		List<Object[]> totals = movements.overspendTotals(query.getDateFrom(), query.getDateTo());
		Object[] row = totals.isEmpty() ? new Object[] { 0L, 0L } : totals.get(0);
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("count", row[0] == null ? 0L : ((Number) row[0]).longValue());
		answer.put("points", row[1] == null ? 0L : ((Number) row[1]).longValue());
		return answer;
	}

	/** For the tests: the bounds a blank filter gives. */
	static LocalDateTime[] bounds(String dateFrom, String dateTo) {
		HistoryQuery query = HistoryQuery.of(0, 1, null, dateFrom, dateTo, null, null, null);
		return new LocalDateTime[] { query.getDateFrom(), query.getDateTo() };
	}
}
