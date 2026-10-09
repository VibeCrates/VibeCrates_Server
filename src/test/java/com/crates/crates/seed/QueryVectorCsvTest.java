package com.crates.crates.seed;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryVectorCsvTest {

    private static final ByteArrayResource FILE = new ByteArrayResource(new byte[0], "test.csv");

    @Test
    void plainFields()
    {
        QueryVectorCsv.Row row = QueryVectorCsv.parse("#1 leather dreams,가죽 꿈결,VNoY+/=,m@1", FILE, 2);

        assertEquals("#1 leather dreams", row.query());
        assertEquals("가죽 꿈결", row.queryKo());
        assertEquals("VNoY+/=", row.vectorBase64());
        assertEquals("m@1", row.modelVersion());
    }

    /** 실제 파일 첫 행. 따옴표로 감싼 칸 안의 "" 는 따옴표 하나다. */
    @Test
    void quotedFieldWithEscapedQuotes()
    {
        QueryVectorCsv.Row row = QueryVectorCsv.parse("\"\"\"I\"\" meets \"\"thou\"\"\",나와 너의 만남,AAAA,m@1", FILE, 2);

        assertEquals("\"I\" meets \"thou\"", row.query());
        assertEquals("나와 너의 만남", row.queryKo());
    }

    /** 한국어 칸에 쉼표가 있으면 따옴표로 감싸 온다. 쉼표로 칸을 나누면 안 된다. */
    @Test
    void quotedFieldWithComma()
    {
        QueryVectorCsv.Row row = QueryVectorCsv.parse("Arnold's legacy light,\"아놀드의 유산, 한 줄기 빛\",AAAA,m@1", FILE, 2);

        assertEquals("Arnold's legacy light", row.query());
        assertEquals("아놀드의 유산, 한 줄기 빛", row.queryKo());
        assertEquals("AAAA", row.vectorBase64());
    }

    @Test
    void rejectsBrokenLine()
    {
        assertThrows(IllegalStateException.class, () -> QueryVectorCsv.parse("only,two", FILE, 2));
        assertThrows(IllegalStateException.class, () -> QueryVectorCsv.parse("\"unclosed,ko,AAAA,m@1", FILE, 2));
    }

    @Test
    void sameQueryGetsSameId()
    {
        assertEquals(com.crates.crates.initializer.QueryVectorLoader.idOf("burnt rose petals"),
                com.crates.crates.initializer.QueryVectorLoader.idOf(new String("burnt rose petals".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
    }
}
