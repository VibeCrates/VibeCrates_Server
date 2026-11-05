package com.crates.crates.service;

import com.crates.crates.DTO.ContentKeyDto;
import com.crates.crates.entity.UserEventLog;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.UserEventType;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.repository.UserEventLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 행동 로그를 user_event_log에 쌓는다. payload 모양은 docs/board-schema.md 13장이다.
 *
 * <p><b>호출하는 쪽의 트랜잭션 안에서</b> 넣는다. 좋아요·저장과 함께 커밋되거나 함께 롤백되므로 로그가 빠지거나
 * 거짓으로 남지 않고, 그 요청이 이미 잡은 커넥션을 쓰므로 커넥션을 더 차지하지 않는다. 비동기로 빼면 다른 스레드가
 * 커넥션을 하나 더 빌려야 하고, 커밋 뒤 앱이 죽으면 로그가 빠진다(13-1).</p>
 *
 * <p>보드는 id만 적지 않고 그 순간의 제목·아이템을 복사한다. 보드는 나중에 고쳐지거나 지워질 수 있고, 로그는 그때의 기록이다.
 * 아이템은 백엔드가 쓰는 content.id와, AI 서버가 콘텐츠를 가리키는 (종류, sourceKey)를 함께 적는다(13-2).</p>
 */
@Component
@RequiredArgsConstructor
public class UserEventLogger {

    /** payload 모양의 버전. 모양을 바꾸면 올려서 후처리가 옛 행과 새 행을 구분하게 한다. */
    static final int PAYLOAD_VERSION = 1;

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final UserEventLogRepository userEventLogRepository;
    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;

    /**
     * 로그에 적을 보드.
     *
     * @param boardId    저장되지 않은 보드(홈 추천·검색 결과)면 null
     * @param boardType  처음부터 만든 보드처럼 원본이 없으면 null
     * @param title      처음부터 만든 보드의 원본이면 null
     * @param contentIds 칸 순서. 원본이 없으면 빈 목록
     */
    public record BoardSnapshot(Long boardId, BoardType boardType, String title, List<Long> contentIds) {

        /** 원본 없이 처음부터 만든 보드의 "원래 상태". */
        public static BoardSnapshot none()
        {
            return new BoardSnapshot(null, null, null, List.of());
        }
    }

    public void boardLiked(Long userId, BoardSnapshot board)
    {
        save(userId, UserEventType.BOARD_LIKE, new BoardEventPayload(PAYLOAD_VERSION, boardOf(board, keysOf(board.contentIds()))));
    }

    /**
     * 좋아요 취소. 직접 취소한 것과, 좋아요한 내 보드를 지워 함께 사라진 것 모두 적는다 — 좋아요 상태는
     * BOARD_LIKE·BOARD_UNLIKE만으로 계산되게 한다. 둘은 같은 시각의 BOARD_DELETE가 있는지로 가린다(13-3).
     *
     * <p>보드가 완전 삭제되기 전에 불러야 한다. 지운 뒤에는 제목·아이템을 읽을 수 없다.</p>
     */
    public void boardUnliked(Long userId, BoardSnapshot board)
    {
        save(userId, UserEventType.BOARD_UNLIKE, new BoardEventPayload(PAYLOAD_VERSION, boardOf(board, keysOf(board.contentIds()))));
    }

    /** 내 보드 삭제. 좋아요 여부와 상관없이 지울 때마다 적는다. 지운 순간의 제목·아이템을 남긴다. */
    public void boardDeleted(Long userId, BoardSnapshot board)
    {
        save(userId, UserEventType.BOARD_DELETE, new BoardEventPayload(PAYLOAD_VERSION, boardOf(board, keysOf(board.contentIds()))));
    }

