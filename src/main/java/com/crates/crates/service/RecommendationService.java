package com.crates.crates.service;

import com.crates.crates.DTO.BoardWithContentsDto;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 홈 화면에 한 줄로 보여줄 추천 보드 수. 스크롤 없이 4개만 노출한다. */
    private static final int RECOMMENDED_BOARD_COUNT = 4;

    private final UserVectorService userVectorService;
    private final ContentVectorService contentVectorService;
    private final ContentService contentService;
    private final BoardService boardService;
    private final BoardTitleService boardTitleService;
    private final RecommendationCache recommendationCache;

    /** 유사도 순으로 고른 콘텐츠와 그 벡터. 벡터는 보드 제목을 정할 때 쓴다. */
    public record SimilarContents(List<ContentResponseDto> contents, Map<Long, float[]> vectorById) {

        /** slice에 담긴 콘텐츠의 벡터. Qdrant 응답에 없던 id는 빠진다. */
        public List<float[]> vectorsOf(List<ContentResponseDto> slice)
        {
            return slice.stream()
                    .map(content -> vectorById.get(content.id()))
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /**
     * 사용자 취향 벡터와 가까운 콘텐츠로 보드 한 장을 만든다.
     *
     * <p>검색 보드와 마찬가지로 저장하지 않는다. 다만 같은 구성의 보드가 이미 저장돼 있으면
     * 그 boardId와 좋아요 상태를 함께 내려준다. 그래야 좋아요를 누른 뒤 새로고침해도
     * 하트가 채워진 상태로 남는다.</p>
     */
    public List<BoardWithContentsDto> recommendBoards(Long userId)
    {
        UserVectorService.VectorSnapshot vector = userVectorService.getSnapshot(userId);

        // 이 취향 벡터로 계산해 둔 결과가 있으면 Qdrant를 건너뛴다(RecommendationCache).
        // 저장 여부·좋아요 상태·콘텐츠 요약은 캐시에 없으므로 아래에서 매번 새로 읽는다.
        Optional<List<BoardWithContentsDto>> cached = recommendationCache.get(userId, vector.version())
                .flatMap(entry -> fromCache(entry, userId));
        if (cached.isPresent())
        {
            return cached.get();
        }

        // 유사도 상위 32건을 받아 8건씩 끊어 4개 보드로 만든다.
        // 앞쪽 보드일수록 취향에 가깝다.
        SimilarContents similar = findSimilar(vector.vector(), Board.ITEMS_PER_BOARD * RECOMMENDED_BOARD_COUNT);
        List<List<ContentResponseDto>> slices = slicesOf(similar.contents());

        // 보드 4개의 제목을 한 번의 묶음 검색으로 정한다. 보드마다 자기 평균 벡터로 따로 검색된다.
        // 캐시에 담아 두려고 저장된 보드의 제목도 함께 구한다(묶음 한 번이라 왕복은 늘지 않는다).
        // 매칭(Qdrant 검색)은 트랜잭션 밖인 여기서 한다. BoardService 안에서 하면 커넥션을 쥐고 기다린다.
        List<String> titles = boardTitleService.titlesFor(slices.stream().map(similar::vectorsOf).toList());

        recommendationCache.put(userId, new RecommendationCache.Entry(
                vector.version(),
                slices.stream().flatMap(List::stream).map(ContentResponseDto::id).toList(),
                titles));

        return toBoards(slices, titles, userId);
    }

    /**
     * 캐시의 콘텐츠 id와 제목으로 보드를 다시 만든다. 그 사이 콘텐츠가 지워져 개수가 맞지 않으면 빈 값 — 다시 계산한다.
     */
    private Optional<List<BoardWithContentsDto>> fromCache(RecommendationCache.Entry entry, Long userId)
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

    /** 같은 구성의 보드가 이미 저장돼 있으면 저장된 보드(제목·좋아요 상태)를, 아니면 정해 둔 제목으로 미저장 보드를 만든다. */
    private List<BoardWithContentsDto> toBoards(List<List<ContentResponseDto>> slices, List<String> titles, Long userId)
    {
        List<BoardWithContentsDto> boards = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++)
        {
            List<ContentResponseDto> slice = slices.get(i);
            String title = titles.get(i);
            boards.add(boardService.findSavedGeneratedBoard(slice, userId)
                    .orElseGet(() -> BoardWithContentsDto.unsaved(title, slice)));
        }
        return boards;
    }

    /**
     * 기준 벡터와 가까운 콘텐츠 요약과 벡터를 유사도 높은 순으로 돌려준다.
     *
     * <p>사용자 취향 벡터와 검색어 벡터가 같은 임베딩 공간에 있으므로 조회 경로를 공유한다.
     * 호출하는 쪽은 벡터의 출처를 신경 쓰지 않는다.</p>
     *
     * <p>Qdrant가 매긴 순위는 getContentSummaries가 입력 순서를 유지해주므로 그대로 보존된다.
     * Qdrant에는 남아 있지만 RDB에서 사라진 콘텐츠도 거기서 함께 걸러진다.</p>
     */
    /**
     * 콘텐츠 종류마다 정해진 개수를 기준 벡터와 가까운 순으로 뽑아, 유사도 높은 순으로 섞어 돌려준다.
     * 검색 보드가 쓴다(SearchService). 정렬과 RDB 필터링은 findSimilar와 같다.
     */
    public SimilarContents findSimilarByType(float[] targetVector, Map<String, Integer> countByType)
    {
        Map<Long, float[]> vectorById = contentVectorService.findSimilarByType(targetVector, countByType);

        return new SimilarContents(contentService.getContentSummaries(List.copyOf(vectorById.keySet())), vectorById);
    }

    public SimilarContents findSimilar(float[] targetVector, int topN)
    {
        Map<Long, float[]> vectorById = contentVectorService.findSimilar(targetVector, topN);

        return new SimilarContents(contentService.getContentSummaries(List.copyOf(vectorById.keySet())), vectorById);
    }
}
