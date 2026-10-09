package com.crates.crates.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 추천·검색 보드의 제목을 정한다. (docs/board-schema.md 4장)
 *
 * <p>보드에 담긴 콘텐츠의 평균 벡터와 가장 가까운 query가 제목이 된다. 제목은 이 방식으로만 정한다 —
 * 대신 쓸 고정 제목이 없다. 그래서 query를 찾지 못하면 빈 제목으로 내보내지 않고 오류로 끝낸다.</p>
 *
 * <p>query와 query_vector는 AI 개발자가 만든 CSV를 기동 때 적재한다(QueryVectorLoader). 제목은 한국어 번역이다.</p>
 *
 * <p>트랜잭션 밖에서 부른다. Qdrant 검색을 기다리는 동안 DB 커넥션을 쥐지 않기 위해서다
 * (BoardService.findSavedGeneratedBoard 참고).</p>
 */
@Service
@RequiredArgsConstructor
public class BoardTitleService {

    private final QueryVectorService queryVectorService;

    /**
     * 콘텐츠 벡터들의 평균과 가장 가까운 query.
     *
     * <p>평균을 정규화하지 않는다. Cosine 거리는 크기를 보지 않으므로 방향만 맞으면 된다.</p>
     *
     * @throws IllegalStateException 벡터가 없거나 query_vector에서 아무것도 찾지 못했을 때.
     *         정상이라면 일어나지 않는다 — 콘텐츠 벡터나 query_vector가 비어 있다는 뜻이다(Qdrant 재적재 중 등).
     */
    public String titleFor(List<float[]> contentVectors)
    {
        if (contentVectors.isEmpty())
        {
            throw new IllegalStateException("보드 제목을 정할 콘텐츠 벡터가 없습니다. content_vector 적재 상태를 확인하세요.");
        }

        return queryVectorService.findMostSimilarQuery(mean(contentVectors))
                .map(QueryMatch::queryText)
                .orElseThrow(() -> new IllegalStateException(
                        "보드 제목으로 쓸 query를 찾지 못했습니다. query_vector 적재 상태를 확인하세요."));
    }

    /**
     * 보드 여러 개의 제목을 한 번의 Qdrant 요청(묶음 검색)으로 정한다. 결과는 입력 순서와 같다.
     *
     * <p>보드마다 <b>자기</b> 콘텐츠 평균 벡터로 따로 검색한다 — 여러 보드의 평균을 다시 평균 내지 않는다.
     * 그래서 titleFor를 보드마다 부른 것과 제목이 같고, Qdrant 왕복만 보드 수만큼에서 1번으로 준다.</p>
     *
     * @throws IllegalStateException 어느 보드든 벡터가 없거나 query를 찾지 못했을 때(titleFor와 같다)
     */
    public List<String> titlesFor(List<List<float[]>> contentVectorsPerBoard)
    {
        if (contentVectorsPerBoard.isEmpty())
        {
            return List.of();
        }

        List<float[]> means = contentVectorsPerBoard.stream()
                .map(vectors -> {
                    if (vectors.isEmpty())
                    {
                        throw new IllegalStateException("보드 제목을 정할 콘텐츠 벡터가 없습니다. content_vector 적재 상태를 확인하세요.");
                    }
                    return mean(vectors);
                })
                .toList();

        return queryVectorService.findMostSimilarQueries(means).stream()
                .map(match -> match.map(QueryMatch::queryText).orElseThrow(() -> new IllegalStateException(
                        "보드 제목으로 쓸 query를 찾지 못했습니다. query_vector 적재 상태를 확인하세요.")))
                .toList();
    }

    private float[] mean(List<float[]> vectors)
    {
        float[] sum = new float[vectors.getFirst().length];
        for (float[] vector : vectors)
        {
            for (int i = 0; i < sum.length; i++)
            {
                sum[i] += vector[i];
            }
        }
        for (int i = 0; i < sum.length; i++)
        {
            sum[i] /= vectors.size();
        }
        return sum;
    }
}
