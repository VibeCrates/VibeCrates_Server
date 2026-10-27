package com.crates.crates.DTO;

/**
 * @param boardId   좋아요한 보드의 id. 새로 저장된 보드라면 새 id이므로 프론트는 이 값을 캐시에 반영해야
 *                  같은 보드를 두 번 저장하지 않는다. 좋아요 취소로 보드가 지워졌으면(좋아요 0개가 된
 *                  추천·검색 보드, 주인이 지운 보드의 마지막 좋아요) null이다.
 * @param likeCount 반영 후의 좋아요 수
 * @param liked     요청한 사용자의 좋아요 여부
 */
public record BoardLikeResponse(
        Long boardId,
        Long likeCount,
        boolean liked
) {
}
