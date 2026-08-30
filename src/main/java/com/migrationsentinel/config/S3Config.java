package com.migrationsentinel.config;

import com.migrationsentinel.config.properties.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 clients, only when {@code sentinel.s3.enabled=true}. Two endpoints on purpose: the
 * {@link S3Client} talks to the internal address, while the {@link S3Presigner} signs URLs
 * against the public one so the browser can actually reach them.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.s3", name = "enabled", havingValue = "true")
public class S3Config {

    private final S3Properties properties;

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
    }

    private S3Configuration serviceConfig() {
        return S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyleAccess()).build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpointInternal()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(serviceConfig())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpointPublic()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(serviceConfig())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        S3Client client = s3Client();
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        } catch (Exception notThere) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
                log.info("created object-storage bucket '{}'", properties.getBucket());
            } catch (Exception ex) {
                log.warn("could not ensure bucket '{}': {}", properties.getBucket(), ex.getMessage());
            }
        }
    }
}
