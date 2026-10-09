package com.crates.crates.service;

import com.crates.crates.qdrant.ScoredPointResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentVectorServiceInterleaveTest {

    /** id 앞자리로 종류를 구분한다: 1xx 책, 2xx 음악, 3xx 영화. */
    private static ScoredPointResult point(long id, float score)
    {
        return new ScoredPointResult(id, score, Map.of(), new float[0]);
    }

    private static List<Long> ids(List<ScoredPointResult> results)
    {
        return results.stream().map(ScoredPointResult::id).toList();
    }

    @Test
    void booksAlternateWithMusicAndMovies()
    {
        List<ScoredPointResult> books = List.of(point(101, .6f), point(102, .5f), point(103, .4f), point(104, .3f));
        List<ScoredPointResult> music = List.of(point(201, .9f), point(202, .8f));
        List<ScoredPointResult> movies = List.of(point(301, .7f), point(302, .2f));

        List<ScoredPointResult> ordered = ContentVectorService.interleave(List.of(books, music, movies));

        // 책이 가장 많아 먼저 오고, 음악(다음 후보 0.9)이 영화(0.7)보다 앞선다. 같은 종류가 연달아 오지 않는다.
        assertEquals(List.of(101L, 201L, 102L, 301L, 103L, 202L, 104L, 302L), ids(ordered));
    }

    /** 종류 안의 순서(유사도 높은 순)는 그대로 지킨다. */
    @Test
    void keepsOrderWithinType()
    {
        List<ScoredPointResult> books = List.of(point(101, .9f), point(102, .1f));
        List<ScoredPointResult> music = List.of(point(201, .5f), point(202, .4f));

        assertEquals(List.of(101L, 201L, 102L, 202L), ids(ContentVectorService.interleave(List.of(books, music))));
    }

    /** 한 종류만 남으면 연달아 놓을 수밖에 없다. */
    @Test
    void fallsBackToSameTypeWhenOthersRunOut()
    {
        List<ScoredPointResult> books = List.of(point(101, .9f), point(102, .8f), point(103, .7f));
        List<ScoredPointResult> music = List.of(point(201, .5f));

        assertEquals(List.of(101L, 201L, 102L, 103L), ids(ContentVectorService.interleave(List.of(books, music))));
    }

    /** 홈 추천: 종류마다 4장 분량을 가까운 순으로 받아 장마다 4·2·2개씩 앞에서부터 나눈다. */
    @Test
    void splitsEachTypeIntoBoardsInOrder()
    {
        Map<String, List<ScoredPointResult>> byType = new LinkedHashMap<>();
        byType.put("BOOK", List.of(point(101, .9f), point(102, .8f), point(103, .7f), point(104, .6f),
                point(105, .5f), point(106, .4f), point(107, .3f), point(108, .2f)));
        byType.put("MUSIC", List.of(point(201, .9f), point(202, .8f), point(203, .7f), point(204, .6f)));
        Map<String, Integer> perBoard = new LinkedHashMap<>();
        perBoard.put("BOOK", 4);
        perBoard.put("MUSIC", 2);

        List<List<List<ScoredPointResult>>> boards = ContentVectorService.splitIntoBoards(byType, perBoard, 2);

        // 1번 보드에 각 종류에서 가장 가까운 것들이 들어간다
        assertEquals(List.of(101L, 102L, 103L, 104L), ids(boards.get(0).get(0)));
        assertEquals(List.of(201L, 202L), ids(boards.get(0).get(1)));
        assertEquals(List.of(105L, 106L, 107L, 108L), ids(boards.get(1).get(0)));
        assertEquals(List.of(203L, 204L), ids(boards.get(1).get(1)));
    }
}
