const { chromium } = require('playwright');
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const [appUrl, controlUrl] = process.argv.slice(2);
async function ctl(mode) {
  const res = await fetch(`${controlUrl}?mode=${mode}`);
  return res.json();
}
async function signIn(page) {
  await page.waitForSelector('#login-modal[open]');
  await page.fill('#login-username', 'alpha');
  await page.fill('#login-password', 'test-alpha-password');
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle' }),
    page.click('#login-submit')
  ]);
  await page.waitForFunction(() => !!sessionStorage.getItem('shipping.accountSession.v1'));
}
(async () => {
  const browser = await chromium.launch();
  try {
    let context = await browser.newContext();
    let page = await context.newPage();
    page.on('dialog', dialog => dialog.accept());

    // 1. Default Backend Mode, Account Login & Step 1
    await ctl('normal');
    await ctl('reset-counts');
    await page.goto(appUrl, { waitUntil: 'networkidle' });
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.accountSession.v1')), null);
    assert.strictEqual((await ctl('stats')).rateCalls, 0);
    await page.fill('#login-username', 'alpha');
    await page.fill('#login-password', 'wrong-password');
    await page.click('#login-submit');
    await page.waitForSelector('#login-error:not([hidden])');
    assert.ok((await page.textContent('#login-error')).includes('Invalid username or password'));
    await signIn(page);
    assert.strictEqual(await page.locator('#mock-toggle').count(), 0);
    assert.strictEqual(await page.locator('#login-preview').count(), 0);
    assert.strictEqual(await page.textContent('#mode-indicator'), 'Backend API');
    assert.strictEqual(await page.isVisible('#step-1'), true);
    assert.strictEqual(await page.isVisible('#step-2'), false);

    // Verify 2-hour fingerprint-bound account token was issued and unauthenticated requests are blocked (401)
    await page.waitForFunction(() => !!sessionStorage.getItem('shipping.accountSession.v1'));
    const guestSession = await page.evaluate(() => JSON.parse(sessionStorage.getItem('shipping.accountSession.v1')));
    assert.ok(guestSession.token && guestSession.token.includes('.'), 'Account token must be signed');
    assert.ok(guestSession.userId && guestSession.userId === 'guest-user', 'Account userId must match configured identity');
    assert.ok(guestSession.fingerprint && guestSession.fingerprint.startsWith('fp-'), 'Client fingerprint must be bound');
    const unauthStatus = await page.evaluate(async () => {
      const res = await fetch('/api/quotes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}'
      });
      return res.status;
    });
    assert.strictEqual(unauthStatus, 401, 'Unauthenticated API call without an account token must return 401');

    // 2. Client-side & server-side field validation across 3-Tab workstation + input retention
    // Empty recipient field while on Sender tab must switch to and display Recipient tab (not Package tab)
    await page.click('#detail-tab-btn-2');
    await page.fill('input[name="to.city"]', '');
    await page.click('#detail-tab-btn-1');
    await page.click('#get-rates-btn');
    assert.strictEqual(await page.isVisible('[data-tab-pane="2"]'), true, 'Recipient tab pane must be visible on recipient error');
    assert.strictEqual(await page.isVisible('[data-tab-pane="3"]'), false, 'Package tab pane must stay hidden');
    await page.fill('input[name="to.city"]', 'Halifax');

    await page.click('#detail-tab-btn-3');
    await page.fill('input[name="weight"]', '0');
    await page.click('#get-rates-btn');
    assert.strictEqual(await page.isVisible('#notice'), true);
    assert.strictEqual((await ctl('stats')).rateCalls, 0);

    await page.fill('input[name="weight"]', '5');
    await page.click('#detail-tab-btn-1');
    await page.fill('input[name="from.postalCode"]', '12345');
    await page.click('#get-rates-btn');
    await page.waitForSelector('input[name="from.postalCode"][aria-invalid="true"]');
    assert.strictEqual(await page.inputValue('input[name="from.attention"]'), 'John');
    assert.strictEqual((await ctl('stats')).rateCalls, 0);

    await page.fill('input[name="from.postalCode"]', 'M5V 3L9');
    await page.fill('input[name="from.email"]', 'sender@example.com');
    await page.check('input[name="from.residential"]');

    // 3. Provider error, 24h draft restoration across reload, retry under same requestId & NO_RATES empty state
    await ctl('rate-error');
    await page.click('#get-rates-btn');
    await page.waitForFunction(() => !document.getElementById('notice').hidden && document.getElementById('notice').textContent.includes('PROVIDER_UNAVAILABLE'));
    assert.strictEqual(await page.isVisible('#step-1'), true);
    const failedDraft = await page.evaluate(() => JSON.parse(localStorage.getItem('shipping.rateDraft.v1')));
    assert.ok(failedDraft && failedDraft.operation && failedDraft.operation.key, 'Draft must retain rate Idempotency-Key');
    assert.ok(failedDraft.operation.requestId, 'Draft must capture failed operation requestId');

    // Reloading restores form fields and the original rate operation key from the 24h local draft
    await page.reload({ waitUntil: 'networkidle' });
    assert.strictEqual(await page.inputValue('input[name="from.email"]'), 'sender@example.com');
    assert.strictEqual(await page.isChecked('input[name="from.residential"]'), true);
    const restoredDraft = await page.evaluate(() => JSON.parse(localStorage.getItem('shipping.rateDraft.v1')));
    assert.strictEqual(restoredDraft.operation.key, failedDraft.operation.key, 'Reload must preserve rate Idempotency-Key');

    // Retrying after failure reuses the same key and requestId
    await ctl('empty-rates');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden])');
    assert.strictEqual(await page.textContent('#rates-count'), '0 shipping options available');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);
    const retriedDraft = await page.evaluate(() => JSON.parse(localStorage.getItem('shipping.rateDraft.v1')));
    assert.strictEqual(retriedDraft.operation.key, failedDraft.operation.key);
    assert.strictEqual(retriedDraft.operation.requestId, failedDraft.operation.requestId);
    await page.click('#step-2 [data-back="1"]');
    await page.waitForSelector('#step-1:not([hidden])');
    assert.strictEqual(await page.inputValue('input[name="from.email"]'), 'sender@example.com');
    assert.strictEqual(await page.isChecked('input[name="from.residential"]'), true);

    // 4. Editing shipment details starts a new rate request and retrieves quotes
    await page.fill('input[name="from.streetAddress2"]', 'Suite 200');
    await ctl('normal');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    assert.strictEqual(await page.textContent('#summary-from'), 'Toronto, ON');
    assert.strictEqual(await page.textContent('#summary-to'), 'Halifax, NS');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);

    let firstRateName = await page.textContent('.rate-card:first-child .rate-name');
    assert.strictEqual(firstRateName, 'Carrier');
    await page.selectOption('#sort-select', 'time');
    firstRateName = await page.textContent('.rate-card:first-child .rate-name');
    assert.strictEqual(firstRateName, 'Loomis');
    await page.selectOption('#sort-select', 'price');

    await page.focus('.rate-card:first-child');
    await page.keyboard.press('Enter');
    assert.strictEqual(await page.getAttribute('.rate-card:first-child', 'aria-checked'), 'true');
    assert.strictEqual(await page.isDisabled('#review-btn'), false);

    // Edit details invalidates previous selection and requires fresh rates
    await page.click('#step-2 [data-back="1"]');
    await page.click('#detail-tab-btn-3');
    await page.fill('input[name="weight"]', '6');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);
    assert.ok((await page.textContent('#summary-package')).includes('6 lb'));

    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    await page.waitForSelector('#step-3:not([hidden]) #review-view:not([hidden])');
    assert.strictEqual(await page.textContent('#review-service'), 'Carrier — Ground');
    assert.ok((await page.textContent('#cost-total')).includes('27.35'));

    // 5. Quote expiry rejection requires fresh rates
    await ctl('expire-quotes');
    await page.click('#create-btn');
    await page.waitForFunction(() => !document.getElementById('notice').hidden && document.getElementById('notice').textContent.includes('Quote is no longer available'));
    assert.strictEqual((await ctl('stats')).createCalls, 0);

    // 6. Definite rejection (422) shows rejection view and returns to Step 1
    await page.click('#review-view [data-back="2"]');
    await page.click('#step-2 [data-back="1"]');
    await ctl('normal');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    await ctl('create-reject');
    await page.click('#create-btn');
    await page.waitForSelector('#unknown-view:not([hidden])');
    assert.strictEqual(await page.textContent('#outcome-title'), 'Shipment rejected');
    assert.strictEqual(await page.isVisible('#check-status-btn'), false);
    await page.click('#unknown-new');
    await page.waitForSelector('#step-1:not([hidden])');

    // 7. Uncertain outcome (202) preserves operation across reload without resubmitting
    await ctl('normal');
    await ctl('reset-counts');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    await ctl('create-uncertain');
    await page.click('#create-btn');
    await page.waitForSelector('#unknown-view:not([hidden])');
    assert.strictEqual(await page.textContent('#outcome-title'), 'Pending confirmation');
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#unknown-view:not([hidden])');
    assert.strictEqual(await page.textContent('#outcome-title'), 'Pending confirmation');
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    // 7b. Unsupported recovery becomes manual review and stops background polling.
    let statusReads = 0;
    page.on('request', request => {
      if (request.method() === 'GET' && /\/api\/bookings\/[^/]+$/.test(new URL(request.url()).pathname)) statusReads++;
    });
    await ctl('manual-review');
    await page.click('#check-status-btn');
    await page.waitForFunction(() => document.getElementById('outcome-title').textContent === 'Manual review required');
    assert.ok((await page.textContent('#outcome-explanation')).includes('Do not submit it again'));
    assert.ok((await page.textContent('#unknown-id')).includes('AUTHORITATIVE_LOOKUP_UNSUPPORTED'));
    assert.strictEqual(await page.isVisible('#check-status-btn'), false);
    assert.strictEqual(await page.isVisible('#unknown-new'), false);
    await page.click('.stepper .step[data-step="1"]');
    assert.strictEqual(await page.textContent('#outcome-title'), 'Manual review required');
    assert.strictEqual(await page.isVisible('#check-status-btn'), false);
    const readsAfterManual = statusReads;
    await page.waitForTimeout(2200);
    assert.strictEqual(statusReads, readsAfterManual, 'Manual review must stop polling');
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.getElementById('outcome-title').textContent === 'Manual review required');
    await page.waitForTimeout(300);
    const readsAfterReload = statusReads;
    await page.waitForTimeout(2200);
    assert.strictEqual(statusReads, readsAfterReload, 'Reload may read once but must not resume polling');
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    // Use an independent browser context for a genuinely separate shipment journey.
    await context.close();
    context = await browser.newContext();
    page = await context.newPage();
    await page.goto(appUrl, { waitUntil: 'networkidle' });
    await signIn(page);

    // 8. Happy path creation + delayed label (1 automatic attempt -> 202 -> manual Retry label -> PDF available)
    await ctl('normal');
    await ctl('reset-counts');

    // 8.0 Quote response ordering, non-polling in-progress handling, and expired selection protection
    const quotePostMatcher = url => url.pathname === '/api/quotes';

    // (a) Editing input while a rate request is in flight immediately re-enables the button and ignores the obsolete response/error
    let releaseObsoletePost = null;
    const obsoletePostStarted = new Promise(resolve => { releaseObsoletePost = resolve; });
    let allowObsoleteFinish = null;
    const obsoleteGate = new Promise(resolve => { allowObsoleteFinish = resolve; });
    await page.route(quotePostMatcher, async route => {
      releaseObsoletePost();
      await obsoleteGate;
      await route.fulfill({
        status: 502,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'PROVIDER_UNAVAILABLE', message: 'PROVIDER UNAVAILABLE', fieldErrors: [] })
      });
    });
    await page.click('#get-rates-btn');
    await obsoletePostStarted;
    assert.strictEqual(await page.isDisabled('#get-rates-btn'), true, 'Button must be disabled while request is in flight');
    await page.fill('input[name="from.streetAddress2"]', 'Suite 101');
    assert.strictEqual(await page.isDisabled('#get-rates-btn'), false, 'Editing input must immediately re-enable Get Rates button');
    allowObsoleteFinish();
    await page.waitForTimeout(80);
    assert.strictEqual(await page.isVisible('#step-1'), true, 'Obsolete response must not leave Step 1');
    assert.strictEqual(await page.isVisible('#notice'), false, 'Obsolete error must not show a notice after input edit');
    await page.unroute(quotePostMatcher);

    // (b) In-progress (RATING / CONTENDED) response stops loading without auto-polling, keeps page, and allows user to click again
    let quoteStatusPolls = 0;
    await page.route('**/api/quotes/contended-rate-req', async route => {
      quoteStatusPolls++;
      await route.abort();
    });
    let quotePostAttempt = 0;
    let notifyFirstPostHeld = null;
    const firstPostHeld = new Promise(resolve => { notifyFirstPostHeld = resolve; });
    let releaseLateRatingPost = null;
    const lateRatingGate = new Promise(resolve => { releaseLateRatingPost = resolve; });
    await page.route(quotePostMatcher, async route => {
      quotePostAttempt++;
      if (quotePostAttempt === 1) {
        await route.fulfill({
          status: 202,
          contentType: 'application/json',
          headers: { Location: '/api/quotes/contended-rate-req', 'Retry-After': '2' },
          body: JSON.stringify({
            requestId: 'contended-rate-req',
            state: 'CONTENDED',
            shippingDate: '2026-10-28',
            quotes: []
          })
        });
      } else if (quotePostAttempt === 2) {
        notifyFirstPostHeld();
        await lateRatingGate;
        await route.fulfill({
          status: 202,
          contentType: 'application/json',
          headers: { Location: '/api/quotes/contended-rate-req', 'Retry-After': '2' },
          body: JSON.stringify({
            requestId: 'contended-rate-req',
            state: 'RATING',
            shippingDate: '2026-10-28',
            quotes: []
          })
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            requestId: 'contended-rate-req',
            state: 'READY',
            shippingDate: '2026-10-28',
            quotes: [{
              quoteId: 'contended-quote-1',
              carrierId: '14',
              carrierName: 'Carrier',
              serviceId: '1',
              serviceName: 'Ground',
              total: { amount: '27.35', currency: 'CAD' },
              transitDays: 2,
              expiresAt: new Date(Date.now() + 600000).toISOString()
            }]
          })
        });
      }
    });
    await page.click('#get-rates-btn');
    await page.waitForFunction(() => !document.getElementById('notice').hidden && document.getElementById('notice').textContent.includes('Rates are still being retrieved'));
    assert.strictEqual(quoteStatusPolls, 0, 'Quote must not auto-poll GET /api/quotes/{id}');
    assert.strictEqual(await page.isDisabled('#get-rates-btn'), false, 'Get Rates button must be re-enabled after in-progress response');
    assert.strictEqual(await page.isVisible('#step-1'), true);

    // (c) Start Attempt 2 (held in flight), re-trigger Attempt 3 with same signature -> READY, then release Attempt 2's late RATING
    await page.click('#get-rates-btn');
    await firstPostHeld;
    await page.fill('input[name="from.streetAddress2"]', 'Suite 102');
    await page.fill('input[name="from.streetAddress2"]', 'Suite 101');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    assert.strictEqual(await page.textContent('.rate-card:first-child .rate-name'), 'Carrier');
    await page.click('.rate-card:first-child');
    assert.strictEqual(await page.isDisabled('#review-btn'), false);
    releaseLateRatingPost();
    await page.waitForTimeout(100);
    assert.strictEqual(await page.isVisible('#step-2'), true, 'Late RATING response must not leave Step 2');
    assert.strictEqual(await page.getAttribute('.rate-card:first-child', 'aria-checked'), 'true', 'Late RATING must not clear selection');
    assert.strictEqual(await page.isDisabled('#review-btn'), false, 'Late RATING must not disable review button');
    assert.strictEqual(await page.isVisible('#notice'), false, 'Late RATING must not display a stale in-progress notice');
    await page.unroute(quotePostMatcher);
    await page.unroute('**/api/quotes/contended-rate-req');
    await page.click('#step-2 [data-back="1"]');
    await page.waitForSelector('#step-1:not([hidden])');
    await page.fill('input[name="from.streetAddress2"]', 'Suite 300');

    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');

    // 8a. If a RETRYABLE booking's quote expires before retry, clear the unsent submission and return to Step 1 for fresh rates.
    const bookingPostMatcher = url => url.pathname === '/api/bookings';
    let expiredRetryCalls = 0;
    await page.route(bookingPostMatcher, async route => {
      expiredRetryCalls++;
      if (expiredRetryCalls === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          headers: { 'Retry-After': '2' },
          body: JSON.stringify({
            code: 'SHIPMENT_SUBMISSION_NOT_DISPATCHED',
            operation: { id: 'expired-retry-booking', statusUrl: '/api/bookings/expired-retry-booking' }
          })
        });
      } else {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'QUOTE_EXPIRED',
            message: 'QUOTE EXPIRED',
            operation: { id: 'expired-retry-booking', statusUrl: '/api/bookings/expired-retry-booking' }
          })
        });
      }
    });
    await page.click('#create-btn');
    await page.waitForFunction(() => document.getElementById('outcome-title').textContent === 'Shipment not submitted');
    await page.click('#check-status-btn');
    await page.waitForSelector('#step-1:not([hidden])');
    assert.ok((await page.textContent('#notice')).includes('Quote is no longer available'));
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.activeBooking.v1')), null, 'Expired unsent retry must clear active booking');
    await page.unroute(bookingPostMatcher);

    // Re-fetch fresh rates and test normal RETRYABLE reload + stale restore GET race protection
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');

    // Exercise the retry UI:
    // - Attempt 1: 503 SHIPMENT_SUBMISSION_NOT_DISPATCHED -> RETRYABLE
    // - Attempt 2 (before reload): 202 CONTENDED -> polls GET /api/bookings/{id} -> discovers RETRYABLE
    // - Reload: holds restore GET in flight while Attempt 3 POST confirms, then releases stale restore GET
    const retryRequests = [];
    let retryStatusReads = 0;
    let holdRestoreGet = false;
    let releaseStaleRestoreGet = null;
    const staleRestoreGetHeld = new Promise(resolve => { releaseStaleRestoreGet = resolve; });
    let unblockRestoreGet = null;
    const restoreGetGate = new Promise(resolve => { unblockRestoreGet = resolve; });
    await page.route('**/api/bookings/retryable-ui-booking', async route => {
      retryStatusReads++;
      if (holdRestoreGet) {
        releaseStaleRestoreGet();
        await restoreGetGate;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ bookingId: 'retryable-ui-booking', status: 'RETRYABLE' })
      });
    });
    await page.route(bookingPostMatcher, async route => {
      const request = route.request();
      retryRequests.push({ key: request.headers()['idempotency-key'], body: request.postDataJSON() });
      if (retryRequests.length === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          headers: { 'Retry-After': '2' },
          body: JSON.stringify({
            code: 'SHIPMENT_SUBMISSION_NOT_DISPATCHED',
            operation: { id: 'retryable-ui-booking', statusUrl: '/api/bookings/retryable-ui-booking' }
          })
        });
      } else if (retryRequests.length === 2) {
        await route.fulfill({
          status: 202,
          contentType: 'application/json',
          headers: { Location: '/api/bookings/retryable-ui-booking', 'Retry-After': '2' },
          body: JSON.stringify({
            bookingId: 'retryable-ui-booking',
            status: 'CONTENDED',
            shipmentId: null,
            trackingNumber: null,
            labelStatus: null,
            labelUrl: null,
            errorCode: null,
            statusUrl: '/api/bookings/retryable-ui-booking'
          })
        });
      } else {
        await route.continue();
      }
    });
    await page.click('#create-btn');
    await page.waitForFunction(() => document.getElementById('outcome-title').textContent === 'Shipment not submitted');
    assert.strictEqual((await ctl('stats')).createCalls, 0);
    assert.strictEqual(await page.textContent('#check-status-btn'), 'Retry shipment');

    // Attempt 2: returns 202 CONTENDED -> polls GET /api/bookings/retryable-ui-booking -> restores Retry shipment
    await page.click('#check-status-btn');
    await page.waitForFunction(() => document.getElementById('check-status-btn').textContent === 'Retry shipment' && !document.getElementById('check-status-btn').disabled);
    assert.strictEqual(retryRequests.length, 2);
    assert.strictEqual(retryStatusReads, 1, 'CONTENDED retry response must poll GET /api/bookings/{id} to discover RETRYABLE');
    assert.strictEqual((await ctl('stats')).createCalls, 0);

    // Reload and hold the restore GET open until after Attempt 3 confirms the shipment
    holdRestoreGet = true;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await staleRestoreGetHeld;
    await page.waitForFunction(() => document.getElementById('outcome-title').textContent === 'Shipment not submitted');
    assert.strictEqual(retryRequests.length, 2, 'Reload must not automatically resubmit');
    assert.strictEqual(retryStatusReads, 2, 'Restore should check current status once');
    await page.click('#check-status-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    // Now let the delayed restore GET return RETRYABLE after the retry POST already confirmed the booking
    unblockRestoreGet();
    await page.waitForTimeout(150);
    assert.strictEqual(await page.isVisible('#success-view'), true, 'Stale restore GET must not overwrite confirmed view');
    assert.strictEqual(await page.evaluate(() => JSON.parse(sessionStorage.getItem('shipping.activeBooking.v1')).status), 'CONFIRMED');
    assert.strictEqual(retryRequests.length, 3);
    assert.ok(retryRequests[0].key);
    for (const request of retryRequests) {
      assert.strictEqual(request.key, retryRequests[0].key, 'Retry must retain the original key');
      assert.deepStrictEqual(request.body, retryRequests[0].body, 'Retry must retain the selected quote');
    }
    await page.unroute(bookingPostMatcher);
    await page.unroute('**/api/bookings/retryable-ui-booking');
    assert.strictEqual(await page.textContent('#result-id'), '98765');
    assert.strictEqual(await page.textContent('#result-tracking'), 'TRK-98765');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('Label not ready; use Retry label later.'));
    assert.strictEqual(await page.textContent('#success-view h2'), 'Shipment confirmed');
    assert.strictEqual(await page.isVisible('#label-link'), true);
    assert.strictEqual(await page.getAttribute('#label-link', 'aria-disabled'), 'true');
    for (const id of ['label-download-btn', 'label-print-btn', 'label-share-btn']) {
      assert.strictEqual(await page.isDisabled('#' + id), true);
    }
    assert.strictEqual(await page.getAttribute('#label-email-link', 'aria-disabled'), 'true');
    assert.strictEqual(await page.isVisible('#refresh-label-btn'), true);
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual((await ctl('stats')).labelCalls, 1);

    await ctl('label-error');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('retrieval failed'));
    assert.strictEqual(await page.isDisabled('#label-download-btn'), true);
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    await ctl('label-pdf');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('Label available.'));
    assert.strictEqual(await page.isVisible('#label-link'), true);
    assert.strictEqual(await page.textContent('#refresh-label-btn'), 'Reload label');
    assert.ok((await page.getAttribute('#label-link', 'href')).startsWith('blob:'));
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual((await ctl('stats')).labelCalls, 3);

    // All ready actions reuse the bytes already read for this shipment.
    const fixture = fs.readFileSync(path.join(__dirname, '../../src/test/resources/fixtures/shipping-label.pdf'));
    const labelHref = await page.getAttribute('#label-link', 'href');
    assert.strictEqual(await page.getAttribute('#label-link', 'aria-disabled'), 'false');
    const downloadPromise = page.waitForEvent('download');
    await page.click('#label-download-btn');
    const downloaded = await downloadPromise;
    assert.strictEqual(downloaded.suggestedFilename(), 'shipping-label-98765.pdf');
    assert.deepStrictEqual(fs.readFileSync(await downloaded.path()), fixture);
    const email = await page.getAttribute('#label-email-link', 'href');
    assert.ok(email.startsWith('mailto:'));
    assert.ok(decodeURIComponent(email).includes('Attach the downloaded PDF'));
    assert.ok(!email.includes('blob:'));

    await page.evaluate(() => {
      window.__openedLabels = [];
      window.open = (...args) => { window.__openedLabels.push(args); return null; };
    });
    await page.click('#label-print-btn');
    assert.strictEqual(await page.evaluate(() => window.__openedLabels[0][0]), labelHref);
    assert.ok((await page.textContent('#notice')).includes('print button in the PDF viewer'));

    await page.evaluate(() => {
      Object.defineProperty(navigator, 'canShare', { configurable: true, value: data => data.files?.length === 1 });
      Object.defineProperty(navigator, 'share', { configurable: true, value: async data => {
        window.__sharedLabel = { name: data.files[0].name, type: data.files[0].type, size: data.files[0].size, url: data.url || null };
      } });
    });
    await page.click('#label-share-btn');
    assert.deepStrictEqual(await page.evaluate(() => window.__sharedLabel), {
      name: 'shipping-label-98765.pdf', type: 'application/pdf', size: fixture.length, url: null
    });
    let downloadCount = 0;
    page.on('download', () => downloadCount++);
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'share', { configurable: true, value: async () => {
        throw new DOMException('Share cancelled', 'AbortError');
      } });
    });
    await page.click('#label-share-btn');
    await page.waitForTimeout(100);
    assert.strictEqual(downloadCount, 0, 'Cancelling share must not download a file');
    await page.evaluate(() => Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => false }));
    const fallbackPromise = page.waitForEvent('download');
    await page.click('#label-share-btn');
    assert.deepStrictEqual(fs.readFileSync(await (await fallbackPromise).path()), fixture);
    assert.ok((await page.textContent('#notice')).includes('share it from your downloads'));
    assert.strictEqual((await ctl('stats')).labelCalls, 3);
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.activeBooking.v1').includes('blob:')), false);

    // Reload restores the booking, not expired in-memory blob URLs; explicitly reload the label.
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual(await page.isDisabled('#label-download-btn'), true);
    assert.strictEqual((await ctl('stats')).labelCalls, 3);
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => !document.getElementById('label-download-btn').disabled);
    assert.strictEqual((await ctl('stats')).labelCalls, 4);

    // URL-only providers remain usable without CORS-dependent cross-origin PDF fetching.
    await ctl('label-url');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-link').href === 'https://labels.example.com/label.pdf');
    await page.evaluate(() => {
      window.__openedLabels = [];
      window.open = (...args) => { window.__openedLabels.push(args); return null; };
    });
    await page.click('#label-download-btn');
    assert.strictEqual(await page.evaluate(() => window.__openedLabels[0][0]), 'https://labels.example.com/label.pdf');
    assert.ok((await page.textContent('#label-action-help')).includes('Provider-hosted label'));
    assert.strictEqual((await ctl('stats')).createCalls, 1);

    await page.click('#new-shipment-btn');
    await page.waitForSelector('#step-1:not([hidden])');
    assert.strictEqual(await page.getAttribute('#label-link', 'href'), '#');
    assert.strictEqual(await page.isDisabled('#label-download-btn'), true);

    // 401 prompts explicit re-authentication without anonymously refreshing or re-POSTing.
    let expiredPosts = 0;
    await page.route('**/api/quotes', async route => {
      expiredPosts++;
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"UNAUTHORIZED"}' });
    });
    await page.click('#get-rates-btn');
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(expiredPosts, 1);
    await page.unroute('**/api/quotes');
    await signIn(page);

    // An expired create session keeps its original operation identity for explicit same-key recovery.
    await ctl('normal');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    let expiredCreates = 0;
    await page.route('**/api/bookings', async route => {
      expiredCreates++;
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"UNAUTHORIZED"}' });
    });
    const createsBeforeReauth = (await ctl('stats')).createCalls;
    await page.click('#create-btn');
    await page.waitForSelector('#login-modal[open]');
    const expiredActive = await page.evaluate(() => JSON.parse(sessionStorage.getItem('shipping.activeBooking.v1')));
    assert.ok(expiredActive.key && expiredActive.quoteId);
    assert.strictEqual(expiredActive.userId, 'guest-user');
    assert.strictEqual(expiredCreates, 1);
    // Old preview entry points must not erase a real pending operation after session expiry.
    await page.evaluate(() => localStorage.setItem('shipping.mockMode.v1', 'true'));
    await page.goto(appUrl + '?mock=1', { waitUntil: 'networkidle' });
    await page.waitForSelector('#login-modal[open]');
    const afterLegacyPreview = await page.evaluate(() => JSON.parse(sessionStorage.getItem('shipping.activeBooking.v1')));
    assert.strictEqual(afterLegacyPreview.key, expiredActive.key);
    assert.strictEqual(afterLegacyPreview.quoteId, expiredActive.quoteId);
    await page.unroute('**/api/bookings');
    await signIn(page);
    await page.waitForSelector('#unknown-view:not([hidden])');
    assert.strictEqual((await ctl('stats')).createCalls, createsBeforeReauth, 'Login must not replay an unresolved write');
    const restoredActive = await page.evaluate(() => JSON.parse(sessionStorage.getItem('shipping.activeBooking.v1')));
    assert.strictEqual(restoredActive.key, expiredActive.key);
    await page.click('#check-status-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual((await ctl('stats')).createCalls, createsBeforeReauth + 1);
    await page.click('#new-shipment-btn');

    // A different account cannot restore the prior identity's drafts or active booking.
    await page.evaluate(() => {
      localStorage.setItem('shipping.rateDraft.v1', JSON.stringify({
        savedAt: Date.now(), mock: false, userId: 'user-beta', fields: { 'from.attention': 'FOREIGN DRAFT' }
      }));
      sessionStorage.setItem('shipping.activeBooking.v1', JSON.stringify({
        userId: 'user-beta', key: 'foreign-key', quoteId: 'foreign-quote', bookingId: 'foreign-booking', mock: false
      }));
    });
    await page.reload({ waitUntil: 'networkidle' });
    assert.notStrictEqual(await page.inputValue('input[name="from.attention"]'), 'FOREIGN DRAFT');
    assert.strictEqual(await page.isVisible('#step-1'), true);

    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle' }),
      page.click('#user-avatar')
    ]);
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.accountSession.v1')), null);
    assert.strictEqual(await page.evaluate(() => sessionStorage.getItem('shipping.activeBooking.v1')), null);
    assert.strictEqual(await page.evaluate(() => localStorage.getItem('shipping.rateDraft.v1')), null);
    const cookies = await context.cookies();
    assert.ok(!cookies.some(cookie => cookie.name === 'shipping_guest_token'));

    // Legacy preview query parameters and stored mode flags cannot bypass sign-in.
    await page.evaluate(() => {
      localStorage.setItem('shipping.mockMode.v1', 'true');
      localStorage.setItem('shipping.rateDraft.v1', JSON.stringify({
        savedAt: Date.now(), mock: true, userId: 'guest-user', fields: { 'from.attention': 'OLD DEMO' }
      }));
    });
    await page.goto(appUrl + '?mock=1', { waitUntil: 'networkidle' });
    await page.waitForSelector('#login-modal[open]');
    assert.strictEqual(await page.textContent('#mode-indicator'), 'Backend API');
    assert.strictEqual(await page.locator('#login-preview').count(), 0);
    assert.strictEqual(await page.evaluate(() => localStorage.getItem('shipping.rateDraft.v1')), null);
    assert.strictEqual((await ctl('stats')).createCalls, 2);
  } finally {
    await browser.close();
  }
})();
