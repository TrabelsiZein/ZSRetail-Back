package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportFieldMappingDTO {

    private String dbField;
    private String excelColumn;
}
