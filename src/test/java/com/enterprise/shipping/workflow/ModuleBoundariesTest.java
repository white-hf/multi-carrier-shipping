package com.enterprise.shipping.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-level dependency rules keep deployment wiring as the only composition boundary. */
class ModuleBoundariesTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/shipping");

    @Test void sharedDoesNotImportAuthOrConcreteProviderImplementation() throws Exception {
        try (var files = Files.walk(ROOT.resolve("shared"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).doesNotContain("com.enterprise.shipping.auth.",
                        "com.enterprise.shipping.provider.sandbox.");
            }
        }
    }

    @Test void providerDoesNotImportBusinessModules() throws Exception {
        try (var files = Files.walk(ROOT.resolve("provider"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).doesNotContain("com.enterprise.shipping.quote.",
                        "com.enterprise.shipping.booking.", "com.enterprise.shipping.reconciliation.");
            }
        }
    }

    @Test void businessCoreDoesNotImportProviderImplementations() throws Exception {
        for (String module : new String[]{"quote", "booking", "reconciliation"}) {
            for (String layer : new String[]{"domain", "application", "port", "contract"}) {
                var directory = ROOT.resolve(module).resolve(layer);
                if (!Files.exists(directory)) continue;
                try (var files = Files.walk(directory)) {
                    for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        assertThat(Files.readString(file)).doesNotContain("com.enterprise.shipping.provider.");
                    }
                }
            }
        }
    }
}
