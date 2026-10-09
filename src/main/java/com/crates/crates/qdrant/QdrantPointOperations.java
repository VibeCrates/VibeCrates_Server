package com.crates.crates.qdrant;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Component
@RequiredArgsConstructor
public class QdrantPointOperations {

    /**
     * 한 번의 retrieve로 가져올 point 수.
     *
     * <p>gRPC 클라이언트의 기본 수신 한도가 4MB(4,194,304 byte)다. 768차원 float32 벡터는
     * 전송 시 point당 약 2.8KB라, 1,400개쯤에서 이 한도를 넘는다. 실측(2026-09-14):</p>
     *
     * <pre>
     *   1,200개 -> OK
     *   1,360개 -> CANCELLED: Failed to read message
     *   1,488개 -> RESOURCE_EXHAUSTED: gRPC message exceeds maximum size 4194304: 4232685
     * </pre>
     *
     * <p>이 상한이 실제로 사용자를 막았다. 취향 벡터 재계산은 좋아요한 보드 전체의 콘텐츠
     * 벡터를 가져오는데, 보드 하나당 콘텐츠가 8건이므로 <b>좋아요 170건을 넘긴 사용자부터
     * 재계산이 통째로 실패</b>했다. 예외는 리스너의 catch에 걸려 로그로만 남고 응답은 200이라
     * 겉으로는 멀쩡해 보인다. 좋아요를 많이 누른 사용자일수록 먼저 망가지는 종류의 결함이다.</p>
     *
     * <p>1,000으로 잡은 이유: 768차원 기준 약 2.8MB라 여유가 30% 남는다. 차원이 커지면
     * point당 크기도 비례해 커지므로(1,536차원이면 두 배) 이 값을 함께 낮춰야 한다.
     * 클라이언트의 maxInboundMessageSize를 올리는 방법도 있지만, 상한을 뒤로 미룰 뿐이고
     * 사용자가 좋아요를 더 모으면 같은 자리에서 다시 터진다.</p>
     */
    private static final int RETRIEVE_CHUNK_SIZE = 1000;

    private final QdrantClient qdrantClient;

    /**
     * 컬렉션이 없으면 지정한 차원과 Cosine 거리 방식으로 생성하고,
     * 이미 존재하면 현재 벡터 설정이 기대한 차원과 일치하는지 검증한다.
     */
    public void ensureCollection(String collectionName, int vectorDimension) {
        if (!await(qdrantClient.collectionExistsAsync(collectionName))) {
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setSize(vectorDimension)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            await(qdrantClient.createCollectionAsync(collectionName, vectorParams));
        }

        Collections.CollectionInfo collectionInfo = await(qdrantClient.getCollectionInfoAsync(collectionName));
        Collections.VectorsConfig vectorsConfig = collectionInfo.getConfig()
                .getParams()
                .getVectorsConfig();

        if (vectorsConfig.getConfigCase() != Collections.VectorsConfig.ConfigCase.PARAMS) {
            throw new IllegalStateException("Qdrant collection must use a single unnamed vector: " + collectionName);
        }

        long actualDimension = vectorsConfig.getParams().getSize();
        if (actualDimension != vectorDimension) {
            throw new IllegalStateException(
                    "Qdrant vector dimension mismatch. collection=" + collectionName
                            + ", expected=" + vectorDimension
                            + ", actual=" + actualDimension
            );
        }
    }

    /**
     * payload 필드에 keyword 인덱스를 만든다. 이미 있으면 Qdrant가 그대로 둔다.
     *
     * <p>인덱스 없이 payload로 거르면 Qdrant가 후보마다 payload를 읽어 확인해야 한다. 검색 보드는 요청마다
     * 콘텐츠 종류로 걸러 검색하므로 인덱스를 둔다.</p>
     */
    public void ensureKeywordIndex(String collectionName, String field) {
        await(qdrantClient.createPayloadIndexAsync(
                collectionName, field, Collections.PayloadSchemaType.Keyword, null, true, null, null));
    }

    /** 컬렉션이 있으면 통째로 지운다. 없으면 아무 일도 하지 않는다. */
    public void deleteCollection(String collectionName) {
        if (await(qdrantClient.collectionExistsAsync(collectionName))) {
            await(qdrantClient.deleteCollectionAsync(collectionName));
        }
    }

    /** 컬렉션의 정확한 point 수. */
    public long count(String collectionName) {
        return await(qdrantClient.countAsync(collectionName, null, true));
    }

