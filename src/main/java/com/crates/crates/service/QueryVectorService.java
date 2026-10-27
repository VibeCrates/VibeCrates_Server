package com.crates.crates.service;

import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.QdrantPointOperations;
import com.crates.crates.qdrant.ScoredPointResult;
import com.crates.crates.qdrant.VectorPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QueryVectorService {

    private final QdrantPointOperations pointOperations;

    @Value("${ai.vectorstore.qdrant.query-collection-name}")
    private String collectionName;

    public void ensureCollection(int vectorDimension) {
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    public void recreateCollection(int vectorDimension) {
        pointOperations.deleteCollection(collectionName);
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    public long countPoints() {
        return pointOperations.count(collectionName);
    }

    /** model_version이 주어진 값 중 하나인 point 수. */
    public long countPointsWithModelVersion(Collection<String> modelVersions) {
        return pointOperations.countMatching(collectionName, VectorPayload.MODEL_VERSION, List.copyOf(modelVersions));
    }

    // 보드 제목(한국어)·영어 원문·벡터, 그 벡터를 만든 모델 버전을 함께 저장한다. 버전은 쓸 때만 필요하다.
    public void upsertAll(List<QueryVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(record.queryId(), record.vector(), payloadOf(record)))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    private Map<String, Object> payloadOf(QueryVectorRecord record) {
        return Map.of(
                VectorPayload.QUERY, record.queryText(),
                VectorPayload.QUERY_EN, record.queryEn(),
                VectorPayload.MODEL_VERSION,
                VectorPayload.requireModelVersion(record.modelVersion(), "queryId " + record.queryId())
        );
    }

    /**
     * 기준 벡터와 가까운 query를 limit개, 벡터와 함께 가까운 순으로 돌려준다. 홈 추천 보드의 제목 후보로 쓴다.
     * 벡터는 후보끼리 얼마나 비슷한지 재는 데 쓴다(BoardTitleService.distinctTitlesNear).
     */
    public List<QueryMatch> findSimilarQueries(float[] targetVector, int limit) {
        return pointOperations.search(collectionName, targetVector, limit, true)
                .stream()
                .map(this::toMatch)
                .toList();
    }

    private QueryMatch toMatch(ScoredPointResult result) {
        return new QueryMatch(result.id(), (String) result.payload().get(VectorPayload.QUERY), result.score(), result.vector());
    }
}
