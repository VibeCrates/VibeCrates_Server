package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardSaveRequest;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.DTO.PageResponse;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.entity.contents.Content;
import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.MyBoardFilter;
import com.crates.crates.enumData.Rating;
import com.crates.crates.enumData.Visibility;
import java.util.HashMap;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import com.crates.crates.Global.paging.OffsetLimit;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    /**
     * 인기 보드를 요청 개수의 몇 배까지 가져올지. signature 중복 제거로 결과가 요청보다 적어지는 것을
     * 막기 위한 여유분이다. 같은 구성의 보드가 인기 상위에 동시에 오르는 일 자체가 드물어 2배면 충분하다.
     */
    private static final int FETCH_MULTIPLIER = 2;

    /** 저장 전 보드 좋아요가 경합(동시 생성, 그 사이 삭제)으로 다시 시도하는 상한. likeGeneratedBoard 참고. */
    private static final int MAX_SAVE_ATTEMPTS = 3;

    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final BoardFeedbackRepository boardFeedbackRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ApplicationEventPublisher eventPublisher;

    public BoardWithContentsDto getBoardWithContents(Long boardId, Long userId)
    {
        Board board = findReadableBoard(boardId, userId);
        List<ContentResponseDto> contents = contentService.getContents(boardId);

        return toResponse(board, contents, userId);
    }

    /**
     * 아직 저장되지 않은 보드(추천 보드, 검색 보드)를 사용자가 고쳤을 때 내 보드로 새로 만든다.
     *
     * <p>고칠 원본이 서버에 없으므로 수정이 아니라 생성이다. 원본이 되는 AI_RECOMMEND 보드는
     * 애초에 저장된 적이 없으니 건드릴 것도 없다.</p>
     */
    @Transactional
    public BoardWithContentsDto createUserBoard(BoardSaveRequest request, Long userId)
    {
        List<Content> contents = validateAndLoadContents(request.contentIds());
        String title = Board.normalizeTitle(request.title());
        ensureNoDuplicateUserBoard(userId, Board.signatureOf(request.contentIds()), title, null);

        User owner = findUser(userId);
        Board board = newUserBoard(owner, title);
        board.replaceContents(contents);
        boardRepository.save(board);

        return toResponse(board, contentService.getContentSummaries(request.contentIds()), userId);
    }

    /**
     * 보드의 제목과 콘텐츠를 통째로 갱신한다. 전달된 순서가 곧 배치 순서(slot 1~8)가 된다.
     *
     * <p>원본의 소유 상태에 따라 두 갈래다.</p>
     * <ul>
     *   <li>내 USER_CUSTOM 보드 → <b>그 자리에서 수정</b></li>
     *   <li>그 밖의 보드(추천·검색 보드, 남의 공개 보드, 주인이 지운 보드) → <b>원본은 두고 내 보드로 새로 저장</b>.
     *       새 보드의 타입은 USER_CUSTOM이고 좋아요는 0에서 시작한다.</li>
     * </ul>
     *
     * <p>새로 저장하는 이유: 추천·검색 보드는 소유자가 없는 전역 공용 보드다. 한 사람이
     * 고치면 그 보드를 좋아요한 다른 모든 사용자의 화면이 함께 바뀐다. 제목을 바꾸든 콘텐츠를
     * 바꾸든 순서만 바꾸든 판단 기준은 같다 — 원본에 소유자가 있는가.</p>
     *
     * @return 수정된(또는 새로 만들어진) 보드. 새로 저장된 경우 boardId가 새 값이므로 프론트가 반영해야 한다.
     */
    @Transactional
    public BoardWithContentsDto updateBoard(Long boardId, BoardSaveRequest request, Long userId)
    {
        List<Content> contents = validateAndLoadContents(request.contentIds());
        String signature = Board.signatureOf(request.contentIds());
        String title = Board.normalizeTitle(request.title());

        Board origin = findReadableBoard(boardId, userId);

        Board target;
        if (isEditableInPlace(origin, userId))
        {
            // 자기 자신은 검사에서 빼야 한다. 순서만 바꾼 수정은 제목·signature가 그대로라,
            // 제외하지 않으면 자기 자신 때문에 "이미 같은 제목과 구성의 보드가 있다"고 거절된다.
            // 제목만 바꾼 수정은 같은 구성의 내 다른 보드와 제목이 겹치면 거절된다.
            ensureNoDuplicateUserBoard(userId, signature, title, origin.getId());
            origin.updateTitle(title);
            origin.replaceContents(contents);
            target = origin;
        }
        else
        {
            // 원본은 그대로 두고 내 USER_CUSTOM 보드를 새로 만든다.
            // 원본의 좋아요는 옮기지 않는다. 원본은 다른 사용자들의 것이기도 하다.
            ensureNoDuplicateUserBoard(userId, signature, title, null);
            Board ownBoard = newUserBoard(findUser(userId), title);
            ownBoard.replaceContents(contents);
            target = boardRepository.save(ownBoard);
        }

        // 방금 넘긴 순서가 곧 slot 순서라, 다시 조회하지 않고 그대로 요약을 만든다.
        return toResponse(target, contentService.getContentSummaries(request.contentIds()), userId);
    }

    private List<Content> validateAndLoadContents(List<Long> contentIds)
    {
        if (contentIds.size() != Board.ITEMS_PER_BOARD)
        {
            throw new BusinessException(
                    "보드에는 콘텐츠 " + Board.ITEMS_PER_BOARD + "건이 필요합니다. 요청: " + contentIds.size());
        }
        return findContentsInRequestOrder(contentIds);
    }

    /** 내 USER_CUSTOM 보드만 그 자리에서 고칠 수 있다. */
    private boolean isEditableInPlace(Board board, Long userId)
    {
        return board.getBoardType() == BoardType.USER_CUSTOM && isOwnedBy(board, userId);
    }

    private boolean isOwnedBy(Board board, Long userId)
    {
        return board.getUser() != null && board.getUser().getId().equals(userId);
    }

    /** 사용자 소유 보드의 초기 상태. 좋아요는 0, 공개 여부는 기본 공개다. */
    private Board newUserBoard(User owner, String title)
    {
        return Board.builder()
                .boardType(BoardType.USER_CUSTOM)
                .user(owner)
                .visibility(Visibility.PUBLIC)
                .title(title)
                .likeCount(0L)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private User findUser(Long userId)
    {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 사용자입니다. userId: " + userId));
    }

    /**
     * uk_board_user_title_signature에 부딪혀 500이 나기 전에, 설명 가능한 메시지로 막는다.
     * 구성만 같고 제목이 다른 보드는 허용한다(docs/board-schema.md 11-5).
     *
     * @param title {@link Board#normalizeTitle}을 거친 제목
     */
    private void ensureNoDuplicateUserBoard(Long userId, String signature, String title, Long excludedBoardId)
    {
        boardRepository.findActiveUserBoard(userId, signature, title)
                .filter(board -> !board.getId().equals(excludedBoardId))
                .ifPresent(board ->
                {
                    throw new BusinessException("이미 같은 제목과 구성의 보드를 갖고 계세요. boardId: " + board.getId());
                });
    }

    /**
     * 조회 가능한 보드만 돌려준다.
     *
     * <p>PRIVATE 보드는 소유자만 볼 수 있다. 접근 불가와 존재하지 않음을 같은 메시지로 처리하는 이유는,
     * 응답을 구분해주면 id를 훑어 남의 비공개 보드가 존재한다는 사실을 알아낼 수 있기 때문이다.</p>
     *
     * <p>주인이 지운 보드는 그 보드에 좋아요한 사람만 볼 수 있다. 좋아요한 사람은 직접 취소할 때까지
     * 그 보드를 갖는다(docs/board-schema.md 11-6). 그 사람이 고치면 다른 공용 보드처럼 원본은 두고 내 보드로 새로 저장된다.</p>
     */
    private Board findReadableBoard(Long boardId, Long userId)
    {
        Board board = boardRepository.findById(boardId)
                .filter(found -> !found.isDeleted()
                        || boardFeedbackRepository.existsByBoardIdAndUserIdAndRating(boardId, userId, Rating.LIKE))
                .orElseThrow(() -> new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId));

        if (board.getVisibility() == Visibility.PRIVATE && !isOwnedBy(board, userId))
        {
            throw new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId);
        }
        return board;
    }

    private BoardWithContentsDto toResponse(Board board, List<ContentResponseDto> contents, Long userId)
    {
        boolean liked = boardFeedbackRepository
                .existsByBoardIdAndUserIdAndRating(board.getId(), userId, Rating.LIKE);

        return new BoardWithContentsDto(
                board.getId(), board.getTitle(), board.getLikeCount(), liked, board.isDeleted(),
                isOwnedBy(board, userId), contents);
    }

    public List<BoardWithContentsDto> getLikedBoards(int n, Long userId)
    {
        // 중복 제거로 결과가 n개 미만이 되지 않도록 여유분을 두고 가져온다.
        List<Board> candidates = boardRepository.findPopularBoards(n * FETCH_MULTIPLIER);

        return toResponses(dedupeBySignature(candidates, n), userId);
    }

    /**
     * 보관함 — 내 보드를 탭별로 한 페이지씩.
     *
     * <p>인기 보드와 달리 signature 중복 제거를 하지 않는다. 인기 목록은 남들에게 보여주는
     * 진열대라 같은 그림이 두 번 뜨면 안 되지만, 여기는 <b>내가 가진 것을 빠짐없이</b> 보여주는
     * 자리다. 좋아요한 보드와 그걸 고쳐 만든 내 보드는 서로 다른 보드이므로 둘 다 나와야 한다.</p>
     *
     * <p>홈의 "내 보드" 섹션도 이 메서드를 쓴다. 그 섹션이 보는 것은 전체 탭의 첫 페이지와
     * 정확히 같아서(같은 필터, 같은 정렬) 조회 경로를 나눌 이유가 없다.</p>
     */
    public PageResponse<BoardWithContentsDto> getMyBoards(MyBoardFilter filter, int page, int size, Long userId)
    {
        // 한 건 더 가져와 초과분이 있는지로 hasNext를 판정한다. count 쿼리가 아예 필요 없다.
        //
        // PageRequest.of(page, size + 1)로 적으면 안 된다. 오프셋이 page * (size + 1)이 되어
        // 2페이지부터 경계의 항목 하나가 건너뛰어진다. 가져오는 개수와 건너뛰는 개수는 다른 값이다.
        List<Board> fetched = findMyBoardsBy(filter, userId, OffsetLimit.forPageWithLookahead(page, size));

        boolean hasNext = fetched.size() > size;
        // 자르고 나서 toResponses를 부른다. 순서를 바꾸면 응답에 나가지도 않을 보드의
        // 콘텐츠 8건을 헛되이 조립하게 된다.
        List<Board> boards = hasNext ? fetched.subList(0, size) : fetched;

        return new PageResponse<>(toResponses(boards, userId), page, size, hasNext);
    }

    /**
     * 탭에 맞는 조회를 고른다.
     *
     * <p>쿼리 하나에 조건 분기를 넣지 않는 이유는 탭마다 <b>정렬 축이 다르기</b> 때문이다.
     * 좋아요는 누른 시각, 만든 것은 생성 시각, 전체는 둘을 합친 값으로 정렬한다.
     * WHERE만이라면 몰라도 ORDER BY까지 분기하면 JPQL 한 벌로는 읽을 수 없게 된다.</p>
     */
    private List<Board> findMyBoardsBy(MyBoardFilter filter, Long userId, Pageable pageable)
    {
        return switch (filter)
        {
            // 네이티브 UNION ALL이라 Pageable을 넘기지 못한다. 각 갈래가 offset + size 만큼
            // 가져와야 합쳐서 잘랐을 때 맞으므로 cap을 따로 계산해 넘긴다.
            case ALL -> boardRepository.findMyBoards(
                    userId, Rating.LIKE.name(),
                    pageable.getPageSize(), pageable.getOffset(),
                    pageable.getOffset() + pageable.getPageSize());
            case LIKED -> boardFeedbackRepository.findLikedBoards(userId, Rating.LIKE, pageable);
            case CREATED -> boardRepository.findCreatedByMe(userId, pageable);
        };
    }

    /**
     * 보드 목록에 콘텐츠와 좋아요 여부를 붙인다.
     *
     * <p>보드마다 조회하면 N번 왕복하므로, 콘텐츠 id·요약·내 좋아요 여부를 각각 한 번에 가져와
     * 메모리에서 맞춘다.</p>
     */
    private List<BoardWithContentsDto> toResponses(List<Board> boards, Long userId)
    {
        if (boards.isEmpty())
        {
            return Collections.emptyList();
        }

        List<Long> boardIds = boards.stream()
                .map(Board::getId)
                .toList();
        List<BoardContentIdDto> pairs = boardItemRepository.findContentIdsByBoardIds(boardIds);

        // slotNo 순으로 정렬돼 오므로 groupingBy가 그 순서를 그대로 유지한다.
        Map<Long, List<Long>> contentIdsByBoardId = pairs.stream()
                .collect(Collectors.groupingBy(
                        BoardContentIdDto::boardId,
                        Collectors.mapping(BoardContentIdDto::contentId, Collectors.toList())
                ));

        List<Long> allContentIds = pairs.stream()
                .map(BoardContentIdDto::contentId)
                .distinct()
                .toList();
        Map<Long, ContentResponseDto> summaryById = contentService.getContentSummaries(allContentIds).stream()
                .collect(Collectors.toMap(ContentResponseDto::id, Function.identity()));

        Set<Long> likedBoardIds = Set.copyOf(findLikedBoardIds(userId, boardIds));

        return boards.stream()
                .map(board -> new BoardWithContentsDto(
                        board.getId(),
                        board.getTitle(),
                        board.getLikeCount(),
                        likedBoardIds.contains(board.getId()),
                        board.isDeleted(),
                        // 소유자는 지연 로딩 프록시지만 id만 읽으므로 users를 조회하지 않는다.
                        isOwnedBy(board, userId),
                        contentIdsByBoardId.getOrDefault(board.getId(), List.of()).stream()
                                .map(summaryById::get)
                                .filter(Objects::nonNull)
                                .toList()
                ))
                .toList();
    }

    /**
     * 이미 저장된 보드에 좋아요를 누른다. 인기 목록·보관함처럼 boardId만 가진 화면이 쓴다.
     *
     * <p>연타나 재요청으로 같은 요청이 두 번 와도 카운트가 두 번 오르지 않도록 멱등하게 동작한다.
     * 예외를 던지지 않는 이유는, 이미 누른 상태라는 것이 사용자 입장에서 실패가 아니기 때문이다.</p>
     *
     * <p>지워진 보드(좋아요가 0이 되어 지워진 추천·검색 보드, 주인이 지운 보드)에는 좋아요를 붙이지 않는다.
     * 홈 추천·검색 화면은 boardId가 아니라 구성·토큰으로 좋아요하므로 이 실패를 겪지 않는다(docs/board-schema.md 11-8).</p>
     */
    @Transactional
    public BoardLikeResponse like(Long boardId, Long userId)
    {
        return tryLike(boardId, userId)
                .orElseThrow(() -> new BusinessException("삭제된 보드입니다. boardId: " + boardId));
    }

    /**
     * 좋아요를 붙인다. 보드가 지워졌으면 비어 있다.
     *
     * <p><b>좋아요 수를 먼저 올리고 기록을 남긴다.</b> 좋아요 수 갱신이 보드 행을 잠그므로, 그 뒤로는 이 트랜잭션이
     * 끝날 때까지 보드가 지워지지 않는다. 반대 순서면 기록을 남기는 사이 다른 사람의 취소로 보드가 지워져
     * 외래 키 오류가 난다. 갱신된 행이 없으면 보드가 이미 없거나 주인이 지운 것이다.</p>
     */
    private Optional<BoardLikeResponse> tryLike(Long boardId, Long userId)
    {
        if (boardFeedbackRepository.existsByBoardIdAndUserIdAndRating(boardId, userId, Rating.LIKE))
        {
            return Optional.of(new BoardLikeResponse(boardId, currentLikeCount(boardId), true));
        }

        if (boardRepository.incrementLikeCount(boardId) == 0)
        {
            return Optional.empty();
        }
        saveFeedback(boardRepository.getReferenceById(boardId), userId);
        publishLikeChanged(userId);

        return Optional.of(new BoardLikeResponse(boardId, currentLikeCount(boardId), true));
    }

    /**
     * 좋아요 취소. 누른 적이 없으면 카운트를 건드리지 않는다.
     *
     * <p>취소로 좋아요가 0이 되면 더 보관할 이유가 없는 보드를 지운다 — 추천·검색 보드, 그리고 주인이 이미 지운
     * 사용자 보드다. 주인이 지우지 않은 사용자 보드는 좋아요가 0이어도 남는다(docs/board-schema.md 11-6).
     * 좋아요 수를 내리는 갱신이 보드 행을 잠근 상태라, 지우는 동안 다른 좋아요가 끼어들지 못한다.</p>
     *
     * <p>보드가 이미 없으면 오류가 아니라 취소된 상태로 응답한다. 사용자가 원하던 상태가 이미 됐다.</p>
     *
     * @return 보드가 지워졌으면 boardId가 null인 응답
     */
    @Transactional
    public BoardLikeResponse unlike(Long boardId, Long userId)
    {
        int removed = boardFeedbackRepository.deleteFeedback(boardId, userId, Rating.LIKE);
        if (removed == 0)
        {
            return boardRepository.findLikeCountById(boardId)
                    .map(likeCount -> new BoardLikeResponse(boardId, likeCount, false))
                    .orElseGet(() -> new BoardLikeResponse(null, 0L, false));
        }

        boardRepository.decrementLikeCount(boardId);
        publishLikeChanged(userId);

        if (deleteIfUnliked(boardId))
        {
            return new BoardLikeResponse(null, 0L, false);
        }
        return new BoardLikeResponse(boardId, currentLikeCount(boardId), false);
    }

    /**
     * 내 보드를 지운다. 보관함에서 주인만 할 수 있다.
     *
     * <ol>
     *   <li>내 좋아요가 있으면 지운다 — 남겨 두면 지운 보드가 내 보관함 좋아요 탭에 다시 나타난다.</li>
     *   <li>소유자를 비우고 지운 시각을 남긴다(소프트 삭제). 다른 사람의 좋아요가 남아 있으면 그 사람들에게는
     *       계속 보이고 취향 벡터에도 남는다. 다른 사람이 자기 의사와 무관하게 좋아요를 잃지 않게 하려는 것이다.</li>
     *   <li>남은 좋아요가 없으면 바로 완전히 지운다. 있으면 마지막 좋아요가 취소될 때 지운다({@link #unlike}).</li>
     * </ol>
     *
     * <p>보드 행을 먼저 잠근다. 지우는 사이 들어온 좋아요는 이 트랜잭션이 끝날 때까지 기다렸다가,
     * 지워진 보드라 거절된다.</p>
     */
    @Transactional
    public void deleteUserBoard(Long boardId, Long userId)
    {
        boardRepository.findByIdForUpdate(boardId)
                .filter(board -> board.getBoardType() == BoardType.USER_CUSTOM && isOwnedBy(board, userId))
                .orElseThrow(() -> new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId));

        if (boardFeedbackRepository.deleteFeedback(boardId, userId, Rating.LIKE) > 0)
        {
            boardRepository.decrementLikeCount(boardId);
            publishLikeChanged(userId);
        }

        boardRepository.softDelete(boardId, LocalDateTime.now());
        deleteIfUnliked(boardId);
    }

    /**
     * 좋아요가 하나도 남지 않은 추천·검색 보드나 주인이 지운 보드를 행째 지운다. 지웠으면 true.
     * 호출하는 쪽이 보드 행을 잠근 상태여야 한다(좋아요 수 갱신 또는 findByIdForUpdate).
     *
     * <p>콘텐츠를 먼저 지운다. 외래 키에 ON DELETE CASCADE를 걸지 않았다 — board_feedback 외래 키가
     * 좋아요가 남은 보드를 지우지 못하게 막는 안전장치라서다(V3 머리말).</p>
     */
    private boolean deleteIfUnliked(Long boardId)
    {
        boardItemRepository.deleteItemsIfUnliked(boardId);
        return boardRepository.deleteIfUnliked(boardId) > 0;
    }

    /**
     * 즉석에서 만들어진 홈 추천 보드와 같은 구성의 보드가 이미 저장돼 있으면 그 응답을 돌려준다.
     *
     * <p>저장돼 있으면 boardId·좋아요 수·내 좋아요 여부를 채워준다. 이 조회가 없으면, 좋아요를 눌러 저장한 뒤
     * 다시 추천받았을 때 하트가 비어 있고 좋아요 수가 0으로 보인다. 저장 여부는 화면이 아니라 DB가 알고 있어야 한다.</p>
     *
     * <p>이미 저장된 보드라면 <b>저장 시점의 제목</b>을 쓴다. 방금 만들어낸 제목으로 덮으면
     * 그 보드에 좋아요를 누른 다른 사용자들이 보던 이름과 달라진다.</p>
     *
     * <p><b>저장되지 않은 보드의 제목은 여기서 만들지 않는다.</b> 제목을 정하려면 Qdrant에서 query를 검색해야 하는데,
     * 이 메서드는 트랜잭션 안이라 그동안 DB 커넥션을 쥐고 기다리게 된다. 예전에는 제목을 Supplier로 받아 여기서
     * 만들었고, Qdrant가 포화되자 커넥션 풀이 막혀 추천과 무관한 API까지 느려졌다(2026-10-04 부하 측정:
     * 혼합 150/s에서 풀 대기 56, 다른 API p95 4ms → 381ms). 호출하는 쪽이 비어 있을 때 트랜잭션 밖에서 제목을 만든다.</p>
     */
    public Optional<BoardWithContentsDto> findSavedGeneratedBoard(List<ContentResponseDto> contents, Long userId)
    {
        String signature = Board.signatureOf(contents.stream().map(ContentResponseDto::id).toList());

        return boardRepository.findActiveAiBoardBySignature(signature)
                .map(board -> savedBoardResponse(board, contents, userId));
    }

    /**
     * 검색 결과와 제목·구성이 같은 검색 보드가 이미 저장돼 있으면 그 응답을 돌려준다.
     * 다시 검색했을 때 하트가 채워져 보이는 것은 이 조회 덕분이다.
     *
     * @param title {@link Board#normalizeTitle}을 거친 검색어
     */
    public Optional<BoardWithContentsDto> findSavedSearchBoard(String title, List<ContentResponseDto> contents,
                                                               Long userId)
    {
        String signature = Board.signatureOf(contents.stream().map(ContentResponseDto::id).toList());

        return boardRepository.findSearchBoard(title, signature)
                .map(board -> savedBoardResponse(board, contents, userId));
    }

    private BoardWithContentsDto savedBoardResponse(Board board, List<ContentResponseDto> contents, Long userId)
    {
        return new BoardWithContentsDto(
                board.getId(),
                board.getTitle(),
                board.getLikeCount(),
                boardFeedbackRepository.existsByBoardIdAndUserIdAndRating(board.getId(), userId, Rating.LIKE),
                false,
                false,  // 추천·검색 보드는 소유자가 없다
                contents);
    }

    /**
     * 홈 추천 보드에 좋아요. 같은 구성의 보드가 있으면 거기에 좋아요를 남기고, 없으면 저장한 뒤 남긴다.
     *
     * <p>같은 구성이면 같은 보드다. 이 규칙이 없으면 재추천 후 좋아요를 누를 때마다 내용이 똑같은 보드가
     * 계속 쌓이고 좋아요가 여러 행으로 갈라져 인기 랭킹이 흐려진다.</p>
     *
     * <p>제목과 콘텐츠는 요청이 아니라 검증된 토큰에서 온 값이다(BoardLikeTokenService).</p>
     *
     * <p>응답의 boardId를 프론트가 반드시 반영해야 다음 클릭이 좋아요 취소로 이어진다.
     */
    @Transactional
    public BoardLikeResponse likeRecommendedBoard(String title, List<Long> contentIds, Long userId)
    {
        return likeGeneratedBoard(GeneratedBoard.AI_RECOMMEND, Board.normalizeTitle(title), contentIds, userId);
    }

    /**
     * 검색 보드에 좋아요. 제목(검색어)과 구성이 같은 보드가 있으면 거기에 좋아요를 남기고, 없으면 저장한 뒤 남긴다.
     *
     * <p>제목과 콘텐츠는 요청이 아니라 검증된 토큰에서 온 값이다(BoardLikeTokenService).</p>
     *
     * @param title {@link Board#normalizeTitle}을 거친 검색어
     */
    @Transactional
    public BoardLikeResponse likeSearchBoard(String title, List<Long> contentIds, Long userId)
    {
        return likeGeneratedBoard(GeneratedBoard.SEARCH_RECOMMEND, title, contentIds, userId);
    }

    /** 좋아요할 때 저장되는 보드 종류. 같은 보드를 찾는 기준과 만드는 문장이 다르다. */
    private enum GeneratedBoard
    {
        AI_RECOMMEND, SEARCH_RECOMMEND
    }

    /**
     * 저장 전 보드(홈 추천·검색)의 좋아요. "있으면 좋아요, 없으면 저장 후 좋아요"를 한 번에 한다.
     *
     * <p>두 경합을 다룬다.</p>
     * <ul>
     *   <li><b>동시에 처음 좋아요</b> — 둘 다 "없음"을 보고 저장하면 유일 인덱스에 걸린다. INSERT ... ON CONFLICT
     *       DO NOTHING으로 오류 없이 만들고, 비어 돌아오면 먼저 만들어진 보드를 다시 찾아 좋아요한다.</li>
     *   <li><b>찾은 보드가 그 사이 지워짐</b> — 마지막 좋아요가 취소되어 보드가 지워지면 좋아요 수 갱신이 0건이다.
     *       지워진 보드는 좋아요가 0개였으니 새로 저장해도 잃는 것이 없다. 다시 찾고 없으면 만든다.</li>
     * </ul>
     * <p>READ COMMITTED라 바퀴마다 새 문장이 다른 트랜잭션의 커밋을 본다. 한 바퀴에 두 경합이 번갈아 겹치는 일은
     * 드물어 바퀴 수에 상한을 둔다.</p>
     */
    private BoardLikeResponse likeGeneratedBoard(GeneratedBoard kind, String title, List<Long> contentIds, Long userId)
    {
        // 보드를 만들기 전에 콘텐츠부터 확인한다. 존재하지 않는 id나 중복이 섞였으면 행을 만들기 전에 거절한다.
        List<Content> contents = validateAndLoadContents(contentIds);
        String signature = Board.signatureOf(contentIds);

        for (int attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++)
        {
            Optional<Board> existing = switch (kind)
            {
                case AI_RECOMMEND -> boardRepository.findActiveAiBoardBySignature(signature);
                case SEARCH_RECOMMEND -> boardRepository.findSearchBoard(title, signature);
            };
            if (existing.isPresent())
            {
                Optional<BoardLikeResponse> liked = tryLike(existing.get().getId(), userId);
                if (liked.isPresent())
                {
                    return liked.get();
                }
                continue;   // 그 사이 지워졌다. 다시 찾고 없으면 만든다
            }

            Optional<Long> createdId = switch (kind)
            {
                case AI_RECOMMEND -> boardRepository.insertAiBoard(title, signature);
                case SEARCH_RECOMMEND -> boardRepository.insertSearchBoard(title, signature);
            };
            if (createdId.isPresent())
            {
                // 행은 좋아요 1로 만들어졌다. 콘텐츠를 채우면 slotNo도 함께 정해진다.
                Board board = boardRepository.findById(createdId.get()).orElseThrow();
                board.replaceContents(contents);
                saveFeedback(board, userId);
                publishLikeChanged(userId);
                return new BoardLikeResponse(board.getId(), board.getLikeCount(), true);
            }
            // 다른 사람이 먼저 만들었다. 다음 바퀴에서 그 보드를 찾는다.
        }

        throw new BusinessException("요청이 몰려 좋아요를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 콘텐츠 구성이 같은 보드가 여러 개 뽑혔을 때 하나만 남긴다.
     *
     * <p>사용자가 직접 만든 보드와 AI가 만든 보드의 콘텐츠가 우연히 같을 수 있다. 이들은 제목과
     * 소유자가 다른 별개의 행이라 저장 단계에서는 합치지 않지만(소유권이 전염된다), 인기 목록에
     * 나란히 노출되면 같은 그림이 두 번 보인다. 그래서 <b>표시 단계에서만</b> 하나로 접는다.</p>
     *
     * <p>가져온 후보 안에서만 비교한다. 후보 밖에 있는 보드를 끌어올리지는 않는다.</p>
     */
    private List<Board> dedupeBySignature(List<Board> candidates, int limit)
    {
        Map<String, Board> winnerBySignature = new HashMap<>();
        for (Board board : candidates)
        {
            winnerBySignature.merge(board.getContentSignature(), board, BoardService::preferBoard);
        }

        // 좋아요 많은 순 정렬을 그대로 유지한 채 대표로 뽑힌 보드만 남긴다.
        // 살아남은 보드를 원래 자리에 두어야 목록이 계속 좋아요 순으로 읽힌다.
        return candidates.stream()
                .filter(board -> board.equals(winnerBySignature.get(board.getContentSignature())))
                .limit(limit)
                .toList();
    }

    /**
     * 같은 콘텐츠 구성의 보드 중 목록에 남길 하나를 고른다.
     *
     * <ol>
     *   <li>타입 우선순위 — USER_CUSTOM &gt; SEARCH_RECOMMEND &gt; AI_RECOMMEND.
     *       사람이 더 많이 관여한 보드가 앞선다. 검색 보드는 사용자가 입력한 검색어가 제목이다.</li>
     *   <li>같은 타입이면 <b>좋아요가 많은 쪽</b>. 인기 보드 목록에서 적게 받은 쪽을 남길 이유가 없다.</li>
     *   <li>좋아요까지 같으면 먼저 만들어진 쪽(id가 작은 쪽).</li>
     * </ol>
     *
     * <p>후보가 이미 좋아요 순으로 정렬돼 들어오지만 그 순서에 기대지 않는다.
     * 호출하는 쪽의 정렬이 바뀌면 조용히 다른 보드가 뽑히게 되기 때문이다.</p>
     */
    private static Board preferBoard(Board left, Board right)
    {
        int byType = Integer.compare(dedupePriority(left.getBoardType()), dedupePriority(right.getBoardType()));
        if (byType != 0)
        {
            return byType < 0 ? left : right;
        }

        long leftLikes = likeCountOf(left);
        long rightLikes = likeCountOf(right);
        if (leftLikes != rightLikes)
        {
            return leftLikes > rightLikes ? left : right;
        }

        return left.getId() <= right.getId() ? left : right;
    }

    private static long likeCountOf(Board board)
    {
        return board.getLikeCount() == null ? 0L : board.getLikeCount();
    }

    // 값이 작을수록 우선. USER_CUSTOM > SEARCH_RECOMMEND > AI_RECOMMEND.
    // 인기 보드 정렬(BoardRepository.findPopularBoards, idx_board_popular)의 CASE 식과 같은 순서다.
    private static int dedupePriority(BoardType boardType)
    {
        return switch (boardType)
        {
            case USER_CUSTOM -> 0;
            case SEARCH_RECOMMEND -> 1;
            case AI_RECOMMEND -> 2;
        };
    }

    /** 화면에 보이는 보드들 중 이 사용자가 좋아요한 것들의 id. 보드마다 조회하지 않도록 한 번에 가져온다. */
    public List<Long> findLikedBoardIds(Long userId, List<Long> boardIds)
    {
        if (boardIds.isEmpty())
        {
            return Collections.emptyList();
        }
        return boardFeedbackRepository.findLikedBoardIds(userId, Rating.LIKE, boardIds);
    }

    /**
     * 요청에 담긴 순서 그대로 콘텐츠를 가져온다.
     *
     * <p>findAllById는 IN 절 결과를 DB가 주는 순서로 돌려주기 때문에 그대로 쓰면
     * 프론트가 보여준 배치(= 유사도 순)가 slotNo에 반영되지 않는다.</p>
     */
    private List<Content> findContentsInRequestOrder(List<Long> contentIds)
    {
        Map<Long, Content> contentById = contentRepository.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, Function.identity()));

        if (contentById.size() != contentIds.size())
        {
            // 존재하지 않는 id가 섞였거나, 같은 id가 두 번 들어온 경우 둘 다 여기서 걸린다.
            throw new BusinessException("보드에 담을 수 없는 콘텐츠가 포함되어 있습니다.");
        }

        return contentIds.stream()
                .map(contentById::get)
                .toList();
    }

    /**
     * 취향 벡터를 다시 계산하라고 알린다.
     *
     * <p>여기서 직접 계산하지 않는 이유는 두 가지다. Qdrant 왕복이 붙어 좋아요 응답이 느려지고,
     * 벡터 계산이 실패하면 좋아요까지 롤백된다. 리스너가 커밋 이후에 비동기로 처리한다.</p>
     */
    private void publishLikeChanged(Long userId)
    {
        eventPublisher.publishEvent(new BoardLikeChangedEvent(userId));
    }

    private void saveFeedback(Board board, Long userId)
    {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 사용자입니다. userId: " + userId));

        boardFeedbackRepository.save(BoardFeedback.builder()
                .board(board)
                .user(user)
                .rating(Rating.LIKE)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Long currentLikeCount(Long boardId)
    {
        return boardRepository.findLikeCountById(boardId).orElse(0L);
    }
}
