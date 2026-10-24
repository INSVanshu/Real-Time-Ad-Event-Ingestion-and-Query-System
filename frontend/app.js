/**
 * app.js - dashboard logic: event simulator, bipartite graph drawing, segment view and query panel.
 * All data goes through Api (Java server or in-browser engine).
 */
const $ = id => document.getElementById(id);

// ---------------------------------------------------------------- simulation settings
const CAMPAIGNS = Array.from({ length: 12 }, (_, i) => `c${i + 1}`);
const USERS = Array.from({ length: 150 }, (_, i) => `u${i + 1}`);
const WEIGHTS = CAMPAIGNS.map((_, i) => 1 / (i + 1));                 // skewed popularity: c1 is the hot campaign
const WEIGHT_SUM = WEIGHTS.reduce((a, b) => a + b, 0);
const START = 8 * 3600;                                               // simulation starts at 08:00
const TICK_MS = 250;                                                  // one tick = 30 simulated seconds
const TICK_SECONDS = 30;
const EVENTS_PER_TICK = 25;

let simTime = START;
let timer = null;
let busy = false;

function pickCampaign() {
  let r = Math.random() * WEIGHT_SUM;
  for (let i = 0; i < CAMPAIGNS.length; i++) { r -= WEIGHTS[i]; if (r <= 0) return CAMPAIGNS[i]; }
  return CAMPAIGNS[0];
}

function pickType() {
  const r = Math.random();
  return r < 0.90 ? 'imp' : r < 0.98 ? 'click' : 'conv';              // 90 % / 8 % / 2 %
}

/** n events spread over the last `span` simulated seconds; ~10 % arrive up to 5 s late (jitter). */
function makeEvents(n, span) {
  const events = [];
  for (let i = 0; i < n; i++) {
    let t = simTime - Math.floor(Math.random() * span);
    if (Math.random() < 0.1) t -= Math.floor(Math.random() * 6);
    events.push({ t: Math.max(0, t), c: pickCampaign(), u: USERS[Math.floor(Math.random() * USERS.length)], type: pickType() });
  }
  return events;
}

