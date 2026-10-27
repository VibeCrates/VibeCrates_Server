package com.crates.crates.repository;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.entity.board.BoardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardItemRepository extends JpaRepository<BoardItem, Long> {
    // JPQL을 사용해 딱 'content'만 프로젝션(추출)해서 리스트로 반환.
    // slotNo 정렬이 없으면 DB가 주는 순서로 나와서 사용자가 배치한 순서가 화면에 반영되지 않는다.
    @Query("SELECT bi.content.id FROM BoardItem bi WHERE bi.board.id = :boardId ORDER BY bi.slotNo")
    List<Long> findContentIdsByBoardId(@Param("boardId") Long boardId);

    // 보드별로 묶어 쓰기 때문에 board.id로 먼저 정렬한 뒤 슬롯 순서를 맞춘다.
    @Query("SELECT new com.crates.crates.DTO.BoardContentIdDto(bi.board.id, bi.content.id) " +
            "FROM BoardItem bi WHERE bi.board.id IN :boardIds ORDER BY bi.board.id, bi.slotNo")
    List<BoardContentIdDto> findContentIdsByBoardIds(@Param("boardIds") List<Long> boardIds);

    /**
     * {@link BoardRepository#deleteIfUnliked}로 지울 보드의 콘텐츠를 먼저 지운다. 조건이 같아야 한다.
     *
     * <p>외래 키에 ON DELETE CASCADE가 없어 보드보다 먼저 지워야 한다. 조건을 여기에도 두는 이유는,
     * 보드를 지우지 않게 된 경우(그 사이 좋아요가 들어옴) 콘텐츠만 지워진 빈 보드가 남지 않게 하려는 것이다.
     * 호출하는 쪽은 보드 행을 먼저 잠가야 두 문장 사이에 조건이 바뀌지 않는다.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM board_item bi
             WHERE bi.board_id = :boardId
               AND EXISTS (SELECT 1 FROM board b
                            WHERE b.id = :boardId AND b.like_count = 0
                              AND (b.board_type IN ('AI_RECOMMEND', 'SEARCH_RECOMMEND') OR b.deleted_at IS NOT NULL))
            """, nativeQuery = true)
    int deleteItemsIfUnliked(@Param("boardId") Long boardId);
}
