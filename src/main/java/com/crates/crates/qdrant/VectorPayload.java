package com.crates.crates.qdrant;

/**
 * Qdrant point payload의 키와 공통 검증.
 *
 * <p>model_version은 벡터를 <b>쓸 때만</b> 싣는다(추가·수정). 조회와 유사도 계산은 버전을 보지 않는다.
 * 나중에 AI 서버가 벡터를 고쳐 보낼 때 어느 point가 어떤 모델로 만들어졌는지 가를 근거로 남겨두는 것이다.</p>
 */
public final class VectorPayload {

    /** content_vector: point id와 같은 content.id. payload 필터에 쓰려고 함께 싣는다. */
    public static final String CONTENT_ID = "content_id";

    /** query_vector: 보드 제목(한국어). 화면에 그대로 보여준다. */
    public static final String QUERY = "query";

    /** query_vector: 영어 원문. 벡터는 이 문장으로 만들었다. */
    public static final String QUERY_EN = "query_en";

    /** 벡터를 만든 AI 모델 체크포인트 (예: {@code trained_model.pt@1786697109}). */
    public static final String MODEL_VERSION = "model_version";

    private VectorPayload() {
    }

    /** 버전 없이 벡터를 쓰지 못하게 막는다. Map.of가 null에서 NPE로 멈추기 전에 이유를 남긴다. */
    public static String requireModelVersion(String modelVersion, String target) {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("model_version 없이 벡터를 저장할 수 없습니다: " + target);
        }
        return modelVersion;
    }
}
