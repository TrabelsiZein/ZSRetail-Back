package com.digithink.zsretail.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.DashboardTodayDTO;
import com.digithink.zsretail.service.DashboardService;

@RestController
@RequestMapping("admin/dashboard")
public class DashboardAPI {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping("/today")
    public ResponseEntity<DashboardTodayDTO> getTodayStats() {
        return ResponseEntity.ok(dashboardService.getTodayStats());
    }
}
