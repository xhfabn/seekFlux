package io.seekflux.apps.contentserver;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MediaStorageConfiguration {
    @Bean
    MinioClient mediaMinioClient(
            @Value("${seekflux.media-upload.endpoint}") String endpoint,
            @Value("${seekflux.media-upload.access-key}") String accessKey,
            @Value("${seekflux.media-upload.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
