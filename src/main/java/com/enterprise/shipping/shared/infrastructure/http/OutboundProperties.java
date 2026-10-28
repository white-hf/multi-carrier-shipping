package com.enterprise.shipping.shared.infrastructure.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Defaults -> optional named template -> dependency overrides -> bounded logical groups. */
@ConfigurationProperties(prefix = "shipping.outbound", ignoreUnknownFields = false)
public class OutboundProperties {
    private Settings defaults = new Settings();
    private Map<String, Settings> templates = new LinkedHashMap<>();
    private Map<String, Settings> dependencies = new LinkedHashMap<>();

    public Settings getDefaults() {
        return defaults;
    }

    public void setDefaults(Settings value) {
        defaults = value;
    }

    public Map<String, Settings> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, Settings> value) {
        templates = value;
    }

    public Map<String, Settings> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, Settings> value) {
        dependencies = value;
    }

    public void validate() {
        if (defaults.template != null) {
            throw new IllegalArgumentException("Defaults cannot reference a template");
        }
        resolveSettings(null, null);
        templates.forEach(
                (name, value) -> {
                    if (value.template != null) {
                        throw new IllegalArgumentException(
                                "Nested outbound templates are unsupported");
                    }
                    resolveSettings(null, value);
                });
        dependencies.keySet().forEach(this::resolve);
    }

    public OutboundPolicy resolve(String dependency) {
        Settings override = dependencies.get(dependency);
        Settings template = null;
        if (override != null && override.template != null) {
            template = templates.get(override.template);
            if (template == null) {
                throw new IllegalArgumentException(
                        "Unknown outbound template: " + override.template);
            }
        }
        return resolveSettings(template, override);
    }

    private OutboundPolicy resolveSettings(Settings template, Settings override) {
        Settings merged = new Settings();
        merged.timeout = Duration.ofSeconds(30);
        merged.connectTimeout = Duration.ofSeconds(3);
        merged.concurrency = 8;
        merged.maxResponseBytes = 4 * 1024 * 1024;
        merged.windowSize = 10;
        merged.minimumCalls = 5;
        merged.failureRate = 50f;
        merged.openDuration = Duration.ofSeconds(10);
        for (Settings layer : new Settings[] {defaults, template, override}) {
            if (layer == null) {
                continue;
            }
            if (layer.timeout != null) {
                merged.timeout = layer.timeout;
            }
            if (layer.connectTimeout != null) {
                merged.connectTimeout = layer.connectTimeout;
            }
            if (layer.concurrency != null) {
                merged.concurrency = layer.concurrency;
            }
            if (layer.maxResponseBytes != null) {
                merged.maxResponseBytes = layer.maxResponseBytes;
            }
            if (layer.windowSize != null) {
                merged.windowSize = layer.windowSize;
            }
            if (layer.minimumCalls != null) {
                merged.minimumCalls = layer.minimumCalls;
            }
            if (layer.failureRate != null) {
                merged.failureRate = layer.failureRate;
            }
            if (layer.openDuration != null) {
                merged.openDuration = layer.openDuration;
            }
            layer.groups.forEach(
                    (name, value) -> {
                        Group target = merged.groups.computeIfAbsent(name, ignored -> new Group());
                        if (value.timeout != null) {
                            target.timeout = value.timeout;
                        }
                        if (value.concurrency != null) {
                            target.concurrency = value.concurrency;
                        }
                    });
        }
        Map<String, OutboundPolicy.Group> groups = new LinkedHashMap<>();
        merged.groups.putIfAbsent("default", new Group());
        merged.groups.forEach(
                (name, group) ->
                        groups.put(
                                name,
                                new OutboundPolicy.Group(
                                        group.timeout == null ? merged.timeout : group.timeout,
                                        group.concurrency)));
        return new OutboundPolicy(
                merged.timeout,
                merged.connectTimeout,
                merged.concurrency,
                merged.maxResponseBytes,
                merged.windowSize,
                merged.minimumCalls,
                merged.failureRate,
                merged.openDuration,
                groups);
    }

    public static class Group {
        protected Duration timeout;
        protected Integer concurrency;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration value) {
            timeout = value;
        }

        public Integer getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(Integer value) {
            concurrency = value;
        }
    }

    public static class Settings extends Group {
        private String template;
        private Duration connectTimeout;
        private Integer maxResponseBytes;
        private Integer windowSize;
        private Integer minimumCalls;
        private Float failureRate;
        private Duration openDuration;
        private Map<String, Group> groups = new LinkedHashMap<>();

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String value) {
            template = value;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration value) {
            connectTimeout = value;
        }

        public Integer getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(Integer value) {
            maxResponseBytes = value;
        }

        public Integer getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(Integer value) {
            windowSize = value;
        }

        public Integer getMinimumCalls() {
            return minimumCalls;
        }

        public void setMinimumCalls(Integer value) {
            minimumCalls = value;
        }

        public Float getFailureRate() {
            return failureRate;
        }

        public void setFailureRate(Float value) {
            failureRate = value;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration value) {
            openDuration = value;
        }

        public Map<String, Group> getGroups() {
            return groups;
        }

        public void setGroups(Map<String, Group> value) {
            groups = value;
        }
    }
}
