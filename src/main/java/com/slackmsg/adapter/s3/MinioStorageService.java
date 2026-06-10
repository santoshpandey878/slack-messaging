package com.slackmsg.adapter.s3;

import com.slackmsg.config.StorageConfig;
import com.slackmsg.port.service.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;

/**
 * MinIO / S3 implementation of ObjectStorageService.
 * MVP: MinIO (local). Prod: S3. Same interface, same code.
 */
@Service
@Slf4j
public class MinioStorageService implements ObjectStorageService {

    private final StorageConfig config;
    private S3Presigner presigner;
    private S3Client s3Client;

    public MinioStorageService(StorageConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        AwsBasicCredentials creds = AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey());

        this.presigner = S3Presigner.builder()
                .region(Region.of(config.getRegion()))
                .endpointOverride(URI.create(config.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        this.s3Client = S3Client.builder()
                .region(Region.of(config.getRegion()))
                .endpointOverride(URI.create(config.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s -> s.pathStyleAccessEnabled(true))
                .build();

        log.info("MinIO/S3 storage initialized: endpoint={} bucket={}", config.getEndpoint(), config.getBucket());
    }

    @PreDestroy
    public void cleanup() {
        if (presigner != null) presigner.close();
        if (s3Client != null) s3Client.close();
        log.info("MinIO/S3 clients closed");
    }

    @Override
    public String generatePresignedUploadUrl(String key, String contentType, long expiryMinutes) {
        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expiryMinutes))
                .putObjectRequest(putReq)
                .build();

        return presigner.presignPutObject(presignReq).url().toString();
    }

    @Override
    public String generatePresignedReadUrl(String key, long expiryMinutes) {
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(key)
                .build();

        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expiryMinutes))
                .getObjectRequest(getReq)
                .build();

        return presigner.presignGetObject(presignReq).url().toString();
    }

    @Override
    public void deleteObject(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(config.getBucket())
                .key(key)
                .build());
    }
}
