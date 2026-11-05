package com.crates.crates.entity.board;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 배치 저장 규칙(docs/board-schema.md 12-1). */
class BoardLayoutTest {

    private static final List<Content> CONTENTS = LongStream.rangeClosed(1, 8).mapToObj(BoardLayoutTest::content).toList();

    @Test
    @DisplayName("배치를 칸 순서대로 저장하고, 콘텐츠가 바뀌어도 signature는 배치와 무관하다")
    void storesLayoutPerSlot()
    {
        Board board = new Board();
        List<ItemLayout> layout = layout();

        board.replaceContents(CONTENTS, layout);

        assertEquals(layout, board.getLayout());
        assertEquals(Board.signatureOf(LongStream.rangeClosed(1, 8).boxed().toList()), board.getContentSignature());
    }

    @Test
    @DisplayName("null이면 기본 배치이고, 저장돼 있던 배치를 지운다")
    void nullMeansDefaultAndClearsSavedLayout()
    {
        Board board = new Board();
        board.replaceContents(CONTENTS, layout());

        board.replaceContents(CONTENTS, null);

        assertNull(board.getLayout());
        board.getBoardItems().forEach(item -> assertNull(item.getLayout()));
    }

    @Test
    @DisplayName("8칸 모두 보내지 않으면 거절한다 — 옮긴 칸만 저장하지 않는다")
    void rejectsPartialLayout()
    {
        List<ItemLayout> seven = layout().subList(0, 7);
        List<ItemLayout> withHole = new ArrayList<>(layout());
        withHole.set(3, null);
        List<ItemLayout> withEmptyValue = new ArrayList<>(layout());
        withEmptyValue.set(3, new ItemLayout(10f, null, 0f, 4));

        assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, seven));
        assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, withHole));
        assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, withEmptyValue));
    }

    @Test
    @DisplayName("위치 0~100, 기울기 -180~180을 벗어나거나 NaN이면 거절한다")
    void rejectsOutOfRange()
    {
        for (ItemLayout bad : List.of(
                new ItemLayout(-0.1f, 50f, 0f, 1),
                new ItemLayout(50f, 100.1f, 0f, 1),
                new ItemLayout(50f, 50f, 181f, 1),
                new ItemLayout(Float.NaN, 50f, 0f, 1)))
        {
            List<ItemLayout> layout = new ArrayList<>(layout());
            layout.set(0, bad);
            assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, layout));
        }
    }

    @Test
    @DisplayName("겹침 순서는 1~8을 한 번씩 써야 한다")
    void rejectsDuplicateOrOutOfRangeZ()
    {
        List<ItemLayout> duplicated = new ArrayList<>(layout());
        duplicated.set(0, new ItemLayout(10f, 10f, 0f, 2));
        List<ItemLayout> outOfRange = new ArrayList<>(layout());
        outOfRange.set(0, new ItemLayout(10f, 10f, 0f, 9));

        assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, duplicated));
        assertThrows(BusinessException.class, () -> new Board().replaceContents(CONTENTS, outOfRange));
    }

    private static List<ItemLayout> layout()
    {
        return IntStream.rangeClosed(1, 8)
                .mapToObj(z -> new ItemLayout(z * 10f, z % 2 == 0 ? 30f : 70f, z * 5f - 20f, z))
                .toList();
    }

    private static Content content(long id)
    {
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(id);
        return content;
    }
}
