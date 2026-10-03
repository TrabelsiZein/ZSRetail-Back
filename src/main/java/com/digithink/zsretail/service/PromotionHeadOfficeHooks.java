package com.digithink.zsretail.service;

import com.digithink.zsretail.model.Promotion;

/**
 * Head office plan, task 3.3: what a head office adds to {@link PromotionService}. Implemented by a head office only
 * bean (HoPromotionService); on a store there is none and PromotionService behaves as before. Called inside the
 * transaction of the save or the delete.
 */
public interface PromotionHeadOfficeHooks {

	/** After a promotion is saved (created, edited, activated, deactivated): its stores get the change. */
	void afterSave(String previousCode, Promotion saved);

	/** Before a promotion is deleted: its stores get a removal, its targets are deleted. */
	void beforeDelete(Promotion promotion);

	/** Sales lines and tickets of every store, received by the head office, that carry this promotion code. */
	long networkUsageCount(String code);
}