// ---------------------------------------------------------------- formatting helpers
const pad = n => String(n).padStart(2, '0');
const fmt = t => `${pad(Math.floor(t / 3600))}:${pad(Math.floor(t % 3600 / 60))}`;
const esc = s => String(s).replace(/[&<>"]/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]));

function parseTime(text) {
  const m = /^(\d{1,3}):(\d{2})$/.exec(text.trim());
  if (!m) throw new Error('Time must look like 09:30');
  return Number(m[1]) * 3600 + Number(m[2]) * 60;
}

function bytes(n) {
  return n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(2)} MB`;
}

// ---------------------------------------------------------------- stream / burst / expire / reset
async function tick() {
  if (busy) return;
  busy = true;
  try {
    simTime += TICK_SECONDS;
    await Api.addEvents(makeEvents(EVENTS_PER_TICK, TICK_SECONDS));
    await refresh();
  } catch (e) { stop(); alert(e.message); }
  busy = false;
}

function start() {
  timer = setInterval(tick, TICK_MS);
  $('btn-stream').textContent = 'Pause stream';
}

function stop() {
  clearInterval(timer);
  timer = null;
  $('btn-stream').textContent = 'Start live stream';
}

$('btn-stream').onclick = () => (timer ? stop() : start());

$('btn-burst').onclick = async () => {
  simTime += 600;                                                      // jump 10 simulated minutes
  await Api.addEvents(makeEvents(1000, 600));
  await refresh();
};

$('btn-drop').onclick = async () => {
  const { dropped } = await Api.drop(simTime - 3600);
  $('q-info').textContent = `Expired ${dropped} edges by dropping whole hourly segments (no data was rewritten).`;
  await refresh();
};

$('btn-reset').onclick = async () => {
  stop();
  await Api.reset();
  simTime = START;
  $('q-result').innerHTML = '';
  $('q-info').textContent = '';
  await refresh();
};

$('btn-connect').onclick = async () => {
  await Api.connect($('backend-url').value);
  await refresh();
};

// ---------------------------------------------------------------- rendering
async function refresh() {
  const [stats, sample] = await Promise.all([Api.stats(), Api.sample(simTime - 900, simTime + 1, 48)]);
  renderMode();
  renderStats(stats);
  renderSegments(stats.segmentList);
  drawGraph(sample.edges);
  $('clock').textContent = fmt(simTime);
}

function renderMode() {
  const server = Api.mode() === 'server';
  $('mode').textContent = server ? 'Backend: Java server' : 'Backend: in-browser engine';
  $('mode').className = 'badge ' + (server ? 'server' : 'local');
}

function renderStats(s) {
  const cards = [
    [s.edges.toLocaleString(), 'edges stored'],
    [`${s.sealed} / ${s.segments}`, 'segments sealed'],
    [s.campaigns, 'campaigns'],
    [s.users, 'users'],
    [s.rejected, 'rejected (too late / invalid)'],
    [bytes(s.edgeBytes), 'edge data (report layout, 26 B per event)'],
  ];
  $('stats').innerHTML = cards.map(([v, l]) => `<div class="card"><b>${esc(v)}</b><span>${l}</span></div>`).join('');
}

function renderSegments(list) {
  if (!list.length) { $('segments').innerHTML = '<p class="empty">No segments yet. Start the stream.</p>'; return; }
  const max = Math.max(...list.map(s => s.edges));
  $('segments').innerHTML = list.map(s => `
    <div class="seg ${s.sealed ? 'sealed' : ''}">
      <span>${pad(s.hour)}:00 - ${pad(s.hour + 1)}:00</span>
      <div class="bar" style="width:${Math.max(2, 100 * s.edges / max)}%"></div>
      <span class="tag">${s.edges} ${s.sealed ? 'sealed' : 'hot'}</span>
    </div>`).join('');
}

const byNumber = (a, b) => Number(a.slice(1)) - Number(b.slice(1));

function drawGraph(edges) {
  const cv = $('graph'), g = cv.getContext('2d'), W = cv.width, H = cv.height;
  const css = getComputedStyle(document.documentElement);
  const color = name => css.getPropertyValue(name).trim();
  g.clearRect(0, 0, W, H);

  if (!edges.length) {
    g.fillStyle = color('--muted');
    g.font = '15px system-ui';
    g.fillText('Events will appear here as edges between campaigns and users.', 150, H / 2);
    return;
  }

  const cs = [...new Set(edges.map(e => e.c))].sort(byNumber);
  const us = [...new Set(edges.map(e => e.u))].sort(byNumber);
  const visible = edges;
  const pos = (list, i) => 24 + i * (H - 48) / Math.max(1, list.length - 1);
  const xC = 90, xU = W - 90;

  const style = { imp: [color('--imp'), 1, 0.35], click: [color('--click'), 1.8, 0.9], conv: [color('--conv'), 2.6, 0.95] };
  for (const type of ['imp', 'click', 'conv']) {                      // draw the rare, important edges last (on top)
    const [stroke, width, alpha] = style[type];
    g.strokeStyle = stroke; g.lineWidth = width; g.globalAlpha = alpha;
    for (const e of visible.filter(x => x.type === type)) {
      g.beginPath();
      g.moveTo(xC, pos(cs, cs.indexOf(e.c)));
      g.lineTo(xU, pos(us, us.indexOf(e.u)));
      g.stroke();
    }
  }
  g.globalAlpha = 1;

  g.font = '11px system-ui';
  g.fillStyle = color('--text');
  cs.forEach((c, i) => { g.beginPath(); g.arc(xC, pos(cs, i), 6, 0, 7); g.fill(); g.textAlign = 'right'; g.fillText(c, xC - 12, pos(cs, i) + 4); });
  us.forEach((u, i) => { g.beginPath(); g.arc(xU, pos(us, i), 4, 0, 7); g.fill(); g.textAlign = 'left'; g.fillText(u, xU + 10, pos(us, i) + 3); });
}

// ---------------------------------------------------------------- query panel
function setupQueryForm() {
  $('q-campaign').innerHTML = CAMPAIGNS.map(c => `<option>${c}</option>`).join('');
  const sync = () => {
    const t = $('q-type').value;
    $('q-campaign').parentElement.hidden = t === 'user';
    $('q-user').parentElement.hidden = !(t === 'user' || t === 'frequency');
  };
  $('q-type').onchange = sync;
  sync();
}

$('btn-last').onclick = () => {
  $('q-from').value = fmt(Math.max(0, simTime - 3600));
  $('q-to').value = fmt(simTime + 60);
};

$('btn-run').onclick = async () => {
  const type = $('q-type').value, c = $('q-campaign').value, u = $('q-user').value.trim();
  try {
    const from = parseTime($('q-from').value), to = parseTime($('q-to').value);
    const started = performance.now();
    let html = '';
    if (type === 'campaign' || type === 'user') {
      const r = type === 'campaign' ? await Api.campaign(c, from, to) : await Api.user(u, from, to);
      html = edgeTable(r);
    } else if (type === 'reach') {
      html = `<div class="big">${(await Api.reach(c, from, to)).reach}</div><div class="hint">distinct users reached by ${esc(c)}</div>`;
    } else if (type === 'frequency') {
      html = `<div class="big">${(await Api.frequency(c, u, from, to)).impressions}</div><div class="hint">impressions of ${esc(c)} shown to ${esc(u)}</div>`;
    } else {
      html = relatedTable(await Api.related(c, from, to));
    }
    const ms = (performance.now() - started).toFixed(2);
    $('q-info').textContent = `Answered in ${ms} ms via ${Api.mode() === 'server' ? 'the Java server (incl. network)' : 'the in-browser engine'}.`;
    $('q-result').innerHTML = html;
  } catch (e) {
    $('q-info').textContent = e.message;
    $('q-result').innerHTML = '';
  }
};

function edgeTable(r) {
  if (!r.count) return '<p class="empty">No events in this window.</p>';
  const rows = r.edges.slice(0, 200).map(e =>
    `<tr><td>${fmt(e.t)}</td><td>${esc(e.c)}</td><td>${esc(e.u)}</td><td class="t-${e.type}">${e.type}</td></tr>`).join('');
  const more = r.count > 200 ? `<p class="hint">Showing the first 200 of ${r.count} edges.</p>` : `<p class="hint">${r.count} edges.</p>`;
  return `${more}<table><tr><th>Time</th><th>Campaign</th><th>User</th><th>Type</th></tr>${rows}</table>`;
}

function relatedTable(r) {
  if (!r.related.length) return '<p class="empty">No related campaigns (needs users who clicked this campaign in the window).</p>';
  const rows = r.related.map(x => `<tr><td>${esc(x.campaign)}</td><td>${x.sharedUsers}</td></tr>`).join('');
  return `<table><tr><th>Campaign</th><th>Shared users</th></tr>${rows}</table>`;
}

// ---------------------------------------------------------------- start
(async function init() {
  setupQueryForm();
  if (location.hostname.endsWith('.github.io')) {                     // <user>.github.io/<repo>/  ->  github.com/<user>/<repo>
    $('repo-link').href = `https://github.com/${location.hostname.split('.')[0]}/${location.pathname.split('/')[1]}`;
  }
  const fromServer = location.protocol.startsWith('http');            // opened from the Java server or Pages?
  await Api.connect(fromServer ? '' : 'http://localhost:8080');
  await refresh();
})();
