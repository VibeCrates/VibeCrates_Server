package com.crates.crates.service;

import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.DTO.SearchBoardDto;
import com.crates.crates.ai.EmbeddingClient;
import com.crates.crates.ai.QueryEmbedding;
import com.crates.crates.entity.board.Board;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EmbeddingClient embeddingClient;
    private final RecommendationService recommendationService;
    private final BoardService boardService;
    private final SearchLikeTokenService searchLikeTokenService;

    /** 보드 한 장에 담을 콘텐츠 종류별 개수(책 4 · 음악 2 · 영화 2, {@code board-mix}, 또는 한 종류 8). */
    private final BoardContentMix boardContentMix;

    /**
     * 검색어를 AI 서버에서 임베딩 벡터로 바꾸고, 검색어가 가리키는 콘텐츠 종류(domain)를 함께 받는다.
     * 벡터 자체는 프론트로 내려주지 않고 내부 조회에만 쓴다.
     * 이 서비스에서 AI 서버를 부르는 곳은 여기뿐이다. AI 서버에 닿지 않으면 AiServerException이 난다.
     */
    public QueryEmbedding getQueryEmbedding(String keyword)
    {
        QueryEmbedding embedding = embeddingClient.embed(keyword);
        log.info("[검색어 벡터 생성] keyword: {} / domain: {} / dimension: {}",
                keyword, embedding.domain(), embedding.vector().length);

        return embedding;
    }

    /**
     * 검색어와 벡터가 유사한 콘텐츠를 모아 보드 한 장을 만든다.
     *
     * <p>콘텐츠는 종류마다 정해진 개수를 각 종류 안에서 검색어와 가까운 순으로 뽑는다. 개수는 AI 서버가 알려 준
     * 검색어의 종류(domain)로 정한다.</p>
     * <ul>
     *   <li>ALL — 책 4 · 음악 2 · 영화 2({@code board-mix}). 보드 안에서는 종류가 번갈아 오도록 놓는다(책 → 음악 → 책 → 영화 …).
     *       홈 추천 보드와 같은 규칙이다.</li>
     *   <li>한 종류("잔잔한 영화" → movie) — 보드 8칸을 모두 그 종류로 채운다.</li>
     * </ul>
     * <p>domain은 요청에 없고 AI 서버 응답에서만 알 수 있다.</p>
     *
     * <p><b>제목은 사용자가 입력한 검색어다.</b> NFC 변환과 앞뒤 공백 제거만 한다(Board.normalizeTitle). 받자마자 한 번 바꾸고
     * 그 값으로 AI 요청·토큰·저장을 모두 한다. 단계마다 다른 값을 쓰면 토큰은 통과했는데 다른 제목으로 저장된다.</p>
     *
     * <p><b>검색할 때마다 AI를 부른다.</b> 같은 검색어로 저장된 보드가 있어도 그것을 대신 내주지 않는다. 처음 좋아요한
     * 사람의 결과로 검색어가 고정되지 않게 하려는 것이다(docs/board-schema.md 11-1).</p>
     *
     * <p>이 보드는 저장하지 않는다. 사용자가 좋아요를 누르면 {@link #likeSearchBoard}로 그때 저장된다.
     * 다만 제목과 구성이 같은 검색 보드가 이미 저장돼 있으면 그 boardId와 좋아요 상태를 함께 내려준다.</p>
     */
    public SearchBoardDto searchBoard(String keyword, Long userId)
    {
        String title = Board.normalizeTitle(keyword);
        QueryEmbedding query = getQueryEmbedding(title);
        List<ContentResponseDto> contents =
                recommendationService.findSimilarByType(query.vector(), boardContentMix.perBoard(query.domain()));

        if (contents.size() < Board.ITEMS_PER_BOARD)
        {
            // 보드는 콘텐츠 8건 고정이라, 모자란 채로 내려가면 사용자가 좋아요를 눌러도
            // BoardService.likeSearchBoard에서 거절당한다. 원인 추적용으로 남긴다.
            log.warn("검색 보드에 담을 콘텐츠가 부족합니다. keyword: {} / domain: {} / 조회: {}건 (Qdrant 시딩 상태 확인 필요)",
                    title, query.domain(), contents.size());
        }

        BoardWithContentsDto board = boardService.findSavedSearchBoard(title, contents, userId)
                .orElseGet(() -> BoardWithContentsDto.unsaved(title, contents));
        String likeToken = searchLikeTokenService.issue(title, contents.stream().map(ContentResponseDto::id).toList());

        return new SearchBoardDto(board.boardId(), board.title(), board.likeCount(), board.liked(),
                board.contents(), likeToken);
    }

    /**
     * 검색 보드에 좋아요. 토큰을 검증하고 <b>토큰 안의</b> 검색어와 콘텐츠로 저장한다.
     *
     * <p>boardId가 있어도 이 경로를 쓴다. 서버가 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하므로 그 사이 보드가
     * 지워졌어도 실패하지 않는다.</p>
     */
    public BoardLikeResponse likeSearchBoard(String likeToken, Long userId)
    {
        SearchLikeTokenService.Verified verified = searchLikeTokenService.verify(likeToken);
        return boardService.likeSearchBoard(verified.keyword(), verified.contentIds(), userId);
    }
}
