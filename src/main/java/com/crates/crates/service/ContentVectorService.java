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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 콘텐츠 종류마다 정해진 개수를 기준 벡터와 가까운 순으로 뽑는다(묶음 검색 한 번). 검색 보드가 쓴다.
     *
     * <p>콘텐츠 벡터는 종류별로 모여 있어서, 그냥 가까운 순으로 뽑으면 한 종류만 나오기 쉽다.
     * 종류마다 따로 걸러 검색해야 그 종류 안에서 가장 가까운 것을 확실히 얻는다 — 넉넉히 뽑아 고르는 방식은
     * 상위 수백 개 안에 어떤 종류가 아예 없을 수 있다.</p>
     *
     * @return 뽑힌 콘텐츠 id를 종류가 번갈아 오도록 놓은 것 ({@link #interleave} 참고)
     */
    public List<Long> findSimilarByType(float[] targetVector, Map<String, Integer> countByType) {
        List<List<ScoredPointResult>> byType = List.copyOf(
                pointOperations.searchEachValue(collectionName, targetVector, VectorPayload.CONTENT_TYPE, countByType)
                        .values());

        return interleave(byType).stream().map(ScoredPointResult::id).toList();
    }

    /**
     * 보드마다 자기 기준 벡터로 종류별 개수를 뽑되, 앞 보드에 담긴 콘텐츠는 다시 담지 않는다. 홈 추천 보드가 쓴다.
     *
     * <p>보드 수 × 종류 수만큼의 검색을 <b>한 번의 묶음 요청</b>으로 보낸다. 앞 보드의 결과를 보고 다음 보드를 검색하면
     * 왕복이 보드 수만큼 순서대로 늘어나므로, 겹침은 받은 뒤 여기서 거른다({@link #pickDistinctBoards}).</p>
     *
     * <p>그래서 종류마다 {@code 장당 개수 × 장 수}를 받는다. 마지막 보드라도 앞 보드들이 그 종류에서 가져간 것이
     * {@code 장당 개수 × (장 수 − 1)}개를 넘지 않으므로, 겹친 것을 모두 빼도 장당 개수가 남는다.</p>
     *
     * @return 보드 순서대로 콘텐츠 id. 보드 안에서는 종류가 번갈아 온다
     */
    public List<List<Long>> findDistinctBoardsByType(List<float[]> targetVectors, Map<String, Integer> perBoard) {
        Map<String, Integer> fetch = new LinkedHashMap<>();
        perBoard.forEach((type, count) -> fetch.put(type, count * targetVectors.size()));

        List<Map<String, List<ScoredPointResult>>> results =
                pointOperations.searchEachValue(collectionName, targetVectors, VectorPayload.CONTENT_TYPE, fetch);

        return pickDistinctBoards(results, perBoard).stream()
                .map(board -> board.stream().map(ScoredPointResult::id).toList())
                .toList();
    }

    /**
     * 보드마다 종류별 결과(가까운 순)에서 앞 보드에 없는 것을 장당 개수만큼 앞에서부터 골라, 종류가 번갈아 오게 놓는다.
     *
     * @param resultsPerBoard 보드마다 종류별 검색 결과
     * @return 보드 순서대로 고른 콘텐츠. 결과가 모자란 종류가 있으면 그 보드는 장당 합보다 짧다
     */
    static List<List<ScoredPointResult>> pickDistinctBoards(List<Map<String, List<ScoredPointResult>>> resultsPerBoard,
                                                           Map<String, Integer> perBoard) {
        Set<Long> used = new HashSet<>();
        List<List<ScoredPointResult>> boards = new ArrayList<>(resultsPerBoard.size());
        for (Map<String, List<ScoredPointResult>> byType : resultsPerBoard) {
            List<List<ScoredPointResult>> parts = new ArrayList<>(perBoard.size());
            for (Map.Entry<String, Integer> entry : perBoard.entrySet()) {
                parts.add(byType.getOrDefault(entry.getKey(), List.of()).stream()
                        .filter(result -> !used.contains(result.id()))
                        .limit(entry.getValue())
                        .toList());
            }
            List<ScoredPointResult> board = interleave(parts);
            board.forEach(result -> used.add(result.id()));
            boards.add(board);
        }
        return boards;
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
