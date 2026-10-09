package com.crates.crates.initializer;

import com.crates.crates.DTO.ContentSourceKeyDto;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.seed.ContentVectorCsv;
import com.crates.crates.seed.ContentVectorFiles;
import com.crates.crates.service.ContentVectorRecord;
import com.crates.crates.service.ContentVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 서버가 만든 콘텐츠 벡터 CSV를 Qdrant content_vector 컬렉션에 적재한다.
 *
 * <p>CSV의 id는 원본 데이터셋의 id라서, V5~V7(시딩)이 content.source_key에 남긴 값으로 content.id를 찾아
 * point id로 쓴다. 이 짝이 RDB에 남아 있으므로 Qdrant가 비워져도(볼륨 없음) 재기동만 하면
 * CSV에서 다시 적재된다 — RDB를 다시 시딩할 필요가 없다.</p>
 *
 * <p><b>언제 적재하는가</b> — 매 기동마다 18만 건을 다시 넣지 않도록, 아래가 모두 맞으면 건너뛴다.</p>
 * <ul>
 *   <li>point 수 == content 수 — RDB를 다시 시딩하면 id가 바뀔 수 있는데, 행 수가 어긋나면 이를 잡는다</li>
 *   <li>그 point가 전부 CSV의 model_version을 달고 있다 — 새 모델의 CSV로 바꾸면 다시 적재된다</li>
 * </ul>
 * <p>다시 적재할 때는 컬렉션을 지우고 만든다. point id가 content.id라서, 남겨두면 RDB 재시딩 전의
 * point가 엉뚱한 콘텐츠에 매달린 채 남는다. 같은 행 수로 순서만 바뀐 재시딩은 위 조건으로 잡히지
 * 않으므로 그때는 {@code ai.vectorstore.qdrant.load.recreate=true}로 강제한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.vectorstore.qdrant.load.enabled", havingValue = "true")
public class ContentVectorLoader implements ApplicationRunner {

    private final ContentRepository contentRepository;
    private final ContentVectorService contentVectorService;
    private final ContentVectorFiles vectorFiles;

    @Value("${ai.server.embedding-dimension}")
    private int vectorDimension;

    @Value("${ai.vectorstore.qdrant.load.batch-size:256}")
    private int batchSize;

    /** true면 이미 적재돼 있어도 컬렉션을 지우고 다시 넣는다. */
    @Value("${ai.vectorstore.qdrant.load.recreate:false}")
    private boolean forceRecreate;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (batchSize <= 0) {
            throw new IllegalStateException("ai.vectorstore.qdrant.load.batch-size는 1 이상이어야 합니다: " + batchSize);
        }

        // Flyway 시딩이 끝난 뒤의 실제 content 수를 기준으로 판단한다.
        long contentCount = contentRepository.count();
        if (contentCount == 0) {
            log.warn("content 테이블이 비어 있어 Qdrant 콘텐츠 벡터 적재를 건너뜁니다.");
            return;
        }

        contentVectorService.ensureCollection(vectorDimension);
        Set<String> modelVersions = modelVersionsInFiles();

        if (!forceRecreate && isAlreadyLoaded(contentCount, modelVersions)) {
            // run-measure.sh가 "seed completed"로 적재 완료를 판단한다. 건너뛸 때도 같은 문구를 남긴다.
            log.info("Qdrant content vector seed completed (이미 적재됨): contentCount={}, modelVersions={}",
                    contentCount, modelVersions);
            return;
        }

        contentVectorService.recreateCollection(vectorDimension);

        long loaded = 0;
        for (String dtype : ContentVectorFiles.DTYPES) {
            loaded += load(dtype);
        }

