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
    private static final List<String> BUSINESS_MODULES =
            List.of("auth", "quote", "booking", "reconciliation");

    @Test
    void sharedDoesNotImportBusinessModulesOrConcreteProviders() throws Exception {
        try (var files = Files.walk(ROOT.resolve("shared"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source)
                        .as(file.toString())
                        .doesNotContain(
                                "com.enterprise.shipping.auth.",
                                "com.enterprise.shipping.quote.",
                                "com.enterprise.shipping.booking.",
                                "com.enterprise.shipping.reconciliation.",
                                "com.enterprise.shipping.provider.",
                                "com.enterprise.shipping.runtime.");
            }
        }
    }

    @Test
    void providerDoesNotImportBusinessModulesOrWebApiLayer() throws Exception {
        try (var files = Files.walk(ROOT.resolve("provider"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertThat(source)
                        .as(file.toString())
                        .doesNotContain(
                                "com.enterprise.shipping.auth.",
                                "com.enterprise.shipping.quote.",
                                "com.enterprise.shipping.booking.",
                                "com.enterprise.shipping.reconciliation.",
                                "com.enterprise.shipping.shared.api.");
            }
        }
    }

    @Test
    void businessCoreDoesNotImportProviderImplementationsOrWebApiLayer() throws Exception {
        for (String module : BUSINESS_MODULES) {
            for (String layer : List.of("domain", "application", "port", "contract")) {
                var directory = ROOT.resolve(module).resolve(layer);
                if (!Files.exists(directory)) {
                    continue;
                }
                try (var files = Files.walk(directory)) {
                    for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        assertThat(source)
                                .as(file.toString())
                                .doesNotContain(
                                        "com.enterprise.shipping.provider.",
                                        "com.enterprise.shipping.shared.api.",
                                        "org.springframework.http.HttpStatus");
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
                        if (peer.equals(consumer)) {
                            continue;
                        }
                        for (String internalLayer :
                                List.of(
                                        "api",
                                        "application",
                                        "domain",
                                        "port",
                                        "infrastructure",
                                        "config")) {
                            assertThat(source)
                                    .as("%s must not import %s.%s", file, peer, internalLayer)
                                    .doesNotContain(
                                            "com.enterprise.shipping."
                                                    + peer
                                                    + "."
                                                    + internalLayer
                                                    + ".");
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
                if (!Files.exists(directory)) {
                    continue;
                }
                try (var files = Files.walk(directory)) {
                    for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        assertThat(source)
                                .as(file.toString())
                                .doesNotContain(
                                        "com.enterprise.shipping." + module + ".application.",
                                        "com.enterprise.shipping." + module + ".api.",
                                        "com.enterprise.shipping." + module + ".infrastructure.",
                                        "com.enterprise.shipping." + module + ".config.");
                    }
                }
            }
        }
    }

    @Test
    void businessResultsAndSharedErrorsNeverContainHttpResponseMetadata() throws Exception {
        var directories = new java.util.ArrayList<Path>();
        directories.add(ROOT.resolve("shared/domain"));
        for (String module : BUSINESS_MODULES) {
            for (String layer : List.of("domain", "application", "port", "contract")) {
                directories.add(ROOT.resolve(module).resolve(layer));
            }
        }
        for (var directory : directories) {
            if (!Files.exists(directory)) {
                continue;
            }
            try (var files = Files.walk(directory)) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(file))
                            .as(file.toString())
                            .doesNotContain(
                                    "org.springframework.http.",
                                    "jakarta.servlet.",
                                    "WebClient",
                                    "ResponseEntity",
                                    "statusCode",
                                    "statusUrl",
                                    "HTTP_",
                                    "/api/",
                                    "ShippingException.badRequest(",
                                    "ShippingException.badGateway(");
                }
            }
        }
    }

    @Test
    void providerCoreAndApiSlicesRemainMutuallyIndependent() throws Exception {
        Path coreDir = ROOT.resolve("provider/core");
        assertThat(Files.exists(coreDir)).isTrue();
        try (var files = Files.walk(coreDir)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file))
                        .as("%s in provider.core must remain vendor-neutral", file)
                        .doesNotContain("com.enterprise.shipping.provider.sandbox.");
            }
        }

        List<String> slices = List.of("rates", "shipments", "labels");
        String providerConfig =
                Files.readString(ROOT.resolve("provider/sandbox/SandboxProviderConfig.java"));
        for (String slice : slices) {
            assertThat(providerConfig)
                    .as("SandboxProviderConfig must not centralize or import API slice %s", slice)
                    .doesNotContain("com.enterprise.shipping.provider.sandbox." + slice + ".");
            Path sliceDir = ROOT.resolve("provider/sandbox").resolve(slice);
            assertThat(Files.exists(sliceDir)).isTrue();
            try (var files = Files.walk(sliceDir)) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String peer : slices) {
                        if (peer.equals(slice)) {
                            continue;
                        }
                        assertThat(source)
                                .as("%s must not import peer provider slice %s", file, peer)
                                .doesNotContain(
                                        "com.enterprise.shipping.provider.sandbox."
                                                + peer
                                                + ".");
                    }
                }
            }
        }
    }
}
