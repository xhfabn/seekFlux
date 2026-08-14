package io.seekflux.apps.contentserver.api;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/media")
public final class MediaUploadController {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "video/mp4", "video/webm", "video/quicktime");

    private final MinioClient minio;
    private final String bucket;
    private final String publicBase;
    private final long maxBytes;

    public MediaUploadController(
            MinioClient minio,
            @Value("${seekflux.media-upload.bucket}") String bucket,
            @Value("${seekflux.media-upload.public-base}") String publicBase,
            @Value("${seekflux.media-upload.max-bytes}") long maxBytes) {
        this.minio = minio;
        this.bucket = bucket;
        this.publicBase = publicBase.replaceAll("/+$", "");
        this.maxBytes = maxBytes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResponse upload(@RequestPart("file") MultipartFile file) throws Exception {
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (file.isEmpty() || file.getSize() > maxBytes || !ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("media file is empty, too large, or has an unsupported type");
        }
        String extension = extension(file.getOriginalFilename(), contentType);
        LocalDate today = LocalDate.now();
        String objectName = "uploads/%d/%02d/%s%s".formatted(
                today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
        try (var input = file.getInputStream()) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectName).contentType(contentType)
                    .stream(input, file.getSize(), -1).build());
        }
        return new MediaUploadResponse(
                publicBase + "/" + bucket + "/" + objectName,
                contentType, file.getSize(), file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
    }

    private static String extension(String filename, String contentType) {
        if (filename != null) {
            String clean = filename.replace('\\', '/');
            int dot = clean.lastIndexOf('.');
            if (dot >= 0 && clean.length() - dot <= 8) {
                String value = clean.substring(dot).toLowerCase(Locale.ROOT);
                if (value.matches("\\.[a-z0-9]+")) return value;
            }
        }
        return contentType.startsWith("video/") ? ".mp4" : ".jpg";
    }

    public record MediaUploadResponse(String uri, String contentType, long size, String originalName) {}
}
