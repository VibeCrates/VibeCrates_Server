package com.crates.crates.service;

import com.crates.crates.DTO.ContentKeyDto;
import com.crates.crates.entity.UserEventLog;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.UserEventType;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.repository.UserEventLogRepository;
import com.crates.crates.service.UserEventLogger.BoardSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 행동 로그 payload 모양(docs/board-schema.md 13장). JSON 키는 후처리가 읽는 계약이라 실제 ObjectMapper로 본다. */
class UserEventLoggerTest {

    private static final Long USER_ID = 7L;
    private static final List<Long> BEFORE = List.of(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L);
    private static final List<Long> AFTER = List.of(11L, 12L, 93L, 14L, 15L, 16L, 17L, 88L);

    private final UserEventLogRepository logRepository = mock(UserEventLogRepository.class);
    private final ContentRepository contentRepository = mock(ContentRepository.class);
    private final UserEventLogger logger = new UserEventLogger(logRepository, contentRepository, new ObjectMapper());

    @BeforeEach
    void setUp()
    {
        // 요청받은 id마다 BOOK·"src-<id>"를 돌려준다. 18은 그 사이 지워진 콘텐츠라 빠진다.
        when(contentRepository.findKeysByIds(any())).thenAnswer(call -> ((Collection<Long>) call.getArgument(0)).stream()
                .filter(id -> id != 18L)
                .map(id -> new ContentKeyDto(id, "BOOK", "src-" + id))
                .toList());
    }

    @Test
    @DisplayName("수정 로그는 원래·저장 구성과, 집합 차로 구한 뺀 작품·넣은 작품을 적는다")
    void editPayload()
    {
        BoardSnapshot source = new BoardSnapshot(null, BoardType.SEARCH_RECOMMEND, "비 오는 날", BEFORE);

        logger.boardEdited(USER_ID, source, 152L, "비 오는 밤", AFTER);

        UserEventLog log = savedLog();
        assertEquals(UserEventType.BOARD_EDIT, log.getEventType());
        Map<String, Object> payload = log.getPayload();
        assertEquals(1, payload.get("v"));
        assertEquals(Map.of("boardType", "SEARCH_RECOMMEND"), withoutNulls(map(payload.get("source"))));
        assertNull(map(payload.get("source")).get("boardId"));
        assertEquals(152L, payload.get("savedBoardId"));
        assertEquals(Map.of("before", "비 오는 날", "after", "비 오는 밤"), payload.get("title"));

        Map<String, Object> contents = map(payload.get("contents"));
        assertEquals(List.of(13L, 18L), ids(contents.get("removed")));
        assertEquals(List.of(93L, 88L), ids(contents.get("added")));
        assertEquals(8, ((List<?>) contents.get("before")).size());
        assertEquals(Map.of("id", 13L, "type", "BOOK", "sourceKey", "src-13"),
                ((List<?>) contents.get("removed")).getFirst());
        // 지워진 콘텐츠도 id는 남기고 종류·원본 id만 비운다.
        assertNull(map(((List<?>) contents.get("removed")).get(1)).get("sourceKey"));
    }

    @Test
    @DisplayName("제목과 구성이 그대로인 저장(배치만·순서만 바꿈)은 쌓지 않는다")
    void skipsLayoutOnlyEdit()
    {
        BoardSnapshot source = new BoardSnapshot(42L, BoardType.USER_CUSTOM, "t", BEFORE);

        logger.boardEdited(USER_ID, source, 42L, "t", BEFORE.reversed());

        verify(logRepository, never()).save(any());
    }

    @Test
    @DisplayName("제목만 바꾼 저장은 쌓고, 뺀 작품·넣은 작품은 비어 있다")
    void titleOnlyEdit()
    {
        logger.boardEdited(USER_ID, new BoardSnapshot(42L, BoardType.USER_CUSTOM, "t", BEFORE), 42L, "새 제목", BEFORE);

        Map<String, Object> contents = map(savedLog().getPayload().get("contents"));
        assertEquals(List.of(), contents.get("removed"));
        assertEquals(List.of(), contents.get("added"));
    }

    @Test
    @DisplayName("원본 없이 만든 보드는 원래 상태가 비어 있고 모두 넣은 작품이다")
    void editWithoutOrigin()
    {
        logger.boardEdited(USER_ID, BoardSnapshot.none(), 152L, "t", AFTER);

        Map<String, Object> payload = savedLog().getPayload();
        assertNull(map(payload.get("source")).get("boardType"));
        assertEquals(8, ((List<?>) map(payload.get("contents")).get("added")).size());
    }

    @Test
    @DisplayName("좋아요 취소는 그 순간의 보드만 적는다")
    void unlikePayload()
    {
        logger.boardUnliked(USER_ID, new BoardSnapshot(42L, BoardType.TASTE_RECOMMEND, "새벽", AFTER));

        UserEventLog log = savedLog();
        assertEquals(UserEventType.BOARD_UNLIKE, log.getEventType());
        assertEquals(java.util.Set.of("v", "board"), log.getPayload().keySet());
        Map<String, Object> board = map(log.getPayload().get("board"));
        assertEquals(42L, board.get("boardId"));
        assertEquals("TASTE_RECOMMEND", board.get("boardType"));
        assertEquals("새벽", board.get("title"));
        assertEquals(AFTER, ids(board.get("items")));
    }

    @Test
    @DisplayName("보드 삭제는 좋아요 정보 없이 지운 순간의 보드만 적는다")
    void deletePayload()
    {
        logger.boardDeleted(USER_ID, new BoardSnapshot(42L, BoardType.USER_CUSTOM, "내 보드", AFTER));

        UserEventLog log = savedLog();
        assertEquals(UserEventType.BOARD_DELETE, log.getEventType());
        assertEquals(java.util.Set.of("v", "board"), log.getPayload().keySet());
        assertEquals("내 보드", map(log.getPayload().get("board")).get("title"));
    }

    @Test
    @DisplayName("사진 저장은 화면의 보드와 원래 보드와 다른지를 적는다")
    void capturePayload()
    {
        logger.boardCaptured(USER_ID, new BoardSnapshot(null, BoardType.SEARCH_RECOMMEND, "재즈", AFTER), true);

        UserEventLog log = savedLog();
        assertEquals(UserEventType.BOARD_CAPTURE, log.getEventType());
        assertEquals(true, log.getPayload().get("edited"));
        assertEquals(USER_ID, log.getUserId());
    }

    private UserEventLog savedLog()
    {
        ArgumentCaptor<UserEventLog> captor = ArgumentCaptor.forClass(UserEventLog.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value)
    {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> value)
    {
        return value.entrySet().stream().filter(e -> e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<Object> ids(Object items)
    {
        return ((List<?>) items).stream().map(item -> map(item).get("id")).toList();
    }
}
