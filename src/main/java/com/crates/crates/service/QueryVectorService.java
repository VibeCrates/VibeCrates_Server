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
import java.util.Optional;

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
     * 기준 벡터마다 가장 가까운 query를 한 번의 Qdrant 요청으로 찾는다. 결과는 입력 순서와 같고,
     * 찾지 못한 자리는 빈 Optional이다. 벡터마다 따로 검색한다.
     */
    public List<Optional<QueryMatch>> findMostSimilarQueries(List<float[]> targetVectors) {
        return pointOperations.searchBatch(collectionName, targetVectors, 1)
                .stream()
                .map(results -> results.stream().findFirst().map(this::toMatch))
                .toList();
    }

    private QueryMatch toMatch(ScoredPointResult result) {
        return new QueryMatch(result.id(), (String) result.payload().get(VectorPayload.QUERY), result.score());
    }
}
