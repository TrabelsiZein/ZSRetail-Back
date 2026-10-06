package com.digithink.zsretail.inventory.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.inventory.model.InventoryCountLine;
import com.digithink.zsretail.repository._BaseRepository;

/**
 * Reads of the lines for the screen (the writes are JDBC batches, {@link InventoryLineStore}). The item is joined by
 * id. A draft compares the counted quantity with the stock now; a validated count shows what was applied.
 */
public interface InventoryCountLineRepository extends _BaseRepository<InventoryCountLine, Long> {

	String FROM = " from InventoryCountLine l left join Item i on i.id = l.itemId where l.count.id = :countId";

	String FILTER = " and (:filter = 'ALL' or (:filter = 'PROBLEMS' and l.status <> :ok)"
			+ " or (:filter = 'DIFFERENCES' and l.status = :ok and ((:validated = 1 and l.differenceApplied <> 0)"
			+ " or (:validated = 0 and l.countedQuantity <> coalesce(i.stockQuantity, 0)))))"
			+ " and (:search = '' or lower(l.code) like :search or lower(i.itemCode) like :search"
			+ " or lower(i.name) like :search)";

	/**
	 * [id, code, itemId, itemCode, itemName, status, countedQuantity, mergedRows, systemQuantityAtImport,
	 * systemQuantityAtValidation, differenceApplied, message, stockNow]; filter ALL, DIFFERENCES or PROBLEMS; search ''
	 * or a lower-case LIKE pattern; validated 1 or 0.
	 */
	@Query(value = "select l.id, l.code, l.itemId, i.itemCode, i.name, l.status, l.countedQuantity, l.mergedRows,"
			+ " l.systemQuantityAtImport, l.systemQuantityAtValidation, l.differenceApplied, l.message, i.stockQuantity"
			+ FROM + FILTER + " order by l.id", countQuery = "select count(l)" + FROM + FILTER)
	Page<Object[]> findLines(@Param("countId") Long countId, @Param("filter") String filter,
			@Param("validated") int validated, @Param("search") String search, @Param("ok") InventoryLineStatus ok,
			Pageable page);

	/**
	 * Per status of a draft, against the stock now: [status, lines, rows, lines with a difference, counted where up,
	 * stock where up, stock where down, counted where down]; quantity up = [4] - [5], down = [6] - [7]. Plain sums: the
	 * HQL of Hibernate 5.4 refuses arithmetic in a CASE inside SUM (a null stock adds nothing, as 0 would).
	 */
	@Query("select l.status, count(l), sum(l.mergedRows),"
			+ " sum(case when l.countedQuantity <> coalesce(i.stockQuantity, 0) then 1 else 0 end),"
			+ " sum(case when l.countedQuantity > coalesce(i.stockQuantity, 0) then l.countedQuantity else 0 end),"
			+ " sum(case when l.countedQuantity > coalesce(i.stockQuantity, 0) then i.stockQuantity else 0 end),"
			+ " sum(case when l.countedQuantity < coalesce(i.stockQuantity, 0) then i.stockQuantity else 0 end),"
			+ " sum(case when l.countedQuantity < coalesce(i.stockQuantity, 0) then l.countedQuantity else 0 end)"
			+ FROM + " group by l.status")
	List<Object[]> summaryOfDraft(@Param("countId") Long countId);

	/**
	 * Same for a validated count, from the differences applied: [status, lines, rows, lines with a difference, sum of
	 * the positive differences, sum of the negative differences (negative)].
	 */
	@Query("select l.status, count(l), sum(l.mergedRows),"
			+ " sum(case when l.differenceApplied <> 0 then 1 else 0 end),"
			+ " sum(case when l.differenceApplied > 0 then l.differenceApplied else 0 end),"
			+ " sum(case when l.differenceApplied < 0 then l.differenceApplied else 0 end)"
			+ " from InventoryCountLine l where l.count.id = :countId group by l.status")
	List<Object[]> summaryOfValidated(@Param("countId") Long countId);
}
