package com.crates.crates.initializer;

import com.crates.crates.seed.ContentVectorCsv;
import com.crates.crates.seed.QueryVectorCsv;
import com.crates.crates.service.QueryVectorRecord;
import com.crates.crates.service.QueryVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 개발자가 만든 query 벡터 CSV를 Qdrant query_vector 컬렉션에 적재한다.
 *
 * <p>query는 추천·검색 보드의 제목 후보다. 보드 콘텐츠의 평균 벡터와 가장 가까운 query가 제목이 된다
 * (BoardTitleService). 파일은 book·movie·music 셋으로 나뉘어 오지만 카테고리는 파일 이름에만 있고,
 * 모두 하나의 컬렉션에 넣는다.</p>
 *
 * <h2>무엇을 넣나</h2>
 * <ul>
 *   <li><b>제목은 한국어</b>({@code query_ko}) — payload {@code query}. 영어 원문은 {@code query_en}에 함께 둔다.
 *       벡터는 영어 원문으로 만든 것이다.</li>
 *   <li><b>같은 영어 문장은 한 번만</b> — 여러 카테고리에 같은 문장이 있다(2026-10-09 파일: 401,571행 중
 *       28,904행). 같은 문장은 벡터도 한국어 제목도 모두 같아서 합쳐도 검색 결과가 달라지지 않는다.
 *       먼저 나온 것(book → movie → music 순)을 남긴다.</li>
 *   <li><b>point id는 영어 문장의 해시</b>(SHA-256 앞 8바이트) — 파일 순서가 바뀌거나 행이 늘어도 같은 문장은
 *       같은 id라, 다시 적재해도 중복 point가 생기지 않는다.</li>
 * </ul>
 *
 * <h2>언제 넣나</h2>
 * <p>매 기동마다 37만 건을 다시 넣지 않도록 콘텐츠 벡터 적재(ContentVectorLoader)와 같은 규칙으로 건너뛴다:
 * point 수가 파일의 고유 문장 수와 같고, 그 point가 전부 파일의 model_version을 달고 있으면 이미 적재된 것이다.
 * 고유 문장 수는 파일을 한 번 훑어 센다(벡터는 디코딩하지 않는다). 다시 넣을 때는 컬렉션을 지우고 만든다.
 * Qdrant에 볼륨이 없어 컨테이너가 다시 만들어지면 비므로, 그때는 기동하면서 다시 채운다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.vectorstore.qdrant.query-load.enabled", havingValue = "true")
public class QueryVectorLoader implements ApplicationRunner {

    /** 적재 순서. 같은 문장이 여러 파일에 있으면 먼저 나온 것을 남긴다. */
    private static final List<String> CATEGORIES = List.of("book", "movie", "music");

    private final QueryVectorService queryVectorService;
    private final ResourceLoader resourceLoader;
    private final Map<String, String> locations;
    private final int vectorDimension;
    private final int batchSize;
    private final boolean forceRecreate;

