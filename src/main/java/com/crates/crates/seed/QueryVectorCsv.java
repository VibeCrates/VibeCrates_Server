package com.crates.crates.seed;

import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * AI 개발자가 내려준 query 벡터 CSV({@code query,query_ko,vector_b64,model_version})를 읽는다.
 *
 * <p>콘텐츠 벡터 CSV와 달리 앞의 두 칸이 사람이 쓴 문장이라 쉼표와 따옴표가 들어간다
 * (예: {@code """I"" meets ""thou""",나와 너의 만남,...}, {@code "아놀드의 유산, 한 줄기 빛"}).
 * 뒤의 두 칸(base64, 버전 문자열)에는 쉼표가 없으므로 오른쪽에서 쉼표 두 개로 떼고,
 * 남은 앞부분만 CSV 규칙(따옴표로 감싼 칸, {@code ""}는 따옴표 하나)대로 두 칸으로 나눈다.
 * 한 줄에 한 행이다 — 줄바꿈이 들어간 칸은 없다(파일 행 수 = 줄 수 - 1로 확인).</p>
 *
 * <p>파일이 수백 MB라(book만 약 980MB) 통째로 올리지 않고 한 줄씩 흘려보낸다.</p>
 */
public final class QueryVectorCsv {

    private static final String HEADER = "query,query_ko,vector_b64,model_version";

    private QueryVectorCsv() {
    }

    /**
     * CSV 한 줄. vectorBase64는 아직 디코딩하지 않은 값이다 — 개수만 셀 때 디코딩 비용을 치르지 않게.
     *
     * @param query   영어 원문. 벡터는 이 문장으로 만들었다
     * @param queryKo 한국어 번역. 화면에 보드 제목으로 보여주는 값이다
     */
    public record Row(String query, String queryKo, String vectorBase64, String modelVersion) {
    }

    public static void forEachRow(Resource file, Consumer<Row> action) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            requireHeader(reader.readLine(), file);

            String line;
            long lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                action.accept(parse(line, file, lineNumber));
            }
        }
    }

    /** 첫 데이터 행. 파일 전체를 읽지 않고 모델 버전만 확인할 때 쓴다. */
    public static Optional<Row> firstRow(Resource file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            requireHeader(reader.readLine(), file);
            String line = reader.readLine();
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(parse(line, file, 2));
        }
    }

    private static void requireHeader(String header, Resource file) {
        // BOM이 붙어 오는 경우가 있어 떼고 비교한다.
        String actual = header == null ? null : header.replace("﻿", "");
        if (!HEADER.equals(actual)) {
            throw new IllegalStateException(
                    "query 벡터 CSV 헤더가 예상과 다릅니다. file=" + file.getDescription()
                            + ", expected=" + HEADER + ", actual=" + header);
        }
    }

    static Row parse(String line, Resource file, long lineNumber) {
        int last = line.lastIndexOf(',');
        int secondLast = last <= 0 ? -1 : line.lastIndexOf(',', last - 1);
        if (secondLast <= 0 || last == line.length() - 1) {
            throw invalid(file, lineNumber);
        }

        List<String> texts = splitQuoted(line.substring(0, secondLast));
        if (texts == null || texts.size() != 2) {
            throw invalid(file, lineNumber);
        }
        return new Row(texts.get(0), texts.get(1), line.substring(secondLast + 1, last), line.substring(last + 1));
    }

    /** CSV 칸 나누기. 따옴표로 감싼 칸 안의 쉼표는 칸을 나누지 않고, {@code ""}는 따옴표 하나다. 형식이 깨지면 null. */
    private static List<String> splitQuoted(String text) {
        List<String> fields = new ArrayList<>(2);
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStart = true;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && fieldStart) {
                quoted = true;
                fieldStart = false;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStart = true;
            } else {
                field.append(c);
                fieldStart = false;
            }
        }
        if (quoted) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }

    private static IllegalStateException invalid(Resource file, long lineNumber) {
        return new IllegalStateException(
                "query 벡터 CSV 형식이 잘못됐습니다. file=" + file.getDescription() + ", line=" + lineNumber);
    }
}
