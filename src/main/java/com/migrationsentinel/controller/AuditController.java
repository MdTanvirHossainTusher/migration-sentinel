package com.migrationsentinel.controller;

import com.migrationsentinel.mapper.DtoMapper;
import com.migrationsentinel.model.entity.AuditEventEntity;
import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.Pagination;
import com.migrationsentinel.payload.common.ResponseBuilder;
import com.migrationsentinel.payload.response.AuditEventResponse;
import com.migrationsentinel.repository.AuditEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "The durable audit trail of every consequential action")
public class AuditController {

    private final AuditEventRepository repository;
    private final DtoMapper mapper;

    @GetMapping
    @Operation(summary = "List audit events, newest first; optionally scoped to one aggregate")
    public ResponseEntity<ApiResponse<List<AuditEventResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(name = "aggregate_type", required = false) String aggregateType,
            @RequestParam(name = "aggregate_id", required = false) String aggregateId) {

        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<AuditEventEntity> events = (aggregateType != null && aggregateId != null)
                ? repository.findByAggregateTypeAndAggregateIdOrderByCreatedAtDesc(aggregateType, aggregateId, pageable)
                : repository.findAllByOrderByCreatedAtDesc(pageable);

        return ResponseBuilder.ok(
                events.getContent().stream().map(mapper::toAuditEventResponse).toList(),
                Pagination.from(events));
    }
}
