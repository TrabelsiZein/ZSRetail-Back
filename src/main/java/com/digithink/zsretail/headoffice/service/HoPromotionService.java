package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.PromotionCopyDTO;
import com.digithink.zsretail.headoffice.dto.PromotionTargetsDTO;
import com.digithink.zsretail.headoffice.dto.PromotionWithTargetsDTO;
import com.digithink.zsretail.headoffice.model.HoPromotionStore;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoPromotionStoreRepository;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.service.PromotionHeadOfficeHooks;
import com.digithink.zsretail.service.PromotionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Head office plan, task 3.3: the promotions of a head office on the copies down mechanism. Target stores per
 * promotion (every store by default, or a list, in ho_promotion_store), the copies by codes for each store, the change
 * of every save, delete and target change, and the usage count of the whole network (the tickets received from the
 * stores). Head office only. See docs/modules/head-office.md, "Promotions owned by the head office".
 */
@Service
@ConditionalOnHeadOffice
public class HoPromotionService implements DownDomainProvider, PromotionHeadOfficeHooks {

	/** The copies: dates as ISO strings (2026-10-03, 10:00:00). */
	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	static final String LIST_REQUIRED = "Choose every store, or at least one store.";

	private final PromotionRepository promotions;
	private final HoPromotionStoreRepository targets;
	private final StoreRepository stores;
	private final HoTicketRepository tickets;

	/** Looked up at the call: the feed itself collects the providers, this one included. */
	private final Supplier<CopiesDownFeed> feed;
	private final Supplier<PromotionService> promotionService;

	/** True while {@link #createWithTargets} saves the promotion: its change is recorded once, with its targets. */
	private final ThreadLocal<Boolean> creating = new ThreadLocal<>();

	@Autowired
	public HoPromotionService(PromotionRepository promotions, HoPromotionStoreRepository targets,
			StoreRepository stores, HoTicketRepository tickets, ObjectProvider<CopiesDownFeed> feed,
			ObjectProvider<PromotionService> promotionService) {
		this(promotions, targets, stores, tickets, (Supplier<CopiesDownFeed>) feed::getObject,
				(Supplier<PromotionService>) promotionService::getObject);
	}

	/** With given collaborators: used by the tests. */
	public HoPromotionService(PromotionRepository promotions, HoPromotionStoreRepository targets,
			StoreRepository stores, HoTicketRepository tickets, Supplier<CopiesDownFeed> feed,
			Supplier<PromotionService> promotionService) {
		this.promotions = promotions;
		this.targets = targets;
		this.stores = stores;
		this.tickets = tickets;
		this.feed = feed;
		this.promotionService = promotionService;
	}

	// ─── Copies down ─────────────────────────────────────────────

