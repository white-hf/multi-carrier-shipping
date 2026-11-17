import com.enterprise.shipping.Application;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Authenticated real-HTTP workload. The only external provider is a loopback stub. */
public class HttpLoadRunner {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final AtomicLong RATE_CALLS = new AtomicLong(), CREATE_CALLS = new AtomicLong();
    static final AtomicReference<String> MODE = new AtomicReference<>("normal");
    static String base, token;
    static final String FP = "load-fingerprint-2026";
    static final String INPUT =
            """
        {"sender":{"contactName":"Load Sender","addressLine1":"100 King St","city":"Toronto","province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100","residential":false},
         "recipient":{"contactName":"Load Recipient","addressLine1":"200 Queen St","city":"Halifax","province":"NS","postalCode":"B3J 3K1","countryCode":"CA","phone":"9025550100","residential":true},
         "parcel":{"length":12,"width":10,"height":8,"weight":5,"dimensionUnit":"IN","weightUnit":"LB","packageType":"PACKAGE"}}
        """;

    record Reply(int status, JsonNode body) {}

    static Reply call(
            String method,
            String path,
            String body,
            String key,
            DatabaseLoadRunner.Stats stats)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("X-Client-Fingerprint", FP);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        long start = System.nanoTime();
        try {
            var r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (stats != null) {
                stats.add(System.nanoTime() - start, 0, Integer.toString(r.statusCode()));
            }
            return new Reply(r.statusCode(), JSON.readTree(r.body()));
        } catch (Exception e) {
            if (stats != null) {
                stats.add(System.nanoTime() - start, 0, e.getClass().getSimpleName());
            }
            throw e;
        }
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }

    static Reply rate(String key, DatabaseLoadRunner.Stats stats) throws Exception {
        return call("POST", "/api/quotes", INPUT, key, stats);
    }

    static String quote(Reply r) {
        require(r.status == 201 || r.status == 200, "Rate preparation failed: " + r.status);
        return r.body.path("quotes").get(0).path("quoteId").asText();
    }

    static Reply book(String quote, String key, DatabaseLoadRunner.Stats stats) throws Exception {
        return call(
                "POST",
                "/api/bookings",
                JSON.writeValueAsString(Map.of("quoteId", quote)),
                key,
                stats);
    }

    static List<Reply> concurrent(int n, Callable<Reply> work) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Reply>> fs = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < n; i++) {
                fs.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return work.call();
                                }));
            }
            go.countDown();
            List<Reply> result = new ArrayList<>();
            for (var f : fs) {
                result.add(f.get(20, TimeUnit.SECONDS));
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean smoke = args.length > 1 && args[1].equals("--smoke");
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        Path db = directory.resolve("http.db");
        if (Files.exists(db)) {
            throw new IllegalArgumentException("Fresh isolated HTTP database required");
        }
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var stubPool = Executors.newFixedThreadPool(8);
        stub.setExecutor(stubPool);
        stub.createContext(
                "/rest/",
                exchange -> {
                    try {
                        String path = exchange.getRequestURI().getPath();
                        int status = 200;
                        String response;
                        if (path.equals("/rest/rates")) {
                            long n = RATE_CALLS.incrementAndGet();
                            Thread.sleep(20);
                            if (MODE.get().equals("rate-error")) {
                                status = 500;
                                response = "{}";
                            } else {
                                response =
                                        "{\"availableRates\":[{\"quoteId\":\"provider-"
                                                + n
                                                + "\",\"carrierId\":\"carrier-1\",\"carrierName\":\"Local Carrier\",\"serviceId\":\"ground\",\"serviceName\":\"Ground\",\"totalCharge\":{\"amount\":2735,\"currency\":\"CAD\"}}]}";
                            }
                        } else if (path.equals("/rest/shipments")) {
                            long n = CREATE_CALLS.incrementAndGet();
                            Thread.sleep(20);
                            if (MODE.get().equals("drop-create")) {
                                exchange.close();
                                return;
                            }
                            status = 201;
                            response =
                                    "{\"shipId\":"
                                            + (100000 + n)
                                            + ",\"trackingNumbers\":[\"LOCAL-"
                                            + n
                                            + "\"]}";
                        } else {
                            status = 202;
                            response = "{}";
                        }
                        byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        stub.start();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("outbound_policy", Map.of("dependency", "sandbox", "shared_concurrency", 4));
        try (var ctx =
                (ServletWebServerApplicationContext)
                        SpringApplication.run(
                                Application.class,
                                "--server.port=0",
                                "--management.server.port=0",
                                "--shipping.database-path=" + db,
                                "--shipping.provider.base-url=http://127.0.0.1:"
                                        + stub.getAddress().getPort(),
                                "--shipping.provider.username=local-test",
                                "--shipping.provider.password=local-test",
                                "--shipping.provider.collection-seconds=5",
                                "--shipping.provider.rate-timeout=7s",
                                "--shipping.provider.create-timeout=1s",
                                "--shipping.provider.read-timeout=1s",
                                "--shipping.outbound.dependencies.sandbox.concurrency=4",
                                "--shipping.auth.token-secret=" + key(),
                                "--shipping.auth.users[0].username=load-test",
                                "--shipping.auth.users[0].password=local-load-test-password",
                                "--shipping.auth.users[0].user-id=load-user",
                                "--shipping.auth.users[0].display-name=Load Test",
                                "--shipping.auth.quotes-per-minute=100000",
                                "--shipping.auth.bookings-per-minute=100000",
                                "--shipping.auth.labels-per-minute=100000",
                                "--shipping.recovery.discovery-interval-ms=3600000",
                                "--logging.level.root=WARN",
                                "--logging.level.com.enterprise.shipping=ERROR",
                                "--logging.file.name=" + directory.resolve("application.log"))) {
            base = "http://127.0.0.1:" + ctx.getWebServer().getPort();
            var auth =
                    call(
                            "POST",
                            "/api/auth/login",
                            JSON.writeValueAsString(
                                    Map.of("username", "load-test", "password", "local-load-test-password", "fingerprint", FP)),
                            null,
                            null);
            require(auth.status == 200, "Account auth failed");
            token = auth.body.path("token").asText();
            var jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
            var checks = new LinkedHashMap<String, Object>();
            String rateKey = key();
            long before = RATE_CALLS.get();
            var rs = concurrent(32, () -> rate(rateKey, null));
            require(
                    rs.stream().allMatch(r -> Set.of(200, 201, 202).contains(r.status)),
                    "Rate concurrency error");
            var saved = rate(rateKey, null);
            String requestId = saved.body.path("requestId").asText();
            require(RATE_CALLS.get() == before + 1, "Duplicate provider rate call");
            require(
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM shipment_requests WHERE idempotency_key=?",
                                    Integer.class,
                                    rateKey)
                            == 1,
                    "Duplicate request row");
            checks.put("concurrent_rate_idempotency", true);
            String bookKey = key(), q = quote(saved);
            before = CREATE_CALLS.get();
            var bs = concurrent(32, () -> book(q, bookKey, null));
            require(
                    bs.stream().allMatch(r -> Set.of(200, 201, 202).contains(r.status)),
                    "Booking concurrency error");
            require(CREATE_CALLS.get() == before + 1, "Duplicate provider creation");
            require(
                    jdbc.queryForObject(
                                    "SELECT count(*) FROM shipment_bookings WHERE idempotency_key=?",
                                    Integer.class,
                                    bookKey)
                            == 1,
                    "Duplicate booking row");
            checks.put("concurrent_booking_idempotency", true);
            var changed =
                    call(
                            "POST",
                            "/api/quotes",
                            INPUT.replace("\"weight\":5", "\"weight\":6"),
                            rateKey,
                            null);
            require(changed.status == 409, "Changed payload was accepted");
            checks.put("payload_conflict", true);
            MODE.set("rate-error");
            String retry = key();
            before = RATE_CALLS.get();
            var failed = rate(retry, null);
            require(failed.status == 502, "Expected provider error");
            String failedId = failed.body.path("operation").path("id").asText();
            MODE.set("normal");
            ctx.getBean(OutboundClientRegistry.class).guard("sandbox").resetCircuitBreakers();
            var retried = rate(retry, null);
            require(
                    retried.status == 200
                            && failedId.equals(retried.body.path("requestId").asText())
                            && RATE_CALLS.get() == before + 2,
                    "Rate retry identity changed");
            checks.put("failed_rate_retry", true);
            String uq = quote(rate(key(), null)), unknownKey = key();
            MODE.set("drop-create");
            before = CREATE_CALLS.get();
            var unknown = book(uq, unknownKey, null);
            String unknownId = unknown.body.path("bookingId").asText();
            require(
                    unknown.status == 202 && !unknownId.isBlank(),
                    "Uncertain creation did not remain pending");
            book(uq, unknownKey, null);
            require(CREATE_CALLS.get() == before + 1, "Uncertain creation was retried");
            jdbc.update(
                    "UPDATE shipment_bookings SET submission_deadline_at=0 WHERE booking_id=?",
                    unknownId);
            ctx.getBean(PendingShipmentDiscovery.class).discover();
            var manual = call("GET", "/api/bookings/" + unknownId, null, null, null);
            require(
                    manual.body.path("status").asText().equals("MANUAL_REVIEW"),
                    "Manual review recovery failed");
            checks.put("uncertain_creation_no_retry", true);
            MODE.set("normal");
            ctx.getBean(OutboundClientRegistry.class).guard("sandbox").resetCircuitBreakers();
            report.put("reliability", checks);
            List<Object> loads = new ArrayList<>();
            boolean availability = true;
            report.put("smoke_only", smoke);
            for (String scenario : List.of("status", "rates", "workflow")) {
                for (int i = 0; i < 20; i++) {
                    if (scenario.equals("status")) {
                        call("GET", "/api/quotes/" + requestId, null, null, null);
                    } else {
                        var warm = rate(key(), null);
                        if (scenario.equals("workflow")) {
                            book(quote(warm), key(), null);
                        }
                    }
                }
                for (int workers : smoke ? new int[] {1} : new int[] {1, 2, 4, 8, 16}) {
                    for (int repetition = 1; repetition <= (smoke ? 1 : 3); repetition++) {
                        var rates = new DatabaseLoadRunner.Stats();
                        var bookings = new DatabaseLoadRunner.Stats();
                        var status = new DatabaseLoadRunner.Stats();
                        var workflow = new DatabaseLoadRunner.Stats();
                        var pool = Executors.newFixedThreadPool(workers);
                        CountDownLatch ready = new CountDownLatch(workers),
                                go = new CountDownLatch(1);
                        long[] deadline = {0};
                        List<Future<?>> futures = new ArrayList<>();
                        for (int w = 0; w < workers; w++) {
                            futures.add(
                                    pool.submit(
                                            () -> {
                                                ready.countDown();
                                                try {
                                                    go.await();
                                                    while (System.nanoTime() < deadline[0]) {
                                                        try {
                                                            if (scenario.equals("status")) {
                                                                call(
                                                                        "GET",
                                                                        "/api/quotes/" + requestId,
                                                                        null,
                                                                        null,
                                                                        status);
                                                            } else {
                                                                long begun = System.nanoTime();
                                                                var r = rate(key(), rates);
                                                                if (scenario.equals("workflow")) {
                                                                    var booked =
                                                                            r.status == 201
                                                                                    ? book(
                                                                                            quote(
                                                                                                    r),
                                                                                            key(),
                                                                                            bookings)
                                                                                    : r;
                                                                    workflow.add(
                                                                            System.nanoTime()
                                                                                    - begun,
                                                                            0,
                                                                            Integer.toString(
                                                                                    booked.status));
                                                                }
                                                            }
                                                        } catch (Exception ignored) {
                                                            /* Transport failures are counted by call(). */

                                                          }
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            }));
                        }
                        ready.await();
                        long cpu =
                                java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                                                instanceof
                                                com.sun.management.OperatingSystemMXBean os
                                        ? os.getProcessCpuTime()
                                        : 0;
                        long start = System.nanoTime();
                        deadline[0] = start + TimeUnit.SECONDS.toNanos(smoke ? 2 : 10);
                        go.countDown();
                        for (var f : futures) {
                            f.get(30, TimeUnit.SECONDS);
                        }
                        pool.shutdown();
                        double seconds = (System.nanoTime() - start) / 1e9;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("scenario", scenario);
                        m.put("workers", workers);
                        m.put("repetition", repetition);
                        m.put("rates", rates.report(seconds));
                        m.put("bookings", bookings.report(seconds));
                        m.put("status", status.report(seconds));
                        m.put("workflow", workflow.report(seconds));
                        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                                instanceof com.sun.management.OperatingSystemMXBean os) {
                            m.put("process_cpu_seconds", (os.getProcessCpuTime() - cpu) / 1e9);
                        }
                        m.put(
                                "heap_used_bytes",
                                Runtime.getRuntime().totalMemory()
                                        - Runtime.getRuntime().freeMemory());
                        for (var stats : List.of(rates, bookings, status)) {
                            for (String outcome : stats.outcomes.keySet()) {
                                if (!Set.of("200", "201", "202", "503").contains(outcome)) {
                                    availability = false;
                                }
                            }
                        }
                        loads.add(m);
                        System.out.println("HTTP " + JSON.writeValueAsString(m));
                        JSON.writerWithDefaultPrettyPrinter()
                                .writeValue(
                                        directory.resolve("http-progress.json").toFile(), loads);
                    }
                }
            }
            var recovered = rate(key(), null);
            require(recovered.status == 201, "Provider did not recover after load");
            report.put("post_load_recovery", true);
            report.put("availability_pass", availability);
            report.put("load", loads);
            report.put(
                    "provider_calls",
                    Map.of("rates", RATE_CALLS.get(), "creates", CREATE_CALLS.get()));
            report.put(
                    "rows",
                    jdbc.queryForList(
                            "SELECT 'requests' AS name,count(*) AS rows FROM shipment_requests UNION ALL SELECT 'bookings',count(*) FROM shipment_bookings"));
            var violations = jdbc.queryForList("PRAGMA foreign_key_check");
            require(violations.isEmpty(), "Foreign key violation");
            report.put("correctness_pass", true);
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("http-results.json").toFile(), report);
        } finally {
            stub.stop(0);
            stubPool.shutdownNow();
        }
    }
}
