package com.crates.crates.service;

import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.RecommendedBoardDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.entity.board.Board;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 홈 화면에 한 줄로 보여줄 추천 보드 수. 스크롤 없이 4개만 노출한다. */
    private static final int RECOMMENDED_BOARD_COUNT = 4;

    /**
     * 보드 콘텐츠를 찾을 때 제목 벡터에 섞는 취향 벡터 비중. 높을수록 취향에 가깝고 보드끼리 비슷해진다
     * (0.3: 보드끼리 유사도 0.9 → 0.5~0.75, 취향 유사도 −0.04~−0.08. AI 개발자 시뮬레이션, 2026-10-23).
     */
    private static final float USER_WEIGHT = 0.3f;

    private final UserVectorService userVectorService;
    private final ContentVectorService contentVectorService;
    private final ContentService contentService;
    private final BoardService boardService;
    private final BoardTitleService boardTitleService;
    private final RecommendationCache recommendationCache;
    private final BoardContentMix boardContentMix;
    private final BoardLikeTokenService boardLikeTokenService;

    /**
     * 사용자 취향 벡터 근처에서 서로 다른 제목 4개를 고르고, 제목마다 콘텐츠 8건을 담아 홈 추천 보드를 만든다.
     *
     * <p>검색 보드와 마찬가지로 저장하지 않는다. 다만 같은 구성의 보드가 이미 저장돼 있으면
     * 그 boardId와 좋아요 상태를 함께 내려준다. 그래야 좋아요를 누른 뒤 새로고침해도
     * 하트가 채워진 상태로 남는다.</p>
     */
    public List<RecommendedBoardDto> recommendBoards(Long userId)
    {
        UserVectorService.VectorSnapshot vector = userVectorService.getSnapshot(userId);

        // 이 취향 벡터로 계산해 둔 결과가 있으면 Qdrant를 건너뛴다(RecommendationCache).
        // 저장 여부·좋아요 상태·콘텐츠 요약은 캐시에 없으므로 아래에서 매번 새로 읽는다.
        Optional<List<RecommendedBoardDto>> cached = recommendationCache.get(userId, vector.version())
                .flatMap(entry -> fromCache(entry, userId));
        if (cached.isPresent())
        {
            return cached.get();
        }

        // 제목을 먼저 고르고, 제목마다 그 제목에 맞는 콘텐츠를 담는다. 한 취향 벡터의 순위 구간으로 보드를 나누면
        // 보드 4개의 내용이 거의 같아져(평균 벡터 유사도 0.9) 제목도 겹친다(docs/board-schema.md 4장).
        // Qdrant 검색은 트랜잭션 밖인 여기서 한다. BoardService 안에서 하면 커넥션을 쥐고 기다린다.
        List<QueryMatch> picked = boardTitleService.distinctTitlesNear(vector.vector(), RECOMMENDED_BOARD_COUNT);

        // 보드마다 "제목 70% + 취향 30%" 지점과 가까운 콘텐츠를 책 4 · 음악 2 · 영화 2씩 담는다(board-mix).
        // 보드 4개의 검색은 묶음 요청 한 번이고, 앞 보드에 담긴 콘텐츠는 뒤 보드에 다시 담지 않는다.
        List<float[]> targets = picked.stream().map(title -> blend(title.vector(), vector.vector())).toList();
        List<List<Long>> idsPerBoard = contentVectorService.findDistinctBoardsByType(targets, boardContentMix.perBoard());
        Map<Long, ContentResponseDto> summaryById = contentService
                .getContentSummaries(idsPerBoard.stream().flatMap(List::stream).toList()).stream()
                .collect(Collectors.toMap(ContentResponseDto::id, Function.identity()));

        List<List<ContentResponseDto>> slices = new ArrayList<>(picked.size());
        List<String> titles = new ArrayList<>(picked.size());
        for (int i = 0; i < picked.size(); i++)
        {
            List<ContentResponseDto> slice = idsPerBoard.get(i).stream()
                    .map(summaryById::get)
                    .filter(Objects::nonNull)
                    .toList();
            // 8건을 못 채운 보드는 뺀다. 모자란 보드는 좋아요를 눌러도 저장이 거절되고, 캐시는 콘텐츠 id를 이어 붙여 두고
            // 8건씩 끊어 되살리므로(fromCache) 짧은 보드가 섞이면 뒤 보드들의 콘텐츠와 제목이 한 칸씩 어긋난다.
            if (slice.size() != Board.ITEMS_PER_BOARD)
            {
                log.warn("추천 보드에 담을 콘텐츠가 부족해 뺍니다. title: {} / 조회: {}건 (Qdrant·RDB 콘텐츠 상태 확인 필요)",
                        picked.get(i).queryText(), slice.size());
                continue;
            }
            slices.add(slice);
            titles.add(picked.get(i).queryText());
        }

        recommendationCache.put(userId, new RecommendationCache.Entry(
                vector.version(),
                slices.stream().flatMap(List::stream).map(ContentResponseDto::id).toList(),
                titles));

        return toBoards(slices, titles, userId);
    }

    /**
     * 캐시의 콘텐츠 id와 제목으로 보드를 다시 만든다. 그 사이 콘텐츠가 지워져 개수가 맞지 않으면 빈 값 — 다시 계산한다.
     */
    private Optional<List<RecommendedBoardDto>> fromCache(RecommendationCache.Entry entry, Long userId)
    {
        List<ContentResponseDto> contents = contentService.getContentSummaries(entry.contentIds());
        if (contents.size() != entry.contentIds().size())
        {
            return Optional.empty();
        }
        return Optional.of(toBoards(slicesOf(contents), entry.titles(), userId));
    }

    /**
     * 콘텐츠를 8건씩 끊는다. 8건을 못 채우는 나머지는 버린다 — 모자란 보드는 좋아요를 눌러도 저장이 거절된다.
     * subList는 원본을 들여다보는 뷰라, 응답에 그대로 담지 않고 복사한다.
     */
    private List<List<ContentResponseDto>> slicesOf(List<ContentResponseDto> contents)
    {
        List<List<ContentResponseDto>> slices = new ArrayList<>(RECOMMENDED_BOARD_COUNT);
        for (int from = 0; from + Board.ITEMS_PER_BOARD <= contents.size(); from += Board.ITEMS_PER_BOARD)
        {
            slices.add(List.copyOf(contents.subList(from, from + Board.ITEMS_PER_BOARD)));
        }
        return slices;
    }

    /**
     * 같은 구성의 보드가 이미 저장돼 있으면 저장된 보드(제목·좋아요 상태)를, 아니면 정해 둔 제목으로 미저장 보드를 만든다.
     *
     * <p>보드마다 좋아요 토큰을 붙인다. 토큰의 제목은 화면에 보이는 제목 그대로다 — 저장된 보드면 저장 시점의 제목이다.
     * 캐시에서 되살린 보드도 여기서 새로 서명하므로 토큰은 캐시에 담지 않는다(서명은 수 마이크로초).</p>
     */
    private List<RecommendedBoardDto> toBoards(List<List<ContentResponseDto>> slices, List<String> titles, Long userId)
    {
        List<RecommendedBoardDto> boards = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++)
        {
            List<ContentResponseDto> slice = slices.get(i);
            String title = titles.get(i);
            BoardWithContentsDto board = boardService.findSavedGeneratedBoard(slice, userId)
                    .orElseGet(() -> BoardWithContentsDto.unsaved(title, slice));
            String likeToken = boardLikeTokenService.issue(BoardLikeTokenService.Purpose.RECOMMEND, board.title(),
                    slice.stream().map(ContentResponseDto::id).toList());
            boards.add(new RecommendedBoardDto(board.boardId(), board.title(), board.likeCount(), board.liked(),
                    board.contents(), likeToken));
        }
        return boards;
    }

    /**
     * 홈 추천 보드에 좋아요. 토큰을 검증하고 <b>토큰 안의</b> 제목과 콘텐츠로 저장한다.
     *
     * <p>boardId가 있어도 이 경로를 쓴다. 서버가 "있으면 좋아요, 없으면 저장 후 좋아요"를 처리하므로 그 사이 보드가
     * 지워졌어도 실패하지 않는다. 예전에는 프론트가 보낸 제목과 콘텐츠를 그대로 믿어, 요청을 직접 만들면 아무 제목과
     * 구성으로 AI 추천 보드를 만들 수 있었다(2026-10-28 토큰으로 바꿈, 검색 보드와 같은 방식).</p>
     */
    public BoardLikeResponse likeRecommendedBoard(String likeToken, Long userId)
    {
        BoardLikeTokenService.Verified verified = boardLikeTokenService.verify(BoardLikeTokenService.Purpose.RECOMMEND, likeToken);
        return boardService.likeRecommendedBoard(verified.title(), verified.contentIds(), userId);
    }

    /**
     * 콘텐츠 종류마다 정해진 개수를 기준 벡터와 가까운 순으로 뽑아, 종류가 번갈아 오도록 놓아 돌려준다.
     * 검색 보드가 쓴다(SearchService).
     *
     * <p>사용자 취향 벡터와 검색어 벡터가 같은 임베딩 공간에 있으므로 조회 경로를 공유한다.
     * 정한 순서는 getContentSummaries가 입력 순서를 유지해주므로 그대로 보존된다.
     * Qdrant에는 남아 있지만 RDB에서 사라진 콘텐츠도 거기서 함께 걸러진다.</p>
     */
    public List<ContentResponseDto> findSimilarByType(float[] targetVector, Map<String, Integer> countByType)
    {
        return contentService.getContentSummaries(contentVectorService.findSimilarByType(targetVector, countByType));
    }

    /**
     * 보드 콘텐츠를 찾을 기준점: (1 − USER_WEIGHT) × 제목 + USER_WEIGHT × 취향.
     *
     * <p>두 벡터 모두 크기가 1이라(query 벡터는 Qdrant가 정규화해 저장하고, 취향 벡터는 UserVectorService가 정규화한다)
     * 그대로 섞어도 비중이 지켜진다. Qdrant가 Cosine이라 섞은 결과를 다시 정규화하지 않는다.</p>
     */
    private float[] blend(float[] title, float[] user)
    {
        float[] result = new float[title.length];
        for (int i = 0; i < result.length; i++)
        {
            result[i] = (1 - USER_WEIGHT) * title[i] + USER_WEIGHT * user[i];
        }
        return result;
    }
}
