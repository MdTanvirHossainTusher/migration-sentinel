package com.migrationsentinel.controller;

import com.migrationsentinel.exception.BadResourceRequestException;
import com.migrationsentinel.payload.common.ApiResponse;
import com.migrationsentinel.payload.common.ResponseBuilder;
import com.migrationsentinel.payload.request.CreateUploadRequest;
import com.migrationsentinel.payload.response.ArtifactResponse;
import com.migrationsentinel.payload.response.PresignedUploadResponse;
import com.migrationsentinel.service.artifact.ArtifactStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Direct-to-storage uploads: {@code POST /uploads} returns a presigned PUT URL, the client
 * PUTs the bytes straight to object storage, then {@code POST /uploads/{id}/confirm} makes
 * the server verify and register it. Available only when {@code sentinel.s3.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
@Tag(name = "Artifacts", description = "Presigned uploads and downloadable report files")
public class ArtifactController {

    private final ObjectProvider<ArtifactStorageService> storage;

    @PostMapping("/uploads")
    @Operation(summary = "Get a presigned URL to upload one file directly to object storage")
    public ResponseEntity<ApiResponse<PresignedUploadResponse>> createUpload(
            @Valid @RequestBody CreateUploadRequest request,
            @RequestHeader(name = "X-Actor", required = false, defaultValue = "api") String actor) {

        ArtifactStorageService svc = require();
        ArtifactStorageService.PresignedUpload up =
                svc.createUpload(request.filename(), request.contentType(), request.sizeBytes(), actor);
        return ResponseBuilder.ok(new PresignedUploadResponse(
                up.artifactId(), up.objectKey(), up.uploadUrl(), "PUT", up.expiresAt(), up.maxBytes()));
    }

    @PostMapping("/uploads/{id}/confirm")
    @Operation(summary = "Confirm an upload: verify the object exists and is within the size limit")
    public ResponseEntity<ApiResponse<ArtifactResponse>> confirm(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Actor", required = false, defaultValue = "api") String actor) {

        ArtifactStorageService.ArtifactView v = require().confirm(id, actor);
        return ResponseBuilder.ok(toResponse(v));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Artifact metadata plus a fresh presigned download URL")
    public ResponseEntity<ApiResponse<ArtifactResponse>> get(@PathVariable UUID id) {
        return ResponseBuilder.ok(toResponse(require().get(id)));
    }

    private ArtifactStorageService require() {
        ArtifactStorageService svc = storage.getIfAvailable();
        if (svc == null) {
            throw new BadResourceRequestException(
                    "Object storage is disabled. Set sentinel.s3.enabled=true to use artifact uploads.");
        }
        return svc;
    }

    private ArtifactResponse toResponse(ArtifactStorageService.ArtifactView v) {
        return new ArtifactResponse(v.id(), v.kind(), v.status(), v.filename(),
                v.contentType(), v.sizeBytes(), v.downloadUrl());
    }
}
