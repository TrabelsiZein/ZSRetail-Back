package com.digithink.zsretail.core.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportResultDTO {

    private int totalRows;
    private int successCount;
    private int errorCount;
    private List<RowError> errors;
    /** 3.1.2, the item import: the cost cells of items that already have a CMP, not imported (0 elsewhere). */
    private int costsIgnored;

    public ImportResultDTO(int totalRows, int successCount, int errorCount, List<RowError> errors) {
        this(totalRows, successCount, errorCount, errors, 0);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;
        private String message;
    }
}
