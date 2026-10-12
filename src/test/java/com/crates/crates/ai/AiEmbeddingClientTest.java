package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerException;
import com.crates.crates.enumData.SearchDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiEmbeddingClientTest {

    private MockRestServiceServer server;
    private AiEmbeddingClient client;

    @BeforeEach
    void setUp()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiEmbeddingClient(builder.build());
        ReflectionTestUtils.setField(client, "embeddingPath", "/api/v1/embeddings");
        ReflectionTestUtils.setField(client, "embeddingDimension", 2);
    }

    private void respond(String domains)
    {
        server.expect(requestTo("http://ai/api/v1/embeddings"))
                .andExpect(content().json("{\"query\": [\"잔잔한 영화\", \"재즈\"]}"))
                .andRespond(withSuccess("""
                        {"dim": 2, "normalized": true, "model_version": "m1",
                         "vectors": [[0.1, 0.2], [0.3, 0.4]]%s}
                        """.formatted(domains), MediaType.APPLICATION_JSON));
    }

    @Test
    void 검색어마다_domain을_순서대로_붙인다()
    {
        respond(", \"domains\": [\"movie\", \"all\"]");

        List<QueryEmbedding> result = client.embedAll(List.of("잔잔한 영화", "재즈"));

        assertThat(result).extracting(QueryEmbedding::domain).containsExactly(SearchDomain.MOVIE, SearchDomain.ALL);
        assertThat(result.get(0).vector()).containsExactly(0.1f, 0.2f);
        assertThat(client.lastModelVersion()).isEqualTo("m1");
    }

    @Test
    void domains가_없는_AI_서버면_모두_ALL()
    {
        respond("");

        assertThat(client.embedAll(List.of("잔잔한 영화", "재즈")))
                .extracting(QueryEmbedding::domain).containsExactly(SearchDomain.ALL, SearchDomain.ALL);
    }

    @Test
    void 모르는_domain은_ALL()
    {
        respond(", \"domains\": [\"podcast\", \"MUSIC\"]");

        assertThat(client.embedAll(List.of("잔잔한 영화", "재즈")))
                .extracting(QueryEmbedding::domain).containsExactly(SearchDomain.ALL, SearchDomain.MUSIC);
    }

    @Test
    void domain_수가_요청과_다르면_AiServerException()
    {
        respond(", \"domains\": [\"movie\"]");

        assertThatThrownBy(() -> client.embedAll(List.of("잔잔한 영화", "재즈")))
                .isInstanceOf(AiServerException.class);
    }
}
