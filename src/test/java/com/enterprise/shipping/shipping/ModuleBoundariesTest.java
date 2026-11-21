package com.enterprise.shipping.shipping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level boundaries: feature layers, independent provider API slices, table ownership. */
class ModuleBoundariesTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/shipping");

    @Test
    void sharedDoesNotImportBusinessFeaturesOrProviderImplementations() throws Exception {
        forbid(ROOT.resolve("shared"), List.of(
                "com.enterprise.shipping.auth.",
                "com.enterprise.shipping.shipping.",
                "com.enterprise.shipping.reconciliation.",
                "com.enterprise.shipping.provider."));
    }

    @Test
    void providerNeverImportsBusinessModules() throws Exception {
        forbid(ROOT.resolve("provider"), List.of(
                "com.enterprise.shipping.shipping.",
                "com.enterprise.shipping.reconciliation.",
                "com.enterprise.shipping.auth.",
                "com.enterprise.shipping.shared.api."));
    }

    @Test
    void businessCoreAndApiNeverImportProviderImplementations() throws Exception {
        for (String layer : List.of("api", "application", "model", "port", "contract")) {
            Path directory = ROOT.resolve("shipping").resolve(layer);
            assertThat(Files.isDirectory(directory)).isTrue();
            forbid(directory, List.of("com.enterprise.shipping.provider."));
        }
    }

    @Test
    void onlyCompositionAndAdaptersSeeBothBusinessAndProvider() throws Exception {
        assertThat(Files.readString(ROOT.resolve("shipping/config/ShippingConfiguration.java")))
                .contains("com.enterprise.shipping.provider.");
        assertThat(Files.readString(ROOT.resolve(
                        "shipping/infrastructure/provider/SandboxShipmentAdapter.java")))
                .contains("com.enterprise.shipping.provider.");
    }

    @Test
    void providerApiSlicesRemainMutuallyIndependent() throws Exception {
        List<String> slices = List.of("rate", "shipment", "label");
        for (String slice : slices) {
            Path directory = ROOT.resolve("provider/sandbox").resolve(slice);
            assertThat(Files.isDirectory(directory)).isTrue();
            try (var files = Files.walk(directory)) {
                for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String peer : slices) {
                        if (peer.equals(slice)) {
                            continue;
                        }
                        assertThat(source)
                                .as("%s must not import peer provider slice %s", file, peer)
                                .doesNotContain(
                                        "com.enterprise.shipping.provider.sandbox." + peer + ".");
                    }
                }
            }
        }
    }

    @Test
    void sharedTransportKnowsNoProviderOrBusinessSlice() throws Exception {
        forbid(ROOT.resolve("shared/infrastructure/transport"), List.of(
                "com.enterprise.shipping.provider.",
                "com.enterprise.shipping.shipping.",
                "com.enterprise.shipping.reconciliation."));
    }

    @Test
    void modelNeverDependsOnApplicationPersistenceOrTransport() throws Exception {
        forbid(ROOT.resolve("shipping/model"), List.of(
                "org.springframework.",
                "jakarta.servlet.",
                "java.sql.",
                "javax.sql.",
                "reactor.",
                "com.enterprise.shipping.provider.",
                "com.enterprise.shipping.shipping.application.",
                "com.enterprise.shipping.shipping.persistence."));
    }

    @Test
    void controllerDoesNotReachRepositoriesOrProviderDirectly() throws Exception {
        assertThat(Files.readString(ROOT.resolve("shipping/api/ShippingController.java")))
                .doesNotContain(
                        "BookingRepository",
                        "ReviewTaskRepository",
                        "com.enterprise.shipping.provider.",
                        "RateProvider",
                        "ShipmentProvider",
                        "LabelProvider");
    }

    @Test
    void reconciliationUsesOnlyThePublishedShippingContract() throws Exception {
        try (var files = Files.walk(ROOT.resolve("reconciliation"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file)
                        .replace("com.enterprise.shipping.shipping.contract.", "");
                assertThat(source)
                        .as("%s must import only shipping.contract", file)
                        .doesNotContain("com.enterprise.shipping.shipping.");
            }
        }
    }

    @Test
    void tableOwnershipRemainsExplicit() throws Exception {
        assertThat(Files.readString(ROOT.resolve("shipping/persistence/BookingRepository.java")))
                .doesNotContain("booking_reconciliation_tasks");
        assertThat(Files.readString(ROOT.resolve("reconciliation/ReviewTaskRepository.java")))
                .doesNotContain("INSERT INTO shipment_bookings", "FROM shipment_bookings");
        forbid(ROOT.resolve("auth"), List.of(
                "shipment_bookings", "booking_reconciliation_tasks"));
    }

    @Test
    void retiredBusinessModulesHaveNoProductionSources() throws Exception {
        for (String retired : List.of("quote", "booking")) {
            Path directory = ROOT.resolve(retired);
            if (Files.exists(directory)) {
                try (var files = Files.walk(directory)) {
                    assertThat(files.filter(path -> path.toString().endsWith(".java")).toList())
                            .isEmpty();
                }
            }
        }
    }

    private static void forbid(Path directory, List<String> forbidden) throws Exception {
        assertThat(Files.isDirectory(directory)).as(directory.toString()).isTrue();
        try (var files = Files.walk(directory)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(sources).as(directory.toString()).isNotEmpty();
            for (var file : sources) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain(forbidden);
            }
        }
    }
}