    /**
     * 저장에 성공한 보드 수정.
     *
     * <p>제목과 구성이 모두 그대로면 쌓지 않는다 — 배치만 바꾼 저장, 순서만 바꾼 저장은 뺀 작품·넣은 작품이 없다(13-4).
     * 뺀 작품과 넣은 작품은 집합 차로 구한다. 칸끼리 비교하면 순서만 바꾼 것을 "버렸다"로 적게 된다.</p>
     *
     * @param source       고치기 시작한 보드. 처음부터 만든 보드면 {@link BoardSnapshot#none()}
     * @param savedBoardId 저장된 보드. 남의 보드·추천 보드를 고쳤으면 새로 만든 내 보드다
     * @param title        저장한 제목({@code Board.normalizeTitle}을 거친 값)
     * @param contentIds   저장한 구성, 칸 순서
     */
    public void boardEdited(Long userId, BoardSnapshot source, Long savedBoardId, String title, List<Long> contentIds)
    {
        List<Long> removed = minus(source.contentIds(), contentIds);
        List<Long> added = minus(contentIds, source.contentIds());
        if (Objects.equals(source.title(), title) && removed.isEmpty() && added.isEmpty())
        {
            return;
        }

        Set<Long> allIds = new LinkedHashSet<>(source.contentIds());
        allIds.addAll(contentIds);
        Map<Long, ContentKeyDto> keys = keysOf(allIds);

        save(userId, UserEventType.BOARD_EDIT, new EditPayload(
                PAYLOAD_VERSION,
                new Source(source.boardId(), typeName(source.boardType())),
                savedBoardId,
                new TitleChange(source.title(), title),
                new ContentsChange(
                        itemsOf(source.contentIds(), keys), itemsOf(contentIds, keys),
                        itemsOf(removed, keys), itemsOf(added, keys))));
    }

    /**
     * 보드 사진 저장. 사진은 화면 그대로라 저장하지 않은 편집 상태일 수 있다(13-5).
     *
     * @param shown  화면에 보이던 보드. boardId·boardType은 원래 보드의 것이고, 제목·아이템은 화면의 것이다
     * @param edited 화면이 원래 보드와 다르면 true. false일 때만 서버가 구성을 확인한 값이다
     */
    public void boardCaptured(Long userId, BoardSnapshot shown, boolean edited)
    {
        save(userId, UserEventType.BOARD_CAPTURE,
                new CapturePayload(PAYLOAD_VERSION, boardOf(shown, keysOf(shown.contentIds())), edited));
    }

    private void save(Long userId, UserEventType type, Object payload)
    {
        userEventLogRepository.save(new UserEventLog(userId, type, objectMapper.convertValue(payload, JSON_OBJECT)));
    }

    /** 한 번에 조회한다. 아이템마다 조회하면 이벤트 한 건에 쿼리가 8~16번 나간다. */
    private Map<Long, ContentKeyDto> keysOf(Collection<Long> contentIds)
    {
        if (contentIds.isEmpty())
        {
            return Map.of();
        }
        return contentRepository.findKeysByIds(contentIds).stream()
                .collect(Collectors.toMap(ContentKeyDto::id, Function.identity()));
    }

    private static BoardPayload boardOf(BoardSnapshot board, Map<Long, ContentKeyDto> keys)
    {
        return new BoardPayload(board.boardId(), typeName(board.boardType()), board.title(),
                itemsOf(board.contentIds(), keys));
    }

    /** 없는 콘텐츠(그 사이 지워진 경우)도 id는 남긴다. 종류와 원본 id만 비운다. */
    private static List<ContentKeyDto> itemsOf(List<Long> contentIds, Map<Long, ContentKeyDto> keys)
    {
        return contentIds.stream()
                .map(id -> keys.getOrDefault(id, new ContentKeyDto(id, null, null)))
                .toList();
    }

    private static List<Long> minus(List<Long> from, List<Long> excluded)
    {
        Set<Long> excludedSet = Set.copyOf(excluded);
        return from.stream().filter(id -> !excludedSet.contains(id)).toList();
    }

    private static String typeName(BoardType boardType)
    {
        return boardType == null ? null : boardType.name();
    }

    // ── payload 모양. 필드 이름이 곧 JSON 키다(docs/board-schema.md 13-2 ~ 13-5) ──

    private record BoardPayload(Long boardId, String boardType, String title, List<ContentKeyDto> items) {
    }

    /** BOARD_LIKE, BOARD_UNLIKE, BOARD_DELETE가 같은 모양이다. */
    private record BoardEventPayload(int v, BoardPayload board) {
    }

    private record Source(Long boardId, String boardType) {
    }

    private record TitleChange(String before, String after) {
    }

    private record ContentsChange(List<ContentKeyDto> before, List<ContentKeyDto> after,
                                  List<ContentKeyDto> removed, List<ContentKeyDto> added) {
    }

    private record EditPayload(int v, Source source, Long savedBoardId, TitleChange title, ContentsChange contents) {
    }

    private record CapturePayload(int v, BoardPayload board, boolean edited) {
    }
}
