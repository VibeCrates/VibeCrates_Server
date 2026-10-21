package com.crates.crates.config;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.VersionsCompatibilityChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${ai.vectorstore.qdrant.host}")
    private String host;

    @Value("${ai.vectorstore.qdrant.port}")
    private int port;

    @Value("${ai.vectorstore.qdrant.grpc-port}")
    private int grpcPort;

    /** 운영 Qdrant는 API 키를 요구한다(보안 그룹에 더한 두 번째 방어선). 로컬 도커는 인증이 없어 빈 값이다. */
    @Value("${ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

    // Rest API
    @Bean
    public RestClient qdrantRestClient() {
        return RestClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // gRPC
    @Bean
    public QdrantClient qdrantClient() {
        // 3번째 인자 false는 TLS를 쓰지 않는다는 뜻이다. 운영도 VPC 사설망 안에서만 오가고 보안 그룹이
        // backend에서 오는 연결만 받으므로 평문으로 둔다.
        //
        // 4번째 인자 false로 라이브러리의 버전 확인을 끄고 아래에서 직접 한다. 라이브러리는 newBuilder() 안에서,
        // 즉 API 키를 붙이기 전에 서버 버전을 물어서, 키를 요구하는 서버(운영)에서는 401로 실패하고 경고 한 줄만
        // 남긴 채 확인을 건너뛴다.
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, grpcPort, false, false);
        if (!apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        QdrantClient client = new QdrantClient(builder.build());
        checkServerVersion(client);
        return client;
    }

    /**
     * 클라이언트와 서버 버전이 맞는지 확인하고, 맞지 않으면 기동을 멈춘다.
     *
     * <p>라이브러리는 경고만 남기지만 여기서는 멈춘다. 버전이 어긋나면 벡터 조회가 예외 없이 빈 배열을 돌려줘
     * 취향 벡터가 나흘 동안 갱신되지 않은 적이 있다(2026-09-11, UserVectorMetrics 참고). 조용히 틀린 결과를
     * 내며 뜨는 것보다 뜨지 않는 편이 낫다.</p>
     *
     * <p>서버에 닿지 못한 것은 버전이 틀린 것과 다르다. 기동 순서상 잠깐 늦게 뜬 것일 수 있어 경고만 남긴다.</p>
     */
    private void checkServerVersion(QdrantClient client) {
        String clientVersion = QdrantGrpcClient.class.getPackage().getImplementationVersion();
        String serverVersion;
        try {
            serverVersion = client.healthCheckAsync().get(5, TimeUnit.SECONDS).getVersion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Qdrant 서버 버전 확인이 중단됐습니다.");
            return;
        } catch (Exception e) {
            log.warn("Qdrant 서버 버전을 확인하지 못했습니다({}:{}). 호환성 검사를 건너뜁니다: {}", host, grpcPort, e.toString());
            return;
        }

        if (clientVersion != null && !VersionsCompatibilityChecker.isCompatible(clientVersion, serverVersion)) {
            throw new IllegalStateException("Qdrant 클라이언트(" + clientVersion + ")와 서버(" + serverVersion
                    + ") 버전이 호환되지 않습니다. build.gradle의 io.qdrant:client와 서버 이미지 태그를 맞추세요.");
        }
        log.info("Qdrant 서버 {} / 클라이언트 {}", serverVersion, clientVersion);
    }
}
