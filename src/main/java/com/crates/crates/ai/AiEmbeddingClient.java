package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerException;
import com.crates.crates.enumData.SearchDomain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 서버의 묶음 임베딩 API를 부른다. 검색어 여러 개를 요청 한 번으로 벡터로 바꾼다.
 *
 * <p>단건 API({@code /api/v1/search/vector})를 동시에 여러 번 부르는 것보다 훨씬 빠르다.
 * 2026-10-10 측정에서 한글 20건을 단건으로 순차 호출하면 5.3초, 묶음 한 번이면 0.93초였고 벡터는 같았다.
 * 묶는 일은 {@link EmbeddingBatcher}가 한다.</p>
 *
 * <p>검색어마다 가리키는 콘텐츠 종류(domains)도 함께 받는다. 이 필드가 없는 AI 서버이거나 모르는 값이면
 * ALL(종류를 섞는 기존 방식)로 본다 — 검색은 그대로 되고 종류만 좁혀지지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiEmbeddingClient {

    @Qualifier("aiServerRestClient")
    private final RestClient aiServerRestClient;

    @Value("${ai.server.embedding-path}")
    private String embeddingPath;

    @Value("${ai.server.embedding-dimension}")
    private int embeddingDimension;

    /** 응답이 실어 온 마지막 model_version. 아직 한 번도 부르지 않았으면 null. 임베딩 캐시가 낡은 벡터를 가려낼 때 쓴다. */
    private volatile String lastModelVersion;

    /**
     * @return {@code texts}와 같은 순서의 임베딩. 각 벡터 길이는 {@code ai.server.embedding-dimension}이다
     */
    public List<QueryEmbedding> embedAll(List<String> texts)
    {
        EmbeddingResponse response;
        try
        {
            response = aiServerRestClient.post()
                    .uri(embeddingPath)
                    .body(new EmbeddingRequest(texts))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        }
        catch (RestClientException e)
        {
            throw new AiServerException("AI 서버 통신에 실패했습니다.", e);
        }

        if (response == null || response.vectors() == null)
        {
            throw new AiServerException("AI 서버가 빈 응답을 반환했습니다.");
        }
        if (response.vectors().size() != texts.size())
        {
            throw new AiServerException("AI 서버 응답 벡터 수가 요청과 다릅니다. 요청: %d, 응답: %d"
                    .formatted(texts.size(), response.vectors().size()));
        }
        for (float[] vector : response.vectors())
        {
            if (vector == null || vector.length != embeddingDimension)
            {
                throw new AiServerException("AI 서버 응답 벡터 차원이 올바르지 않습니다. 예상: %d, 실제: %d"
                        .formatted(embeddingDimension, vector == null ? 0 : vector.length));
            }
        }
        if (response.domains() != null && response.domains().size() != texts.size())
        {
            throw new AiServerException("AI 서버 응답 domain 수가 요청과 다릅니다. 요청: %d, 응답: %d"
                    .formatted(texts.size(), response.domains().size()));
        }
        if (response.modelVersion() != null)
        {
            lastModelVersion = response.modelVersion();
        }

        List<QueryEmbedding> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++)
        {
            String domain = response.domains() == null ? null : response.domains().get(i);
            embeddings.add(new QueryEmbedding(response.vectors().get(i), domainOf(texts.get(i), domain)));
        }
        return embeddings;
    }

    private static SearchDomain domainOf(String text, String domain)
    {
        return SearchDomain.fromAiValue(domain).orElseGet(() -> {
            if (domain != null)
            {
                log.warn("AI 서버가 모르는 domain을 보냈습니다. 종류를 섞어 검색합니다. text: {}, domain: {}", text, domain);
            }
            return SearchDomain.ALL;
        });
    }

    public String lastModelVersion()
    {
        return lastModelVersion;
    }
}
