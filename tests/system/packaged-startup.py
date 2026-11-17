#!/usr/bin/env python3
"""Exercise the real launcher/JAR with isolated SQLite and a loopback provider only."""
import hashlib
import html.parser
import http.server
import json
import os
import secrets
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "target" / "packaged-startup"


def free_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def request(url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=10) as response:
        raw = response.read()
        return response.status, (json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw)


class Provider(http.server.BaseHTTPRequestHandler):
    creates = 0
    rates = 0

    def log_message(self, *_):
        pass

    def reply(self, status, payload, media="application/json"):
        raw = json.dumps(payload).encode() if media == "application/json" else payload
        self.send_response(status)
        self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/rest/rates":
            Provider.rates += 1
            self.reply(200, {"availableRates": [{
                "quoteId": "Smoke/Case+Quote", "carrierId": "14", "serviceId": "1",
                "carrierName": "Carrier", "serviceName": "Ground",
                "totalCharge": {"amount": 2735, "currency": "CAD"}}]})
        elif self.path == "/rest/shipments":
            assert body == {"quoteId": "Smoke/Case+Quote"}, body
            Provider.creates += 1
            self.reply(201, {"shipId": 12345, "trackingNumbers": ["SMOKE-TRACK"]})
        else:
            self.reply(404, {})

    def do_GET(self):
        if self.path == "/rest/shipments/12345/label":
            self.reply(200, (ROOT / "src/test/resources/fixtures/shipping-label.pdf").read_bytes(), "application/pdf")
        else:
            self.reply(404, {})


