package com.enterprise.shipping.provider.sandbox.labels;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Self-contained label-retrieval API operation for the sandbox provider.
 * Owns its /rest/shipments/{shipmentId}/label endpoint, custom decoder, and URL allowlist checks.
 */
@Component
public class SandboxLabelOperation {

    private final SandboxLabelsHttpService http;
    private final OutboundGuard guard;
    private final ProviderAccountContext context;
    private final Duration readTimeout;
    private final LabelResponseDecoder decoder;

    @Autowired
    public SandboxLabelOperation(
            @Qualifier("sandboxLabelsHttpService") SandboxLabelsHttpService http,
            @Qualifier("sandboxOutboundGuard") OutboundGuard guard,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties properties) {
        this(http, guard, context, properties.readTimeout());
    }

    public SandboxLabelOperation(
            SandboxLabelsHttpService http,
            OutboundGuard guard,
            ProviderAccountContext context,
            Duration readTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.context = Objects.requireNonNull(context, "context");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.decoder = new LabelResponseDecoder(context::allowedUrl);
        guard.registerOperation("labels");
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public LabelData fetch(LabelIdentity identity) {
        // 1. Verify provider and account routing context and validate shipment identifier syntax
        context.requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }

        // 2. Spring binds the path variable; decode PDF/URL data inside the read protection scope.
        var diagnostic = OutboundDiagnostics.call(context.providerId(), "labels", status ->
                status == 202 || status == 404 ? OutboundDiagnostics.Classification.EXPECTED_BRANCH
                        : status >= 200 && status < 300 ? OutboundDiagnostics.Classification.SUCCESS
                                : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE);
        return guard.execute("default", "labels", OutboundDeadline.after(readTimeout), effective -> {
            try {
                return decoder.decode(OutboundDeadline.await(http.label(id).contextWrite(diagnostic::context), effective));
            } catch (WebClientResponseException failure) {
                int status = failure.getStatusCode().value();
                if (status == 202 || status == 404) {
                    return null;
                }
                throw ProviderAccountContext.readFailure(status);
            } catch (ShippingException invalid) {
                diagnostic.protocolError();
                throw invalid;
            }
        });
    }

    public boolean allowedUrl(String url) {
        return context.allowedUrl(url);
    }
}
