package com.crates.crates.repository;

import com.crates.crates.DTO.ContentKeyDto;
import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.DTO.ContentSourceKeyDto;
import com.crates.crates.entity.contents.Content;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {

    // 대량의 content ID를 메모리에 한 번에 올리지 않도록 마지막 ID 이후의 데이터만 배치 조회한다.
    @Query("SELECT c.id FROM Content c WHERE c.id > :afterId ORDER BY c.id")
    List<Long> findContentIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    /**
     * 원본 id(source_key)로 content.id를 찾는다. 벡터 CSV 적재에 쓴다.
     * (dtype, source_key) 유니크 인덱스(V1)를 탄다. 없는 키는 결과에서 빠진다.
     */
    @Query("SELECT new com.crates.crates.DTO.ContentSourceKeyDto(c.id, c.sourceKey) " +
            "FROM Content c WHERE c.dtype = :dtype AND c.sourceKey IN :sourceKeys")
    List<ContentSourceKeyDto> findIdsBySourceKeys(@Param("dtype") String dtype,
                                                  @Param("sourceKeys") Collection<String> sourceKeys);

    /** 사용자 행동 로그에 적을 콘텐츠 종류와 원본 id(docs/board-schema.md 13-2). 없는 id는 결과에서 빠진다. */
    @Query("SELECT new com.crates.crates.DTO.ContentKeyDto(c.id, c.dtype, c.sourceKey) " +
            "FROM Content c WHERE c.id IN :ids")
    List<ContentKeyDto> findKeysByIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id IN :ids")
    List<ContentQueryDto> findContentsByIds(@Param("ids") List<Long> ids);

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id = :id")
    Optional<ContentQueryDto> findContentSummaryById(@Param("id") Long id);

    /**
     * 제목으로 콘텐츠를 검색해 id만 유사도 순으로 돌려준다.
     *
     * <p>id만 뽑는 이유: 요약 DTO 조립(이미지 URL 생성, 순서 보존)은 이미
     * {@code ContentService.getContentSummaries}가 하고 있어 그대로 재사용한다.
     * 네이티브 쿼리로 엔티티를 프로젝션하면 releaseYear의 AttributeConverter가 적용되지 않는다.</p>
     *
     * <p>정렬 기준은 세 단계다.</p>
     * <ol>
     *   <li>제목이 검색어로 <b>시작</b>하는 것 우선 — "harry potter"에 "We Love Harry Potter!"보다
     *       "Harry Potter Collection"이 먼저 나와야 자동완성으로 쓸 만하다.</li>
     *   <li>word_similarity — 검색어가 제목의 <b>일부</b>와 얼마나 맞는지. similarity()는 제목 전체와
     *       비교해서, 긴 제목이 검색어를 포함해도 점수가 낮게 나온다.</li>
     *   <li>짧은 제목 우선, 마지막으로 id — 동점일 때 순서를 확정해 페이지 경계를 안정시킨다.</li>
     * </ol>
     *
     * <p>ILIKE '%...%'는 gin_trgm_ops 인덱스를 탄다. 다만 트라이그램이 3글자 단위라
     * 검색어가 3글자 미만이면 Seq Scan으로 떨어진다 — 호출하는 쪽에서 막아야 한다.</p>
     */
    @Query(value = """
            SELECT c.id
              FROM content c
             WHERE c.title ILIKE '%' || :keyword || '%'
             ORDER BY (c.title ILIKE :keyword || '%') DESC,
                      word_similarity(:keyword, c.title) DESC,
                      length(c.title),
                      c.id
             LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Long> searchContentIdsByTitle(@Param("keyword") String keyword,
                                       @Param("size") int size,
                                       @Param("offset") int offset);
}