    /**
     * payload의 keyword 필드가 values 중 하나인 point 수.
     *
     * <p>payload 인덱스가 없으면 전체를 훑는다. 기동 시 한 번 부르는 용도라 괜찮지만,
     * 요청 경로에서 부르게 되면 그 필드에 keyword 인덱스를 먼저 만들 것.</p>
     */
    public long countMatching(String collectionName, String key, List<String> values) {
        Points.Filter filter = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeywords(key, values))
                .build();
        return await(qdrantClient.countAsync(collectionName, filter, true));
    }

    /**
     * point id로 벡터를 가져온다.
     *
     * <p>사용자 취향 벡터를 다시 계산할 때 좋아요한 보드 전부의 콘텐츠 벡터가 필요하다.
     * 보드마다 조회하면 왕복이 보드 수만큼 늘어나므로 id를 모아서 받되,
     * gRPC 수신 한도를 넘지 않도록 {@link #RETRIEVE_CHUNK_SIZE}개씩 나눠 요청한다.
     * 왕복 횟수는 보드 수가 아니라 콘텐츠 수에 비례하며, 1,000개마다 한 번이다.</p>
     *
     * <p>없는 id는 결과에 담기지 않는다. Qdrant에 아직 벡터가 없는 콘텐츠가 섞여 있을 수 있어
     * 호출하는 쪽이 빠진 id를 감안해야 한다.</p>
     */
    public Map<Long, float[]> retrieveVectors(String collectionName, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<Points.PointId> pointIds = ids.stream()
                .distinct()
                .map(PointIdFactory::id)
                .toList();

        Map<Long, float[]> vectorById = new HashMap<>(pointIds.size());
        for (int from = 0; from < pointIds.size(); from += RETRIEVE_CHUNK_SIZE) {
            int to = Math.min(from + RETRIEVE_CHUNK_SIZE, pointIds.size());
            collectVectors(collectionName, pointIds.subList(from, to), vectorById);
        }
        return vectorById;
    }

    /** 한 번의 retrieve 결과를 누적 맵에 담는다. id가 중복되지 않으므로 덮어쓸 일이 없다. */
    private void collectVectors(String collectionName,
                                List<Points.PointId> chunk,
                                Map<Long, float[]> into) {
        List<Points.RetrievedPoint> points = await(
                qdrantClient.retrieveAsync(collectionName, chunk, false, true, null)
        );

        for (Points.RetrievedPoint point : points) {
            Points.PointId pointId = point.getId();
            if (pointId.getPointIdOptionsCase() != Points.PointId.PointIdOptionsCase.NUM) {
                throw new IllegalStateException("Qdrant point id is not numeric.");
            }
            into.put(pointId.getNum(), toFloatArray(point.getVectors().getVector()));
        }
    }

    /**
     * 조회 응답의 dense 벡터를 float 배열로 꺼낸다.
     *
     * <p>두 자리를 모두 본다. Qdrant는 조회 응답에서 dense 벡터를 {@code VectorOutput.dense}
     * 하위 메시지에 담지만, 예전 서버는 최상위 {@code data}에 직접 담았고 그 필드도 아직 남아 있다.
     * 어느 쪽이 채워질지는 서버 버전이 정하므로 호출부가 신경 쓰지 않도록 여기서 흡수한다.</p>
     *
     * <p>둘 다 비어 있으면 빈 배열이 나간다. 이때 예외를 던지지 않는 이유는 조회 대상 중 일부만
     * 비어 있는 경우와 구분할 수 없어서다. 대신 쓰는 쪽(UserVectorService)이 기대 차원과 비교해
     * 어긋나면 오류로 남긴다.</p>
     */
    private float[] toFloatArray(Points.VectorOutput vector) {
        List<Float> data = vector.hasDense() ? vector.getDense().getDataList() : vector.getDataList();

        float[] result = new float[data.size()];
        for (int index = 0; index < data.size(); index++) {
            result[index] = data.get(index);
        }
        return result;
    }

    // 단일 벡터를 Qdrant point 형식으로 변환해 저장한다.
    public void upsertPoint(String collectionName, long id, float[] vector, Map<String, Object> payload) {
        upsertPoints(collectionName, List.of(new PointRecord(id, vector, payload)));
    }

    // 네트워크 호출 횟수를 줄이기 위해 여러 Qdrant point를 한 요청으로 저장한다.
    public void upsertPoints(String collectionName, List<PointRecord> points) {
        if (points.isEmpty()) {
            return;
        }

        List<Points.PointStruct> pointStructs = points.stream()
                .map(this::toPointStruct)
                .toList();

        await(qdrantClient.upsertAsync(collectionName, pointStructs));
    }

    public List<ScoredPointResult> search(String collectionName, float[] queryVector, int topK) {
        return search(collectionName, queryVector, topK, false);
    }

    /**
     * withVectors가 true면 결과에 point의 벡터도 담는다.
     *
     * <p>보드 제목을 정하려면 고른 콘텐츠의 평균 벡터가 필요하다. 검색과 따로 retrieve하면 왕복이
     * 한 번 늘어나므로 검색 응답에 함께 싣는다. topK가 수십 건이라 gRPC 수신 한도와는 거리가 멀다.</p>
     */
    public List<ScoredPointResult> search(String collectionName, float[] queryVector, int topK, boolean withVectors) {
        Points.SearchPoints request = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(toFloatList(queryVector))
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setWithVectors(WithVectorsSelectorFactory.enable(withVectors))
                .build();

        return await(qdrantClient.searchAsync(request))
                .stream()
                .map(this::toResult)
                .toList();
    }

    /**
     * 기준 벡터 여러 개를 한 번의 요청으로 검색한다. 결과는 입력 순서대로, 기준 벡터마다 따로 돌아온다.
     *
     * <p>추천 보드 4개의 제목을 정할 때 쓴다. 보드마다 자기 평균 벡터로 검색되므로 따로 4번 보낸 것과 결과가 같고,
     * Qdrant 왕복만 4번에서 1번으로 준다.</p>
     */
    public List<List<ScoredPointResult>> searchBatch(String collectionName, List<float[]> queryVectors, int topK) {
        if (queryVectors.isEmpty()) {
            return List.of();
        }

        List<Points.SearchPoints> requests = queryVectors.stream()
                .map(vector -> Points.SearchPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .addAllVector(toFloatList(vector))
                        .setLimit(topK)
                        .setWithPayload(WithPayloadSelectorFactory.enable(true))
                        .build())
                .toList();

        return await(qdrantClient.searchBatchAsync(collectionName, requests, null))
                .stream()
                .map(batch -> batch.getResultList().stream().map(this::toResult).toList())
                .toList();
    }

    /**
     * 같은 기준 벡터로, payload 필드 값마다 정해진 개수를 따로 검색한다. 한 번의 요청(묶음 검색)으로 보낸다.
     *
     * <p>결과는 값마다 따로 돌아온다(입력 Map의 순서). 벡터도 함께 받는다 — 보드 제목을 정할 때 평균을 낸다.</p>
     *
     * @param limitByValue 필드 값 → 그 값에서 뽑을 개수 (예: BOOK 4, MUSIC 2, MOVIE 2)
     */
    public Map<String, List<ScoredPointResult>> searchEachValue(String collectionName, float[] queryVector,
                                                                String field, Map<String, Integer> limitByValue) {
        List<String> values = List.copyOf(limitByValue.keySet());
        List<Points.SearchPoints> requests = values.stream()
                .map(value -> Points.SearchPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .addAllVector(toFloatList(queryVector))
                        .setFilter(Points.Filter.newBuilder().addMust(ConditionFactory.matchKeyword(field, value)).build())
                        .setLimit(limitByValue.get(value))
                        .setWithPayload(WithPayloadSelectorFactory.enable(true))
                        .setWithVectors(WithVectorsSelectorFactory.enable(true))
                        .build())
                .toList();

        List<Points.BatchResult> results = await(qdrantClient.searchBatchAsync(collectionName, requests, null));
        Map<String, List<ScoredPointResult>> byValue = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            byValue.put(values.get(i), results.get(i).getResultList().stream().map(this::toResult).toList());
        }
        return byValue;
    }

    private Points.PointStruct toPointStruct(PointRecord point) {
        return Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(point.id()))
                .setVectors(VectorsFactory.vectors(point.vector()))
                .putAllPayload(toQdrantPayload(point.payload()))
                .build();
    }

    private ScoredPointResult toResult(Points.ScoredPoint point) {
        return new ScoredPointResult(
                extractNumericId(point),
                point.getScore(),
                toJavaPayload(point.getPayloadMap()),
                toFloatArray(point.getVectors().getVector())
        );
    }

    private long extractNumericId(Points.ScoredPoint point) {
        Points.PointId pointId = point.getId();
        if (pointId.getPointIdOptionsCase() != Points.PointId.PointIdOptionsCase.NUM) {
            throw new IllegalStateException("Qdrant point id is not numeric.");
        }
        return pointId.getNum();
    }

    private Map<String, JsonWithInt.Value> toQdrantPayload(Map<String, Object> payload) {
        Map<String, JsonWithInt.Value> result = new HashMap<>();
        payload.forEach((key, value) -> result.put(key, toQdrantValue(value)));
        return result;
    }

    private JsonWithInt.Value toQdrantValue(Object value) {
        if (value == null) {
            return ValueFactory.nullValue();
        }
        if (value instanceof String stringValue) {
            return ValueFactory.value(stringValue);
        }
        if (value instanceof Integer integerValue) {
            return ValueFactory.value(integerValue.longValue());
        }
        if (value instanceof Long longValue) {
            return ValueFactory.value(longValue);
        }
        if (value instanceof Float floatValue) {
            return ValueFactory.value(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            return ValueFactory.value(doubleValue);
        }
        if (value instanceof Boolean booleanValue) {
            return ValueFactory.value(booleanValue);
        }
        throw new IllegalArgumentException("Unsupported Qdrant payload value type: " + value.getClass().getName());
    }

    private Map<String, Object> toJavaPayload(Map<String, JsonWithInt.Value> payload) {
        Map<String, Object> result = new HashMap<>();
        payload.forEach((key, value) -> result.put(key, toJavaValue(value)));
        return result;
    }

    private Object toJavaValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE, KIND_NOT_SET -> null;
            case STRUCT_VALUE, LIST_VALUE -> value.toString();
        };
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> result = new java.util.ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(value);
        }
        return result;
    }

    private <T> T await(com.google.common.util.concurrent.ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant operation interrupted.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant operation failed.", e.getCause());
        }
    }
}
