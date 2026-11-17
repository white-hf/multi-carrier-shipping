(() => {
  'use strict';
  if (window.__SHIPPING_UI_READY__) return;
  window.__SHIPPING_UI_READY__ = true;

  const $ = id => document.getElementById(id);
  const $all = sel => [...document.querySelectorAll(sel)];
  const draftStorage = 'shipping.rateDraft.v1';
  let rateBusy = false;
  const activeStorage = 'shipping.activeBooking.v2';
  const legacyActiveStorage = 'shipping.activeBooking.v1';
  let savedOperations = [];
  let bookingSequence = 0;
  const clientIdStorage = 'shipping.clientId.v1';
  const sessionKey = 'shipping.accountSession.v1';
  const ownerStorage = 'shipping.workspaceOwner.v1';

  const state = {
    step: 1,
    detailTab: 1,
    unitSystem: 'IMPERIAL',
    request: null,
    requestId: null,
    quotes: [],
    selected: null,
    revision: 0,
    operation: null,
    bookingId: null,
    booking: null,
    creating: false,
    watching: false,
    replaying: false,
    labelAttempted: false,
    labelObjectUrl: null,
    labelTarget: null,
    labelFile: null,
    labelBusy: false,
    sessionToken: null,
    sessionUserId: null,
    fingerprint: null,
    supportedCarriers: [
      'Canada Post',
      'Purolator',
      'FedEx',
      'UPS',
      'DHL Express',
      'Canpar',
      'Loomis Express'
    ]
  };

  let loadingInterval = null;

  // Format currency amounts safely
  const fmt = (value, currency = 'CAD') => {
    if (value == null) return 'Included in total';
    const v = typeof value === 'object' ? value.amount : value;
    const c = (typeof value === 'object' ? value.currency : currency) || 'CAD';
    const n = Number(v);
    return Number.isFinite(n)
        ? new Intl.NumberFormat('en-CA', { style: 'currency', currency: c }).format(n)
        : 'Included in total';
  };

  const esc = x => String(x ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[c]));

  // Distinct carrier badge codes so Canpar (CN) and Canada Post (CP) never collide
  function carrierBadge(name) {
    const n = String(name || '').toLowerCase();
    if (n.includes('canada post')) return 'CP';
    if (n.includes('canpar')) return 'CN';
    if (n.includes('fedex')) return 'FX';
    if (n.includes('purolator')) return 'PU';
    if (n.includes('loomis')) return 'LM';
    if (n.includes('gls')) return 'GL';
    if (n.includes('ups')) return 'UP';
    if (n.includes('dhl')) return 'DH';
    return String(name || 'CA').slice(0, 2).toUpperCase();
  }

  function notice(message, kind = 'error') {
    const el = $('notice');
    el.hidden = !message;
    el.textContent = message;
    el.className = 'notice ' + (kind === 'info' ? 'info' : '');
  }

  function renderStagePills(items, activeIndex) {
    const stagesEl = $('loading-stages');
    if (!stagesEl) return;
    stagesEl.innerHTML = items.map((label, idx) => {
      const cls = idx < activeIndex ? 'done' : idx === activeIndex ? 'active' : '';
      const prefix = idx < activeIndex ? '✓ ' : '';
      return `<span class="stage-pill ${cls}">${prefix}${esc(label)}</span>`;
    }).join('');
  }

  function startRateLoadingBanner() {
    const banner = $('loading-banner');
    if (!banner) return;
    const carriers = Array.isArray(state.supportedCarriers) && state.supportedCarriers.length
        ? state.supportedCarriers
        : ['Canada Post', 'Purolator', 'FedEx', 'UPS'];

    let idx = 0;
    $('loading-title').textContent = `Fetching live quotes from ${carriers[0]}…`;
    $('loading-subtitle').textContent = `Checking real-time rates and transit times across ${carriers.join(', ')}.`;
    $('loading-timer').textContent = '0s';
    renderStagePills(carriers, 0);

    const fill = $('loading-progress-fill');
    if (fill) fill.style.width = '18%';
    banner.hidden = false;

    const startedAt = Date.now();
    clearInterval(loadingInterval);
    loadingInterval = setInterval(() => {
      const elapsedMs = Date.now() - startedAt;
      const elapsedSec = Math.floor(elapsedMs / 1000);
      $('loading-timer').textContent = `${elapsedSec}s`;

      const nextIdx = Math.min(carriers.length - 1, Math.floor(elapsedMs / 1800));
      if (nextIdx !== idx) {
        idx = nextIdx;
        $('loading-title').textContent = `Fetching live quotes from ${carriers[idx]}…`;
        renderStagePills(carriers, idx);
      }
      if (fill) {
        const pct = Math.min(94, 18 + Math.round(((idx + 1) / carriers.length) * 68) + Math.min(8, elapsedSec));
        fill.style.width = `${pct}%`;
      }
    }, 400);
  }

  function startBookingLoadingBanner(carrierName, serviceName) {
    const banner = $('loading-banner');
    if (!banner) return;
    const carrierLabel = carrierName || 'selected carrier';
    const serviceLabel = serviceName || 'shipping service';
    const steps = [
      `Connecting to ${carrierLabel}`,
      `Confirming ${serviceLabel}`,
      `Issuing ${carrierLabel} tracking number`
    ];

    $('loading-title').textContent = `Booking your shipment with ${carrierLabel}…`;
    $('loading-subtitle').textContent = `Submitting your parcel details to ${carrierLabel} and generating your waybill.`;
    $('loading-timer').textContent = '0s';
    renderStagePills(steps, 0);

    const fill = $('loading-progress-fill');
    if (fill) fill.style.width = '24%';
    banner.hidden = false;

    const startedAt = Date.now();
    clearInterval(loadingInterval);
    loadingInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - startedAt) / 1000);
      $('loading-timer').textContent = `${elapsed}s`;
      const stepIdx = elapsed < 2 ? 0 : elapsed < 7 ? 1 : 2;
      renderStagePills(steps, stepIdx);
      if (fill) {
        fill.style.width = `${Math.min(94, 28 + elapsed * 6)}%`;
      }
    }, 400);
  }

  function stopLoadingBanner() {
    clearInterval(loadingInterval);
    loadingInterval = null;
    const banner = $('loading-banner');
    if (banner) banner.hidden = true;
  }

  function busy(id, on, label) {
    const btn = $(id);
    if (!btn) return;
    if (!btn.dataset.original) {
      btn.dataset.original = btn.innerHTML;
    }
    btn.disabled = on;
    btn.innerHTML = on
        ? `<span class="spinner" aria-hidden="true"></span><span>${esc(label)}</span>`
        : btn.dataset.original;
  }

  // Unit system toggle (Imperial IN/LB vs Metric CM/KG)
  function setUnitSystem(system) {
    state.unitSystem = system === 'METRIC' ? 'METRIC' : 'IMPERIAL';
    const isMetric = state.unitSystem === 'METRIC';
    $all('.unit-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.unit === state.unitSystem);
    });
    $all('[data-dim-unit]').forEach(el => {
      el.textContent = isMetric ? 'cm' : 'in';
    });
    $all('[data-weight-unit]').forEach(el => {
      el.textContent = isMetric ? 'kg' : 'lb';
    });
    const sub = $('package-unit-subtitle');
    if (sub) {
      sub.textContent = isMetric
          ? 'One package · metric measurements (cm / kg)'
          : 'One package · imperial measurements (in / lb)';
    }
    saveDraft();
    state.revision++;
    if (rateBusy) {
      rateBusy = false;
      stopLoadingBanner();
      busy('get-rates-btn', false);
    }
    if (state.quotes.length) {
      state.quotes = [];
      state.selected = null;
    }
  }

  $all('.unit-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (state.operation) return;
      setUnitSystem(btn.dataset.unit);
    });
  });

  // Step 1 single-screen 3-Tab navigation & live summary badges
  function setDetailTab(tab) {
    const n = Math.min(3, Math.max(1, Number(tab) || 1));
    state.detailTab = n;
    const track = $('detail-tab-track');
    if (track) track.dataset.activeTab = String(n);
    $all('.detail-tab').forEach(btn => {
      const active = Number(btn.dataset.detailTab) === n;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', String(active));
    });
    $all('.tab-pane').forEach(pane => {
      pane.hidden = Number(pane.dataset.tabPane) !== n;
    });
    const prevBtn = $('prev-tab-btn');
    const nextBtn = $('next-tab-btn');
    if (prevBtn) prevBtn.hidden = n === 1;
    if (nextBtn) {
      nextBtn.hidden = n === 3;
      nextBtn.innerHTML = n === 1
          ? 'Next: Recipient <span>→</span>'
          : 'Next: Package <span>→</span>';
    }
  }

  function updateTabSummaries() {
    const form = $('shipping-form');
    if (!form) return;
    const v = name => String(form.elements.namedItem(name)?.value || '').trim();
    const isMetric = state.unitSystem === 'METRIC';
    const dUnit = isMetric ? 'cm' : 'in';
    const wUnit = isMetric ? 'kg' : 'lb';

    if ($('tab-summary-from')) {
      $('tab-summary-from').textContent = `${v('from.attention') || 'Sender'} · ${v('from.city') || 'City'}, ${v('from.state') || 'ON'}`;
    }
    if ($('tab-summary-to')) {
      $('tab-summary-to').textContent = `${v('to.attention') || 'Recipient'} · ${v('to.city') || 'City'}, ${v('to.state') || 'NS'}`;
    }
    if ($('tab-summary-pkg')) {
      $('tab-summary-pkg').textContent = `${v('weight') || '0'} ${wUnit} · ${v('length') || '0'} × ${v('width') || '0'} × ${v('height') || '0'} ${dUnit}`;
    }
    if ($('package-quick-summary')) {
      $('package-quick-summary').innerHTML = `Route preview: <strong>${esc(v('from.city') || 'Origin')}, ${esc(v('from.state'))} (${esc(v('from.postalCode').toUpperCase())}) → ${esc(v('to.city') || 'Destination')}, ${esc(v('to.state'))} (${esc(v('to.postalCode').toUpperCase())})</strong>`;
    }
    if ($('step1-sticky-summary')) {
      $('step1-sticky-summary').textContent = `${v('from.city') || 'Origin'}, ${v('from.state') || 'ON'} → ${v('to.city') || 'Destination'}, ${v('to.state') || 'NS'} · ${v('weight') || '0'} ${wUnit} (${v('length') || '0'}×${v('width') || '0'}×${v('height') || '0'} ${dUnit})`;
    }
  }

  $all('.detail-tab').forEach(btn => {
    btn.addEventListener('click', () => setDetailTab(btn.dataset.detailTab));
  });

  if ($('prev-tab-btn')) {
    $('prev-tab-btn').addEventListener('click', () => setDetailTab(state.detailTab - 1));
  }
  if ($('next-tab-btn')) {
    $('next-tab-btn').addEventListener('click', () => setDetailTab(state.detailTab + 1));
  }

  // Automatically bring any focused input's tab pane into view
  $all('.tab-pane').forEach(pane => {
    pane.addEventListener('focusin', () => {
      const paneTab = Number(pane.dataset.tabPane);
      if (paneTab && paneTab !== state.detailTab) {
        setDetailTab(paneTab);
      }
    });
  });

  // Step navigation & interactive stepper
  function go(n) {
    state.step = n;
    notice('');
    for (let i = 1; i <= 3; i++) {
      $('step-' + i).hidden = i !== n;
      const st = document.querySelector(`.step[data-step="${i}"]`);
      st.classList.toggle('active', i === n);
      st.classList.toggle('done', i < n);
      st.querySelector('.step-number').textContent = i < n ? '✓' : i;
    }
    const headings = ['Create a shipment', 'Choose your shipping service', 'Shipment confirmation'];
    $('page-title').textContent = headings[n - 1];
    window.scrollTo(0, 0);
  }

  $all('.stepper .step').forEach(stepEl => {
    stepEl.addEventListener('click', () => {
      const targetStep = Number(stepEl.dataset.step);
      if (targetStep < state.step) {
        if (state.operation) {
          showPending('Creation has already been submitted. You cannot change this booking.');
          return;
        }
        go(targetStep);
      }
    });
  });

  // Form serialization & validation
  function address(form, prefix) {
    const val = k => (form.elements.namedItem(prefix + '.' + k)?.value || '').trim();
    return {
      contactName: val('attention'),
      companyName: val('companyName') || null,
      addressLine1: val('streetAddress'),
      addressLine2: val('streetAddress2') || null,
      city: val('city'),
      province: val('state'),
      postalCode: val('postalCode').toUpperCase(),
      countryCode: 'CA',
      phone: val('phone'),
      email: val('email') || null,
      residential: !!form.elements.namedItem(prefix + '.residential')?.checked
    };
  }

  function inputPayload(form) {
    const v = k => Number(form.elements.namedItem(k).value);
    const isMetric = state.unitSystem === 'METRIC';
    return {
      sender: address(form, 'from'),
      recipient: address(form, 'to'),
      parcel: {
        length: v('length'),
        width: v('width'),
        height: v('height'),
        weight: v('weight'),
        dimensionUnit: isMetric ? 'CM' : 'IN',
        weightUnit: isMetric ? 'KG' : 'LB',
        packageType: 'PACKAGE'
      }
    };
  }

  function validate(form) {
    clearFieldErrors();
    let first = null;
    for (const input of form.querySelectorAll('input, select')) {
      if (input.disabled || input.type === 'checkbox') continue;
      const raw = String(input.value || '').trim();
      const isRequired = input.hasAttribute('required');
      let wrong = false;
      if (isRequired && (!raw || !input.checkValidity())) {
        wrong = true;
      } else if (!isRequired && raw && !input.checkValidity()) {
        wrong = true;
      } else if (input.type === 'number') {
        const num = Number(raw);
        wrong = !(num > 0 && num <= 10000) || !/^\d+(\.\d{1,4})?$/.test(raw);
      }
      input.classList.toggle('invalid', wrong);
      if (wrong) {
        const pane = input.closest('[data-tab-pane]');
        if (pane) {
          const tabNum = Number(pane.dataset.tabPane);
          const tabBtn = $(`detail-tab-btn-${tabNum}`);
          if (tabBtn) tabBtn.classList.add('has-error');
          const statusEl = $(`tab-status-${tabNum}`);
          if (statusEl) statusEl.textContent = '!';
        }
        if (!first) first = input;
      }
    }
    if (first) {
      const pane = first.closest('[data-tab-pane]');
      if (pane) {
        setDetailTab(Number(pane.dataset.tabPane));
      }
      first.focus({ preventScroll: true });
      notice(first.type === 'number'
          ? 'Please enter valid positive package dimensions and weight (for standard parcels: 0.1–150 lb / 0.1–68 kg, max 108 in / 274 cm per side).'
          : 'Please complete all highlighted required fields before requesting shipping rates.');
      return false;
    }
    return true;
  }

  class HttpError extends Error {
    constructor(status, payload) {
      super(payload?.message || `HTTP ${status}`);
      this.status = status;
      this.code = payload?.code || '';
      this.fieldErrors = payload?.fieldErrors || [];
      this.payload = payload;
    }
  }

  // Resolve a stable browser client ID and environment fingerprint for 2-hour Guest Token binding
  function resolveClientId() {
    let cid = localStorage.getItem(clientIdStorage);
    if (!cid || !/^[A-Za-z0-9._:-]{8,128}$/.test(cid)) {
      cid = 'client-' + (crypto.randomUUID
          ? crypto.randomUUID()
          : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`);
      localStorage.setItem(clientIdStorage, cid);
    }
    return cid;
  }

  async function computeClientFingerprint() {
    if (state.fingerprint) return state.fingerprint;
    const raw = [
      navigator.userAgent || 'unknown-ua',
      navigator.language || 'en-CA',
      Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
      `${screen?.width || 0}x${screen?.height || 0}`,
      String(navigator.hardwareConcurrency || 1)
    ].join('|');

    if (crypto?.subtle?.digest) {
      const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw));
      const hex = [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
      state.fingerprint = 'fp-' + hex.slice(0, 32);
      return state.fingerprint;
    }

    let hash = 2166136261;
    for (let i = 0; i < raw.length; i++) {
      hash ^= raw.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    state.fingerprint = 'fp-fallback-' + (hash >>> 0).toString(16).padStart(8, '0');
    return state.fingerprint;
  }

  function cachedSession() {
    try {
      const session = JSON.parse(sessionStorage.getItem(sessionKey) || 'null');
      return session?.mode === 'AUTHENTICATED' && session.token
          && new Date(session.expiresAt).getTime() > Date.now() ? session : null;
    } catch {
      return null;
    }
  }

  function renderAccount() {
    const session = cachedSession();
    const dialog = $('login-modal');
    $('user-avatar').textContent = session
        ? `${session.displayName || session.username} · Sign out` : 'Sign in';
    if (!session && !dialog.open) dialog.showModal();
    if (session && dialog.open) dialog.close();
  }

  async function ensureSession() {
    const session = cachedSession();
    const fingerprint = await computeClientFingerprint();
    if (!session || session.fingerprint !== fingerprint) {
      sessionStorage.removeItem(sessionKey);
      renderAccount();
      throw new HttpError(401, { code: 'UNAUTHORIZED', message: 'Please sign in to continue.' });
    }
    state.sessionToken = session.token;
    state.sessionUserId = session.userId;
    state.supportedCarriers = session.supportedCarriers || state.supportedCarriers;
    return session;
  }

  function clearWorkspace() {
    localStorage.removeItem(draftStorage);
    localStorage.removeItem(activeStorage);
    sessionStorage.removeItem(activeStorage);
    sessionStorage.removeItem(legacyActiveStorage);
    localStorage.removeItem(legacyActiveStorage);
    savedOperations = [];
  }

  function expireSession() {
    sessionStorage.removeItem(sessionKey);
    // Reload discards callbacks from the old document. Keep same-account operation recovery.
    location.reload();
  }

  $('login-modal').addEventListener('cancel', event => event.preventDefault());
  $('login-form').addEventListener('submit', async event => {
    event.preventDefault();
    busy('login-submit', true, 'Signing in…');
    $('login-error').hidden = true;
    try {
      const fingerprint = await computeClientFingerprint();
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: $('login-username').value,
          password: $('login-password').value, fingerprint })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.token) {
        const wait = res.headers.get('Retry-After');
        throw new Error(res.status === 429
            ? `Too many sign-in attempts. Try again in ${wait || 'a few'} seconds.`
            : res.status === 401 ? 'Invalid username or password.' : 'Sign-in failed. Please try again.');
      }
      if (sessionStorage.getItem(ownerStorage) !== data.userId) clearWorkspace();
      sessionStorage.setItem(ownerStorage, data.userId);
      sessionStorage.removeItem('shipping.guestSession.v1');
      sessionStorage.setItem(sessionKey, JSON.stringify({ ...data, fingerprint }));
      location.reload();
    } catch (error) {
      $('login-error').textContent = error.message;
      $('login-error').hidden = false;
    } finally {
      $('login-password').value = '';
      busy('login-submit', false);
    }
  });
  $('user-avatar').addEventListener('click', async () => {
    const session = cachedSession();
    if (!session) {
      $('login-modal').showModal();
      return;
    }
    if ((state.operation || savedOperations.length || state.creating || rateBusy) && !confirm(
        'An operation is active. Signing out clears its local recovery information. Sign out anyway?')) return;
    try {
      const res = await fetch('/api/auth/logout', { method: 'POST' });
      if (!res.ok) throw new Error('Sign-out failed. Please try again.');
      sessionStorage.removeItem(sessionKey);
      sessionStorage.removeItem(ownerStorage);
      clearWorkspace();
      location.reload();
    } catch (error) {
      notice(error.message);
    }
  });

  async function http(path, { method = 'GET', body = null, headers = {} } = {}) {
    const session = await ensureSession();
    const fp = await computeClientFingerprint();
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), 125000);
    try {
      const res = await fetch(path, {
        method,
        headers: {
          ...(body ? { 'Content-Type': 'application/json' } : {}),
          ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
          ...(fp ? { 'X-Client-Fingerprint': fp } : {}),
          ...headers
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: ctrl.signal
      });
      if (res.status === 401) {
        expireSession();
        throw new HttpError(401, { code: 'UNAUTHORIZED', message: 'Session expired. Please sign in.' });
      }
      let data = {};
      if (res.status !== 204) {
        const type = res.headers.get('content-type') || '';
        data = type.includes('json') ? await res.json().catch(() => ({})) : {};
      }
      if (!res.ok) throw new HttpError(res.status, data);
      return { status: res.status, data, headers: res.headers };
    } finally {
      clearTimeout(timeout);
    }
  }

  const delay = ms => new Promise(r => setTimeout(r, ms));
  const api = {
    quotes: data => http('/api/quotes', { method: 'POST', body: data }),
    booking: body => http('/api/bookings', { method: 'POST', body }),
    retry: id => http(`/api/bookings/${encodeURIComponent(id)}/retry`, { method: 'POST' }),
    lookup: operation => http('/api/bookings/lookup?' + new URLSearchParams({
      providerId: operation.providerId, providerQuoteId: operation.providerQuoteId
    })),
    status: id => http(`/api/bookings/${encodeURIComponent(id)}`)
  };

  const quoteId = q => JSON.stringify([q?.providerId, q?.providerQuoteId]);
  const total = q => q.total ?? q.totalCharge ?? null;
  const carrier = q => q.carrierName ?? q.carrier?.name ?? q.carrier ?? 'Carrier';
  const service = q => q.serviceName ?? q.service?.name ?? q.service ?? 'Shipping service';

  function formatPackageSummary(p) {
    const wUnit = p.weightUnit === 'KG' ? 'kg' : 'lb';
    const dUnit = p.dimensionUnit === 'CM' ? 'cm' : 'in';
    return `1 package · ${p.weight} ${wUnit} · ${p.length} × ${p.width} × ${p.height} ${dUnit}`;
  }

  function routes() {
    const r = state.request;
    $('summary-from').textContent = `${r.sender.city}, ${r.sender.province}`;
    $('summary-to').textContent = `${r.recipient.city}, ${r.recipient.province}`;
    $('summary-package').textContent = formatPackageSummary(r.parcel);
  }

  function renderQuotes() {
    const list = [...state.quotes].sort((a, b) => {
      const byTime = $('sort-select').value === 'time';
      return byTime
          ? (a.transitDays ?? 999) - (b.transitDays ?? 999) || Number(total(a)?.amount ?? Infinity) - Number(total(b)?.amount ?? Infinity)
          : Number(total(a)?.amount ?? Infinity) - Number(total(b)?.amount ?? Infinity);
    });

    const minPrice = list.length ? Math.min(...list.map(q => Number(total(q)?.amount ?? Infinity))) : Infinity;
    const validDays = list.map(q => q.transitDays).filter(d => d != null && d > 0);
    const minDays = validDays.length ? Math.min(...validDays) : null;

    $('rates-count').textContent = `${list.length} shipping options available`;

    $('rates-list').innerHTML = list.map(q => {
      const chosen = state.selected && quoteId(state.selected) === quoteId(q);
      const code = carrierBadge(carrier(q));
      const amount = Number(total(q)?.amount ?? Infinity);
      const isBestPrice = Number.isFinite(minPrice) && amount === minPrice;
      const isFastest = minDays != null && q.transitDays === minDays;

      const tags = [];
      if (isBestPrice) tags.push('<span class="badge-best">★ Best value</span>');
      if (isFastest) tags.push('<span class="badge-fast">⚡ Fastest</span>');
      tags.push('<span>Tax &amp; fuel included</span>');
      if (q.expiresAt) {
        tags.push(`<span>${q.expirySource === 'PROVIDER' ? 'Provider expiry' : 'Estimated expiry'} ${esc(new Date(q.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))}</span>`);
      }

      const transitText = q.transitDays == null
          ? 'Transit estimate unavailable'
          : `${esc(q.transitDays)} business ${q.transitDays === 1 ? 'day' : 'days'}`;

      return `
        <div class="rate-card ${chosen ? 'selected' : ''}" data-quote="${esc(quoteId(q))}" tabindex="0" role="radio" aria-checked="${!!chosen}">
          <div class="rate-left">
            <div class="carrier-logo" data-carrier="${esc(code)}">${esc(code)}</div>
            <div>
              <div class="rate-name">${esc(carrier(q))}</div>
              <div class="rate-service">${esc(service(q))} · ${transitText}</div>
              <div class="rate-tags">${tags.join('')}</div>
            </div>
          </div>
          <div class="rate-right">
            <div class="rate-price-group">
              <div class="rate-price">${fmt(total(q))}</div>
              <div class="rate-price-sub">CAD total</div>
            </div>
            <span class="rate-radio"></span>
          </div>
        </div>`;
    }).join('');

    $('review-btn').disabled = !state.selected;
    const stickySummary = $('selected-rate-summary');
    if (stickySummary) {
      stickySummary.textContent = state.selected
          ? `Selected: ${carrier(state.selected)} — ${service(state.selected)} (${fmt(total(state.selected))})`
          : 'Select a shipping option above to continue';
    }

    document.querySelectorAll('.rate-card').forEach(el => {
      const select = () => {
        state.selected = state.quotes.find(q => quoteId(q) === el.dataset.quote);
        if (!state.selected?.providerId || !state.selected?.providerQuoteId) {
          state.selected = null;
          notice('This offer has no usable provider quote identity. Request fresh rates.');
        }
        renderQuotes();
      };
      el.onclick = select;
      el.onkeydown = e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          select();
        }
      };
    });
  }

  function clearFieldErrors() {
    document.querySelectorAll('.field-error').forEach(e => e.remove());
    document.querySelectorAll('.invalid').forEach(e => e.classList.remove('invalid'));
    document.querySelectorAll('[aria-invalid]').forEach(e => e.removeAttribute('aria-invalid'));
    document.querySelectorAll('.detail-tab.has-error').forEach(e => e.classList.remove('has-error'));
    for (let i = 1; i <= 3; i++) {
      const st = $(`tab-status-${i}`);
      if (st) st.textContent = '✓';
    }
  }

  function showFieldErrors(errors) {
    clearFieldErrors();
    const fields = {
      contactName: 'attention',
      addressLine1: 'streetAddress',
      addressLine2: 'streetAddress2',
      province: 'state'
    };
    let firstInvalidTab = null;
    for (const error of errors) {
      const pieces = (error.field || '').split('.');
      const name = pieces[0] === 'parcel'
          ? pieces[1]
          : (pieces[0] === 'sender' ? 'from.' : pieces[0] === 'recipient' ? 'to.' : '') + (fields[pieces[1]] || pieces[1] || '');
      const input = $('shipping-form').elements.namedItem(name);
      if (!input) continue;
      input.classList.add('invalid');
      input.setAttribute('aria-invalid', 'true');
      const message = document.createElement('span');
      message.className = 'field-error';
      message.textContent = error.message || 'Invalid value';
      input.parentElement.appendChild(message);

      const pane = input.closest('[data-tab-pane]');
      if (pane) {
        const tabNum = Number(pane.dataset.tabPane);
        if (!firstInvalidTab) firstInvalidTab = tabNum;
        const tabBtn = $(`detail-tab-btn-${tabNum}`);
        if (tabBtn) tabBtn.classList.add('has-error');
        const statusEl = $(`tab-status-${tabNum}`);
        if (statusEl) statusEl.textContent = '!';
      }
    }
    if (firstInvalidTab) {
      setDetailTab(firstInvalidTab);
    }
  }

  function errorText(err) {
    if (err instanceof HttpError) {
      if (err.code === 'PROVIDER_REQUEST_REJECTED') {
        return 'The shipping details were rejected by carrier validation. Please check the origin, destination, and package measurements.';
      }
      if (err.code === 'QUOTE_EXPIRED' || err.code === 'QUOTE_NOT_BOOKABLE') {
        return 'Quote is no longer available. Please obtain new shipping rates.';
      }
      if (err.code === 'REQUEST_DATE_EXPIRED') {
        return 'The saved shipping date has passed. Request new shipping rates for today.';
      }
      if (err.fieldErrors.length) {
        return err.message + ' ' + err.fieldErrors.map(f => `${f.field || f.name || 'Field'}: ${f.message || 'invalid'}`).join('; ');
      }
      return `${err.code ? err.code + ': ' : ''}${err.message}`;
    }
    return err.name === 'AbortError' ? 'Request timed out.' : err.message || 'Unexpected error';
  }

  function saveActive() {
    if (!state.operation) return;
    Object.assign(state.operation, {
      bookingId: state.bookingId, labelAttempted: state.labelAttempted,
      status: state.booking?.status || state.operation.status,
      errorCode: state.booking?.errorCode
    });
    const identity = operationIdentity(state.operation);
    savedOperations = savedOperations.filter(op => operationIdentity(op) !== identity);
    savedOperations.push(state.operation);
    persistOperations(identity);
    renderSavedOperations();
  }

  function persistOperations(active) {
    localStorage.setItem(activeStorage, JSON.stringify({ version: 2,
      userId: cachedSession()?.userId || sessionStorage.getItem(ownerStorage),
      active, operations: savedOperations }));
  }

  function operationIdentity(op) {
    return JSON.stringify([op.userId, op.providerId || null, op.providerQuoteId || null,
      op.providerQuoteId ? null : op.bookingId || 'legacy-unrecoverable']);
  }

  function renderSavedOperations() {
    let list = $('saved-operations');
    if (!list) {
      list = document.createElement('section');
      list.id = 'saved-operations';
      $('notice').insertAdjacentElement('afterend', list);
    }
    list.replaceChildren();
    for (const op of savedOperations) {
      if (['CONFIRMED', 'REJECTED'].includes(op.status)) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'button secondary';
      button.textContent = `Inspect saved shipment: ${op.bookingId || op.providerQuoteId || 'older operation'} (${op.status || 'UNKNOWN'})`;
      button.addEventListener('click', () => restoreOperation(op));
      list.append(button);
    }
  }

  function clearActive() {
    pollToken++;
    if (state.labelObjectUrl) {
      URL.revokeObjectURL(state.labelObjectUrl);
      state.labelObjectUrl = null;
    }
    state.labelTarget = null;
    state.labelFile = null;
    state.labelBusy = false;
    state.labelAttempted = false;
    bookingSequence++;
    state.operation = null;
    state.bookingId = null;
    state.booking = null;
    state.creating = false;
    state.replaying = false;
    state.watching = false;
    busy('create-btn', false);
    busy('check-status-btn', false);
    updateLabelActions();
  }

  function showPending(message) {
    if (state.booking?.status === 'MANUAL_REVIEW') {
      showManualReview(state.booking);
      return;
    }
    $('unknown-new').hidden = false;
    $('outcome-title').textContent = 'Pending confirmation';
    $('outcome-explanation').textContent = 'The provider may have accepted this request. Check the original operation status before taking further action.';
    $('unknown-new').textContent = 'Start a different shipment';
    $('check-status-btn').hidden = false;
    $('check-status-btn').textContent = 'Check Status';
    $('check-status-btn').dataset.original = 'Check Status';
    go(3);
    $('review-view').hidden = true;
    $('success-view').hidden = true;
    $('unknown-view').hidden = false;
    $('unknown-id').textContent = message || 'Creation outcome is not confirmed. Check status; do not resend automatically.';
    $('check-status-btn').disabled = !(state.bookingId || state.operation?.providerQuoteId);
    $('retry-send-btn').hidden = true;
    $('retry-initial-btn').hidden = true;
  }

  function showRetryable(data) {
    state.booking = { ...data, status: 'RETRYABLE' };
    if (data.bookingId) state.bookingId = data.bookingId;
    showPending(`Submission was not sent. ${state.bookingId ? 'Booking: ' + state.bookingId : ''}`);
    $('outcome-title').textContent = 'Shipment not submitted';
    $('outcome-explanation').textContent = 'This attempt was definitely not sent. Retry sending explicitly using this booking’s saved details.';
    $('retry-send-btn').hidden = false;
    $('retry-send-btn').disabled = !state.bookingId || state.replaying;
    pollToken++;
    saveActive();
  }

  function handleNotDispatched(error) {
    if (!(error instanceof HttpError) || error.code !== 'SHIPMENT_SUBMISSION_NOT_DISPATCHED') return false;
    showRetryable({ bookingId: error.payload?.operation?.id || state.bookingId });
    return true;
  }

  function handleInitialAdmissionFailure(error, firstInitialAttempt) {
    if (!firstInitialAttempt || !(error instanceof HttpError)
        || state.bookingId || error.payload?.operation) return false;
    const invalidInput = error.status === 400
      && ['INVALID_REQUEST', 'INVALID_SHIPPING_DATE', 'INVALID_PROVIDER_QUOTE'].includes(error.code);
    const expiredQuote = error.status === 409 && error.code === 'QUOTE_EXPIRED';
    if (!invalidInput && !expiredQuote) return false;

    // Only this first, definitively unclaimed attempt can be discarded. A later 400/409
    // cannot prove that an earlier lost request did not commit after a no-row lookup.
    const identity = operationIdentity(state.operation);
    savedOperations = savedOperations.filter(op => operationIdentity(op) !== identity);
    clearActive();
    reset();
    renderSavedOperations();
    showFieldErrors(error.fieldErrors.map(field => ({ ...field,
      field: field.field?.replace(/^shippingInput\./, '') })));
    notice('Shipment was not submitted. ' + errorText(error) + ' Review the details and request fresh rates.');
    return true;
  }

  function handleUnavailableQuote(error, returnToStep1 = false) {
    if (!(error instanceof HttpError)) return false;
    if (!['QUOTE_EXPIRED', 'QUOTE_NOT_BOOKABLE', 'REQUEST_DATE_EXPIRED', 'INVALID_SHIPPING_DATE'].includes(error.code)) {
      return false;
    }
    if (state.booking?.status === 'RETRYABLE') {
      showRetryable(state.booking);
      $('retry-send-btn').hidden = true;
      $('outcome-explanation').textContent = 'This booking was not sent. Its quote or shipping date is no longer usable. The original booking is retained; request fresh rates for a different shipment.';
      notice(errorText(error));
      return true;
    }
    // Expiry is never evidence that an active booking was not created.
    if (state.operation) {
      showPending(errorText(error) + ' The original operation is retained; check its status.');
      return true;
    }
    state.selected = null;
    state.quotes = [];
    state.creating = false;
    if (returnToStep1) {
      go(1);
    }
    notice(errorText(error));
    return true;
  }

  // Manual review is unresolved, but no longer awaiting automatic recovery.
  function showManualReview(data) {
    state.booking = data;
    if (data.bookingId) state.bookingId = data.bookingId;
    saveActive();
    pollToken++;
    go(3);
    $('review-view').hidden = true;
    $('success-view').hidden = true;
    $('unknown-view').hidden = false;
    $('outcome-title').textContent = 'Manual review required';
    $('outcome-explanation').textContent = 'We could not confirm whether the provider created this shipment. Do not submit it again. Contact support with the booking reference to verify the shipment before taking further action.';
    $('unknown-id').textContent = `Booking reference: ${state.bookingId}. Reason: ${data.errorCode || 'AUTOMATIC_RECOVERY_UNAVAILABLE'}.`;
    $('check-status-btn').hidden = true;
    $('unknown-new').hidden = false;
    $('unknown-new').textContent = 'Start a different shipment';
    $('retry-send-btn').hidden = true;
    $('retry-initial-btn').hidden = true;
  }

  // Label retrieval is read-only: one automatic attempt, then an explicit user retry.
  function showConfirmed(data) {
    state.booking = data;
    saveActive();
    go(3);
    $('review-view').hidden = true;
    $('unknown-view').hidden = true;
    $('success-view').hidden = false;

    $('result-id').textContent = data.shipmentId ?? 'Pending';
    $('result-tracking').textContent = (data.trackingNumbers || [data.trackingNumber].filter(Boolean)).join(', ') || 'Not yet available';
    $('result-carrier').textContent = state.selected
        ? `${carrier(state.selected)} · ${service(state.selected)}`
        : (state.savedService || 'Selected shipping service');

    updateLabelActions();
    $('label-status-text').textContent = state.labelTarget
        ? 'Shipment confirmed. Label available.'
        : 'Shipment confirmed. Retrieve the label to view, download or print it.';

    if (!state.labelTarget && !state.labelAttempted) {
      state.labelAttempted = true;
      saveActive();
      fetchLabel();
    }
  }

  function showRejected(data) {
    state.booking = data;
    if (data.bookingId) state.bookingId = data.bookingId;
    saveActive();
    showPending('The provider rejected this request. Correct the details and request fresh rates.');
    $('outcome-title').textContent = 'Shipment rejected';
    $('outcome-explanation').textContent = 'Creation was rejected. You can correct the shipment details and request new rates.';
    $('unknown-new').textContent = 'Correct shipment details';
    $('check-status-btn').hidden = true;
  }

  function labelFilename() {
    const identity = String(state.booking?.shipmentId || state.bookingId || 'shipment');
    return `shipping-label-${identity.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 80)}.pdf`;
  }

  function updateLabelActions() {
    const ready = !!state.labelTarget && !state.labelBusy;
    for (const id of ['label-download-btn', 'label-print-btn', 'label-share-btn']) {
      if ($(id)) $(id).disabled = !ready;
    }
    const emailBody = `Shipping label for shipment ${state.booking?.shipmentId || state.bookingId || ''}.\n\nAttach the downloaded PDF before sending this email.`;
    const email = `mailto:?subject=${encodeURIComponent('Shipping label ' + (state.booking?.shipmentId || ''))}&body=${encodeURIComponent(emailBody)}`;
    for (const [id, target] of [['label-link', state.labelTarget], ['label-email-link', email]]) {
      const link = $(id);
      if (!link) continue;
      link.hidden = false;
      link.href = ready ? target : '#';
      link.setAttribute('aria-disabled', String(!ready));
      link.tabIndex = ready ? 0 : -1;
    }
    if ($('refresh-label-btn')) {
      $('refresh-label-btn').hidden = false;
      $('refresh-label-btn').disabled = state.labelBusy;
      if (!state.labelBusy) {
        $('refresh-label-btn').textContent = state.labelTarget ? 'Reload label' : 'Retry label';
      }
    }
    if ($('label-action-help')) {
      $('label-action-help').textContent = !ready
          ? 'Retrieve the label to enable these actions. Creation will not be repeated.'
          : state.labelFile
              ? 'Email opens your mail app; attach the downloaded PDF yourself. Sharing depends on browser support.'
              : 'Provider-hosted label: use its viewer to save or print. Download the PDF before attaching or sharing it.';
    }
  }

  function openLabelViewer(printing = false) {
    if (!state.labelTarget || state.labelBusy) return;
    window.open(state.labelTarget, '_blank', 'noopener,noreferrer');
    notice(printing
        ? 'Use the print button in the PDF viewer, or Ctrl/Cmd+P. If no tab opens, allow pop-ups for this site.'
        : 'Use Save or Download in the provider PDF viewer. If no tab opens, allow pop-ups for this site.', 'info');
  }

  function downloadLabel() {
    if (!state.labelTarget || state.labelBusy) return;
    if (!state.labelFile) {
      openLabelViewer();
      return;
    }
    const link = document.createElement('a');
    link.href = state.labelObjectUrl;
    link.download = state.labelFile.name;
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  async function shareLabel() {
    if (!state.labelTarget || state.labelBusy) return;
    const bookingId = state.bookingId;
    const file = state.labelFile;
    try {
      if (state.labelFile && typeof navigator.share === 'function'
          && typeof navigator.canShare === 'function' && navigator.canShare({ files: [state.labelFile] })) {
        // Invoke during the click gesture; never send a browser-local blob URL to a recipient.
        await navigator.share({ files: [state.labelFile], title: 'Shipping label' });
        return;
      }
    } catch (error) {
      if (error.name === 'AbortError') return;
    }
    if (state.bookingId !== bookingId || state.labelFile !== file) return;
    downloadLabel();
    notice(state.labelFile
        ? 'File sharing is unavailable here. The PDF was downloaded; share it from your downloads.'
        : 'Open the provider label, save its PDF, then share the saved file.', 'info');
  }

  async function fetchLabel() {
    if (!state.bookingId || state.labelBusy) return;
    const bookingId = state.bookingId;
    state.labelBusy = true;
    busy('refresh-label-btn', true, 'Checking label…');
    updateLabelActions();
    try {
      const session = await ensureSession();
      const fp = await computeClientFingerprint();
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 45000);
      let res;
      try {
        res = await fetch(`/api/bookings/${encodeURIComponent(bookingId)}/label`, {
          headers: {
            Accept: 'application/json, application/pdf',
            ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
            ...(fp ? { 'X-Client-Fingerprint': fp } : {})
          },
          signal: controller.signal
        });
      } finally {
        clearTimeout(timeout);
      }
      if (state.bookingId !== bookingId) return;
      if (res.status === 401) { expireSession(); return; }
      if (res.status === 202) {
        $('label-status-text').textContent = 'Shipment confirmed. Label not ready; use Retry label later.';
        return;
      }
      if (!res.ok) throw new Error(`Label lookup failed (${res.status}).`);

      const type = res.headers.get('content-type') || '';
      let target;
      let file = null;
      if (type.includes('application/pdf')) {
        const blob = await res.blob();
        if ((await blob.slice(0, 5).text()) !== '%PDF-') throw new Error('Invalid label PDF.');
        file = new File([blob], labelFilename(), { type: 'application/pdf' });
        target = URL.createObjectURL(file);
      } else if (type.includes('application/json')) {
        const body = await res.json();
        if (typeof body.labelUrl !== 'string' || !body.labelUrl.trim()) throw new Error('Missing label location.');
        const url = new URL(body.labelUrl, location.origin);
        if (!['https:', 'http:'].includes(url.protocol)
            || (url.protocol !== 'https:' && url.origin !== location.origin)) {
          throw new Error('Unsupported label location.');
        }
        target = url.href;
      } else {
        throw new Error('Unsupported label response.');
      }

      // Body reading may finish after the user starts another shipment.
      if (state.bookingId !== bookingId) {
        if (file) URL.revokeObjectURL(target);
        return;
      }
      if (state.labelObjectUrl) URL.revokeObjectURL(state.labelObjectUrl);
      state.labelObjectUrl = file ? target : null;
      state.labelFile = file;
      state.labelTarget = target;
      $('label-status-text').textContent = 'Shipment confirmed. Label available.';
    } catch (error) {
      if (state.bookingId === bookingId) {
        $('label-status-text').textContent = 'Shipment confirmed. Label retrieval failed; use Retry label.';
        notice(errorText(error));
      }
    } finally {
      if (state.bookingId === bookingId) {
        state.labelBusy = false;
        busy('refresh-label-btn', false);
        updateLabelActions();
      }
    }
  }

  function canAcceptBookingUpdate(nextStatus) {
    const current = (state.booking?.status || '').toUpperCase();
    const next = (nextStatus || '').toUpperCase();
    if (current === 'CONFIRMED' || current === 'REJECTED') {
      return false;
    }
    if (current === 'MANUAL_REVIEW') {
      return next === 'CONFIRMED';
    }
    return true;
  }

  function handleBooking(data) {
    const status = (data.status || '').toUpperCase();
    if (!canAcceptBookingUpdate(status)) return;
    if (data.bookingId) state.bookingId = data.bookingId;
    if (status === 'RETRYABLE') {
      showRetryable(data);
      return;
    }
    if (status === 'CONFIRMED') {
      showConfirmed(data);
      return;
    }
    if (status === 'REJECTED') {
      showRejected(data);
      return;
    }
    if (status === 'MANUAL_REVIEW') {
      showManualReview(data);
      return;
    }
    // CONTENDED is a response-only claim-loss signal, not a persisted DB state.
    state.booking = { ...data, status: status === 'CONTENDED' ? 'PENDING_CONFIRMATION' : (data.status || 'PENDING_CONFIRMATION') };
    saveActive();
    showPending(`Pending confirmation (${status || 'UNKNOWN'}). ${state.bookingId ? 'Booking: ' + state.bookingId : 'Response lost; provider quote identity retained.'}`);
    if (state.bookingId) poll();
  }

  let pollToken = 0;
  async function poll() {
    if (!state.bookingId || state.watching) return;
    state.watching = true;
    const token = ++pollToken;
    const bookingId = state.bookingId;
    const operation = state.operation;
    try {
      for (let n = 0; n < 5 && token === pollToken && state.bookingId === bookingId && state.operation === operation; n++) {
        try {
          const reply = await api.status(bookingId);
          if (token !== pollToken || state.bookingId !== bookingId || state.operation !== operation) break;
          handleStatus(reply.data);
          if (['CONFIRMED', 'REJECTED', 'MANUAL_REVIEW', 'RETRYABLE'].includes((reply.data.status || '').toUpperCase())) break;
        } catch (e) {
          if (token !== pollToken || state.bookingId !== bookingId || state.operation !== operation) break;
          if (!['CONFIRMED', 'REJECTED'].includes((state.booking?.status || '').toUpperCase())) {
            notice('Status check unavailable: ' + errorText(e));
          }
        }
        if (n < 4 && token === pollToken) await delay(Math.min(1200 * 2 ** n, 8000));
      }
    } finally {
      if (token === pollToken) state.watching = false;
    }
  }

  function handleStatus(data) {
    const status = (data.status || '').toUpperCase();
    if (!canAcceptBookingUpdate(status)) return;
    if (data.bookingId) state.bookingId = data.bookingId;
    if (status === 'RETRYABLE') showRetryable(data);
    else if (status === 'CONFIRMED') showConfirmed(data);
    else if (status === 'REJECTED') showRejected(data);
    else if (status === 'MANUAL_REVIEW') showManualReview(data);
    else {
      state.booking = { ...data, status: status === 'CONTENDED' ? 'PENDING_CONFIRMATION' : (data.status || 'PENDING_CONFIRMATION') };
      saveActive();
      showPending(`Booking ${state.bookingId}: ${data.status || 'PENDING_CONFIRMATION'}. Do not create another shipment.`);
    }
  }

  // Drafts restore inputs only; rates have no persisted execution to resume.
  function saveDraft() {
    const fields = {};
    for (const field of $('shipping-form').elements) {
      if (field.name) {
        fields[field.name] = field.type === 'checkbox' ? field.checked : field.value;
      }
    }
    try {
      localStorage.setItem(draftStorage, JSON.stringify({
        savedAt: Date.now(),
        userId: cachedSession()?.userId || sessionStorage.getItem(ownerStorage),
        unitSystem: state.unitSystem,
        fields
      }));
    } catch {
      // Input drafts are optional. Active submission persistence is required before dispatch.
    }
  }

  function restoreDraft() {
    try {
      const draft = JSON.parse(localStorage.getItem(draftStorage) || 'null');
      // Discard legacy sample data instead of replaying it against the real backend.
      if (!draft || draft.mock === true || draft.userId !== cachedSession()?.userId
          || Date.now() - draft.savedAt > 86400000) {
        localStorage.removeItem(draftStorage);
        return;
      }
      for (const [name, value] of Object.entries(draft.fields || {})) {
        const field = $('shipping-form').elements.namedItem(name);
        if (!field) continue;
        if (field.type === 'checkbox') field.checked = !!value;
        else field.value = value;
      }
      setUnitSystem(draft.unitSystem);
    } catch {
      localStorage.removeItem(draftStorage);
    }
  }

  function disableExpiredSelection() {
    state.selected = null;
    if ($('review-btn')) $('review-btn').disabled = true;
    if ($('selected-rate-summary')) {
      $('selected-rate-summary').textContent = 'Select a shipping option above to continue';
    }
    if (state.quotes.length) {
      renderQuotes();
    }
  }

  function acceptRateResponse(data, req) {
    if (!data || typeof data !== 'object') {
      throw new Error('Invalid /api/quotes response');
    }
    if (!Array.isArray(data.quotes)) {
      throw new Error('Invalid /api/quotes response: quotes[] missing');
    }

    const discoveredCarriers = [...new Set(data.quotes.map(q => carrier(q)).filter(Boolean))];
    if (discoveredCarriers.length) {
      const merged = [...state.supportedCarriers];
      for (const c of discoveredCarriers) {
        if (!merged.some(existing => existing.toLowerCase() === c.toLowerCase())) {
          merged.push(c);
        }
      }
      state.supportedCarriers = merged;
    }

    state.request = req;
    state.request.shippingDate = data.shippingDate;
    state.quotes = data.quotes;
    state.selected = null;
    routes();
    renderQuotes();
    go(2);
    if (!state.quotes.length) {
      notice('No shipping rates available. Please edit shipment details.', 'info');
    }
  }

  // Step 1 form events
  $('shipping-form').addEventListener('input', () => {
    updateTabSummaries();
    state.revision++;
    if (rateBusy) {
      rateBusy = false;
      stopLoadingBanner();
      busy('get-rates-btn', false);
    }
    if (state.quotes.length) {
      state.quotes = [];
      state.selected = null;
    }
    saveDraft();
  });

  $('shipping-form').addEventListener('submit', async e => {
    e.preventDefault();
    if (state.operation) {
      notice('A shipment submission is already active; resolve it before starting another.');
      return;
    }
    if (rateBusy) return;
    const form = e.currentTarget;
    if (!validate(form)) return;
    const rev = ++state.revision;
    rateBusy = true;
    busy('get-rates-btn', true, 'Getting shipping rates…');
    startRateLoadingBanner();
    try {
      const req = inputPayload(form);
      saveDraft();
      const response = await api.quotes(req);
      if (rev !== state.revision) return;
      acceptRateResponse(response.data, req);
    } catch (err) {
      if (rev === state.revision) {
        if (err instanceof HttpError) {
          showFieldErrors(err.fieldErrors);
          saveDraft();
        }
        notice(errorText(err));
      }
    } finally {
      if (rev === state.revision) {
        rateBusy = false;
        stopLoadingBanner();
        busy('get-rates-btn', false);
      }
    }
  });

  $('sort-select').addEventListener('change', renderQuotes);

  // Step 2 -> Step 3 Review population
  $('review-btn').addEventListener('click', () => {
    const q = state.selected;
    if (!q) return;
    if (q.expiresAt && Date.now() >= new Date(q.expiresAt).getTime() - 15000) {
      disableExpiredSelection();
      notice('These rates have expired. Click Get Rates to refresh them.', 'info');
      return;
    }
    const s = state.request.sender;
    const r = state.request.recipient;
    const p = state.request.parcel;

    $('review-route').textContent = `${s.city}, ${s.province} → ${r.city}, ${r.province}`;
    $('review-package').textContent = formatPackageSummary(p);

    if ($('review-sender-name')) {
      $('review-sender-name').textContent = s.companyName ? `${s.contactName} (${s.companyName})` : s.contactName;
      $('review-sender-street').textContent = [s.addressLine1, s.addressLine2].filter(Boolean).join(', ');
      $('review-sender-city').textContent = `${s.city}, ${s.province} ${s.postalCode} · CA`;
      $('review-sender-contact').textContent = `Tel: ${s.phone}${s.email ? ' · ' + s.email : ''}${s.residential ? ' · Residential' : ''}`;
    }

    if ($('review-recipient-name')) {
      $('review-recipient-name').textContent = r.companyName ? `${r.contactName} (${r.companyName})` : r.contactName;
      $('review-recipient-street').textContent = [r.addressLine1, r.addressLine2].filter(Boolean).join(', ');
      $('review-recipient-city').textContent = `${r.city}, ${r.province} ${r.postalCode} · CA`;
      $('review-recipient-contact').textContent = `Tel: ${r.phone}${r.email ? ' · ' + r.email : ''}${r.residential ? ' · Residential' : ''}`;
    }

    $('review-service').textContent = `${carrier(q)} — ${service(q)}`;
    if ($('review-transit')) {
      $('review-transit').textContent = q.transitDays == null
          ? 'Transit estimate unavailable'
          : `Estimated transit: ${q.transitDays} business ${q.transitDays === 1 ? 'day' : 'days'}`;
    }

    $('cost-base').textContent = fmt(q.base ?? q.baseCharge);
    $('cost-surcharges').textContent = fmt(q.surchargesTotal ?? q.surcharges);
    $('cost-taxes').textContent = fmt(q.taxesTotal ?? q.taxes);
    $('cost-total').textContent = fmt(total(q));

    $('review-view').hidden = false;
    $('success-view').hidden = true;
    $('unknown-view').hidden = true;
    go(3);
  });

  // Step 3 Create Shipment
  $('create-btn').addEventListener('click', async () => {
    if (!state.selected || state.creating) return;
    if (state.operation) {
      showPending('Existing submission detected. Check status; do not create again.');
      return;
    }
    if (state.selected.expiresAt && Date.now() >= new Date(state.selected.expiresAt).getTime() - 15000) {
      state.selected = null;
      state.quotes = [];
      notice('Quote expired or too close to expiration. Get new rates.');
      return;
    }

    const q = state.selected;
    const payload = { providerId: q.providerId, providerQuoteId: q.providerQuoteId,
      shippingInput: state.request, displaySnapshot: {
        carrierId: q.carrierId, carrierName: q.carrierName,
        serviceId: q.serviceId, serviceName: q.serviceName,
        total: q.total, expiresAt: q.expiresAt, expirySource: q.expirySource
      } };
    const operation = { version: 2, userId: cachedSession()?.userId,
      providerId: q.providerId, providerQuoteId: q.providerQuoteId, payload,
      status: 'UNKNOWN', sendAttempted: false };
    const existing = savedOperations.find(op => operationIdentity(op) === operationIdentity(operation));
    if (existing) {
      restoreOperation(existing);
      return;
    }
    state.operation = operation;
    try { saveActive(); } catch {
      state.operation = null;
      notice('Cannot save recovery information. Enable browser storage before submitting.');
      return;
    }
    await sendActive(false);
  });

  async function sendActive(retry) {
    if (!state.operation || state.creating) return;
    const operation = state.operation;
    // Missing markers on older records are conservative: they may already have been sent.
    const firstInitialAttempt = !retry && operation.sendAttempted === false && !state.bookingId;
    operation.sendAttempted = true;
    try { saveActive(); } catch {
      notice('Cannot save recovery information. No request was sent. Enable browser storage before submitting.');
      return;
    }
    const sequence = ++bookingSequence;
    state.replaying = false;
    busy('check-status-btn', false);
    state.creating = true;
    busy('create-btn', true, 'Creating shipment…');
    $('retry-send-btn').disabled = true;
    $('retry-initial-btn').disabled = true;
    startBookingLoadingBanner(carrier(state.selected || {}), service(state.selected || {}));
    try {
      const resp = retry ? await api.retry(state.bookingId) : await api.booking(operation.payload);
      if (state.operation !== operation || sequence !== bookingSequence) return;
      handleBooking(resp.data);
    } catch (err) {
      if (state.operation !== operation || sequence !== bookingSequence) return;
      if ( ['CONFIRMED', 'REJECTED'].includes((state.booking?.status || '').toUpperCase())) return;
      if (handleNotDispatched(err)) return;
      if (handleInitialAdmissionFailure(err, firstInitialAttempt)) return;
      if (handleUnavailableQuote(err, false)) return;
      if (err instanceof HttpError && err.status === 422) {
        showRejected(err.payload);
        return;
      }
      if (err instanceof HttpError && err.status === 409) {
        showPending(`Conflict: ${errorText(err)}. Original submission preserved.`);
        return;
      }
      showPending(`Creation outcome uncertain (${errorText(err)}). Original provider quote identity retained. Check status before any further action.`);
    } finally {
      if (state.operation === operation && sequence === bookingSequence) {
        stopLoadingBanner();
        state.creating = false;
        busy('create-btn', false);
        $('retry-send-btn').disabled = false;
        $('retry-initial-btn').disabled = false;
      }
    }
  }

  $('check-status-btn').addEventListener('click', recoverReadOnly);
  $('retry-send-btn').addEventListener('click', () => {
    if (state.booking?.status === 'RETRYABLE' && state.bookingId) sendActive(true);
  });
  $('retry-initial-btn').addEventListener('click', () => {
    if (!state.bookingId && state.operation?.payload && state.operation?.noRecord) sendActive(false);
  });

  async function recoverReadOnly() {
    if (!state.operation || state.replaying || state.creating) return;
    if (!state.bookingId && !state.operation.providerQuoteId) {
      showPending('This older operation has no usable recovery identity. Contact support; do not assume it was not submitted.');
      return;
    }
    pollToken++;
    const operation = state.operation;
    const sequence = ++bookingSequence;
    state.replaying = true;
    busy('check-status-btn', true, 'Checking…');
    try {
      const r = state.bookingId ? await api.status(state.bookingId) : await api.lookup(operation);
      if (state.operation !== operation || sequence !== bookingSequence) return;
      state.replaying = false;
      busy('check-status-btn', false);
      operation.noRecord = false;
      handleStatus(r.data);
    } catch (e) {
      if (state.operation !== operation || sequence !== bookingSequence) return;
      state.replaying = false;
      busy('check-status-btn', false);
      if (e instanceof HttpError && e.status === 404 && !state.bookingId) {
        operation.noRecord = true;
        saveActive();
        showPending('No booking is visible yet. An earlier request may still arrive. Your original quote identity is retained.');
        $('retry-initial-btn').hidden = !operation.payload;
        if (!operation.payload) notice('Saved submission details are unavailable. Contact support before resubmitting.');
      } else {
        notice('Status check unavailable: ' + errorText(e));
      }
    } finally {
      if (state.operation === operation && sequence === bookingSequence) {
        state.replaying = false;
        state.watching = false;
        busy('check-status-btn', false);
      }
    }
  }

  $('label-link').addEventListener('click', event => {
    if ($('label-link').getAttribute('aria-disabled') === 'true') event.preventDefault();
  });
  $('label-email-link').addEventListener('click', event => {
    if ($('label-email-link').getAttribute('aria-disabled') === 'true') event.preventDefault();
  });
  $('label-download-btn').addEventListener('click', downloadLabel);
  $('label-print-btn').addEventListener('click', () => openLabelViewer(true));
  $('label-share-btn').addEventListener('click', shareLabel);
  $('refresh-label-btn').addEventListener('click', fetchLabel);

  // Copy buttons for Shipment ID & Tracking Number
  $all('[data-copy]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const target = $(btn.dataset.copy);
      if (!target || !navigator.clipboard) return;
      try {
        await navigator.clipboard.writeText(target.textContent.trim());
        const prev = btn.textContent;
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.textContent = prev; }, 1400);
      } catch {
        // Ignore clipboard permission errors in headless environments
      }
    });
  });

  function reset() {
    localStorage.removeItem(draftStorage);
    if (state.operation) saveActive();
    // Leave recovery entries available without restoring this operation on the next reload.
    persistOperations(null);
    state.revision++;
    rateBusy = false;
    stopLoadingBanner();
    pollToken++;
    clearActive();
    state.request = null;
    state.requestId = null;
    state.quotes = [];
    state.selected = null;
    go(1);
  }

  $('new-shipment-btn').addEventListener('click', reset);

  $('unknown-new').addEventListener('click', () => {
    if (state.booking?.status === 'REJECTED' || state.booking?.status === 'RETRYABLE') {
      reset();
      return;
    }
    if (!confirm('The previous shipment outcome may still be unknown. Creating a different request could cause a duplicate charge. Start a new request anyway?')) {
      return;
    }
    reset();
  });

  $all('[data-back]').forEach(el => {
    el.addEventListener('click', () => {
      if (state.operation) {
        showPending('Creation has already been submitted. You cannot change this booking.');
        return;
      }
      go(Number(el.dataset.back));
    });
  });

  restoreDraft();
  updateTabSummaries();
  $all('.unit-btn').forEach(btn => btn.addEventListener('click', () => {
    updateTabSummaries();
    saveDraft();
  }));

  function restoreOperation(operation) {
    if (state.operation) saveActive();
    clearActive();
    state.operation = operation;
    state.bookingId = operation.bookingId || null;
    state.labelAttempted = !!operation.labelAttempted;
    state.selected = operation.payload?.displaySnapshot || null;
    state.savedService = operation.payload?.displaySnapshot?.serviceName || 'Saved shipment';
    if (operation.status === 'MANUAL_REVIEW') {
      showManualReview({ bookingId: state.bookingId, status: operation.status, errorCode: operation.errorCode });
    } else if (operation.status === 'RETRYABLE') {
      showRetryable({ bookingId: state.bookingId, status: 'RETRYABLE' });
    } else {
      showPending('Restored shipment. Checking its status without sending creation.');
    }
    recoverReadOnly();
  }

  // Restore only real operations owned by the signed-in account.
  renderAccount();
  try {
    const userId = cachedSession()?.userId;
    let stored = JSON.parse(localStorage.getItem(activeStorage) || 'null');
    if (!stored && userId) {
      const legacy = JSON.parse(sessionStorage.getItem(legacyActiveStorage)
        || localStorage.getItem(legacyActiveStorage) || 'null');
      if (legacy && legacy.userId === userId && !legacy.mock) {
        const migrated = { version: 2, userId, bookingId: legacy.bookingId || null,
          status: legacy.status || 'UNKNOWN', labelAttempted: legacy.labelAttempted,
          errorCode: legacy.errorCode };
        stored = { version: 2, userId, active: operationIdentity(migrated), operations: [migrated] };
        localStorage.setItem(activeStorage, JSON.stringify(stored));
        sessionStorage.removeItem(legacyActiveStorage);
        localStorage.removeItem(legacyActiveStorage);
      }
    }
    if (userId && stored?.version === 2 && stored.userId === userId) {
      savedOperations = (stored.operations || []).filter(op => op.userId === userId);
      renderSavedOperations();
      const active = savedOperations.find(op => operationIdentity(op) === stored.active);
      if (active) restoreOperation(active);
      else go(1);
    } else go(1);
  } catch {
    go(1);
  }
})();
