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

    /** 홈 추천: 보드마다 자기 결과에서 앞 보드에 담긴 것을 빼고 종류별 개수만큼 앞에서부터 고른다. */
    @Test
    void skipsContentsAlreadyTakenByEarlierBoards()
    {
        Map<String, Integer> perBoard = new LinkedHashMap<>();
        perBoard.put("BOOK", 2);
        perBoard.put("MUSIC", 1);

        Map<String, List<ScoredPointResult>> first = new LinkedHashMap<>();
        first.put("BOOK", List.of(point(101, .9f), point(102, .8f), point(103, .7f), point(104, .6f)));
        first.put("MUSIC", List.of(point(201, .9f), point(202, .8f)));
        // 2번 보드의 결과가 1번 보드와 많이 겹친다
        Map<String, List<ScoredPointResult>> second = new LinkedHashMap<>();
        second.put("BOOK", List.of(point(102, .9f), point(105, .8f), point(101, .7f), point(106, .6f)));
        second.put("MUSIC", List.of(point(201, .9f), point(203, .8f)));

        List<List<ScoredPointResult>> boards = ContentVectorService.pickDistinctBoards(List.of(first, second), perBoard);

        assertEquals(List.of(101L, 201L, 102L), ids(boards.get(0)));
        // 101·102·201은 1번 보드가 가져갔으므로 건너뛰고 다음 것을 담는다
        assertEquals(List.of(105L, 203L, 106L), ids(boards.get(1)));
    }

    /** 겹친 것을 빼고 나니 모자라면 그 보드는 짧게 돌아간다(호출하는 쪽이 뺀다). */
    @Test
    void returnsShortBoardWhenNotEnoughLeft()
    {
        Map<String, Integer> perBoard = Map.of("BOOK", 2);
        Map<String, List<ScoredPointResult>> first = Map.of("BOOK", List.of(point(101, .9f), point(102, .8f)));
        Map<String, List<ScoredPointResult>> second = Map.of("BOOK", List.of(point(101, .9f), point(103, .8f)));

        List<List<ScoredPointResult>> boards = ContentVectorService.pickDistinctBoards(List.of(first, second), perBoard);

        assertEquals(List.of(103L), ids(boards.get(1)));
    }
}
