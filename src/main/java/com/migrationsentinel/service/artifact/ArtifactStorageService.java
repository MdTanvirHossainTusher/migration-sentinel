package com.migrationsentinel.service.artifact;

import com.migrationsentinel.aspect.Audited;
import com.migrationsentinel.config.properties.S3Properties;
import com.migrationsentinel.exception.BadResourceRequestException;
import com.migrationsentinel.exception.ResourceNotFoundException;
import com.migrationsentinel.model.entity.ArtifactEntity;
import com.migrationsentinel.model.enums.ArtifactKind;
import com.migrationsentinel.model.enums.ArtifactStatus;
import com.migrationsentinel.repository.ArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The one door to object storage. Direct-from-browser uploads go out as presigned PUT URLs
 * and are only trusted once {@link #confirm} has verified the object exists and fits the
 * size limit; nothing streams file bytes through this service. Present only when
 * {@code sentinel.s3.enabled=true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.s3", name = "enabled", havingValue = "true")
public class ArtifactStorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final ArtifactRepository repository;
    private final S3Properties properties;

    public record PresignedUpload(UUID artifactId, String objectKey, String uploadUrl,
                                  Instant expiresAt, long maxBytes) {
    }

    public record ArtifactView(UUID id, String kind, String status, String filename,
                               String contentType, Long sizeBytes, String downloadUrl) {
    }

    @Transactional
    public PresignedUpload createUpload(String filename, String contentType, long declaredSize, String actor) {
        long max = properties.getMaxFileSize().toBytes();
        if (declaredSize <= 0) {
            throw new BadResourceRequestException("size_bytes must be a positive number.");
        }
        if (declaredSize > max) {
            throw new BadResourceRequestException("File is " + declaredSize + " bytes, over the "
                    + max + "-byte limit (sentinel.s3.max-file-size).");
        }

        String safe = sanitize(filename);
        String key = "uploads/" + UUID.randomUUID() + "/" + safe;

        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setKind(ArtifactKind.USER_UPLOAD);
        artifact.setStatus(ArtifactStatus.PENDING);
        artifact.setObjectKey(key);
        artifact.setFilename(safe);
        artifact.setContentType(contentType);
        artifact.setCreatedBy(actor);
        artifact = repository.save(artifact);

        PutObjectRequest por = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignExpiry())
                .putObjectRequest(por)
                .build();
        String url = presigner.presignPutObject(presign).url().toString();

        return new PresignedUpload(artifact.getId(), key, url,
                Instant.now().plus(properties.getPresignExpiry()), max);
    }

    @Transactional
    @Audited(action = "artifact.confirmed", aggregateType = "artifact", id = "artifactId")
    public ArtifactView confirm(UUID artifactId, String actor) {
        ArtifactEntity artifact = repository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact " + artifactId + " not found"));
        if (artifact.getStatus() == ArtifactStatus.CONFIRMED) {
            return view(artifact);
        }

        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket()).key(artifact.getObjectKey()).build());
        } catch (Exception ex) {
            throw new BadResourceRequestException("No object was uploaded to " + artifact.getObjectKey()
                    + " — upload it to the presigned URL first, then confirm.");
        }

        long max = properties.getMaxFileSize().toBytes();
        if (head.contentLength() != null && head.contentLength() > max) {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket()).key(artifact.getObjectKey()).build());
            artifact.setStatus(ArtifactStatus.REJECTED);
            repository.save(artifact);
            throw new BadResourceRequestException("Uploaded file is " + head.contentLength()
                    + " bytes, over the " + max + "-byte limit. It has been deleted.");
        }

        artifact.setSizeBytes(head.contentLength());
        artifact.setStatus(ArtifactStatus.CONFIRMED);
        return view(repository.save(artifact));
    }

    @Transactional(readOnly = true)
    public ArtifactView get(UUID artifactId) {
        return view(repository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact " + artifactId + " not found")));
    }

    /** Server-side upload of a rendered report; returns a CONFIRMED artifact. */
    @Transactional
    public ArtifactEntity storeReport(UUID reviewJobId, String filename, String markdown) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String key = "reports/" + reviewJobId + "/" + sanitize(filename);

        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType("text/markdown; charset=utf-8")
                        .build(),
                RequestBody.fromBytes(bytes));

        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setKind(ArtifactKind.REVIEW_REPORT);
        artifact.setStatus(ArtifactStatus.CONFIRMED);
        artifact.setObjectKey(key);
        artifact.setFilename(sanitize(filename));
        artifact.setContentType("text/markdown; charset=utf-8");
        artifact.setSizeBytes((long) bytes.length);
        artifact.setReviewJobId(reviewJobId);
        artifact.setCreatedBy("system");
        return repository.save(artifact);
    }

    public String downloadUrl(UUID artifactId) {
        ArtifactEntity artifact = repository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact " + artifactId + " not found"));
        return presignedGet(artifact);
    }

    private ArtifactView view(ArtifactEntity a) {
        String url = a.getStatus() == ArtifactStatus.CONFIRMED ? presignedGet(a) : null;
        return new ArtifactView(a.getId(), a.getKind().name(), a.getStatus().name(),
                a.getFilename(), a.getContentType(), a.getSizeBytes(), url);
    }

    private String presignedGet(ArtifactEntity a) {
        GetObjectRequest gor = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(a.getObjectKey())
                .responseContentDisposition("attachment; filename=\"" + a.getFilename() + "\"")
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignExpiry())
                .getObjectRequest(gor)
                .build();
        return presigner.presignGetObject(presign).url().toString();
    }

    private String sanitize(String name) {
        String base = name == null || name.isBlank() ? "file" : name;
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
