package com.migrationsentinel.controller;

import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.ResponseBuilder;
import com.migrationsentinel.payload.response.HealthResponse;
import com.migrationsentinel.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Tag(name = "Health")
public class HealthController {

    private final HealthService healthService;

    @GetMapping
    @Operation(summary = "Service health, configured LLM provider and Docker/sandbox availability")
    public ResponseEntity<ApiResponse<HealthResponse>> health() {
        return ResponseBuilder.ok(healthService.health());
    }
}
