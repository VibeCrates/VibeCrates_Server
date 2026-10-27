package com.crates.crates.service;

import com.crates.crates.DTO.BoardLikeRequest;
import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardSaveRequest;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.contents.Content;
import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.Rating;
import com.crates.crates.enumData.Visibility;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 좋아요·삭제 규칙(docs/board-schema.md 11장)의 분기. SQL 자체(ON CONFLICT, 조건부 삭제)는 저장소가 맡고,
 * 여기서는 그 결과에 따라 서비스가 올바른 길로 가는지만 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BoardServiceLikeTest {

    private static final Long USER_ID = 7L;
    private static final Long BOARD_ID = 100L;
    private static final List<Long> CONTENT_IDS = LongStream.rangeClosed(1, 8).boxed().toList();
    private static final String SIGNATURE = Board.signatureOf(CONTENT_IDS);

    @Mock private BoardRepository boardRepository;
    @Mock private BoardItemRepository boardItemRepository;
    @Mock private BoardFeedbackRepository boardFeedbackRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentService contentService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BoardService service;

    @BeforeEach
    void setUp()
    {
        List<Content> contents = CONTENT_IDS.stream().map(BoardServiceLikeTest::content).toList();
        when(contentService.getContentSummaries(any())).thenReturn(List.of());
        when(contentRepository.findAllById(any())).thenReturn(contents);
        // 목 안에서 목을 만들면(when 안의 when) 스터빙이 꼬인다. 먼저 만들어 둔다.
        User me = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(me));
        when(boardRepository.getReferenceById(anyLong())).thenAnswer(call -> board(call.getArgument(0), BoardType.AI_RECOMMEND));
    }

    @Nested
    @DisplayName("저장된 보드에 좋아요")
    class Like {

        @Test
        @DisplayName("지워진 보드(좋아요 수 갱신 0건)면 '삭제된 보드'로 거절하고 좋아요 기록을 남기지 않는다")
        void rejectsDeletedBoard()
        {
            when(boardRepository.incrementLikeCount(BOARD_ID)).thenReturn(0);

            BusinessException e = assertThrows(BusinessException.class, () -> service.like(BOARD_ID, USER_ID));

            assertTrue(e.getMessage().startsWith("삭제된 보드입니다."));
            verify(boardFeedbackRepository, never()).save(any());
        }

        @Test
        @DisplayName("좋아요 수를 먼저 올려 행을 잠근 뒤 기록을 남긴다")
        void incrementsBeforeSavingFeedback()
        {
            when(boardRepository.incrementLikeCount(BOARD_ID)).thenReturn(1);
            when(boardRepository.findLikeCountById(BOARD_ID)).thenReturn(Optional.of(3L));

            BoardLikeResponse response = service.like(BOARD_ID, USER_ID);

            var order = inOrder(boardRepository, boardFeedbackRepository);
            order.verify(boardRepository).incrementLikeCount(BOARD_ID);
            order.verify(boardFeedbackRepository).save(any());
            assertEquals(new BoardLikeResponse(BOARD_ID, 3L, true), response);
        }
    }

    @Nested
    @DisplayName("좋아요 취소")
    class Unlike {

        @Test
        @DisplayName("취소로 보드가 지워지면 boardId가 null인 응답을 준다")
        void returnsNullBoardIdWhenBoardRemoved()
        {
            when(boardFeedbackRepository.deleteFeedback(BOARD_ID, USER_ID, Rating.LIKE)).thenReturn(1);
            when(boardRepository.deleteIfUnliked(BOARD_ID)).thenReturn(1);

            BoardLikeResponse response = service.unlike(BOARD_ID, USER_ID);

            var order = inOrder(boardRepository, boardItemRepository);
            order.verify(boardRepository).decrementLikeCount(BOARD_ID);
            order.verify(boardItemRepository).deleteItemsIfUnliked(BOARD_ID);
            order.verify(boardRepository).deleteIfUnliked(BOARD_ID);
            assertEquals(new BoardLikeResponse(null, 0L, false), response);
        }

        @Test
        @DisplayName("좋아요가 남으면 보드를 그대로 두고 남은 수를 돌려준다")
        void keepsBoardWithRemainingLikes()
        {
            when(boardFeedbackRepository.deleteFeedback(BOARD_ID, USER_ID, Rating.LIKE)).thenReturn(1);
            when(boardRepository.deleteIfUnliked(BOARD_ID)).thenReturn(0);
            when(boardRepository.findLikeCountById(BOARD_ID)).thenReturn(Optional.of(4L));

            assertEquals(new BoardLikeResponse(BOARD_ID, 4L, false), service.unlike(BOARD_ID, USER_ID));
        }

        @Test
        @DisplayName("이미 없는 보드의 취소는 오류가 아니라 취소된 상태로 응답한다")
        void treatsMissingBoardAsUnliked()
        {
            when(boardFeedbackRepository.deleteFeedback(BOARD_ID, USER_ID, Rating.LIKE)).thenReturn(0);
            when(boardRepository.findLikeCountById(BOARD_ID)).thenReturn(Optional.empty());

            assertEquals(new BoardLikeResponse(null, 0L, false), service.unlike(BOARD_ID, USER_ID));
            verify(boardRepository, never()).deleteIfUnliked(anyLong());
        }
    }

    @Nested
    @DisplayName("저장 전 보드(홈 추천·검색)에 좋아요")
    class LikeGenerated {

        @Test
        @DisplayName("같은 제목·구성의 검색 보드가 없으면 새로 만들고 콘텐츠를 채운다")
        void createsSearchBoard()
        {
            Board created = board(BOARD_ID, BoardType.SEARCH_RECOMMEND);
            when(boardRepository.findSearchBoard("재즈", SIGNATURE)).thenReturn(Optional.empty());
            when(boardRepository.insertSearchBoard("재즈", SIGNATURE)).thenReturn(Optional.of(BOARD_ID));
            when(boardRepository.findById(BOARD_ID)).thenReturn(Optional.of(created));

            BoardLikeResponse response = service.likeSearchBoard("재즈", CONTENT_IDS, USER_ID);

            assertEquals(BOARD_ID, response.boardId());
            assertTrue(response.liked());
            assertEquals(SIGNATURE, created.getContentSignature());
            verify(boardFeedbackRepository).save(any());
        }

        @Test
        @DisplayName("동시에 먼저 만들어졌으면(ON CONFLICT로 빈 결과) 그 보드를 다시 찾아 좋아요한다")
        void likesBoardCreatedConcurrently()
        {
            when(boardRepository.findSearchBoard("재즈", SIGNATURE))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(board(BOARD_ID, BoardType.SEARCH_RECOMMEND)));
            when(boardRepository.insertSearchBoard("재즈", SIGNATURE)).thenReturn(Optional.empty());
            when(boardRepository.incrementLikeCount(BOARD_ID)).thenReturn(1);
            when(boardRepository.findLikeCountById(BOARD_ID)).thenReturn(Optional.of(2L));

            assertEquals(new BoardLikeResponse(BOARD_ID, 2L, true),
                    service.likeSearchBoard("재즈", CONTENT_IDS, USER_ID));
        }

        @Test
        @DisplayName("찾은 보드가 그 사이 지워졌으면 새로 만든다")
        void recreatesWhenFoundBoardWasDeleted()
        {
            Long recreatedId = 200L;
            when(boardRepository.findActiveAiBoardBySignature(SIGNATURE))
                    .thenReturn(Optional.of(board(BOARD_ID, BoardType.AI_RECOMMEND)))
                    .thenReturn(Optional.empty());
            when(boardRepository.incrementLikeCount(BOARD_ID)).thenReturn(0);
            when(boardRepository.insertAiBoard("새벽 감성", SIGNATURE)).thenReturn(Optional.of(recreatedId));
            when(boardRepository.findById(recreatedId))
                    .thenReturn(Optional.of(board(recreatedId, BoardType.AI_RECOMMEND)));

            BoardLikeResponse response = service.likeNewBoard(new BoardLikeRequest(" 새벽 감성 ", CONTENT_IDS), USER_ID);

            assertEquals(recreatedId, response.boardId());
            verify(boardRepository).insertAiBoard("새벽 감성", SIGNATURE);
        }

        @Test
        @DisplayName("콘텐츠가 8건이 아니면 보드를 만들기 전에 거절한다")
        void rejectsWrongSize()
        {
            assertThrows(BusinessException.class,
                    () -> service.likeSearchBoard("재즈", CONTENT_IDS.subList(0, 7), USER_ID));
            verify(boardRepository, never()).insertSearchBoard(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("내 보드 삭제")
    class DeleteUserBoard {

        @Test
        @DisplayName("남의 보드는 지울 수 없다")
        void rejectsOthersBoard()
        {
            User other = user(99L);
            Board others = userBoard(BOARD_ID, other);
            when(boardRepository.findByIdForUpdate(BOARD_ID)).thenReturn(Optional.of(others));

            assertThrows(BusinessException.class, () -> service.deleteUserBoard(BOARD_ID, USER_ID));
            verify(boardRepository, never()).softDelete(anyLong(), any());
        }

        @Test
        @DisplayName("내 좋아요를 지우고 소프트 삭제한 뒤, 좋아요가 남지 않았으면 완전히 지운다")
        void removesOwnLikeThenSoftDeletesThenTriesHardDelete()
        {
            Board mine = userBoard(BOARD_ID, user(USER_ID));
            when(boardRepository.findByIdForUpdate(BOARD_ID)).thenReturn(Optional.of(mine));
            when(boardFeedbackRepository.deleteFeedback(BOARD_ID, USER_ID, Rating.LIKE)).thenReturn(1);

            service.deleteUserBoard(BOARD_ID, USER_ID);

            var order = inOrder(boardFeedbackRepository, boardRepository, boardItemRepository);
            order.verify(boardFeedbackRepository).deleteFeedback(BOARD_ID, USER_ID, Rating.LIKE);
            order.verify(boardRepository).decrementLikeCount(BOARD_ID);
            order.verify(boardRepository).softDelete(eq(BOARD_ID), any());
            order.verify(boardItemRepository).deleteItemsIfUnliked(BOARD_ID);
            order.verify(boardRepository).deleteIfUnliked(BOARD_ID);
        }
    }

    @Test
    @DisplayName("같은 구성이라도 제목이 다르면 내 보드를 하나 더 만들 수 있고, 제목까지 같으면 거절한다")
    void userBoardDuplicateIsTitlePlusSignature()
    {
        Board existing = userBoard(BOARD_ID, user(USER_ID));
        when(boardRepository.findActiveUserBoard(USER_ID, SIGNATURE, "재즈")).thenReturn(Optional.of(existing));
        when(boardRepository.findActiveUserBoard(USER_ID, SIGNATURE, "새벽")).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createUserBoard(new BoardSaveRequest(" 재즈 ", CONTENT_IDS), USER_ID));
        assertTrue(e.getMessage().startsWith("이미 같은 제목과 구성의 보드"));

        service.createUserBoard(new BoardSaveRequest("새벽", CONTENT_IDS), USER_ID);
        verify(boardRepository).save(any(Board.class));
    }

    private static Board board(Long id, BoardType type)
    {
        return Board.builder().id(id).boardType(type).visibility(Visibility.PUBLIC)
                .title("t").contentSignature("x").likeCount(1L).build();
    }

    private static Board userBoard(Long id, User owner)
    {
        return Board.builder().id(id).boardType(BoardType.USER_CUSTOM).user(owner).visibility(Visibility.PUBLIC)
                .title("t").contentSignature(SIGNATURE).likeCount(0L).build();
    }

    private static User user(Long id)
    {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private static Content content(Long id)
    {
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(id);
        return content;
    }
}
