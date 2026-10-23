const { chromium } = require('playwright');
const assert = require('assert');
const [appUrl, controlUrl] = process.argv.slice(2);
async function ctl(mode) {
  const res = await fetch(`${controlUrl}?mode=${mode}`);
  return res.json();
}
(async () => {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('dialog', dialog => dialog.accept());

    // 1. Default Backend Mode & Step 1
    await ctl('normal');
    await ctl('reset-counts');
    await page.goto(appUrl, { waitUntil: 'networkidle' });
    assert.strictEqual(await page.isChecked('#mock-toggle'), false, 'Should default to backend mode');
    assert.strictEqual(await page.textContent('#mode-indicator'), 'Backend API');
    assert.strictEqual(await page.isVisible('#step-1'), true);
    assert.strictEqual(await page.isVisible('#step-2'), false);

    // 2. Client-side & server-side field validation + input retention
    await page.fill('input[name="weight"]', '0');
    await page.click('#get-rates-btn');
    assert.strictEqual(await page.isVisible('#notice'), true);
    assert.strictEqual((await ctl('stats')).rateCalls, 0);

    await page.fill('input[name="weight"]', '5');
    await page.fill('input[name="from.postalCode"]', '12345');
    await page.click('#get-rates-btn');
    await page.waitForSelector('input[name="from.postalCode"][aria-invalid="true"]');
    assert.strictEqual(await page.inputValue('input[name="from.attention"]'), 'John');
    assert.strictEqual((await ctl('stats')).rateCalls, 0);

    await page.fill('input[name="from.postalCode"]', 'M5V 3L9');
    await page.fill('input[name="from.email"]', 'sender@example.com');
    await page.check('input[name="from.residential"]');

    // 3. Provider error & NO_RATES empty state + backward navigation input preservation
    await ctl('rate-error');
    await page.click('#get-rates-btn');
    await page.waitForFunction(() => !document.getElementById('notice').hidden && document.getElementById('notice').textContent.includes('PROVIDER_UNAVAILABLE'));
    assert.strictEqual(await page.isVisible('#step-1'), true);

    await ctl('empty-rates');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden])');
    assert.strictEqual(await page.textContent('#rates-count'), '0 shipping options available');
    assert.strictEqual(await page.isDisabled('#review-btn'), true);
    await page.click('#step-2 [data-back="1"]');
    await page.waitForSelector('#step-1:not([hidden])');
    assert.strictEqual(await page.inputValue('input[name="from.email"]'), 'sender@example.com');
    assert.strictEqual(await page.isChecked('input[name="from.residential"]'), true);

    // 4. Rate retrieval, sorting, selection, and edit invalidation
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

    // Mode switch is blocked while a submission is active
    await page.click('#mock-toggle');
    assert.strictEqual(await page.isChecked('#mock-toggle'), false);
    assert.ok((await page.textContent('#notice')).includes('Finish or resolve the active shipment'));

    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('#unknown-view:not([hidden])');
    assert.strictEqual(await page.textContent('#outcome-title'), 'Pending confirmation');
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    await page.click('#unknown-new');
    await page.waitForSelector('#step-1:not([hidden])');

    // 8. Happy path creation + delayed label (1 automatic attempt -> 202 -> manual Retry label -> PDF available)
    await ctl('normal');
    await ctl('reset-counts');
    await page.click('#get-rates-btn');
    await page.waitForSelector('#step-2:not([hidden]) .rate-card');
    await page.click('.rate-card:first-child');
    await page.click('#review-btn');
    await page.click('#create-btn');
    await page.waitForSelector('#success-view:not([hidden])');
    assert.strictEqual(await page.textContent('#result-id'), '98765');
    assert.strictEqual(await page.textContent('#result-tracking'), 'TRK-98765');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('Label not ready; use Retry label later.'));
    assert.strictEqual(await page.isVisible('#label-link'), false);
    assert.strictEqual(await page.isVisible('#refresh-label-btn'), true);
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual((await ctl('stats')).labelCalls, 1);

    await ctl('label-pdf');
    await page.click('#refresh-label-btn');
    await page.waitForFunction(() => document.getElementById('label-status-text').textContent.includes('Label available.'));
    assert.strictEqual(await page.isVisible('#label-link'), true);
    assert.strictEqual(await page.isVisible('#refresh-label-btn'), false);
    assert.ok((await page.getAttribute('#label-link', 'href')).startsWith('blob:'));
    assert.strictEqual((await ctl('stats')).createCalls, 1);
    assert.strictEqual((await ctl('stats')).labelCalls, 2);

    await page.click('#new-shipment-btn');
    await page.waitForSelector('#step-1:not([hidden])');
  } finally {
    await browser.close();
  }
})();
