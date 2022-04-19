package com.github.academivillage.jcloud.gcp.sdk;

import com.github.academivillage.jcloud.errors.AppException;
import com.github.academivillage.jcloud.errors.JCloudError;
import com.github.academivillage.jcloud.gcp.CloudMetadata;
import com.github.academivillage.jcloud.gcp.CloudStorage;
import com.github.academivillage.jcloud.gcp.Scope;
import com.google.api.gax.paging.Page;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.*;
import com.google.cloud.storage.Storage.SignUrlOption;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static com.github.academivillage.jcloud.util.FileUtil.getFileName;

/**
 * The default implementation of {@link CloudMetadata} and {@link CloudStorage} for all GCP serverless services.
 */
@Slf4j
public class GcpSdk implements CloudMetadata, CloudStorage {

    private final Storage storage;
    private final String  serviceAccountName;

    public GcpSdk(Storage storage) {
        this.storage            = storage;
        this.serviceAccountName = storage.getServiceAccount(storage.getOptions().getProjectId()).getEmail();
    }

    public GcpSdk() {
        String projectId = getProjectId().orElseThrow(() -> new AppException(JCloudError.PROJECT_ID_NOT_AVAILABLE));
        this.storage = StorageOptions.newBuilder()
                .setProjectId(projectId)
                .build()
                .getService();

        this.serviceAccountName = storage.getServiceAccount(projectId).getEmail();
    }

    @Override
    public Optional<String> getProjectId() {
        return Optional.ofNullable(ServiceOptions.getDefaultProjectId());
    }

    @Override
    public String serviceAccountName() {
        return serviceAccountName;
    }

    @Override
    public String getSignedUrl(String bucketName, String storagePath, Duration expiration, Scope scope) {
        log.debug("About to create a signed URL for resource in Google Storage path {}/{} using GCP SDK - Expiration: {}, Scope: {}",
                bucketName, storagePath, expiration, scope);
        storagePath = fixPath(storagePath);
        // Define resource
        BlobInfo      blobInfo    = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).build();
        SignUrlOption v4Signature = SignUrlOption.withV4Signature();
        URL           url         = storage.signUrl(blobInfo, expiration.getSeconds(), TimeUnit.SECONDS, v4Signature);

        return url.toString();
    }

    @Override
    public String getSignedUrl(String bucketName, String directoryPrefix, Pattern fileNamePattern, Duration expiration, Scope scope) {
        log.debug("About to create a signed URL for resource in Google Storage path {}/{} using GCP SDK - File Name Pattern: {} - Expiration: {} - Scope: {}",
                bucketName, directoryPrefix, fileNamePattern.pattern(), expiration, scope);
        Blob blob = getBlob(bucketName, directoryPrefix, fileNamePattern);

        SignUrlOption v4Signature = SignUrlOption.withV4Signature();
        URL           url         = blob.signUrl(expiration.getSeconds(), TimeUnit.SECONDS, v4Signature);

        return url.toString();
    }

    @Override
    public byte[] downloadInMemory(String bucketName, String storagePath) {
        log.debug("About to download file in memory from Google Storage path {}/{} using GCP SDK", bucketName, storagePath);
        storagePath = fixPath(storagePath);
        return storage.readAllBytes(bucketName, storagePath);
    }

    @Override
    public File downloadInFile(String bucketName, String storagePath) {
        log.debug("About to download file from Google Storage path {}/{} using GCP SDK", bucketName, storagePath);
        storagePath = fixPath(storagePath);
        String fileName = getFileName(storagePath);
        File   tempFile = createTempFile(fileName);
        storage.get(BlobId.of(bucketName, storagePath)).downloadTo(tempFile.toPath());

        return tempFile;
    }

    @Override
    public File downloadInFile(String bucketName, String directoryPrefix, Pattern fileNamePattern) {
        log.debug("About to download file from Google Storage path {}/{} using GCP SDK - File Name Patter: {}",
                bucketName, directoryPrefix, fileNamePattern.pattern());
        String fileType = getFileType(fileNamePattern);
        String fileName = "gcp-run-" + Instant.now().toEpochMilli() + "." + fileType;
        File   tempFile = createTempFile(fileName);

        Blob blob = getBlob(bucketName, directoryPrefix, fileNamePattern);
        blob.downloadTo(tempFile.toPath());

        return tempFile;
    }

    @Override
    public void uploadFile(String bucketName, String storagePath, byte[] fileBytes) {
        log.debug("About to upload file to Google Storage path {}/{} using GCP SDK - File size: {} KB",
                bucketName, storagePath, fileBytes.length / 1024);
        storagePath = fixPath(storagePath);

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).build();
        storage.create(blobInfo, fileBytes, Storage.BlobTargetOption.detectContentType());
    }

    @Override
    @SneakyThrows
    public void uploadFile(String bucketName, String storagePath, File file) {
        log.debug("About to upload file to Google Storage path {}/{} using GCP SDK - File path: {} - File size: {} KB",
                bucketName, storagePath, file.getName(), file.length() / 1024);
        storagePath = fixPath(storagePath);
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, storagePath)).build();
        storage.createFrom(blobInfo, file.toPath(), Storage.BlobWriteOption.detectContentType());
    }

    private String getFileType(Pattern fileNamePattern) {
        String[] split = fileNamePattern.pattern().split("\\.");
        if (split.length > 1) return split[split.length - 1];

        return fileNamePattern.pattern();
    }

    @SneakyThrows
    private File createTempFile(String fileName) {
        try {
            return Files.createTempFile("gcp-storage-", fileName).toFile();
        } catch (Exception e) {
            log.error("Failed to generate a temp file {}", fileName, e);
            throw e;
        }
    }

    /**
     * Drops the leading {@code /} for the storage path.
     *
     * @return The fixed storage path.
     */
    private String fixPath(String storagePath) {
        if (storagePath.startsWith("/"))
            return storagePath.substring(1);

        return storagePath;
    }

    private Blob getBlob(String bucketName, String directoryPrefix, Pattern fileNamePattern) {
        Page<Blob> blobs = storage.list(
                bucketName,
                Storage.BlobListOption.prefix(directoryPrefix),
                Storage.BlobListOption.currentDirectory());

        Predicate<String> predicate = fileNamePattern.asPredicate();

        return StreamSupport.stream(blobs.iterateAll().spliterator(), false)
                .filter(it -> predicate.test(getFileName(it.getBlobId().getName())))
                .findFirst()
                .orElseThrow(() -> new AppException(JCloudError.FILE_NOT_FOUND));
    }
}
