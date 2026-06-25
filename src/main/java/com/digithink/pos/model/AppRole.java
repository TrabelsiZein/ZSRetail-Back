package com.digithink.pos.model;

import java.util.HashSet;
import java.util.Set;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_role")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class AppRole extends _BaseEntity {

    @Column(unique = true, nullable = false)
    private String name; // slug: 'ADMIN', 'RESPONSIBLE', 'POS_USER', or custom

    @Column(nullable = false)
    private String label; // display label: 'Administrateur', 'Responsable', etc.

    @Column(name = "is_pos_role", nullable = false)
    private Boolean isPosRole = false; // true => redirects to cashier interface

    // Stored as "action:subject" strings e.g. "read:home", "write:admin-users"
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_key")
    private Set<String> permissions = new HashSet<>();

    public AppRole(String name, String label, boolean isPosRole) {
        this.name = name;
        this.label = label;
        this.isPosRole = isPosRole;
    }
}
