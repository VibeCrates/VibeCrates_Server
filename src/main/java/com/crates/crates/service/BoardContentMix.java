package com.crates.crates.service;

import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.contents.Book;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.entity.contents.Music;
import com.crates.crates.enumData.SearchDomain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 즉석에서 만드는 보드(홈 추천 보드, 검색 보드) 한 장에 담을 콘텐츠 종류별 개수.
 *
 * <p>콘텐츠 벡터는 종류별로 모여 있어서, 기준 벡터와 그냥 가까운 순으로 8개를 고르면 한 종류만 나오기 쉽다
 * (음악 콘텐츠 벡터로 시험: 가까운 8개가 모두 음악). 그래서 종류마다 정해진 개수를 따로 뽑는다
 * (ContentVectorService.findSimilarByType). 합은 보드 크기(8)여야 하고, 아니면 기동 때 멈춘다.</p>
 */
@Component
public class BoardContentMix {

    private final Map<String, Integer> countByType;

    public BoardContentMix(@Value("${board-mix.book}") int book,
                           @Value("${board-mix.music}") int music,
                           @Value("${board-mix.movie}") int movie)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(Book.DTYPE, book);
        counts.put(Music.DTYPE, music);
        counts.put(Movie.DTYPE, movie);

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total != Board.ITEMS_PER_BOARD || counts.values().stream().anyMatch(count -> count < 0))
        {
            throw new IllegalStateException("board-mix의 합은 " + Board.ITEMS_PER_BOARD + "이고 음수가 없어야 합니다: " + counts);
        }
        // 0개인 종류는 검색하지 않는다(Qdrant는 limit 0 요청을 받지 않는다).
        counts.values().removeIf(count -> count == 0);
        this.countByType = Map.copyOf(counts);
    }

    /** 보드 한 장에 담을 종류별 개수. */
    public Map<String, Integer> perBoard()
    {
        return countByType;
    }

    /**
     * 검색어가 가리키는 종류에 맞춘 보드 한 장의 종류별 개수.
     *
     * <p>ALL이면 {@link #perBoard()}(책 4 · 음악 2 · 영화 2)이고, 한 종류면 보드 8칸을 모두 그 종류로 채운다
     * ("잔잔한 영화" → 영화 8).</p>
     */
    public Map<String, Integer> perBoard(SearchDomain domain)
    {
        return domain.dtype()
                .map(dtype -> Map.of(dtype, Board.ITEMS_PER_BOARD))
                .orElse(countByType);
    }
}
