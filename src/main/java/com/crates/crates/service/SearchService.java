package com.crates.crates.service;

import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.ai.EmbeddingClient;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.contents.Book;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.entity.contents.Music;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EmbeddingClient embeddingClient;
    private final RecommendationService recommendationService;
    private final BoardService boardService;
    private final BoardTitleService boardTitleService;

    /**
     * 검색 보드에 담을 콘텐츠 종류별 개수. 합이 보드 크기(8)여야 한다.
     *
     * <p>콘텐츠 벡터는 종류별로 모여 있어서, 검색어 벡터와 그냥 가까운 순으로 8개를 고르면 한 종류만 나오기 쉽다.
     * 그래서 종류마다 정해진 개수를 따로 뽑는다(ContentVectorService.findSimilarByType).</p>
     */
    @Value("${search.board-mix.book}")
    private int bookCount;

    @Value("${search.board-mix.music}")
    private int musicCount;

    @Value("${search.board-mix.movie}")
    private int movieCount;

    private Map<String, Integer> countByType;

    @PostConstruct
    void validateBoardMix()
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(Book.DTYPE, bookCount);
        counts.put(Music.DTYPE, musicCount);
        counts.put(Movie.DTYPE, movieCount);

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total != Board.ITEMS_PER_BOARD || counts.values().stream().anyMatch(count -> count < 0))
        {
            throw new IllegalStateException("search.board-mix의 합은 " + Board.ITEMS_PER_BOARD
                    + "이고 음수가 없어야 합니다: " + counts);
        }
        // 0개인 종류는 검색하지 않는다(Qdrant는 limit 0 요청을 받지 않는다).
        counts.values().removeIf(count -> count == 0);
        this.countByType = Map.copyOf(counts);
    }

    /**
     * 검색어를 AI 서버에서 임베딩 벡터로 바꾼다. 벡터 자체는 프론트로 내려주지 않고 내부 조회에만 쓴다.
     * 이 서비스에서 AI 서버를 부르는 곳은 여기뿐이다. AI 서버에 닿지 않으면 AiServerException이 난다.
     */
    public float[] getQueryVector(String keyword)
    {
        float[] vector = embeddingClient.embed(keyword);
        log.info("[검색어 벡터 생성] keyword: {} / dimension: {}", keyword, vector.length);

        return vector;
    }

    /**
     * 검색어와 벡터가 유사한 콘텐츠를 모아 보드 한 장을 만든다.
     *
     * <p>콘텐츠는 종류마다 정해진 개수(책 4 · 음악 2 · 영화 2, {@code search.board-mix})를 각 종류 안에서
     * 검색어와 가까운 순으로 뽑고, 보드 안에서는 종류와 상관없이 유사도 높은 순으로 놓는다.</p>
     *
     * <p>이 보드는 저장하지 않는다. 사용자가 좋아요를 눌렀을 때
     * {@code POST /api/boards/likes}로 그때 저장되므로 보통은 boardId가 null이다.</p>
     *
     * <p>다만 누군가 이미 같은 구성의 보드를 저장해뒀다면 그 boardId와 좋아요 상태를 함께 내려준다.
     * 검색 결과는 검색어에 대해 결정론적이라, 한 번 저장된 뒤 다시 검색하면 같은 보드가 나온다.</p>
     */
    public BoardWithContentsDto searchBoard(String keyword, Long userId)
    {
        float[] queryVector = getQueryVector(keyword);
        RecommendationService.SimilarContents similar =
                recommendationService.findSimilarByType(queryVector, countByType);
        List<ContentResponseDto> contents = similar.contents();

        if (contents.size() < Board.ITEMS_PER_BOARD)
        {
            // 보드는 콘텐츠 8건 고정이라, 모자란 채로 내려가면 사용자가 좋아요를 눌러도
            // BoardService.likeNewBoard에서 거절당한다. 원인 추적용으로 남긴다.
            log.warn("검색 보드에 담을 콘텐츠가 부족합니다. keyword: {} / 조회: {}건 (Qdrant 시딩 상태 확인 필요)",
                    keyword, contents.size());
        }

        // 제목 매칭(Qdrant 검색)은 트랜잭션 밖인 여기서 한다. BoardService 안에서 하면 커넥션을 쥐고 기다린다.
        // 제목은 검색어가 아니라 결과 콘텐츠의 평균 벡터와 가장 가까운 query다(추천 보드와 같은 규칙).
        return boardService.findSavedGeneratedBoard(contents, userId)
                .orElseGet(() -> BoardWithContentsDto.unsaved(
                        boardTitleService.titleFor(similar.vectorsOf(contents)), contents));
    }
}
