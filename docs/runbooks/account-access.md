# Account access and public demo deployment

The backend requires configured accounts. `/api/auth/guest` no longer issues tokens and
`shipping.auth.require-token=false` no longer bypasses authentication. All UI operations require
sign-in and use the backend. Local preview and its sample-data paths are removed.

## Configure a single operator

Copy `.env.example`, fill in the provider settings and these **separate application login** settings:

- `SHIPPING_AUTH_USERNAME`: account name (1–64 letters, digits, `.`, `_`, `@`, `-`).
- `SHIPPING_AUTH_PASSWORD`: a unique password, 12–1024 characters, or use the hash option below.
- `SHIPPING_AUTH_USER_ID`: stable data owner, e.g. `user-operator`; do not reuse across accounts.
- `SHIPPING_AUTH_DISPLAY_NAME`: UI account label.
- `SHIPPING_AUTH_TOKEN_SECRET`: at least 32 characters; generate with `openssl rand -hex 32`.
- `SHIPPING_AUTH_TOKEN_TTL`: default `2h`, at most `24h`.

There are no usable default credentials. Missing/invalid account settings fail startup.
Run `./scripts/start-workflow.sh --check-config`, then `./scripts/start-workflow.sh` and sign in.
The launcher sources `.env`; direct Maven/JAR launches do not automatically source it.
Never reuse provider Basic Auth credentials as application login credentials.

## Prehashed passwords and multiple accounts

Passwords use PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte random salt, 32-byte output).
For prehashed configuration, leave `SHIPPING_AUTH_PASSWORD` empty and set
`SHIPPING_AUTH_PASSWORD_HASH` to `pbkdf2-sha256$600000$<base64-salt>$<base64-hash>`.
Single-quote values containing `$` when sourcing shell configuration. Exactly one password form
is required. Plain environment passwords are converted to salted hashes for verification at startup;
Spring's configuration/environment can still retain the supplied plaintext, so use hashes for public deployment.

Generate a compatible hash interactively without putting the password into shell history:

```sh
python3 - <<'PY'
import base64, getpass, hashlib, secrets
password = getpass.getpass('New application password: ')
if not 12 <= len(password) <= 1024:
    raise SystemExit('Password must contain 12–1024 characters')
salt = secrets.token_bytes(16)
hash_value = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000)
print('pbkdf2-sha256$600000$' + base64.b64encode(salt).decode() + '$' + base64.b64encode(hash_value).decode())
PY
```

Multiple accounts use Spring's `shipping.auth.users` list, supplied as a complete external YAML
configuration or `SPRING_APPLICATION_JSON`. Include all fields for each list item:

```json
{"shipping":{"auth":{"users":[
  {"username":"operator-a","password-hash":"<encoded-hash-a>","user-id":"user-a","display-name":"Operator A"},
  {"username":"operator-b","password-hash":"<encoded-hash-b>","user-id":"user-b","display-name":"Operator B"}
]}}}
```

For multi-account external configuration, launch the JAR with the appropriate Spring configuration;
the convenience launcher checks the single-operator environment variables. Usernames and user IDs
must both be unique. Data ownership follows user ID; changing it creates a different data scope.
No existing guest data is automatically transferred to an account.

## Limits and deployment boundary

- Login: 5 attempts/second globally; 5 failed attempts per IP or username in 5 minutes lock for
  15 minutes. Success clears the username failure history, not the IP history. Account lockouts
  can also deny a legitimate user temporarily; choose thresholds appropriate to the demo audience.
- Authenticated traffic: 10 quote requests, 5 booking requests and 20 label reads per minute,
  independently by account and peer IP. Replays count; ordinary status GETs do not dispatch.
- The counter table is bounded (4096 entries); saturation returns 429 rather than forgetting
  active counters. Expired entries are reclaimed during requests. Limits reset on restart and
  apply to one instance. Coordinate limits at the gateway before deploying multiple instances.
- Configure `SHIPPING_AUTH_MAX_FAILED_ATTEMPTS`, `SHIPPING_AUTH_LOCKOUT_DURATION`,
  `SHIPPING_AUTH_QUOTES_PER_MINUTE`, `SHIPPING_AUTH_BOOKINGS_PER_MINUTE`, and
  `SHIPPING_AUTH_LABELS_PER_MINUTE` as needed. Spring configuration also exposes
  `failure-window`, `login-per-second`, and `max-entries` under `shipping.auth`.
- Tests and the performance harness explicitly raise quotas against a local stub. Keep the
  normal quotas for real sandbox use. Outbound concurrency/circuits protect capacity, not
  requests per minute or daily provider allowances.

Serve public traffic over HTTPS. Cookies become Secure when the servlet container reports HTTPS.
Direct client `X-Forwarded-For`/`X-Forwarded-Proto` headers are not trusted by application code.
Behind a proxy, restrict backend reachability to that proxy and configure the container's trusted
proxy handling (e.g. Tomcat RemoteIpValve with an explicit internal/trusted proxy range). Then verify
Secure cookies, same-origin behavior and client IP quotas through the actual public URL. A proxy
without trusted client-IP handling causes users to share the proxy's IP quota. Do not blindly trust
arbitrary forwarded headers or publicly expose the management port.

Device fingerprints are client-supplied bindings, not an additional authentication factor: a copied
token **and** fingerprint can be replayed. Same-origin checks protect browser writes with Origin;
they do not stop scripts from omitting or forging Origin. Credentials, HTTPS and quotas form the
public API boundary. Logout expires browser cookies but does not revoke copied stateless tokens;
rotate the signing secret to invalidate all sessions when needed.

The implementation uses the JDK PBKDF2 provider and constant-time derived-key comparison,
including a dummy hash for unknown accounts. See the
[OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
and [authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).