    public QueryVectorLoader(QueryVectorService queryVectorService,
                             ResourceLoader resourceLoader,
                             @Value("${ai.vectorstore.qdrant.query-load.files.book}") String book,
                             @Value("${ai.vectorstore.qdrant.query-load.files.movie}") String movie,
                             @Value("${ai.vectorstore.qdrant.query-load.files.music}") String music,
                             @Value("${ai.server.embedding-dimension}") int vectorDimension,
                             @Value("${ai.vectorstore.qdrant.query-load.batch-size:256}") int batchSize,
                             @Value("${ai.vectorstore.qdrant.query-load.recreate:false}") boolean forceRecreate)
    {
        this.queryVectorService = queryVectorService;
        this.resourceLoader = resourceLoader;
        this.locations = new LinkedHashMap<>(Map.of("book", book, "movie", movie, "music", music));
        this.vectorDimension = vectorDimension;
        this.batchSize = batchSize;
        this.forceRecreate = forceRecreate;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException
    {
        if (batchSize <= 0)
        {
            throw new IllegalStateException("ai.vectorstore.qdrant.query-load.batch-size는 1 이상이어야 합니다: " + batchSize);
        }

        queryVectorService.ensureCollection(vectorDimension);

        Scan scan = scan();
        if (!forceRecreate && isAlreadyLoaded(scan))
        {
            // 부하 측정 스크립트(load-test/lib/common.sh)가 이 문구로 적재 완료를 판단한다. 건너뛸 때도 같은 문구를 남긴다.
            log.info("Qdrant query vector seed completed (이미 적재됨): points={}, modelVersions={}",
                    scan.uniqueQueries(), scan.modelVersions());
            return;
        }

        queryVectorService.recreateCollection(vectorDimension);
        Map<String, Long> loadedByCategory = load();

        long points = queryVectorService.countPoints();
        if (points != scan.uniqueQueries())
        {
            log.warn("query 벡터 point 수가 파일의 고유 문장 수와 다릅니다. expected={}, points={}",
                    scan.uniqueQueries(), points);
        }
        log.info("Qdrant query vector seed completed: rows={}, uniqueQueries={}, points={}, byCategory={}, modelVersions={}",
                scan.rows(), scan.uniqueQueries(), points, loadedByCategory, scan.modelVersions());
    }

    /** 파일의 행 수, 고유 영어 문장 수, model_version. 벡터는 디코딩하지 않는다. */
    private record Scan(long rows, long uniqueQueries, Set<String> modelVersions) {
    }

    private Scan scan() throws IOException
    {
        Set<String> queries = new HashSet<>();
        Set<String> versions = new LinkedHashSet<>();
        long[] rows = {0};
        for (String category : CATEGORIES)
        {
            QueryVectorCsv.forEachRow(resource(category), row -> {
                rows[0]++;
                // 적재(load)와 같은 기준으로 센다. 한국어 제목이 없는 행은 넣지 않으므로 기대 개수에서도 뺀다.
                if (!row.queryKo().isBlank())
                {
                    queries.add(row.query());
                    versions.add(row.modelVersion());
                }
            });
        }
        return new Scan(rows[0], queries.size(), versions);
    }

    private boolean isAlreadyLoaded(Scan scan)
    {
        long points = queryVectorService.countPoints();
        if (points != scan.uniqueQueries())
        {
            log.info("query 벡터 point 수({})가 파일의 고유 문장 수({})와 달라 다시 적재합니다.", points, scan.uniqueQueries());
            return false;
        }
        long current = queryVectorService.countPointsWithModelVersion(scan.modelVersions());
        if (current != points)
        {
            log.info("model_version이 {}가 아닌 query 벡터가 {}건 있어 다시 적재합니다.", scan.modelVersions(), points - current);
            return false;
        }
        return true;
    }

    /** 파일마다 batchSize개씩 적재하고 카테고리별 적재 건수를 돌려준다. */
    private Map<String, Long> load() throws IOException
    {
        Set<String> seen = new HashSet<>();
        Map<Long, String> queryById = new HashMap<>();
        Map<String, Long> loadedByCategory = new LinkedHashMap<>();

        for (String category : CATEGORIES)
        {
            List<QueryVectorRecord> batch = new ArrayList<>(batchSize);
            long[] counts = {0, 0, 0}; // 적재, 중복으로 건너뜀, 한국어 제목 없음
            QueryVectorCsv.forEachRow(resource(category), row -> {
                if (row.queryKo().isBlank())
                {
                    // 화면에 보여줄 제목이 없다. 파일을 고쳐 다시 받아야 한다(2026-10-09 파일은 35행을 번역해 채웠다).
                    counts[2]++;
                    return;
                }
                if (!seen.add(row.query()))
                {
                    counts[1]++;
                    return;
                }

                long id = idOf(row.query());
                String previous = queryById.putIfAbsent(id, row.query());
                if (previous != null)
                {
                    throw new IllegalStateException("query id가 겹칩니다(해시 충돌). id=" + id
                            + ", a=" + previous + ", b=" + row.query());
                }

                batch.add(new QueryVectorRecord(id, row.queryKo(), row.query(),
                        ContentVectorCsv.decode(row.vectorBase64(), vectorDimension), row.modelVersion()));
                if (batch.size() == batchSize)
                {
                    queryVectorService.upsertAll(batch);
                    batch.clear();
                }
                counts[0]++;
            });
            queryVectorService.upsertAll(batch);

            log.info("[{}] query 벡터 적재: loaded={}, 중복 문장 건너뜀={}, 한국어 제목 없음={}",
                    category, counts[0], counts[1], counts[2]);
            loadedByCategory.put(category, counts[0]);
        }
        return loadedByCategory;
    }

    private Resource resource(String category)
    {
        String location = locations.get(category);
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists())
        {
            throw new IllegalStateException("query 벡터 파일을 찾을 수 없습니다: " + location);
        }
        return resource;
    }

    /** 영어 문장의 SHA-256 앞 8바이트를 양수 long으로. Qdrant point id는 부호 없는 64비트라 음수를 피한다. */
    public static long idOf(String query)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
