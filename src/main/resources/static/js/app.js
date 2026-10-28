(() => {
  'use strict';
  if (window.__SHIPPING_UI_READY__) return;
  window.__SHIPPING_UI_READY__ = true;

  const $ = id => document.getElementById(id);
  const $all = sel => [...document.querySelectorAll(sel)];
  const draftStorage = 'shipping.rateDraft.v1';
  let rateOperation = null;
  let rateBusy = false;
  const activeStorage = 'shipping.activeBooking.v1';
  const mockStorage = 'shipping.mockMode.v1';
  const clientIdStorage = 'shipping.clientId.v1';
  const guestSessionStorage = 'shipping.guestSession.v1';

  const state = {
    step: 1,
    detailTab: 1,
    unitSystem: 'IMPERIAL',
    request: null,
    requestId: null,
    quotes: [],
    selected: null,
    revision: 0,
    key: null,
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
    guestToken: null,
    guestUserId: null,
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

  let mock = location.protocol === 'file:'
      || new URLSearchParams(location.search).get('mock') === '1'
      || localStorage.getItem(mockStorage) === 'true';

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

  function mode(on) {
    mock = on;
    $('mock-toggle').checked = on;
    $('mode-indicator').textContent = on ? 'Demo preview' : 'Backend API';
    $('environment-tag').textContent = on ? '● DEMO PREVIEW' : '● BACKEND API';
    localStorage.setItem(mockStorage, String(on));
  }

  mode(mock);
  $('mock-toggle').addEventListener('change', e => {
    if (state.key) {
      e.target.checked = mock;
      notice('Finish or resolve the active shipment before switching modes.');
      return;
    }
    mode(e.target.checked);
    rateOperation = null;
    saveDraft();
  });

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
    if (state.quotes.length && !state.key) {
      state.quotes = [];
      state.selected = null;
    }
  }

  $all('.unit-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (state.key) return;
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
        if (state.key) {
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

  let sessionPromise = null;
  async function ensureGuestSession(forceRefresh = false) {
    if (mock) return null;
    const fp = await computeClientFingerprint();

    if (!forceRefresh) {
      try {
        const cached = JSON.parse(sessionStorage.getItem(guestSessionStorage) || 'null');
        if (cached?.token && cached?.fingerprint === fp && cached?.expiresAt) {
          const remainingMs = new Date(cached.expiresAt).getTime() - Date.now();
          if (remainingMs > 60000) {
            state.guestToken = cached.token;
            state.guestUserId = cached.userId;
            if (Array.isArray(cached.supportedCarriers) && cached.supportedCarriers.length) {
              state.supportedCarriers = cached.supportedCarriers;
            }
            const avatar = $('user-avatar');
            if (avatar && cached.userId) avatar.title = `Guest session: ${cached.userId}`;
            return cached;
          }
        }
      } catch {
        // Ignore malformed cache and request a fresh guest token
      }
    }

    if (sessionPromise) return sessionPromise;

    sessionPromise = (async () => {
      const clientId = resolveClientId();
      const res = await fetch('/api/auth/guest', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Client-Fingerprint': fp
        },
        body: JSON.stringify({ clientId, fingerprint: fp })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.token) {
        throw new HttpError(res.status, data);
      }
      if (Array.isArray(data.supportedCarriers) && data.supportedCarriers.length) {
        state.supportedCarriers = data.supportedCarriers;
      }
      const session = {
        token: data.token,
        userId: data.userId,
        expiresAt: data.expiresAt,
        fingerprint: fp,
        supportedCarriers: state.supportedCarriers
      };
      sessionStorage.setItem(guestSessionStorage, JSON.stringify(session));
      state.guestToken = session.token;
      state.guestUserId = session.userId;
      const avatar = $('user-avatar');
      if (avatar && session.userId) avatar.title = `Guest session: ${session.userId}`;
      return session;
    })();

    try {
      return await sessionPromise;
    } finally {
      sessionPromise = null;
    }
  }

  async function http(path, { method = 'GET', body = null, headers = {}, retriedAuth = false } = {}) {
    const session = await ensureGuestSession(false);
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
      if (res.status === 401 && !retriedAuth) {
        sessionStorage.removeItem(guestSessionStorage);
        await ensureGuestSession(true);
        return http(path, { method, body, headers, retriedAuth: true });
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
  const seed = [
    ['Canpar', 'Ground', 27.35, 2],
    ['Canada Post', 'Expedited Parcel', 34.02, 3],
    ['Canada Post', 'Regular Parcel', 34.27, 5],
    ['Loomis', 'Loomis Ground', 35.93, 2],
    ['GLS', 'Ground', 39.66, 2]
  ];

  const demo = {
    async quotes() {
      await delay(450);
      return {
        status: 201,
        data: {
          requestId: 'demo-request-' + Date.now(),
          state: 'READY',
          shippingDate: '2026-10-23',
          quotes: seed.map((r, i) => ({
            quoteId: 'demo-quote-' + i,
            carrierName: r[0],
            serviceName: r[1],
            total: { amount: r[2].toFixed(2), currency: 'CAD' },
            transitDays: r[3],
            base: null,
            surcharges: null,
            taxes: null,
            expiresAt: new Date(Date.now() + 14 * 60000).toISOString()
          }))
        }
      };
    },
    async booking(quote, key) {
      await delay(650);
      return {
        status: 201,
        data: {
          bookingId: 'demo-booking-' + key,
          status: 'CONFIRMED',
          shipmentId: 'DEMO-104582',
          trackingNumbers: ['DEMO-TRACK-001'],
          labelStatus: 'PENDING',
          statusUrl: '/api/bookings/demo-booking-' + key
        }
      };
    },
    async status(id) {
      await delay(250);
      return {
        status: 200,
        data: {
          bookingId: id,
          status: 'CONFIRMED',
          shipmentId: 'DEMO-104582',
          trackingNumbers: ['DEMO-TRACK-001'],
          labelStatus: 'PENDING'
        }
      };
    }
  };

  const api = {
    quotes: (data, key) => mock
        ? demo.quotes()
        : http('/api/quotes', { method: 'POST', body: data, headers: { 'Idempotency-Key': key } }),
    quoteStatus: id => http('/api/quotes/' + encodeURIComponent(id)),
    booking: (quote, key) => mock
        ? demo.booking(quote, key)
        : http('/api/bookings', { method: 'POST', body: { quoteId: quote }, headers: { 'Idempotency-Key': key } }),
    status: id => mock ? demo.status(id) : http(`/api/bookings/${encodeURIComponent(id)}`)
  };

  const quoteId = q => q.quoteId;
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
        tags.push(`<span>Valid until ${esc(new Date(q.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))}</span>`);
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
    if (!state.key) return;
    sessionStorage.setItem(activeStorage, JSON.stringify({
      key: state.key,
      quoteId: quoteId(state.selected),
      bookingId: state.bookingId,
      carrierName: carrier(state.selected),
      serviceName: service(state.selected),
      labelAttempted: state.labelAttempted,
      status: state.booking?.status,
      errorCode: state.booking?.errorCode,
      mock
    }));
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
    sessionStorage.removeItem(activeStorage);
    state.key = null;
    state.bookingId = null;
    state.booking = null;
    state.creating = false;
    state.replaying = false;
    updateLabelActions();
  }

  function showPending(message) {
    if (state.booking?.status === 'MANUAL_REVIEW') {
      showManualReview(state.booking);
      return;
    }
    $('unknown-new').hidden = false;
    $('outcome-title').textContent = 'Pending confirmation';
    $('outcome-explanation').textContent = 'The provider may have accepted this request. Keep the original operation identity; do not submit it again with a new key.';
    $('unknown-new').textContent = 'Start a different shipment';
    $('check-status-btn').hidden = false;
    $('check-status-btn').textContent = 'Check Status';
    $('check-status-btn').dataset.original = 'Check Status';
    go(3);
    $('review-view').hidden = true;
    $('success-view').hidden = true;
    $('unknown-view').hidden = false;
    $('unknown-id').textContent = message || 'Creation outcome is not confirmed. Do not resubmit with a new key.';
    $('check-status-btn').disabled = !(state.bookingId || (state.key && state.selected));
  }

  function showRetryable(data) {
    state.booking = { ...data, status: 'RETRYABLE' };
    if (data.bookingId) state.bookingId = data.bookingId;
    showPending(`Submission was not sent. ${state.bookingId ? 'Booking: ' + state.bookingId : ''}`);
    $('outcome-title').textContent = 'Shipment not submitted';
    $('outcome-explanation').textContent = 'Local capacity protection stopped this attempt before it was sent. Retry the same shipment with its original key.';
    $('check-status-btn').textContent = 'Retry shipment';
    $('check-status-btn').dataset.original = 'Retry shipment';
    $('check-status-btn').disabled = !(state.key && state.selected) || state.replaying;
    pollToken++;
    saveActive();
  }

  function handleNotDispatched(error) {
    if (!(error instanceof HttpError) || error.code !== 'SHIPMENT_SUBMISSION_NOT_DISPATCHED') return false;
    showRetryable({ bookingId: error.payload?.operation?.id || state.bookingId });
    return true;
  }

  function handleUnavailableQuote(error, returnToStep1 = false) {
    if (!(error instanceof HttpError)) return false;
    if (!['QUOTE_EXPIRED', 'QUOTE_NOT_BOOKABLE', 'REQUEST_DATE_EXPIRED'].includes(error.code)) {
      return false;
    }
    if (error.code === 'REQUEST_DATE_EXPIRED') {
      rateOperation = null;
      saveDraft();
    }
    clearActive();
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
    $('unknown-new').hidden = true;
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
    $('label-status-text').textContent = mock
        ? 'Demo preview: no real shipment or label was created.'
        : state.labelTarget
            ? 'Shipment confirmed. Label available.'
            : 'Shipment confirmed. Retrieve the label to view, download or print it.';

    if (!mock && !state.labelTarget && !state.labelAttempted) {
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
    const ready = !!state.labelTarget && !state.labelBusy && !mock;
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
      $('refresh-label-btn').hidden = mock;
      $('refresh-label-btn').disabled = state.labelBusy;
      if (!state.labelBusy) {
        $('refresh-label-btn').textContent = state.labelTarget ? 'Reload label' : 'Retry label';
      }
    }
    if ($('label-action-help')) {
      $('label-action-help').textContent = mock
          ? 'Demo mode has no real label. Start a new shipment in Backend API mode to retrieve one.'
          : !ready
              ? 'Retrieve the label to enable these actions. Creation will not be repeated.'
              : state.labelFile
                  ? 'Email opens your mail app; attach the downloaded PDF yourself. Sharing depends on browser support.'
                  : 'Provider-hosted label: use its viewer to save or print. Download the PDF before attaching or sharing it.';
    }
  }

  function openLabelViewer(printing = false) {
    if (!state.labelTarget || state.labelBusy || mock) return;
    window.open(state.labelTarget, '_blank', 'noopener,noreferrer');
    notice(printing
        ? 'Use the print button in the PDF viewer, or Ctrl/Cmd+P. If no tab opens, allow pop-ups for this site.'
        : 'Use Save or Download in the provider PDF viewer. If no tab opens, allow pop-ups for this site.', 'info');
  }

  function downloadLabel() {
    if (!state.labelTarget || state.labelBusy || mock) return;
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
    if (!state.labelTarget || state.labelBusy || mock) return;
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
    if (!state.bookingId || mock || state.labelBusy) return;
    const bookingId = state.bookingId;
    state.labelBusy = true;
    busy('refresh-label-btn', true, 'Checking label…');
    updateLabelActions();
    try {
      const session = await ensureGuestSession(false);
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

  function handleBooking(data) {
    state.booking = data;
    if (data.bookingId) state.bookingId = data.bookingId;
    saveActive();
    const status = (data.status || '').toUpperCase();
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
    showPending(`Pending confirmation (${status || 'UNKNOWN'}). ${state.bookingId ? 'Booking: ' + state.bookingId : 'Response lost; original key retained.'}`);
    if (state.bookingId) poll();
  }

  let pollToken = 0;
  async function poll() {
    if (!state.bookingId || state.watching) return;
    state.watching = true;
    const token = ++pollToken;
    for (let n = 0; n < 5 && token === pollToken; n++) {
      try {
        const reply = await api.status(state.bookingId);
        handleStatus(reply.data);
        if (['CONFIRMED', 'REJECTED', 'MANUAL_REVIEW', 'RETRYABLE'].includes((reply.data.status || '').toUpperCase())) break;
      } catch (e) {
        notice('Status check unavailable: ' + errorText(e));
      }
      if (n < 4) await delay(Math.min(1200 * 2 ** n, 8000));
    }
    state.watching = false;
  }

  function handleStatus(data) {
    state.booking = data;
    saveActive();
    if ((data.status || '').toUpperCase() === 'RETRYABLE') showRetryable(data);
    else if ((data.status || '').toUpperCase() === 'CONFIRMED') showConfirmed(data);
    else if ((data.status || '').toUpperCase() === 'REJECTED') showRejected(data);
    else if ((data.status || '').toUpperCase() === 'MANUAL_REVIEW') showManualReview(data);
    else showPending(`Booking ${state.bookingId}: ${data.status || 'PENDING_CONFIRMATION'}. Do not create another shipment.`);
  }

  // Local drafts improve navigation only. The backend owns the durable customer request;
  // neither browser storage nor a third-party provider is our business system of record.
  // Do not store credentials, labels or booking responses in this draft.
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
        mock,
        unitSystem: state.unitSystem,
        fields,
        operation: rateOperation
      }));
    } catch {
      // Storage may be disabled; server-side request idempotency still applies.
    }
  }

  function restoreDraft() {
    try {
      const draft = JSON.parse(localStorage.getItem(draftStorage) || 'null');
      if (!draft || Date.now() - draft.savedAt > 86400000) {
        localStorage.removeItem(draftStorage);
        return;
      }
      for (const [name, value] of Object.entries(draft.fields || {})) {
        const field = $('shipping-form').elements.namedItem(name);
        if (!field) continue;
        if (field.type === 'checkbox') field.checked = !!value;
        else field.value = value;
      }
      if (draft.mock === mock) rateOperation = draft.operation;
      setUnitSystem(draft.unitSystem);
    } catch {
      localStorage.removeItem(draftStorage);
    }
  }

  function displayRates(data, req) {
    if (data.state === 'RATING') {
      notice('Rates are still being retrieved. Click Get Rates again to check or retry safely.', 'info');
      return;
    }
    if (data.state === 'RATE_FAILED' || data.state === 'QUOTES_EXPIRED') {
      notice(data.state === 'RATE_FAILED'
          ? 'Rates could not be retrieved. Click Get Rates to retry the saved request.'
          : 'These rates have expired. Click Get Rates to refresh them.', 'info');
      return;
    }
    if (!Array.isArray(data.quotes)) throw Error('Invalid /api/quotes response: quotes[] missing');
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
    state.requestId = data.requestId;
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
    saveDraft();
    state.revision++;
    if (state.quotes.length && !state.key) {
      state.quotes = [];
      state.selected = null;
    }
  });

  $('shipping-form').addEventListener('submit', async e => {
    e.preventDefault();
    if (state.key) {
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
      // Save identity before sending: a lost HTTP response must reuse the same key.
      const signature = JSON.stringify(req);
      if (!rateOperation || rateOperation.signature !== signature) {
        const key = crypto.randomUUID
            ? crypto.randomUUID()
            : `rate-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
        rateOperation = { key, signature, requestId: null };
      }
      const operation = rateOperation;
      saveDraft();
      let response = await api.quotes(req, operation.key);
      operation.requestId = response.data.requestId;
      saveDraft();
      // Status reads never trigger a provider request. Retries remain user initiated.
      for (let i = 0; !mock && response.data.state === 'RATING' && i < 5; i++) {
        await delay(2000);
        if (rev !== state.revision) return;
        response = await api.quoteStatus(operation.requestId);
      }
      if (rev !== state.revision) return;
      displayRates(response.data, req);
    } catch (err) {
      if (rev === state.revision) {
        if (err instanceof HttpError) {
          showFieldErrors(err.fieldErrors);
          if (rateOperation && err.payload?.operation?.id) {
            rateOperation.requestId = err.payload.operation.id;
          }
          if (err.code === 'REQUEST_DATE_EXPIRED') {
            rateOperation = null;
          }
          saveDraft();
        }
        notice(errorText(err));
      }
    } finally {
      rateBusy = false;
      stopLoadingBanner();
      busy('get-rates-btn', false);
    }
  });

  $('sort-select').addEventListener('change', renderQuotes);

  // Step 2 -> Step 3 Review population
  $('review-btn').addEventListener('click', () => {
    const q = state.selected;
    if (!q) return;
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
    if (state.key) {
      showPending('Existing submission detected. Check status; do not create again.');
      return;
    }
    if (state.selected.expiresAt && Date.now() >= new Date(state.selected.expiresAt).getTime() - 15000) {
      state.selected = null;
      state.quotes = [];
      notice('Quote expired or too close to expiration. Get new rates.');
      return;
    }

    state.key = crypto.randomUUID ? crypto.randomUUID() : `demo-${Date.now()}-${Math.random()}`;
    saveActive();
    state.creating = true;
    busy('create-btn', true, 'Creating shipment…');
    startBookingLoadingBanner(carrier(state.selected), service(state.selected));
    try {
      const resp = await api.booking(quoteId(state.selected), state.key);
      handleBooking(resp.data);
    } catch (err) {
      if (handleNotDispatched(err)) return;
      if (handleUnavailableQuote(err, false)) return;
      if (err instanceof HttpError && err.status === 422) {
        showRejected(err.payload);
        return;
      }
      if (err instanceof HttpError && err.code === 'SHIPMENT_ALREADY_SUBMITTED' && err.payload?.booking) {
        handleBooking(err.payload.booking);
        return;
      }
      if (err instanceof HttpError && err.status === 409) {
        showPending(`Conflict: ${errorText(err)}. Original submission preserved.`);
        return;
      }
      showPending(`Creation outcome uncertain (${errorText(err)}). Original Idempotency-Key preserved. Never submit with a new key.`);
    } finally {
      stopLoadingBanner();
      state.creating = false;
      busy('create-btn', false);
    }
  });

  $('check-status-btn').addEventListener('click', () => {
    if (state.booking?.status === 'RETRYABLE') recoverByReplay();
    else if (state.bookingId) poll();
    else if (state.key && state.selected) recoverByReplay();
  });

  async function recoverByReplay() {
    if (!state.key || !state.selected || state.replaying || state.watching) return;
    pollToken++;
    state.replaying = true;
    busy('check-status-btn', true, 'Checking…');
    try {
      const r = await api.booking(quoteId(state.selected), state.key);
      handleBooking(r.data);
    } catch (e) {
      if (handleNotDispatched(e)) return;
      if (handleUnavailableQuote(e, true)) return;
      state.booking = { bookingId: state.bookingId, status: 'PENDING_CONFIRMATION' };
      saveActive();
      showPending('Could not confirm creation: ' + errorText(e) + '. Retry with the SAME key only.');
    } finally {
      state.replaying = false;
      busy('check-status-btn', false);
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
    rateOperation = null;
    localStorage.removeItem(draftStorage);
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
      if (state.key) {
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

  function refreshRestoredStatus() {
    if (!state.bookingId) return;
    const token = ++pollToken;
    const bookingId = state.bookingId;
    const key = state.key;
    api.status(bookingId)
      .then(reply => {
        if (token !== pollToken || state.replaying || state.bookingId !== bookingId || state.key !== key) return;
        handleStatus(reply.data);
      })
      .catch(error => {
        if (token !== pollToken || state.replaying || state.bookingId !== bookingId || state.key !== key) return;
        notice(errorText(error));
      });
  }

  // Restore active submission on page reload and eagerly initialize the 2-hour Guest Token
  if (!mock) {
    ensureGuestSession(false).catch(() => {});
  }
  try {
    const previous = JSON.parse(sessionStorage.getItem(activeStorage) || 'null');
    if (previous?.key && previous?.quoteId) {
      state.labelAttempted = !!previous.labelAttempted;
      state.key = previous.key;
      state.bookingId = previous.bookingId || null;
      state.selected = {
        quoteId: previous.quoteId,
        carrierName: previous.carrierName,
        serviceName: previous.serviceName
      };
      state.savedService = `${previous.carrierName || ''} · ${previous.serviceName || ''}`;
      if (mock !== previous.mock) mode(previous.mock);
      if (previous.status === 'RETRYABLE') {
        showRetryable({ bookingId: state.bookingId });
        refreshRestoredStatus();
      } else if (previous.status === 'MANUAL_REVIEW') {
        showManualReview({ bookingId: state.bookingId, status: 'MANUAL_REVIEW', errorCode: previous.errorCode });
        // One fresh read may discover a late confirmation; do not restart polling.
        refreshRestoredStatus();
      } else {
        showPending(`Restored active shipment submission. ${state.bookingId ? 'Booking: ' + state.bookingId : 'Click Check Status to replay with the original key.'}`);
        if (state.bookingId) poll();
      }
    } else {
      go(1);
    }
  } catch {
    go(1);
  }
})();
