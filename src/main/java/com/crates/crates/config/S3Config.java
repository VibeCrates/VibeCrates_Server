package com.crates.crates.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * 이미지 저장소 클라이언트. 로컬(MinIO)과 운영(AWS S3)이 같은 S3Client를 쓰고 설정만 다르다.
 *
 * <ul>
 *   <li><b>로컬</b> — {@code storage.endpoint}에 MinIO 주소, {@code storage.access-key}·{@code secret-key}에
 *       MinIO 계정을 준다. MinIO는 {@code 버킷.호스트} 형식 주소를 받지 못하므로 경로 방식
 *       ({@code 호스트/버킷/키})으로 보낸다.</li>
 *   <li><b>운영</b> — 둘 다 비운다. 주소는 리전의 S3로, 자격 증명은 기본 체인
 *       (환경 변수 → 프로필 → ECS 작업 역할)으로 찾는다. 오래 가는 액세스 키를 어디에도 두지 않으려는 것이다.</li>
 * </ul>
 */
@Configuration
public class S3Config {

    @Value("${storage.endpoint:}")
    private String endpoint;

    @Value("${storage.region:ap-northeast-2}")
    private String region;

    @Value("${storage.access-key:}")
    private String accessKey;

    @Value("${storage.secret-key:}")
    private String secretKey;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider());

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    /** 키가 주어지면(로컬 MinIO) 그 키를, 아니면 기본 체인(운영 IAM 역할)을 쓴다. */
    private AwsCredentialsProvider credentialsProvider() {
        if (accessKey.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
