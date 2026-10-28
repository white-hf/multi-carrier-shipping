package com.enterprise.shipping.shared.identity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TimeOrderedIdsTest {
    @Test
    void canonicalTextIdsAreVersionSevenAndOrderedWithinOneGenerator() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            ids.add(TimeOrderedIds.next());
        }
        assertThat(ids).doesNotHaveDuplicates().isSorted();
        for (String id : ids) {
            UUID value = UUID.fromString(id);
            assertThat(value.version()).isEqualTo(7);
            assertThat(value.variant()).isEqualTo(2);
            assertThat(value.toString()).isEqualTo(id);
        }
    }

    @Test
    void concurrentCallersShareSafeGenerationWithoutDuplicateIds() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<String>>> jobs = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                jobs.add(
                        () -> {
                            List<String> ids = new ArrayList<>();
                            for (int i = 0; i < 1000; i++) {
                                ids.add(TimeOrderedIds.next());
                            }
                            return ids;
                        });
            }
            var unique = new HashSet<String>();
            for (var result : executor.invokeAll(jobs)) {
                unique.addAll(result.get(5, TimeUnit.SECONDS));
            }
            assertThat(unique).hasSize(8000);
        } finally {
            executor.shutdownNow();
        }
    }
}
