package com.crates.crates.service;

import com.crates.crates.qdrant.QdrantPointOperations;
import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.ScoredPointResult;
import com.crates.crates.qdrant.VectorPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentVectorService {

    private final QdrantPointOperations pointOperations;

    @Value("${ai.vectorstore.qdrant.content-collection-name}")
    private String collectionName;

    // 콘텐츠 벡터 컬렉션을 생성하거나 기존 컬렉션의 차원을 검증한다.
    public void ensureCollection(int vectorDimension) {
        pointOperations.ensureCollection(collectionName, vectorDimension);
        pointOperations.ensureKeywordIndex(collectionName, VectorPayload.CONTENT_TYPE);
    }

    /**
     * 컬렉션을 지우고 다시 만든다.
     *
     * <p>관계형 DB를 초기화하면 content.id가 1부터 다시 부여되는데, Qdrant는 그 사실을 모른다.
     * 이전 실행의 point가 남아 있으면 시더가 "이미 있다"고 판단해 건너뛰고,
     * 결과적으로 새 content에 옛 벡터가 매달린 채 조용히 잘못된 추천이 나간다.
     * 로컬에서 DB를 갈아엎을 때 함께 호출한다.</p>
     */
    public void recreateCollection(int vectorDimension) {
        pointOperations.deleteCollection(collectionName);
        ensureCollection(vectorDimension);
    }

    /** 컬렉션의 point 수. */
    public long countPoints() {
        return pointOperations.count(collectionName);
    }

    /** model_version이 주어진 값 중 하나인 point 수. */
    public long countPointsWithModelVersion(Collection<String> modelVersions) {
        return pointOperations.countMatching(collectionName, VectorPayload.MODEL_VERSION, List.copyOf(modelVersions));
    }

    /** 콘텐츠 종류(dtype)가 주어진 값 중 하나인 point 수. dtype을 싣기 전에 적재된 point를 가려낸다. */
    public long countPointsWithType(Collection<String> dtypes) {
        return pointOperations.countMatching(collectionName, VectorPayload.CONTENT_TYPE, List.copyOf(dtypes));
    }

    // 여러 콘텐츠 벡터를 Qdrant point로 변환해 배치 저장한다.
    // Qdrant point ID와 payload의 content_id를 관계형 DB의 content ID로 통일한다.
    public void upsertAll(List<ContentVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(record.contentId(), record.vector(), payloadOf(record)))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    private Map<String, Object> payloadOf(ContentVectorRecord record) {
        return Map.of(
                VectorPayload.CONTENT_ID, record.contentId(),
                VectorPayload.CONTENT_TYPE, record.dtype(),
                VectorPayload.MODEL_VERSION,
                VectorPayload.requireModelVersion(record.modelVersion(), "contentId " + record.contentId())
        );
    }

    /**
     * 콘텐츠 id로 벡터를 가져온다. id마다 따로 묻지 않고 모아서 넘기되, gRPC 수신 한도 때문에
     * 내부에서 1,000개씩 나눠 요청한다(QdrantPointOperations 참고). Qdrant에 없는 id는 결과에서 빠진다.
     */
    public Map<Long, float[]> findVectors(Collection<Long> contentIds) {
        return pointOperations.retrieveVectors(collectionName, contentIds);
    }

    /**
     * 기준 벡터와 가까운 콘텐츠의 id와 벡터를 유사도 높은 순으로 돌려준다.
     *
     * <p>벡터를 함께 받는 이유는 보드 제목 때문이다. 제목은 보드에 담긴 콘텐츠의 평균 벡터와 가장 가까운
     * query로 정하는데, 따로 retrieve하면 Qdrant 왕복이 한 번 늘어난다.</p>
     */
    /**
     * 콘텐츠 종류마다 정해진 개수를 기준 벡터와 가까운 순으로 뽑는다(묶음 검색 한 번).
     *
     * <p>콘텐츠 벡터는 종류별로 모여 있어서, 그냥 가까운 순으로 뽑으면 한 종류만 나오기 쉽다.
     * 종류마다 따로 걸러 검색해야 그 종류 안에서 가장 가까운 것을 확실히 얻는다 — 넉넉히 뽑아 고르는 방식은
     * 상위 수백 개 안에 어떤 종류가 아예 없을 수 있다.</p>
     *
     * @return 뽑힌 콘텐츠를 종류가 번갈아 오도록 놓은 id → 벡터 ({@link #interleave} 참고)
     */
    public Map<Long, float[]> findSimilarByType(float[] targetVector, Map<String, Integer> countByType) {
        List<List<ScoredPointResult>> byType = List.copyOf(
                pointOperations.searchEachValue(collectionName, targetVector, VectorPayload.CONTENT_TYPE, countByType)
                        .values());

        return interleave(byType).stream()
                .collect(LinkedHashMap::new, (map, result) -> map.put(result.id(), result.vector()), Map::putAll);
    }

    /**
     * 보드 여러 장 분량을 종류마다 한 번에 뽑아(묶음 검색 한 번) 보드마다 정해진 개수씩 나눠 담는다. 홈 추천 보드가 쓴다.
     *
     * <p>종류마다 {@code 장당 개수 × 장 수}를 가까운 순으로 받아, 앞에서부터 장당 개수씩 끊어 1번 보드부터 채운다.
     * 그래서 앞쪽 보드일수록 각 종류에서 취향에 더 가까운 콘텐츠가 들어간다. 보드 안에서는 {@link #interleave}로
     * 종류가 번갈아 오게 놓는다.</p>
     *
     * @return 보드 1번부터 차례로 이어 붙인 id → 벡터. 앞에서부터 보드 크기씩 끊으면 보드 한 장이다
     */
    public Map<Long, float[]> findSimilarBoardsByType(float[] targetVector, Map<String, Integer> perBoard, int boards) {
        Map<String, Integer> total = new LinkedHashMap<>();
        perBoard.forEach((type, count) -> total.put(type, count * boards));

        Map<String, List<ScoredPointResult>> byType =
                pointOperations.searchEachValue(collectionName, targetVector, VectorPayload.CONTENT_TYPE, total);

        return splitIntoBoards(byType, perBoard, boards).stream()
                .flatMap(board -> interleave(board).stream())
                .collect(LinkedHashMap::new, (map, result) -> map.put(result.id(), result.vector()), Map::putAll);
    }

    /**
     * 종류별 결과(가까운 순)를 앞에서부터 장당 개수씩 끊어 보드 장마다 나눈다.
     *
     * @return 보드마다 종류별 결과 목록
     */
    static List<List<List<ScoredPointResult>>> splitIntoBoards(Map<String, List<ScoredPointResult>> byType,
                                                              Map<String, Integer> perBoard, int boards) {
        List<List<List<ScoredPointResult>>> result = new ArrayList<>(boards);
        for (int board = 0; board < boards; board++) {
            List<List<ScoredPointResult>> parts = new ArrayList<>(perBoard.size());
            for (Map.Entry<String, Integer> entry : perBoard.entrySet()) {
                List<ScoredPointResult> results = byType.getOrDefault(entry.getKey(), List.of());
                int from = Math.min(board * entry.getValue(), results.size());
                int to = Math.min(from + entry.getValue(), results.size());
                parts.add(results.subList(from, to));
            }
            result.add(parts);
        }
        return result;
    }

    /**
     * 종류별 결과를 종류가 번갈아 오도록 한 줄로 놓는다.
     *
     * <p>규칙: 매 칸마다 <b>남은 개수가 가장 많은 종류</b>를 고르되, <b>바로 앞 칸과 같은 종류는 피한다</b>(다른 종류가
     * 남아 있을 때). 남은 개수가 같으면 다음 후보의 유사도가 높은 종류를 먼저 놓는다 — 결과가 매번 같고, 검색어와
     * 가까운 종류가 앞에 온다. 종류 안에서는 유사도 높은 순(검색 결과 순서) 그대로다.
     * 책 4 · 음악 2 · 영화 2면 책 → 음악 → 책 → 영화 → 책 → 음악 → 책 → 영화(음악·영화의 앞뒤는 유사도로 정해진다).</p>
     *
     * <p>순서는 보드를 가르는 기준이 아니다. 같은 보드인지는 정렬한 콘텐츠 id(content_signature)로 판단하므로,
     * 놓는 순서를 바꿔도 새 보드가 생기거나 저장된 보드를 못 찾는 일은 없다.</p>
     *
     * @param byType 종류별 검색 결과. 각 목록은 유사도 높은 순이다
     */
    static List<ScoredPointResult> interleave(List<List<ScoredPointResult>> byType) {
        List<Deque<ScoredPointResult>> queues = byType.stream()
                .map(results -> (Deque<ScoredPointResult>) new ArrayDeque<>(results))
                .toList();
        int total = queues.stream().mapToInt(Deque::size).sum();

        List<ScoredPointResult> ordered = new ArrayList<>(total);
        Deque<ScoredPointResult> previous = null;
        while (ordered.size() < total) {
            Deque<ScoredPointResult> lastPick = previous;
            Deque<ScoredPointResult> pick = queues.stream()
                    .filter(queue -> !queue.isEmpty())
                    .max(Comparator.<Deque<ScoredPointResult>>comparingInt(queue -> queue == lastPick ? 0 : 1)
                            .thenComparingInt(Deque::size)
                            .thenComparingDouble(queue -> queue.peekFirst().score()))
                    .orElseThrow();
            ordered.add(pick.pollFirst());
            previous = pick;
        }
        return ordered;
    }
}
