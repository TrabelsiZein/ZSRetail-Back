package com.digithink.zsretail.service;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.repository.AppRoleRepository;

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

    public AppRole create(String name, String label, boolean isPosRole, Set<String> permissions, String createdBy) {
        if (appRoleRepository.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role name already exists: " + name);
        }
        AppRole role = new AppRole(name, label, isPosRole);
        role.setPermissions(permissions);
        role.setCreatedBy(createdBy);
        role.setUpdatedBy(createdBy);
        return appRoleRepository.save(role);
    }

    public AppRole update(Long id, String label, boolean isPosRole, Set<String> permissions, String updatedBy) {
        AppRole role = findById(id);
        role.setLabel(label);
        role.setIsPosRole(isPosRole);
        role.setPermissions(permissions);
        role.setUpdatedBy(updatedBy);
        return appRoleRepository.save(role);
    }

    public void delete(Long id) {
        AppRole role = findById(id);
        appRoleRepository.delete(role);
    }
}
