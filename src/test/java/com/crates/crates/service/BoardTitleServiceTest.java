package com.crates.crates.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardTitleServiceTest {

    private static final double PENALTY = 0.7;

    /** 크기 1인 2차원 벡터. 두 후보의 유사도는 각도의 코사인이다. */
    private static QueryMatch candidate(long id, String text, float score, double degrees)
    {
        double radians = Math.toRadians(degrees);
        return new QueryMatch(id, text, score, new float[]{(float) Math.cos(radians), (float) Math.sin(radians)});
    }

    private static List<String> texts(List<QueryMatch> matches)
    {
        return matches.stream().map(QueryMatch::queryText).toList();
    }

    /** 취향 점수가 2등이어도 첫 제목과 거의 같으면 밀려나고, 덜 가깝지만 다른 주제가 뽑힌다. */
    @Test
    void pushesAwayCandidatesSimilarToChosenTitle()
    {
        List<QueryMatch> candidates = List.of(
                candidate(1, "새로운 시작", .80f, 0),
                candidate(2, "새로운 출발", .79f, 10),   // 첫 제목과 유사도 0.98
                candidate(3, "비 오는 밤", .70f, 70),    // 0.34
                candidate(4, "여름의 끝", .65f, 110));   // 첫 제목과 -0.34, 비 오는 밤과 0.77

        List<QueryMatch> chosen = BoardTitleService.pickDistinct(candidates, 3, PENALTY);

        assertEquals(List.of("새로운 시작", "여름의 끝", "비 오는 밤"), texts(chosen));
    }

    /** 영어 원문이 달라 id가 달라도 한국어 제목이 같으면 한 번만 고른다. */
    @Test
    void skipsSameTextWithDifferentId()
    {
        List<QueryMatch> candidates = List.of(
                candidate(1, "새로운 시작", .80f, 0),
                candidate(2, "새로운 시작", .79f, 90),
                candidate(3, "비 오는 밤", .50f, 90));

        assertEquals(List.of("새로운 시작", "비 오는 밤"), texts(BoardTitleService.pickDistinct(candidates, 2, PENALTY)));
    }

    /** 벌칙이 0이면 취향과 가까운 순서 그대로다. */
    @Test
    void withoutPenaltyKeepsSimilarityOrder()
    {
        List<QueryMatch> candidates = List.of(
                candidate(1, "새로운 시작", .80f, 0),
                candidate(2, "새로운 출발", .79f, 10),
                candidate(3, "비 오는 밤", .70f, 70));

        assertEquals(List.of("새로운 시작", "새로운 출발"), texts(BoardTitleService.pickDistinct(candidates, 2, 0)));
    }

    @Test
    void failsWhenNotEnoughDistinctTitles()
    {
        List<QueryMatch> candidates = List.of(candidate(1, "새로운 시작", .80f, 0));

        assertThrows(IllegalStateException.class, () -> BoardTitleService.pickDistinct(candidates, 2, PENALTY));
    }
}
