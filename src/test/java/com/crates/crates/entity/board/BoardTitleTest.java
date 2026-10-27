package com.crates.crates.entity.board;

import com.crates.crates.Global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardTitleTest {

    @Test
    @DisplayName("자모가 분리된(NFD) 한글을 완성형(NFC)으로 바꿔 같은 제목으로 만든다")
    void normalizesToNfc()
    {
        String decomposed = Normalizer.normalize("재즈 명반", Normalizer.Form.NFD);

        assertEquals("재즈 명반", Board.normalizeTitle(decomposed));
    }

    @Test
    @DisplayName("앞뒤 공백만 걷고 문장 안의 공백과 대소문자는 그대로 둔다")
    void stripsOnlyEnds()
    {
        assertEquals("Jazz  명반", Board.normalizeTitle("  Jazz  명반 \t"));
    }

    @Test
    @DisplayName("전각 공백만 있는 제목은 거절한다 — @NotBlank는 ASCII 공백만 거른다")
    void rejectsIdeographicSpaceOnly()
    {
        assertThrows(BusinessException.class, () -> Board.normalizeTitle("　　"));
    }
}
