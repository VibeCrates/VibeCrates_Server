package com.crates.crates.Global.exception;

import lombok.Getter;

/**
 * 저장하려는 보드와 제목·구성이 같은 내 보드가 이미 있다(docs/board-schema.md 11-5).
 *
 * <p>응답에 그 보드의 id를 담아 프론트가 "보관함의 보드 열기"로 안내할 수 있게 한다. 배치만 바꿔 저장하려다
 * 거절되면 그 배치는 저장되지 않으므로, 기존 보드로 옮겨 가 이어서 고치게 하려는 것이다(12-4).</p>
 */
@Getter
public class DuplicateBoardException extends BusinessException {

    private final Long existingBoardId;

    public DuplicateBoardException(Long existingBoardId)
    {
        super("이미 같은 제목과 구성의 보드를 갖고 계세요.");
        this.existingBoardId = existingBoardId;
    }
}
