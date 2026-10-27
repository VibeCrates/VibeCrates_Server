package com.crates.crates.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 홈 추천 보드의 제목을 정한다. (docs/board-schema.md 4장) 검색 보드는 사용자가 입력한 검색어가 제목이다(SearchService).
 *
 * <p>제목을 먼저 고르고 그 제목에 맞는 콘텐츠를 담는다(RecommendationService). 제목은 취향 벡터와 가까운 query 중에서
 * 서로 다른 것으로 고른다 — 대신 쓸 고정 제목이 없어서, query를 찾지 못하면 빈 제목으로 내보내지 않고 오류로 끝낸다.</p>
 *
 * <p>query와 query_vector는 AI 개발자가 만든 CSV를 기동 때 적재한다(QueryVectorLoader). 제목은 한국어 번역이다.</p>
 *
 * <p>트랜잭션 밖에서 부른다. Qdrant 검색을 기다리는 동안 DB 커넥션을 쥐지 않기 위해서다
 * (BoardService.findSavedGeneratedBoard 참고).</p>
 */
@Service
@RequiredArgsConstructor
public class BoardTitleService {

    /**
     * 제목 후보 수. 취향 벡터와 가까운 query를 이만큼 받아 그중 보드 수만큼 고른다.
     *
     * <p>가장 가까운 query들은 한 주제의 다른 표현("새로운 시작", "새로운 출발"…)으로 몰려 있어, 후보가 적으면
     * 벌칙을 줘도 고를 수 있는 것이 같은 주제뿐이다. 200은 AI 개발자가 제안한 값이고 측정으로 정하지 않았다 —
     * 실제로 고른 제목이 후보 몇 위까지 내려가는지 재 보고 줄일 수 있다.</p>
     */
    private static final int TITLE_CANDIDATES = 200;

    /**
     * 이미 고른 제목과 비슷한 후보에 주는 벌칙의 세기(MMR). 0.7 이상이면 거의 같은 제목(유사도 0.8 이상)이
     * 사라지고 개인화는 거의 줄지 않는다(AI 개발자 시뮬레이션, 2026-10-23).
     */
    private static final double TITLE_DIVERSITY_PENALTY = 0.7;

    private final QueryVectorService queryVectorService;

    /**
     * 취향 벡터 근처에서 서로 다른 제목을 count개 고른다. 홈 추천 보드가 쓴다.
     *
     * @throws IllegalStateException 서로 다른 후보가 count개보다 적을 때. query_vector가 비어 있다는 뜻이다
     *         (Qdrant 재적재 중 등)
     */
    public List<QueryMatch> distinctTitlesNear(float[] userVector, int count)
    {
        return pickDistinct(queryVectorService.findSimilarQueries(userVector, TITLE_CANDIDATES), count, TITLE_DIVERSITY_PENALTY);
    }

    /**
     * 후보 중에서 서로 다른 제목을 count개 고른다(MMR).
     *
     * <p>매번 "취향과의 유사도 − penalty × 이미 고른 제목들과의 유사도 중 가장 큰 값"이 가장 큰 후보를 하나씩 고른다.
     * 첫 제목은 고른 것이 없어 벌칙이 0이므로 취향과 가장 가까운 query다. 벌칙을 평균이 아닌 최댓값으로 재서,
     * 이미 고른 제목 하나와만 비슷해도 크게 밀려난다.</p>
     *
     * <p>영어 원문이 달라도 한국어 번역이 같은 query가 많아(QueryVectorLoader는 영어 원문으로만 중복을 거른다)
     * 이미 고른 제목과 문자열이 같은 후보는 점수를 매기지 않고 건너뛴다.</p>
     *
     * @param candidates 취향과 가까운 순의 후보. score는 취향과의 유사도, vector는 크기 1이다
     */
    static List<QueryMatch> pickDistinct(List<QueryMatch> candidates, int count, double penalty)
    {
        List<QueryMatch> chosen = new ArrayList<>(count);
        while (chosen.size() < count)
        {
            QueryMatch best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (QueryMatch candidate : candidates)
            {
                if (chosen.stream().anyMatch(used -> used.queryText().equals(candidate.queryText())))
                {
                    continue;
                }
                double overlap = chosen.stream()
                        .mapToDouble(used -> dot(candidate.vector(), used.vector()))
                        .max()
                        .orElse(0);
                double score = candidate.score() - penalty * overlap;
                if (score > bestScore)
                {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null)
            {
                throw new IllegalStateException("보드 제목으로 쓸 query를 찾지 못했습니다. query_vector 적재 상태를 확인하세요.");
            }
            chosen.add(best);
        }
        return chosen;
    }

    /** 두 벡터 모두 크기가 1이라 내적이 곧 코사인 유사도다. */
    private static double dot(float[] a, float[] b)
    {
        double sum = 0;
        for (int i = 0; i < a.length; i++)
        {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
