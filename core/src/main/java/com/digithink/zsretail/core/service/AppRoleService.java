package com.digithink.zsretail.core.service;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.repository.AppRoleRepository;

@Service
public class AppRoleService {

    @Autowired
    private AppRoleRepository appRoleRepository;

    public List<AppRole> findAll() {
        return appRoleRepository.findAll();
    }

    public AppRole findById(Long id) {
        return appRoleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
    }

    /** 3.4.0, event log step 9: the roles created, their rights changed, deleted (absent in hand-built tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.digithink.zsretail.core.eventlog.service.AdminEvents adminEvents;

    public AppRole create(String name, String label, boolean isPosRole, Set<String> permissions, String createdBy) {
        if (appRoleRepository.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role name already exists: " + name);
        }
        AppRole role = new AppRole(name, label, isPosRole);
        role.setPermissions(permissions);
        role.setCreatedBy(createdBy);
        role.setUpdatedBy(createdBy);
        AppRole saved = appRoleRepository.save(role);
        if (adminEvents != null) adminEvents.roleCreated(saved); // 3.4.0, event log step 9
        return saved;
    }

    public AppRole update(Long id, String label, boolean isPosRole, Set<String> permissions, String updatedBy) {
        AppRole role = findById(id);
        // 3.4.0, event log step 9: the rights before (the live set is replaced below)
        Set<String> rightsBefore = role.getPermissions() == null ? new java.util.HashSet<>() : new java.util.HashSet<>(role.getPermissions());
        String labelBefore = role.getLabel();
        role.setLabel(label);
        role.setIsPosRole(isPosRole);
        role.setPermissions(permissions);
        role.setUpdatedBy(updatedBy);
        AppRole saved = appRoleRepository.save(role);
        if (adminEvents != null) {
            adminEvents.roleRenamed(saved, labelBefore, saved.getLabel()); // 3.4.0, event log step 9
            adminEvents.roleRightsChanged(saved, rightsBefore, saved.getPermissions());
        }
        return saved;
    }

    public void delete(Long id) {
        AppRole role = findById(id);
        appRoleRepository.delete(role);
        if (adminEvents != null) adminEvents.roleDeleted(role); // 3.4.0, event log step 9
    }
}
