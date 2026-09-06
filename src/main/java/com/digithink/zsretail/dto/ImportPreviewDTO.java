package com.digithink.zsretail.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreviewDTO {

    private List<String> columns;
    private List<List<String>> rows;
    private int totalRows;
}
