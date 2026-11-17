const { chromium } = require('playwright');
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const [appUrl, controlUrl] = process.argv.slice(2);
const evidenceDir = process.env.SHIPTIME_EVIDENCE_DIR;
const traces = [];
async function ctl(mode) {
  return (await fetch(`${controlUrl}?mode=${mode}`)).json();
}
async function checkpoint(page, name) {
  if (!evidenceDir) return;
  fs.mkdirSync(evidenceDir, { recursive: true });
  await page.screenshot({ path: path.join(evidenceDir, `${name}.png`), fullPage: true });
  fs.writeFileSync(path.join(evidenceDir, `${name}.json`), JSON.stringify({
    checkpoint: name, url: page.url(), carrierCalls: await ctl('stats')
  }, null, 2));
}
async function signIn(page, username = 'alpha') {
  await page.waitForSelector('#login-modal[open]');
  await page.fill('#login-username', username);
  await page.fill('#login-password', `test-${username}-password`);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.click('#login-submit')]);
}
async function rates(page) {
  await page.click('#get-rates-btn');
  await page.waitForSelector('#step-2:not([hidden]) .rate-card');
}
async function review(page) {
  await page.click('.rate-card:first-child');
  await page.click('#review-btn');
  await page.waitForSelector('#review-view:not([hidden])');
}
async function active(page) {
  return page.evaluate(() => {
    const stored = JSON.parse(localStorage.getItem('shipping.activeBooking.v2'));
    return stored?.operations.find(op => JSON.stringify([op.userId, op.providerId || null,
      op.providerQuoteId || null, op.providerQuoteId ? null : op.bookingId || 'legacy-unrecoverable']) === stored.active);
  });
}
async function outcome(page, title) {
  await page.waitForFunction(value => document.getElementById('outcome-title').textContent === value, title);
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('dialog', dialog => dialog.accept());
  if (evidenceDir) {
    fs.mkdirSync(evidenceDir, { recursive: true });
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    traces.push(path.join(evidenceDir, 'browser-trace-1.zip'));
  }
  try {
    await ctl('normal');
    await ctl('reset-counts');
    await page.goto(appUrl, { waitUntil: 'networkidle' });
    await page.waitForSelector('#login-modal[open]');
    await page.fill('#login-username', 'alpha');
    await page.fill('#login-password', 'incorrect');
    await page.click('#login-submit');
    await page.waitForSelector('#login-error:not([hidden])');
    assert.ok((await page.textContent('#login-error')).includes('Invalid username or password'));
    await signIn(page);
    assert.strictEqual(await page.locator('#mock-toggle').count(), 0);
    assert.strictEqual(await page.textContent('#mode-indicator'), 'Backend API');
    assert.strictEqual(await page.evaluate(async () => (await fetch('/api/quotes', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    })).status), 401);
    await checkpoint(page, 'signed-in');

    // Validation and optional input drafts survive reload; no persisted rate task/key is stored.
    await page.click('#detail-tab-btn-2');
    await page.fill('input[name="to.city"]', '');
    await page.click('#detail-tab-btn-1');
    await page.click('#get-rates-btn');
    assert.strictEqual(await page.isVisible('[data-tab-pane="2"]'), true);
    await page.fill('input[name="to.city"]', 'Halifax');
    await page.click('#detail-tab-btn-3');
    await page.fill('input[name="weight"]', '0');
    await page.click('#get-rates-btn');
    assert.strictEqual((await ctl('stats')).rateCalls, 0);
    await page.fill('input[name="weight"]', '5');
    await page.click('#detail-tab-btn-1');
    await page.fill('input[name="from.postalCode"]', '12345');
    await page.click('#get-rates-btn');
    await page.waitForSelector('input[name="from.postalCode"][aria-invalid="true"]');
    await page.fill('input[name="from.postalCode"]', 'M5V 3L9');
    await page.fill('input[name="from.email"]', 'sender@example.com');
    await page.check('input[name="from.residential"]');
    await ctl('rate-error');
    await page.click('#get-rates-btn');
    await page.waitForFunction(() => document.getElementById('notice').textContent.includes('PROVIDER_UNAVAILABLE'));
    const draft = await page.evaluate(() => JSON.parse(localStorage.getItem('shipping.rateDraft.v1')));
    assert.strictEqual(draft.operation, undefined);
    await page.reload({ waitUntil: 'networkidle' });
    assert.strictEqual(await page.inputValue('input[name="from.email"]'), 'sender@example.com');
    assert.strictEqual(await page.isChecked('input[name="from.residential"]'), true);
    await ctl('empty-rates');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden])');
    assert.strictEqual(await page.textContent('#rates-count'), '0 shipping options available');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);
    await page.click('#step-2 [data-back="1"]');

    // A stale response/error cannot replace a later input revision, even if the text changes back.
    await ctl('normal');
    let release;
    let started;
    const gate = new Promise(resolve => { release = resolve; });
    const held = new Promise(resolve => { started = resolve; });
    let attempts = 0;
    await page.route('**/api/quotes', async route => {
      assert.strictEqual(route.request().headers()['idempotency-key'], undefined);
      if (++attempts > 1) return route.continue();
      started();
      await gate;
      await route.fulfill({ status: 502, contentType: 'application/json', body: '{"code":"STALE_ERROR"}' });
    });
    await page.click('#get-rates-btn');
    await held;
    await page.fill('input[name="from.streetAddress2"]', 'Suite 101');
    assert.strictEqual(await page.isDisabled('#get-rates-btn'), false);
    await rates(page);
    await page.click('.rate-card:first-child');
    release();
    await page.waitForTimeout(100);
    assert.strictEqual(await page.isVisible('#notice'), false);
    assert.strictEqual(await page.getAttribute('.rate-card:first-child', 'aria-checked'), 'true');
    await page.unroute('**/api/quotes');
    await page.selectOption('#sort-select', 'time');
    assert.strictEqual(await page.textContent('.rate-card:first-child .rate-name'), 'Loomis');
    await page.selectOption('#sort-select', 'price');

    // Expired, unsubmitted offers cannot be confirmed; edits clear selection.
    await page.click('#step-2 [data-back="1"]');
    await page.route('**/api/quotes', async route => {
      const response = await route.fetch();
      const data = await response.json();
      data.quotes.forEach(q => { q.expiresAt = '2020-01-01T00:00:00Z'; });
      await route.fulfill({ response, json: data });
    });
    await rates(page);
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);
    assert.strictEqual((await ctl('stats')).createCalls, 0);
    await page.unroute('**/api/quotes');
    await page.click('#step-2 [data-back="1"]');
    await ctl('normal');
    await rates(page);
    await review(page);
    await ctl('create-reject');
    await page.click('#create-btn');
    await outcome(page, 'Shipment rejected');
    assert.strictEqual(await page.isVisible('#retry-send-btn'), false);
    await page.click('#unknown-new');

    // Unknown outcomes survive refresh and are never resent; manual review retains a query entry.
    await ctl('normal');
    await ctl('reset-counts');
    await rates(page);
    await review(page);
    await ctl('create-uncertain');
    await page.click('#create-btn');
    await outcome(page, 'Pending confirmation');
    await checkpoint(page, 'pending-confirmation');
    const unresolved = await active(page);
    assert.ok(unresolved.providerId && unresolved.providerQuoteId && unresolved.bookingId);
    assert.strictEqual(unresolved.key, undefined);
    await page.reload({ waitUntil: 'networkidle' });
    await outcome(page, 'Pending confirmation');
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    await ctl('manual-review');
    await page.click('#check-status-btn');
    await outcome(page, 'Manual review required');
    assert.strictEqual(await page.isVisible('#retry-send-btn'), false);
    assert.strictEqual(await page.isVisible('#unknown-new'), true);
    let reads = 0;
    page.on('request', request => {
      if (request.method() === 'GET' && request.url().includes('/api/bookings/')) reads++;
    });
    await page.waitForTimeout(1400);
    assert.strictEqual(reads, 0);
    await checkpoint(page, 'first-shipment');
    await page.click('#unknown-new');
    assert.ok(await page.locator('#saved-operations button').count());
    await page.locator('#saved-operations button').first().click();
    await outcome(page, 'Manual review required');
    await page.click('#unknown-new');

    // Keep the same account/workspace to verify retained operations, with separate trace segments.
    if (evidenceDir) {
      await context.tracing.stop({ path: traces[0] });
      await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
      traces.push(path.join(evidenceDir, 'browser-trace-2.zip'));
    }

    // Lost create response: GET lookup recovers the committed operation without sending again.
    await ctl('normal');
    await ctl('reset-counts');
    await rates(page);
    await review(page);
    await page.route('**/api/bookings', async route => {
      assert.strictEqual(route.request().headers()['idempotency-key'], undefined);
      const body = route.request().postDataJSON();
      assert.ok(body.providerId && body.providerQuoteId && body.shippingInput && body.displaySnapshot);
      assert.strictEqual(body.quoteId, undefined);
      await route.fetch();
      await route.abort('failed');
    });
    await page.click('#create-btn');
    await outcome(page, 'Pending confirmation');
    assert.ok(!(await active(page)).bookingId);
    await page.unroute('**/api/bookings');
    // A deliberately aborted response can keep browser network-idle accounting unsettled.
    // The recovered confirmed UI below is the readiness assertion, not network quiescence.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    await page.click('#new-shipment-btn');

    // No-row recovery is read-only; an explicit initial retry keeps the same provider identity.
    await ctl('normal');
    await ctl('reset-counts');
    await rates(page);
    await review(page);
    await page.route('**/api/bookings', route => route.abort('failed'));
    await page.click('#create-btn');
    await outcome(page, 'Pending confirmation');
    const unsaved = await active(page);
    await page.unroute('**/api/bookings');
    await page.click('#check-status-btn');
    await page.waitForSelector('#retry-initial-btn:not([hidden])');
    assert.strictEqual((await ctl('stats')).createCalls, 0);
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#retry-initial-btn:not([hidden])');
    assert.strictEqual((await ctl('stats')).createCalls, 0);
    await page.click('#retry-initial-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual((await active(page)).providerQuoteId, unsaved.providerQuoteId);
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    await page.click('#new-shipment-btn');

    // UI-only RETRYABLE fixture: Check status stays GET; only the explicit retry endpoint sends.
    await ctl('normal');
    await ctl('reset-counts');
    await rates(page);
    await review(page);
    let creates = 0;
    let retries = 0;
    let holdStatus = false;
    let statusHeld;
    let releaseStatus;
    const statusStarted = new Promise(resolve => { statusHeld = resolve; });
    const statusGate = new Promise(resolve => { releaseStatus = resolve; });
    await page.route('**/api/bookings', async route => {
      creates++;
      await route.fulfill({ status: 503, contentType: 'application/json', json: {
        code: 'SHIPMENT_SUBMISSION_NOT_DISPATCHED', operation: { id: 'retry-ui' }
      } });
    });
    await page.route('**/api/bookings/retry-ui', async route => {
      if (holdStatus) {
        statusHeld();
        await statusGate;
      }
      await route.fulfill({ json: { bookingId: 'retry-ui', status: 'RETRYABLE' } });
    });
    await page.route('**/api/bookings/retry-ui/retry', async route => {
      retries++;
      await route.fulfill({ json: { bookingId: 'retry-ui', status: 'CONFIRMED', shipmentId: 'ui-only' } });
    });
    await page.click('#create-btn');
    await outcome(page, 'Shipment not submitted');
    await page.click('#check-status-btn');
    await outcome(page, 'Shipment not submitted');
    assert.strictEqual(creates, 1);
    assert.strictEqual(retries, 0);
    holdStatus = true;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await statusStarted;
    await outcome(page, 'Shipment not submitted');
    assert.strictEqual(retries, 0);
    await page.click('#retry-send-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    releaseStatus();
    await page.waitForTimeout(100);
    assert.strictEqual(await page.isVisible('#success-view'), true, 'A late pre-retry GET cannot replace confirmation');
    assert.strictEqual(creates, 1);
    assert.strictEqual(retries, 1);
    await page.unroute('**/api/bookings');
    await page.unroute('**/api/bookings/retry-ui');
    await page.unroute('**/api/bookings/retry-ui/retry');
    await page.click('#new-shipment-btn');

    // Actual confirmed shipment: label pending/error/PDF/URL and all local actions remain separate.
    await ctl('normal');
    await ctl('reset-counts');
    await rates(page);
    await review(page);
    await page.click('#create-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('Label not ready'));
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual((await ctl('stats')).labelCalls, 1);
    await ctl('label-error');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('retrieval failed'));
    assert.strictEqual(await page.isDisabled('#label-download-btn'), true);
    await ctl('label-pdf');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => !document.getElementById('label-download-btn').disabled);
    const fixture = fs.readFileSync(path.join(__dirname, '../../src/test/resources/fixtures/shipping-label.pdf'));
    const href = await page.getAttribute('#label-link', 'href');
    assert.ok(href.startsWith('blob:'));
    const download = page.waitForEvent('download');
    await page.click('#label-download-btn');
    const file = await download;
    assert.strictEqual(file.suggestedFilename(), 'shipping-label-98765.pdf');
    assert.deepStrictEqual(fs.readFileSync(await file.path()), fixture);
    const email = await page.getAttribute('#label-email-link', 'href');
    assert.ok(decodeURIComponent(email).includes('Attach the downloaded PDF'));
    assert.ok(!email.includes('blob:'));
    await page.evaluate(() => { window.open = (...args) => { window.__opened = args; return null; }; });
    await page.click('#label-print-btn');
    assert.strictEqual(await page.evaluate(() => window.__opened[0]), href);
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
      Object.defineProperty(navigator, 'share', { configurable: true, value: async data => {
        window.__shared = { name: data.files[0].name, size: data.files[0].size, url: data.url || null };
      } });
    });
    await page.click('#label-share-btn');
    assert.deepStrictEqual(await page.evaluate(() => window.__shared), {
      name: 'shipping-label-98765.pdf', size: fixture.length, url: null
    });
    let downloads = 0;
    page.on('download', () => downloads++);
    await page.evaluate(() => Object.defineProperty(navigator, 'share', { configurable: true, value: async () => {
      throw new DOMException('cancel', 'AbortError');
    } }));
    await page.click('#label-share-btn');
    await page.waitForTimeout(80);
    assert.strictEqual(downloads, 0);
    await page.evaluate(() => Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => false }));
    const fallback = page.waitForEvent('download');
    await page.click('#label-share-btn');
    assert.deepStrictEqual(fs.readFileSync(await (await fallback).path()), fixture);
    assert.strictEqual((await ctl('stats')).labelCalls, 3);
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.ok(!(await page.evaluate(() => localStorage.getItem('shipping.activeBooking.v2'))).includes('blob:'));
    await checkpoint(page, 'label-available');
    const confirmed = await active(page);
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual(await page.isDisabled('#label-download-btn'), true);
    assert.strictEqual((await ctl('stats')).labelCalls, 3);
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => !document.getElementById('label-download-btn').disabled);
    await ctl('label-url');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-link').href === 'https://labels.example.com/label.pdf');
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    // Upgrade old local entries: bookingId is usable; internal quoteId alone never becomes a provider ID.
    await page.evaluate(id => {
      localStorage.removeItem('shipping.activeBooking.v2');
      sessionStorage.setItem('shipping.activeBooking.v1', JSON.stringify({ userId: 'guest-user', bookingId: id,
        quoteId: 'internal-only', key: 'old-key', labelAttempted: true }));
    }, confirmed.bookingId);
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#success-view:not([hidden])');
    assert.ok(!(await active(page)).providerQuoteId);
    await page.evaluate(() => {
      localStorage.removeItem('shipping.activeBooking.v2');
      sessionStorage.setItem('shipping.activeBooking.v1', JSON.stringify({ userId: 'guest-user', quoteId: 'internal-only', key: 'old-key' }));
    });
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.getElementById('unknown-id').textContent.includes('no usable recovery identity'));
    assert.strictEqual(await page.isVisible('#retry-initial-btn'), false);
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    // Session expiry never replays a write, and switching account/logout clears private local data.
    await page.click('#unknown-new');
    await page.route('**/api/quotes', route => route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED' } }));
    await page.click('#get-rates-btn');
    await page.waitForSelector('#login-modal[open]');
    await page.unroute('**/api/quotes');
    await signIn(page, 'beta');
    assert.strictEqual(await page.isVisible('#step-1'), true);
    assert.strictEqual(await page.locator('#saved-operations button').count(), 0);
    assert.strictEqual(await page.evaluate(() => localStorage.getItem('shipping.activeBooking.v2')), null);
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }), page.click('#user-avatar')]);
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(await page.evaluate(() => localStorage.getItem('shipping.rateDraft.v1')), null);
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.activeBooking.v1')), null);
    assert.ok(!(await context.cookies()).some(cookie => cookie.name === 'shipping_guest_token'));
    await page.goto(appUrl + '?mock=1', { waitUntil: 'networkidle' });
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(await page.locator('#login-preview').count(), 0);
    await checkpoint(page, 'journey-complete');
  } catch (error) {
    if (evidenceDir) await page.screenshot({ path: path.join(evidenceDir, 'failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    try {
      if (traces.length) await context.tracing.stop({ path: traces[traces.length - 1] });
    } finally {
      await browser.close();
    }
  }
})();