        long points = contentVectorService.countPoints();
        if (points != contentCount) {
            // V5~V7(시딩)이 벡터 있는 콘텐츠만 넣으므로 둘은 같아야 한다. 다르면 시딩과 적재가 다른 파일을 본 것이다.
            log.warn("content 수와 point 수가 다릅니다. 벡터 없는 콘텐츠는 추천에 나오지 않습니다. "
                    + "contentCount={}, points={}", contentCount, points);
        }
        log.info("Qdrant content vector seed completed: contentCount={}, loaded={}, points={}, modelVersions={}",
                contentCount, loaded, points, modelVersions);
    }

    private boolean isAlreadyLoaded(long contentCount, Set<String> modelVersions) {
        long points = contentVectorService.countPoints();
        if (points != contentCount) {
            log.info("Qdrant point 수({})가 content 수({})와 달라 콘텐츠 벡터를 다시 적재합니다.", points, contentCount);
            return false;
        }
        long current = contentVectorService.countPointsWithModelVersion(modelVersions);
        if (current != points) {
            log.info("model_version이 {}가 아닌 point가 {}건 있어 콘텐츠 벡터를 다시 적재합니다.",
                    modelVersions, points - current);
            return false;
        }
        // 콘텐츠 종류(dtype)를 payload에 싣기 전에 적재된 point는 검색 보드가 종류로 거를 수 없다.
        long typed = contentVectorService.countPointsWithType(ContentVectorFiles.DTYPES);
        if (typed != points) {
            log.info("콘텐츠 종류(dtype)가 없는 point가 {}건 있어 콘텐츠 벡터를 다시 적재합니다.", points - typed);
            return false;
        }
        return true;
    }

    /** 각 파일 첫 행의 model_version. 파일 전체를 읽지 않고 적재 여부를 판단하기 위해서다. */
    private Set<String> modelVersionsInFiles() throws IOException {
        Set<String> versions = new LinkedHashSet<>();
        for (String dtype : ContentVectorFiles.DTYPES) {
            ContentVectorCsv.firstRow(vectorFiles.resourceFor(dtype))
                    .map(ContentVectorCsv.Row::modelVersion)
                    .ifPresent(versions::add);
        }
        return versions;
    }

    /** 한 도메인의 CSV를 batchSize 줄씩 읽어 적재하고 적재 건수를 돌려준다. */
    private long load(String dtype) throws IOException {
        LoadProgress progress = new LoadProgress();
        List<ContentVectorCsv.Row> batch = new ArrayList<>(batchSize);

        ContentVectorCsv.forEachRow(vectorFiles.resourceFor(dtype), row -> {
            batch.add(row);
            if (batch.size() == batchSize) {
                flush(dtype, batch, progress);
                batch.clear();
            }
        });
        flush(dtype, batch, progress);

        // book은 원본 CSV에 없는 벡터 id가 29건 있어 unmatched가 0이 아니어도 정상이다.
        log.info("[{}] 콘텐츠 벡터 적재: rows={}, loaded={}, unmatched={}",
                dtype, progress.rows, progress.loaded, progress.unmatched);
        return progress.loaded;
    }

    private void flush(String dtype, List<ContentVectorCsv.Row> rows, LoadProgress progress) {
        if (rows.isEmpty()) {
            return;
        }

        List<String> sourceKeys = rows.stream().map(ContentVectorCsv.Row::sourceKey).toList();
        Map<String, Long> contentIdBySourceKey = contentRepository.findIdsBySourceKeys(dtype, sourceKeys)
                .stream()
                .collect(Collectors.toMap(ContentSourceKeyDto::sourceKey, ContentSourceKeyDto::id));

        List<ContentVectorRecord> records = new ArrayList<>(rows.size());
        for (ContentVectorCsv.Row row : rows) {
            Long contentId = contentIdBySourceKey.get(row.sourceKey());
            if (contentId == null) {
                progress.unmatched++;
                continue;
            }
            records.add(new ContentVectorRecord(
                    contentId,
                    dtype,
                    ContentVectorCsv.decode(row.vectorBase64(), vectorDimension),
                    row.modelVersion()
            ));
        }

        contentVectorService.upsertAll(records);
        progress.rows += rows.size();
        progress.loaded += records.size();
    }

    /** 람다 안에서 누적하려고 둔 가변 카운터. */
    private static final class LoadProgress {
        long rows;
        long loaded;
        long unmatched;
    }
}
