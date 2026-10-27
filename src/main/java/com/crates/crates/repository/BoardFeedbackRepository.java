package com.crates.crates.repository;

import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.enumData.Rating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardFeedbackRepository extends JpaRepository<BoardFeedback, Long> {

    boolean existsByBoardIdAndUserIdAndRating(Long boardId, Long userId, Rating rating);

    boolean existsByUserIdAndRating(Long userId, Rating rating);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM BoardFeedback f WHERE f.board.id = :boardId AND f.user.id = :userId AND f.rating = :rating")
    int deleteFeedback(@Param("boardId") Long boardId,
                       @Param("userId") Long userId,
                       @Param("rating") Rating rating);

    /**
     * 이 사용자가 좋아요한 보드 전부를 좋아요한 시각과 함께. 취향 벡터를 다시 계산할 때 쓴다.
     *
     * <p>삭제된 보드를 배제하지 않는다. deleted_at이 있는 보드는 주인이 지운 USER_CUSTOM뿐이고, 좋아요한
     * 사람의 취향에는 그 사람이 직접 취소할 때까지 남아야 한다(docs/board-schema.md 11-6). 다른 보드는 좋아요가
     * 0이 될 때만 행째 지워지므로 좋아요 기록이 남은 채 사라지는 보드가 없다.</p>
     *
     * <p>board와 조인하지 않고 board_feedback만 읽는다. idx_feedback_user_recent가 board_id를 INCLUDE로 담고 있어
     * 힙을 찾아가지 않는다(V3). 서브쿼리 대신 {@code f.board.id}를 쓰는 것이 중요하다 — {@code f.board}는
     * board로 조인을 유발한다.</p>
     */
    @Query("SELECT new com.crates.crates.DTO.LikedBoardDto(f.board.id, f.createdAt) " +
           "FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating")
    List<LikedBoardDto> findLikedBoardsWithTime(@Param("userId") Long userId,
                                                @Param("rating") Rating rating);

    /**
     * 보관함 "좋아요" 탭 — 내가 좋아요한 보드를 최근에 누른 순으로.
     *
     * <p>보드가 아니라 <b>board_feedback에서 출발한다.</b> 정렬 기준이 "내가 언제 눌렀는지"라
     * f.createdAt이 필요하고, 그 값은 좋아요 행에만 있다.</p>
     *
     * <p>동률 기준이 b.id가 아니라 <b>f.id</b>인 이유: 좋아요 행 id는 누른 순서와 같은 방향이라
     * 의미가 정렬 축과 맞고, idx_feedback_user_recent (user_id, rating, created_at DESC, id DESC)의
     * 마지막 컬럼이라 정렬이 통째로 인덱스로 해결된다. b.id는 다른 테이블 컬럼이라 인덱스가
     * 커버하지 못해 별도 정렬이 붙는다.</p>
     *
     * <p>rating 조건은 성능 때문이 아니라 정확성 때문에 있다. 지금은 LIKE만 저장되지만
     * Rating에 DISLIKE가 있어, 싫어요를 쓰기 시작하면 그 보드들이 보관함에 "좋아요한 보드"로
     * 섞여 들어온다. 겸사겸사 위 인덱스의 매칭 조건이기도 하다.</p>
     *
     * <p>주인이 지운 보드도 보인다. 좋아요한 사람은 직접 취소할 때까지 그 보드를 갖는다(11-6).</p>
     */
    @Query("SELECT b FROM BoardFeedback f JOIN f.board b " +
           "WHERE f.user.id = :userId AND f.rating = :rating " +
           "ORDER BY f.createdAt DESC, f.id DESC")
    List<Board> findLikedBoards(@Param("userId") Long userId,
                                @Param("rating") Rating rating,
                                Pageable pageable);

    /** 화면에 보이는 보드들 중 내가 좋아요한 것만 추려낸다. 보드마다 조회하지 않기 위한 일괄 조회. */
    @Query("SELECT f.board.id FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND f.board.id IN :boardIds")
    List<Long> findLikedBoardIds(@Param("userId") Long userId,
                                 @Param("rating") Rating rating,
                                 @Param("boardIds") List<Long> boardIds);
}
