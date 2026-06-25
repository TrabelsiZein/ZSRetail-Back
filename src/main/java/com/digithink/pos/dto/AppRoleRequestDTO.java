package com.digithink.pos.dto;

import java.util.Set;

import lombok.Data;

@Data
public class AppRoleRequestDTO {
    private String name;
    private String label;
    private Boolean isPosRole = false;
    private Set<String> permissions;
}