	@Override
	public DataDomain getDomain() {
		return DataDomain.PROMOTIONS;
	}

	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		List<Promotion> found = promotions.findByCodeIn(codes);
		Map<Long, StoreTargets> targetsById = targetsOf(found.stream().map(Promotion::getId).collect(Collectors.toList()));
		Map<String, JsonNode> copies = new LinkedHashMap<>();
		for (Promotion promotion : found) {
			if (targetsById.get(promotion.getId()).includes(store.getId())) {
				copies.put(promotion.getCode(), COPY_MAPPER.valueToTree(PromotionCopyDTO.of(promotion)));
			}
		}
		return copies;
	}

	@Override
	public Map<String, StoreTargets> currentTargets() {
		List<Promotion> all = promotions.findAll();
		Map<Long, StoreTargets> targetsById = targetsOf(all.stream().map(Promotion::getId).collect(Collectors.toList()));
		Map<String, StoreTargets> result = new LinkedHashMap<>();
		for (Promotion promotion : all) {
			result.put(promotion.getCode(), targetsById.get(promotion.getId()));
		}
		return result;
	}

	// ─── Hooks of PromotionService ───────────────────────────────

	@Override
	@Transactional
	public void afterSave(String previousCode, Promotion saved) {
		if (Boolean.TRUE.equals(creating.get())) {
			return;
		}
		StoreTargets current = targetsOf(saved.getId());
		if (previousCode != null && !previousCode.equals(saved.getCode())) {
			feed.get().recordChange(DataDomain.PROMOTIONS, previousCode, current); // the old code is removed
		}
		feed.get().recordChange(DataDomain.PROMOTIONS, saved.getCode(), current);
	}

	@Override
	@Transactional
	public void beforeDelete(Promotion promotion) {
		StoreTargets current = targetsOf(promotion.getId());
		List<HoPromotionStore> rows = targets.findByPromotionId(promotion.getId());
		if (!rows.isEmpty()) {
			targets.deleteAll(rows);
		}
		feed.get().recordChange(DataDomain.PROMOTIONS, promotion.getCode(), current);
	}

	@Override
	public long networkUsageCount(String code) {
		if (code == null) {
			return 0;
		}
		return tickets.countByPromotionCode(code) + tickets.countLinesByPromotionCode(code);
	}

	// ─── Targets ─────────────────────────────────────────────────

	/** Every promotion with its targets, for the list page (without the stores' names). */
	public List<PromotionTargetsDTO> listTargets() {
		List<Promotion> all = promotions.findAll();
		Map<Long, StoreTargets> targetsById = targetsOf(all.stream().map(Promotion::getId).collect(Collectors.toList()));
		List<PromotionTargetsDTO> result = new ArrayList<>();
		for (Promotion promotion : all) {
			result.add(view(promotion, targetsById.get(promotion.getId()), false));
		}
		return result;
	}

	/** One promotion's targets with the stores' code and name. Throws NoSuchElementException when unknown. */
	public PromotionTargetsDTO getTargets(Long promotionId) {
		Promotion promotion = promotions.findById(promotionId)
				.orElseThrow(() -> new NoSuchElementException("Not found"));
		return view(promotion, targetsOf(promotionId), true);
	}

	/**
	 * Changes a promotion's targets; the stores taken off get a removal, the stores added get the promotion. Unchanged
	 * targets record nothing. Throws NoSuchElementException (unknown promotion), IllegalArgumentException (empty list,
	 * unknown store).
	 */
	@Transactional
	public PromotionTargetsDTO setTargets(Long promotionId, PromotionTargetsDTO request) {
		Promotion promotion = promotions.findById(promotionId)
				.orElseThrow(() -> new NoSuchElementException("Not found"));
		StoreTargets wanted = parse(request == null ? null : request.getAllStores(),
				request == null ? null : request.getStoreIds(), false);
		StoreTargets old = targetsOf(promotionId);
		if (!old.equals(wanted)) {
			replaceRows(promotionId, wanted);
			feed.get().recordChange(DataDomain.PROMOTIONS, promotion.getCode(), old.union(wanted));
		}
		return view(promotion, wanted, true);
	}

	/**
	 * Creates a promotion with its targets in one transaction: no store outside the list ever receives it. Targets
	 * absent: every store. Throws IllegalArgumentException for invalid targets or an invalid promotion (as POST
	 * /promotion).
	 */
	@Transactional
	public Map<String, Object> createWithTargets(PromotionWithTargetsDTO request) throws Exception {
		if (request == null || request.getPromotion() == null) {
			throw new IllegalArgumentException("promotion is required");
		}
		StoreTargets wanted = parse(request.getAllStores(), request.getStoreIds(), true);
		Promotion promotion = request.getPromotion();
		promotion.setId(null);
		promotion.setOrigin(null);
		Promotion saved;
		creating.set(Boolean.TRUE);
		try {
			saved = promotionService.get().save(promotion);
		} finally {
			creating.remove();
		}
		replaceRows(saved.getId(), wanted);
		feed.get().recordChange(DataDomain.PROMOTIONS, saved.getCode(), wanted);
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("promotion", saved);
		answer.put("targets", view(saved, wanted, true));
		return answer;
	}

	/**
	 * allStores true: every store. Otherwise the list, which must hold at least one existing store. Both absent: every
	 * store when {@code defaultAll}, refused otherwise.
	 */
	StoreTargets parse(Boolean allStores, List<Long> storeIds, boolean defaultAll) {
		if (Boolean.TRUE.equals(allStores)) {
			return StoreTargets.all();
		}
		StoreTargets list = StoreTargets.of(storeIds);
		if (list.getStoreIds().isEmpty()) {
			if (defaultAll && allStores == null) {
				return StoreTargets.all();
			}
			throw new IllegalArgumentException(LIST_REQUIRED);
		}
		Set<Long> known = new TreeSet<>();
		stores.findAllById(list.getStoreIds()).forEach(store -> known.add(store.getId()));
		Set<Long> unknown = new TreeSet<>(list.getStoreIds());
		unknown.removeAll(known);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown store id(s): " + unknown);
		}
		return list;
	}

	private void replaceRows(Long promotionId, StoreTargets wanted) {
		List<HoPromotionStore> rows = targets.findByPromotionId(promotionId);
		if (!rows.isEmpty()) {
			targets.deleteAll(rows);
		}
		for (Long storeId : wanted.getStoreIds()) {
			HoPromotionStore row = new HoPromotionStore();
			row.setPromotionId(promotionId);
			row.setStoreId(storeId);
			targets.save(row);
		}
	}

	StoreTargets targetsOf(Long promotionId) {
		List<Long> ids = new ArrayList<>();
		for (HoPromotionStore row : targets.findByPromotionId(promotionId)) {
			ids.add(row.getStoreId());
		}
		return ids.isEmpty() ? StoreTargets.all() : StoreTargets.of(ids);
	}

	/** The targets of each promotion, by id; every promotion of the list gets an entry. */
	private Map<Long, StoreTargets> targetsOf(Collection<Long> promotionIds) {
		Map<Long, List<Long>> rows = new HashMap<>();
		if (!promotionIds.isEmpty()) {
			for (HoPromotionStore row : targets.findByPromotionIdIn(promotionIds)) {
				rows.computeIfAbsent(row.getPromotionId(), id -> new ArrayList<>()).add(row.getStoreId());
			}
		}
		Map<Long, StoreTargets> result = new HashMap<>();
		for (Long id : promotionIds) {
			List<Long> storeIds = rows.get(id);
			result.put(id, storeIds == null ? StoreTargets.all() : StoreTargets.of(storeIds));
		}
		return result;
	}

	private PromotionTargetsDTO view(Promotion promotion, StoreTargets current, boolean withStores) {
		PromotionTargetsDTO dto = new PromotionTargetsDTO();
		dto.setPromotionId(promotion.getId());
		dto.setCode(promotion.getCode());
		dto.setAllStores(current.isAllStores());
		dto.setStoreIds(new ArrayList<>(current.getStoreIds()));
		if (withStores) {
			List<PromotionTargetsDTO.StoreOptionDTO> options = new ArrayList<>();
			if (!current.getStoreIds().isEmpty()) {
				stores.findAllById(current.getStoreIds()).forEach(store -> options.add(
						new PromotionTargetsDTO.StoreOptionDTO(store.getId(), store.getCode(), store.getName(),
								store.getActive())));
			}
			options.sort((a, b) -> a.getCode().compareToIgnoreCase(b.getCode()));
			dto.setStores(options);
		}
		return dto;
	}
}