class StylesheetLinks(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == "link" and "stylesheet" in attributes.get("rel", "").split():
            self.links.append(attributes.get("href", ""))


def check_stylesheets(base, page):
    parser = StylesheetLinks()
    parser.feed(page.decode("utf-8"))
    assert any(urllib.parse.urlsplit(link).path == "/css/app.css" for link in parser.links), "Application CSS is not linked"
    checked = []
    for link in parser.links:
        url = urllib.parse.urljoin(base + "/", link)
        assert urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(base).netloc, "Unexpected external stylesheet"
        with urllib.request.urlopen(url, timeout=10) as response:
            assert response.status == 200 and response.headers.get_content_type() == "text/css", url
            css = response.read()
            assert css, f"Empty stylesheet: {url}"
            if urllib.parse.urlsplit(url).path == "/css/app.css":
                assert b".app-shell" in css and b"--navy" in css, "Application CSS content is missing"
        checked.append(link)
    return checked


def run():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / "report.json").unlink(missing_ok=True)
    previous_log = EVIDENCE / "launcher.log"
    if previous_log.exists() and "Applied migration checksum changed" in previous_log.read_text():
        (EVIDENCE / "reproduced-checksum-failure.log").write_text(previous_log.read_text())
    provider = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    threading.Thread(target=provider.serve_forever, daemon=True).start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="shiptime-packaged-") as temporary:
            scratch = Path(temporary)
            old_db = scratch / "old.db"
            new_db = scratch / "stateless.db"
            with sqlite3.connect(old_db) as connection:
                connection.executescript("CREATE TABLE schema_migrations(version TEXT PRIMARY KEY, checksum TEXT NOT NULL);"
                                         "INSERT INTO schema_migrations VALUES('V001__shipping_schema.sql', 'old-baseline');"
                                         "CREATE TABLE preserved_old_data(value TEXT); INSERT INTO preserved_old_data VALUES('keep');")
            connection.close()
            original_bytes = old_db.read_bytes()
            env_file = scratch / "smoke.env"
            # Deliberately conflict with the explicit shell override, reproducing old launch behavior.
            env_file.write_text(f"SHIPPING_DATABASE_PATH='{old_db}'\n")
            port = free_port()
            # Isolated accounts exist only for this process; no fixed credentials enter source control.
            password = secrets.token_urlsafe(24)
            environment = {**os.environ,
                "SHIPPING_ENV_FILE": str(env_file),
                "SHIPPING_DATABASE_PATH": str(new_db),
                "SERVER_PORT": str(port), "MANAGEMENT_SERVER_PORT": str(free_port()),
                "SHIPPING_LOG_FILE": str(scratch / "application.log"),
                "SHIPPING_PROVIDER_BASE_URL": f"http://127.0.0.1:{provider.server_port}",
                "SHIPPING_PROVIDER_USERNAME": "stub", "SHIPPING_PROVIDER_PASSWORD": secrets.token_urlsafe(24),
                "SHIPPING_AUTH_TOKEN_SECRET": secrets.token_hex(32),
                "SHIPPING_AUTH_USERNAME": "smoke", "SHIPPING_AUTH_PASSWORD": password,
                "SHIPPING_AUTH_PASSWORD_HASH": "", "SHIPPING_AUTH_USER_ID": "smoke-user"}
            reset_environment = {key: value for key, value in environment.items() if key != "SHIPPING_DATABASE_PATH"}
            with (EVIDENCE / "reset.log").open("w") as reset_log:
                reset = subprocess.run(["bash", "scripts/start-workflow.sh", "--reset-database-only"],
                                       cwd=ROOT, env=reset_environment, stdout=reset_log, stderr=subprocess.STDOUT,
                                       timeout=15)
            assert reset.returncode == 0 and not old_db.exists(), "Explicit configured-database reset failed"
            assert not Path(str(old_db) + "-wal").exists() and not Path(str(old_db) + "-shm").exists()
            assert Provider.creates == 0
            old_db.write_bytes(original_bytes)
            with (EVIDENCE / "launcher.log").open("w") as log:
                process = subprocess.Popen(["bash", "scripts/start-workflow.sh"], cwd=ROOT,
                                           env=environment, stdout=log, stderr=subprocess.STDOUT)
                base = f"http://127.0.0.1:{port}"
                deadline = time.monotonic() + 100
                while True:
                    if process.poll() is not None:
                        raise AssertionError(f"Packaged launcher exited {process.returncode}; see {EVIDENCE / 'launcher.log'}")
                    try:
                        status, page = request(base + "/")
                        if status == 200:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise AssertionError("Packaged application did not become ready")
                    time.sleep(0.2)
                stylesheets = check_stylesheets(base, page)
                fingerprint = "fp-packaged-smoke-0001"
                _, session = request(base + "/api/auth/login", {
                    "username": "smoke", "password": password, "fingerprint": fingerprint})
                headers = {"Authorization": "Bearer " + session["token"], "X-Client-Fingerprint": fingerprint}
                address = {"contactName": "Test", "addressLine1": "100 Test Street", "city": "Toronto",
                           "province": "ON", "postalCode": "M5V 3L9", "countryCode": "CA", "phone": "4165550100"}
                shipping = {"sender": address, "recipient": address,
                            "parcel": {"length": 12, "width": 10, "height": 8, "weight": 5}}
                status, rates = request(base + "/api/quotes", shipping, headers)
                assert status == 200 and rates["quotes"][0]["providerQuoteId"] == "Smoke/Case+Quote"
                quote = rates["quotes"][0]
                shipping["shippingDate"] = rates["shippingDate"]
                command = {"providerId": quote["providerId"], "providerQuoteId": quote["providerQuoteId"],
                           "shippingInput": shipping, "displaySnapshot": {
                               field: quote.get(field) for field in ["carrierId", "carrierName", "serviceId",
                                   "serviceName", "total", "expiresAt", "expirySource"]}}
                status, booking = request(base + "/api/bookings", command, headers)
                assert status == 201 and booking["status"] == "CONFIRMED", booking
                status, replay = request(base + "/api/bookings", command, headers)
                assert status == 200 and replay["bookingId"] == booking["bookingId"]
                query = urllib.parse.urlencode({"providerId": quote["providerId"], "providerQuoteId": quote["providerQuoteId"]})
                assert request(base + "/api/bookings/lookup?" + query, headers=headers)[1]["bookingId"] == booking["bookingId"]
                assert request(base + "/api/bookings/" + booking["bookingId"], headers=headers)[1]["status"] == "CONFIRMED"
                status, pdf = request(base + "/api/bookings/" + booking["bookingId"] + "/label", headers=headers)
                assert status == 200 and pdf.startswith(b"%PDF-")
                assert Provider.creates == 1 and Provider.rates == 1
                assert old_db.read_bytes() == original_bytes, "Launcher modified the old database"
                with sqlite3.connect(new_db) as connection:
                    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    assert tables == {"shipment_bookings", "booking_reconciliation_tasks", "schema_migrations"}, tables
                jar = ROOT / "target/multi-courier-aggregation-service-1.0.0.jar"
                report = {"status": "passed", "artifact": jar.name,
                          "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                          "launcher": "scripts/start-workflow.sh", "provider": "loopback simulation",
                          "rates": Provider.rates, "creates": Provider.creates,
                          "old_database_unchanged": True,
                          "explicit_reset_verified": True,
                          "stylesheets": stylesheets,
                          "checks": ["explicit configured database reset", "JAR startup", "packaged stylesheet responses", "env database override", "login", "stateless rates",
                                     "create", "duplicate replay", "read-only lookup/status", "PDF label"]}
                (EVIDENCE / "report.json").write_text(json.dumps(report, indent=2) + "\n")
                print(json.dumps(report, indent=2))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        provider.shutdown()
        provider.server_close()


if __name__ == "__main__":
    run()
