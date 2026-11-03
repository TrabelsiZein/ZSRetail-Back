package com.digithink.zsretail.core.dto.report;

/**
 * 3.1.2: the filters on the item that the stock report and the stock movements report share (ReportService.where /
 * bind): the code or name contains search, the family, sub-family, category, section; null is none.
 */
public interface ReportItemFilter {

    String getSearch();

    Long getFamilyId();

    Long getSubFamilyId();

    String getCategoryCode();

    String getSectionCode();
}
