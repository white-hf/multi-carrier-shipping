package com.enterprise.shipping.shipping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level architectural fitness functions enforcing strict modular monolith boundaries
 * and hexagonal intra-module layering for future microservice extraction.
 */
class ModuleBoundariesTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/shipping");
    private static final List<String> BUSINESS_MODULES = List.of("auth", "quote", "booking", "reconciliation");

    @Test
    void sharedDoesNotImportBusinessModulesOrConcreteProviders() throws Exception {
        try (var files = Files.walk(ROOT.resolve("shared"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file.toString()).doesNotContain(
                        "com.enterprise.shipping.auth.",
                        "com.enterprise.shipping.quote.",
                        "com.enterprise.shipping.booking.",
                        "com.enterprise.shipping.reconciliation.",
                        "com.enterprise.shipping.provider.",
                        "com.enterprise.shipping.runtime."
                );
            }
        }
    }

    @Test
    void providerDoesNotImportBusinessModulesOrWebApiLayer() throws Exception {
        try (var files = Files.walk(ROOT.resolve("provider"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file.toString()).doesNotContain(
                        "com.enterprise.shipping.auth.",
                        "com.enterprise.shipping.quote.",
                        "com.enterprise.shipping.booking.",
                        "com.enterprise.shipping.reconciliation.",
                        "com.enterprise.shipping.shared.api."
                );
            }
        }
    }

    @Test
    void businessCoreDoesNotImportProviderImplementationsOrWebApiLayer() throws Exception {
        for (String module : BUSINESS_MODULES) {
            for (String layer : List.of("domain", "application", "port", "contract")) {
                var directory = ROOT.resolve(module).resolve(layer);
                if (!Files.exists(directory)) continue;
                try (var files = Files.walk(directory)) {
                    for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        assertThat(source).as(file.toString()).doesNotContain(
                                "com.enterprise.shipping.provider.",
                                "com.enterprise.shipping.shared.api.",
                                "org.springframework.http.HttpStatus"
                        );
                    }
                }
            }
        }
    }

    @Test
    void businessModulesCommunicateExclusivelyThroughPublishedContractPackages() throws Exception {
        for (String consumer : BUSINESS_MODULES) {
            try (var files = Files.walk(ROOT.resolve(consumer))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String peer : BUSINESS_MODULES) {
                        if (peer.equals(consumer)) continue;
                        for (String internalLayer : List.of("api", "application", "domain", "port", "infrastructure", "config")) {
                            assertThat(source).as("%s must not import %s.%s", file, peer, internalLayer)
                                    .doesNotContain("com.enterprise.shipping." + peer + "." + internalLayer + ".");
                        }
                    }
                }
            }
        }
    }

    @Test
    void domainAndPortLayersNeverDependUpwardOnApplicationApiOrInfrastructure() throws Exception {
        for (String module : BUSINESS_MODULES) {
            for (String innerLayer : List.of("domain", "port", "contract")) {
                var directory = ROOT.resolve(module).resolve(innerLayer);
                if (!Files.exists(directory)) continue;
                try (var files = Files.walk(directory)) {
                    for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        assertThat(source).as(file.toString()).doesNotContain(
                                "com.enterprise.shipping." + module + ".application.",
                                "com.enterprise.shipping." + module + ".api.",
                                "com.enterprise.shipping." + module + ".infrastructure.",
                                "com.enterprise.shipping." + module + ".config."
                        );
                    }
                }
            }
        }
    }
}
