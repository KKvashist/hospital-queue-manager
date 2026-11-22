package com.hospital.queue.controller;

import com.hospital.queue.model.DashboardStats;
import com.hospital.queue.service.DashboardService;
import com.hospital.queue.service.SimulationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {

    public record SimulationRequest(int desks, int doctors, int appointments) { }

    private final DashboardService dashboardService;
    private final SimulationService simulationService;

    public DashboardController(DashboardService dashboardService, SimulationService simulationService) {
        this.dashboardService = dashboardService;
        this.simulationService = simulationService;
    }

    @GetMapping("/dashboard")
    public DashboardStats dashboard() {
        return dashboardService.stats();
    }

    @PostMapping("/simulation/run")
    public SimulationService.Result runSimulation(@RequestBody SimulationRequest r) {
        return simulationService.run(r.desks(), r.doctors(), r.appointments());
    }
}
