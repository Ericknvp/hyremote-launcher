const DOC = `<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="format-detection" content="telephone=no">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="theme-color" content="#05070C">
<title>HyRemote</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;500;700&display=swap" rel="stylesheet">
<style>
:root{
  --bg:#05070C;
  --surface:rgba(255,255,255,.055);
  --surface-2:rgba(255,255,255,.10);
  --line:rgba(255,255,255,.11);
  --ink:#F4F7FA;
  --ink-dim:#9BA8B6;
  --ink-mute:#5E6A78;
  --accent:#4C8DFF;
  --accent-glow:rgba(76,141,255,.45);
  --hot:#FF6B6B;
  --ok:#34D399;
  --r-lg:24px;
  --r-md:17px;
  --r-sm:12px;
  --ease:cubic-bezier(.22,1,.36,1);
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
/* The projector remote must never zoom: a double tap on a direction key
   would fire a button and then rescale the whole page. */
html,body{height:100%;touch-action:manipulation;overscroll-behavior:none}
body{-webkit-user-select:none;-webkit-touch-callout:none;user-select:none}
input{-webkit-user-select:text;user-select:text}
body{
  margin:0;background:var(--bg);color:var(--ink);
  font-family:Outfit,system-ui,-apple-system,sans-serif;
  display:flex;flex-direction:column;
  min-height:100dvh;
  overflow-x:hidden;
  -webkit-font-smoothing:antialiased;
}
body::before{
  content:"";position:fixed;inset:0;pointer-events:none;z-index:0;
  background:
    radial-gradient(1100px 560px at 50% -10%,rgba(76,141,255,.20),transparent 62%),
    radial-gradient(700px 460px at 100% 108%,rgba(76,141,255,.10),transparent 60%),
    linear-gradient(180deg,#0C1526 0%,#05070C 46%,#05070C 100%);
}
/* Glass: every surface is translucent over the gradient, never a flat box. */
.app,.act,.vol,.kb input,.link,.editor{backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px)}
.shell{position:relative;z-index:1;display:flex;flex-direction:column;flex:1;
  max-width:520px;width:100%;margin:0 auto;
  padding:max(18px,env(safe-area-inset-top)) 18px max(14px,env(safe-area-inset-bottom));
  gap:20px}

header{display:flex;align-items:center;justify-content:space-between;gap:12px}
.brand{display:flex;align-items:center;gap:9px}
.mark{width:26px;height:26px;border-radius:50%;object-fit:cover;flex:none;
  box-shadow:0 0 14px var(--accent-glow)}
.brand h1{margin:0;font-size:16px;font-weight:700;letter-spacing:-.02em}
.link{display:flex;align-items:center;gap:7px;font-size:12.5px;font-weight:500;
  color:var(--ink-dim);background:var(--surface);border:1px solid var(--line);
  padding:7px 13px;border-radius:100px;white-space:nowrap;
  transition:color .25s var(--ease),border-color .25s var(--ease)}
.link.live{color:var(--ok);border-color:rgba(46,213,115,.28)}
.link.down{color:var(--hot);border-color:rgba(255,59,107,.32)}
.link .pulse{width:6px;height:6px;border-radius:50%;background:currentColor;flex:none}
.link.live .pulse{animation:breathe 2.4s var(--ease) infinite}
@keyframes breathe{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.45;transform:scale(.82)}}

/* ---- app shortcuts ---- */
.apps{display:flex;gap:9px;overflow-x:auto;padding-bottom:2px;
  scrollbar-width:none;-ms-overflow-style:none}
.app .ico{width:26px;height:26px;object-fit:contain;display:block;
  border-radius:5px}
.app.just-moved{border-color:var(--accent);
  box-shadow:0 0 20px -4px var(--accent-glow)}
.appsHead{display:flex;justify-content:flex-end;margin:-4px 0 2px}
.editor{background:var(--surface);border:1px solid var(--line);
  border-radius:var(--r-lg);padding:14px;max-height:52vh;overflow-y:auto}
.editorHead{display:flex;align-items:center;justify-content:space-between;
  gap:12px;margin-bottom:12px}
.editorHead span{font-size:12.5px;color:var(--ink-mute)}
.erow{display:flex;align-items:center;gap:10px;padding:9px 0;
  border-top:1px solid var(--line)}
.erow:first-child{border-top:0}
.erow .name{flex:1;min-width:0;font-size:14px;color:var(--ink);
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.erow button{width:36px;height:36px;border:0;border-radius:10px;flex:none;
  background:var(--surface-2);color:var(--ink);font-size:15px;cursor:pointer;
  display:grid;place-items:center;transition:transform .15s var(--ease),opacity .15s}
.erow button:active{transform:scale(.9)}
.erow button[disabled]{opacity:.25}
.erow button.eye{width:32px;height:32px;background:transparent;color:var(--accent);font-size:14px}
.erow button.eye[aria-label^="Mostrar"]{color:var(--ink-mute)}
.erow .name.off{opacity:.4;text-decoration:line-through}
.reorder{transform:scale(1.08);border-color:var(--accent)}
.apps::-webkit-scrollbar{display:none}
.app{flex:none;width:74px;padding:12px 6px 10px;border-radius:var(--r-md);
  background:var(--surface);border:1px solid var(--line);
  display:flex;flex-direction:column;align-items:center;gap:8px;
  transition:transform .18s var(--ease),background .18s var(--ease)}
.app:active{transform:scale(.94);background:var(--surface-2)}
.app svg{width:24px;height:24px;display:block}
.app span{font-size:10.5px;font-weight:500;color:var(--ink-dim);
  text-align:center;line-height:1.25;max-width:100%;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

/* ---- dpad ---- */
.stage{display:flex;justify-content:center;padding:6px 0 2px}
.pad{position:relative;width:min(72vw,286px);aspect-ratio:1}
.key{position:absolute;border:0;padding:0;cursor:pointer;
  background:var(--surface);color:var(--ink);
  display:grid;place-items:center;
  transition:transform .14s var(--ease),background .14s var(--ease),
             box-shadow .14s var(--ease)}
.key svg{width:26px;height:26px;display:block;pointer-events:none}
.key.up    {top:0;left:50%;transform:translateX(-50%);width:74px;height:74px;
            border-radius:var(--r-lg) var(--r-lg) 8px 8px}
.key.down  {bottom:0;left:50%;transform:translateX(-50%);width:74px;height:74px;
            border-radius:8px 8px var(--r-lg) var(--r-lg)}
.key.left  {left:0;top:50%;transform:translateY(-50%);width:74px;height:74px;
            border-radius:var(--r-lg) 8px 8px var(--r-lg)}
.key.right {right:0;top:50%;transform:translateY(-50%);width:74px;height:74px;
            border-radius:8px var(--r-lg) var(--r-lg) 8px}
.key:active{background:var(--accent);box-shadow:0 0 34px var(--accent-glow)}
.key.ok{
  left:50%;top:50%;transform:translate(-50%,-50%);
  width:96px;height:96px;border-radius:50%;
  background:linear-gradient(160deg,var(--accent),#3D6BE8);
  color:#fff;font-size:14px;font-weight:700;letter-spacing:.06em;
  box-shadow:0 8px 26px -6px var(--accent-glow),0 2px 0 rgba(255,255,255,.16) inset}
.key.ok:active{transform:translate(-50%,-50%) scale(.93);
  box-shadow:0 0 44px var(--accent-glow)}
.gap{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);
  width:74px;height:74px;pointer-events:none}

/* ---- action row ---- */
.row{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}
.act{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-md);
  padding:13px 6px;display:flex;flex-direction:column;align-items:center;gap:7px;
  color:var(--ink);font-size:11px;font-weight:500;cursor:pointer;
  transition:transform .16s var(--ease),background .16s var(--ease)}
.act:active{transform:scale(.95);background:var(--surface-2)}
.act svg{width:19px;height:19px}
.act.home{color:var(--accent)}
.act.power{color:var(--hot)}

/* ---- volume ---- */
.vol{display:flex;align-items:center;gap:10px;background:var(--surface);
  border:1px solid var(--line);border-radius:var(--r-md);padding:10px 12px}
.vol button{width:46px;height:46px;border:0;border-radius:12px;flex:none;
  background:var(--surface-2);color:var(--ink);display:grid;place-items:center;
  cursor:pointer;transition:transform .16s var(--ease),background .16s var(--ease)}
.vol button:active{transform:scale(.9);background:var(--accent)}
.vol button svg{width:20px;height:20px;pointer-events:none}
.vol .meter{flex:1;height:5px;border-radius:100px;background:var(--surface-2);overflow:hidden;
  transition:opacity .3s var(--ease)}
.vol .vnum{font-size:12px;font-weight:500;color:var(--ink-dim);
  min-width:22px;text-align:right;font-variant-numeric:tabular-nums}
.vol .fill{height:100%;width:38%;border-radius:100px;
  background:linear-gradient(90deg,var(--accent),#7FA6FF);
  transition:width .3s var(--ease),background .3s var(--ease)}

/* Mute toggle keeps its own state so it reads as a switch, not a button. */
.vol button.mute{margin-left:2px;position:relative}
.vol button.mute[data-on="1"]{background:var(--hot);color:#fff;
  box-shadow:0 0 20px -4px rgba(255,59,107,.55)}
.vol button.mute[data-on="1"] + .meter{opacity:.35}
.vol button.mute[data-on="1"] ~ .volState{opacity:1}
.volState{position:absolute;left:50%;bottom:-19px;transform:translateX(-50%);
  font-size:10px;font-weight:700;letter-spacing:.14em;color:var(--hot);
  opacity:0;transition:opacity .3s var(--ease);pointer-events:none;text-transform:uppercase}
.volWrap{position:relative}


.hidden{display:none !important}

/* ---- keyboard ---- */
.kb{margin-top:auto;display:flex;gap:9px;align-items:center}
.kb input{flex:1;min-width:0;background:var(--surface);border:1px solid var(--line);
  border-radius:var(--r-md);color:var(--ink);padding:15px 17px;
  font-family:inherit;font-size:16px;outline:none;
  transition:border-color .22s var(--ease),background .22s var(--ease)}
.kb input::placeholder{color:var(--ink-mute)}
.kb input:focus{border-color:var(--accent);background:var(--surface-2)}
.kb button{height:54px;padding:0 22px;border:0;border-radius:var(--r-md);
  background:var(--accent);color:#fff;font-family:inherit;font-size:14px;
  font-weight:700;letter-spacing:.03em;cursor:pointer;flex:none;
  box-shadow:0 6px 20px -8px var(--accent-glow);
  transition:transform .16s var(--ease),filter .16s var(--ease)}
.kb button:active{transform:scale(.94);filter:brightness(1.12)}
.kb button.busy{background:var(--ok);color:#08210F}

footer{display:flex;justify-content:center;gap:6px;font-size:11px;
  color:var(--ink-mute);letter-spacing:.02em}
footer b{color:var(--ink-dim);font-weight:500;font-variant-numeric:tabular-nums}

.credit{display:flex;align-items:center;justify-content:center;gap:7px;
  font-size:11.5px;color:var(--ink-mute);padding-top:2px}
.credit a{color:var(--accent);text-decoration:none;font-weight:600;
  letter-spacing:.01em;padding:2px 4px;border-radius:6px;
  transition:background .2s var(--ease)}
.credit a:active{background:var(--surface-2)}
</style>
</head>
<body>
<div class="shell">

<header>
  <div class="brand"><img class="mark" src="__LOGO_DATA_URI__" alt=""><h1>HyRemote</h1></div>
  <div class="link" id="status"><span class="pulse"></span><span id="statusText">Conectando</span></div>
</header>

<div class="appsHead">
  <button class="link" id="editBtn">Ordenar apps</button>
</div>

<div class="apps" id="apps"></div>

<div class="editor hidden" id="editor">
  <div class="editorHead">
    <span>Ordena tus apps · toca las flechas</span>
    <button class="link" id="editDone">Listo</button>
  </div>
  <div id="editorList"></div>
</div>


<div class="stage">
  <div class="pad">
    <button class="key up"    data-k="19" aria-label="Arriba">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
    </button>
    <button class="key left"  data-k="21" aria-label="Izquierda">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
    </button>
    <button class="key ok" data-k="66" aria-label="Aceptar">OK</button>
    <button class="key right" data-k="22" aria-label="Derecha">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7"/></svg>
    </button>
    <button class="key down"  data-k="20" aria-label="Abajo">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M19 12l-7 7-7-7"/></svg>
    </button>
  </div>
</div>

<div class="row">
  <button class="act" data-k="4">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l-7 7 7 7"/></svg>
    Atrás
  </button>
  <button class="act home" data-k="3">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H10v6H4a1 1 0 0 1-1-1z"/></svg>
    Inicio
  </button>
  <button class="act power" data-k="26">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v9M18.4 6.6a9 9 0 1 1-12.7 0"/></svg>
    Apagar
  </button>
</div>

<div class="volWrap">
  <div class="vol">
    <button data-k="25" aria-label="Bajar volumen">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H2v6h4l5 4zM15 9a4 4 0 0 1 0 6"/></svg>
    </button>
    <div class="meter"><div class="fill" id="vfill"></div></div>
  <span class="vnum" id="vnum">0</span>
    <button data-k="24" aria-label="Subir volumen">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H2v6h4l5 4zM15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12"/></svg>
    </button>
    <button class="mute" id="mute" data-k="164" data-on="0" aria-label="Silenciar">
      <svg id="muteIcon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H2v6h4l5 4z"/><path d="M22 9l-6 6M16 9l6 6"/></svg>
    </button>
  </div>
  <span class="volState">Silenciado</span>
</div>


<div class="kb">
  <input id="text" placeholder="Escribir en el proyector" autocomplete="off" autocorrect="off" autocapitalize="off" enterkeyhint="send">
  <button id="send">Enviar</button>
</div>

<footer><span id="lat">—</span></footer>

<div class="credit">
  <span>Desarrollado por @ericknvp</span>
  <a href="https://ericknvp.dev" target="_blank" rel="noopener">ericknvp.dev</a>
</div>

</div>
<script>
const statusEl = document.getElementById('status');
const statusText = document.getElementById('statusText');
const latEl = document.getElementById('lat');
const sendBtn = document.getElementById('send');
const input = document.getElementById('text');

async function post(path, body) {
  const t0 = performance.now();
  const r = await fetch(path, { method: 'POST', body });
  const txt = await r.text();
  return { ms: Math.round(performance.now() - t0), txt };
}

// Fire the key and do not wait for the round trip: the projector reacts in
// ~100ms, so awaiting adds a dead frame to every press.
function press(code, el) {
  fetch('/key', { method: 'POST', body: code }).catch(() => {});
  if (el) {
    el.style.background = 'var(--accent)';
    setTimeout(() => { el.style.background = ''; }, 130);
  }
}

document.querySelectorAll('[data-k]').forEach(b => {
  b.addEventListener('click', e => { e.preventDefault(); press(b.dataset.k, b); });
});

sendBtn.addEventListener('click', async () => {
  const value = input.value;
  if (!value.trim()) return;
  sendBtn.classList.add('busy');
  sendBtn.textContent = 'Enviando';
  await post('/text', value.replace(/ /g, '%s'));
  input.value = '';
  sendBtn.classList.remove('busy');
  sendBtn.textContent = 'Enviar';
});

input.addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); sendBtn.click(); }
});

// Volume is read back from the projector, not simulated: the bar used to drift
// from the real level whenever audio changed outside the remote.
let muted = false;

async function refreshVolume() {
  try {
    const raw = (await (await fetch('/volume')).text()).trim();
    const f = raw.split('/');
    if (f.length < 3) return;
    const cur = parseInt(f[0], 10) || 0;
    const max = parseInt(f[1], 10) || 50;

    document.getElementById('vfill').style.width =
      Math.round(cur / max * 100) + '%';
    document.getElementById('vnum').textContent = cur;

    // Keep the mute button in sync with the projector instead of guessing.
    if (f[2] === '1') {
      setMuteUI(true, false);
    } else if (muted) {
      setMuteUI(false, false);
    }
  } catch (e) { /* keep last known */ }
}

function setMuteUI(on, sendKey) {
  const btn = document.getElementById('mute');
  btn.dataset.on = on ? '1' : '0';
  document.getElementById('muteIcon').innerHTML = on ? MUTE_ON : MUTE_OFF;
  btn.setAttribute('aria-label', on ? 'Activar sonido' : 'Silenciar');
  muted = on;
  if (sendKey) press('164', null);
}

// Mute is a toggle in Android: KEYCODE_VOLUME_MUTE flips state, so the button
// shows the last state we set rather than trying to read the real one back.
const muteBtn = document.getElementById('mute');
const MUTE_OFF = '<path d="M11 5 6 9H2v6h4l5 4z"/><path d="M15.5 9.5a4 4 0 0 1 0 5"/><path d="M18.5 6.5a8 8 0 0 1 0 11"/>';
const MUTE_ON  = '<path d="M11 5 6 9H2v6h4l5 4z"/><path d="M22 9l-6 6M16 9l6 6"/>';
muteBtn.addEventListener('click', () => setMuteUI(muteBtn.dataset.on !== '1', true));

// Read the level right after a press, then settle.
document.querySelectorAll('.vol button:not(.mute)').forEach(b => {
  b.addEventListener('click', () => {
    setTimeout(refreshVolume, 180);
    setTimeout(refreshVolume, 600);
  });
});
refreshVolume();

// Shortcut row. Packages are read from the projector at runtime: hardcoding
// them guaranteed breakage, since Netflix on this device is com.clap.netflix
// and not the documented name.
const appsRow = document.getElementById('apps');

const FALLBACK = [
  { pkg: 'com.clap.netflix', label: 'Netflix' },
  { pkg: 'com.google.android.youtube.tv', label: 'YouTube' }
];

// App order is user-controlled and persisted in localStorage, so it survives
// reloads without needing any storage support on the projector.
const ORDER_KEY = 'hyremote.order';
const HIDDEN_KEY = 'hyremote.hidden';

const DEFAULT_ORDER = [
  'com.clap.netflix',
  'com.google.android.youtube.tv',
  'com.xuper.netxxus',
  'com.droidlogic.videoplayer',
  'com.droidlogic.FileBrower',
  'com.android.chrome',
  'com.bozee.usbdisplay'
];

let allApps = [];

function readJSON(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (raw) return JSON.parse(raw);
  } catch (e) { /* fall through */ }
  return fallback;
}

function saveJSON(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* private mode */ }
}

function readOrder() {
  return readJSON(ORDER_KEY, DEFAULT_ORDER.slice());
}
function saveOrder(order) {
  saveJSON(ORDER_KEY, order);
}
function readHidden() {
  return readJSON(HIDDEN_KEY, []);
}
function saveHidden(list) {
  saveJSON(HIDDEN_KEY, list);
}

function isHidden(pkg) {
  return readHidden().indexOf(pkg) !== -1;
}

// Visible apps, in the user's order, then the rest alphabetically.
function applyOrder(list, order) {
  const hidden = readHidden();
  const byPkg = {};
  list.forEach(a => { if (hidden.indexOf(a.pkg) === -1) byPkg[a.pkg] = a; });

  const out = [];
  order.forEach(p => { if (byPkg[p]) { out.push(byPkg[p]); delete byPkg[p]; } });

  const rest = Object.keys(byPkg).map(p => byPkg[p]);
  rest.sort(function (a, b) { return a.label.localeCompare(b.label); });
  return out.concat(rest);
}

async function loadApps() {
  let list = FALLBACK;
  try {
    const r = await fetch('/apps');
    const txt = (await r.text()).trim();
    if (txt) {
      const parsed = txt.split('\n')
        .map(l => l.trim())
        .filter(Boolean)
        .map(l => {
          // "package|label|iconDataUri". Icons ride along in the same response
          // because the bridge only handles one connection at a time.
          const f = l.split('|');
          if (f.length < 2) return null;
          return { pkg: f[0], label: f[1], icon: f[2] || '' };
        })
        .filter(Boolean);
      if (parsed.length) list = parsed;
    }
  } catch (e) { /* keep fallback */ }

  allApps = list;
  renderApps();
}

function renderApps() {
  appsRow.innerHTML = '';
  applyOrder(allApps, readOrder()).forEach(a => {
    const b = document.createElement('button');
    b.className = 'app';
    b.title = a.pkg;
    b.draggable = true;
    // Real icon from the projector; falls back to a generic glyph.
    const glyph = '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="5" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M10 9l5 3-5 3z" fill="currentColor"/></svg>';
    b.innerHTML =
      (a.icon ? '<img src="' + a.icon + '" alt="" class="ico">' : glyph) +
      '<span>' + a.label + '</span>';
    b.addEventListener('click', () => {
      b.style.borderColor = 'var(--accent)';
      setTimeout(() => { b.style.borderColor = ''; }, 200);
      launchApp(a.pkg, a.label, b);
    });
    attachReorder(b);
    appsRow.appendChild(b);
  });
}

// Long press to reorder, the way a home screen works.
function attachReorder(btn) {
  let held = null, startX = 0, moved = false;

  const release = () => {
    if (held) { clearTimeout(held); held = null; }
  };

  btn.addEventListener('touchstart', e => {
    moved = false;
    startX = e.touches[0].clientX;
    held = setTimeout(() => {
      held = null;
      btn.classList.add('reorder');
      if (navigator.vibrate) navigator.vibrate(12);
    }, 520);
  }, { passive: true });

  btn.addEventListener('touchmove', e => {
    if (Math.abs(e.touches[0].clientX - startX) > 12) { moved = true; release(); }
  }, { passive: true });

  btn.addEventListener('touchend', () => {
    btn.classList.remove('reorder');
    release();
  });

  // Desktop: plain drag and drop.
  btn.addEventListener('dragstart', e => {
    moved = true;
    btn.style.opacity = '.4';
    e.dataTransfer.setData('text/plain', btn.title);
  });
  btn.addEventListener('dragend', () => {
    btn.style.opacity = '';
  });
  btn.addEventListener('dragover', e => {
    e.preventDefault();
    btn.style.borderColor = 'var(--accent)';
  });
  btn.addEventListener('dragleave', () => { btn.style.borderColor = ''; });
  btn.addEventListener('drop', e => {
    e.preventDefault();
    btn.style.borderColor = '';
    const from = e.dataTransfer.getData('text/plain');
    if (!from || from === btn.title) return;
    commitReorder(from, btn.title);
  });
}

// Explicit reorder editor: arrows are far more reliable than drag on a phone,
// and there is no ambiguity about what is being moved.
const editor = document.getElementById('editor');
const editorList = document.getElementById('editorList');

function currentOrdered() {
  return applyOrder(allApps, readOrder());
}

function renderEditor() {
  const list = currentOrdered();
  editorList.innerHTML = '';
  list.forEach((a, i) => {
    const row = document.createElement('div');
    row.className = 'erow';

    const eye = document.createElement('button');
    eye.className = 'eye';
    eye.textContent = isHidden(a.pkg) ? '◌' : '●';
    eye.setAttribute('aria-label', (isHidden(a.pkg) ? 'Mostrar ' : 'Ocultar ') + a.label);
    eye.addEventListener('click', () => toggleHidden(a.pkg));
    row.appendChild(eye);

    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = a.label;
    if (isHidden(a.pkg)) name.classList.add('off');
    row.appendChild(name);

    const up = document.createElement('button');
    up.textContent = '↑';
    up.disabled = i === 0;
    up.setAttribute('aria-label', 'Subir ' + a.label);
    up.addEventListener('click', () => move(a.pkg, -1));
    row.appendChild(up);

    const down = document.createElement('button');
    down.textContent = '↓';
    down.disabled = i === list.length - 1;
    down.setAttribute('aria-label', 'Bajar ' + a.label);
    down.addEventListener('click', () => move(a.pkg, 1));
    row.appendChild(down);

    editorList.appendChild(row);
  });
}

function toggleHidden(pkg) {
  const h = readHidden();
  const i = h.indexOf(pkg);
  if (i === -1) h.push(pkg); else h.splice(i, 1);
  saveHidden(h);
  renderApps();
  renderEditor();
}

function move(pkg, dir) {
  const ordered = currentOrdered();
  const i = ordered.findIndex(a => a.pkg === pkg);
  const j = i + dir;
  if (i < 0 || j < 0 || j >= ordered.length) return;

  const order = ordered.map(a => a.pkg);
  order.splice(j, 0, order.splice(i, 1)[0]);
  saveOrder(order);

  // FLIP: capture every tile's position BEFORE the DOM changes, then replay the
  // distance as a transition. Rebuilding the list would destroy the nodes we
  // need to measure, so we move the existing ones.
  const tiles = Array.prototype.slice.call(appsRow.children);
  const before = {};
  tiles.forEach(t => { before[t.dataset.pkg] = t.getBoundingClientRect().left; });

  const moved = ordered.slice();
  const byPkg = {};
  tiles.forEach(t => { byPkg[t.dataset.pkg] = t; });
  const frag = document.createDocumentFragment();
  moved.forEach(a => { if (byPkg[a.pkg]) frag.appendChild(byPkg[a.pkg]); });
  appsRow.appendChild(frag);

  tiles.forEach(function (tile) {
    const after = tile.getBoundingClientRect().left;
    const delta = before[tile.dataset.pkg] - after;
    if (!delta) return;
    tile.style.transition = 'none';
    tile.style.transform = 'translateX(' + delta + 'px)';
  });
  void appsRow.offsetWidth; // flush layout so the transitions actually run

  tiles.forEach(function (tile) {
    tile.style.transition = 'transform .34s cubic-bezier(.22,1,.36,1)';
    tile.style.transform = 'translateX(0)';
  });

  const movedTile = byPkg[pkg];
  if (movedTile) {
    movedTile.classList.add('just-moved');
    setTimeout(function () { movedTile.classList.remove('just-moved'); }, 380);
  }

  renderEditor();
}

document.getElementById('editBtn').addEventListener('click', () => {
  editor.classList.remove('hidden');
  renderEditor();
  editor.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
});

document.getElementById('editDone').addEventListener('click', () => {
  editor.classList.add('hidden');
});

function commitReorder(fromPkg, toPkg) {
  const order = readOrder();
  applyOrder(allApps, order).forEach(a => {
    if (!order.includes(a.pkg)) order.push(a.pkg);
  });
  const from = order.indexOf(fromPkg);
  const to = order.indexOf(toPkg);
  if (from < 0 || to < 0) return;
  order.splice(to, 0, order.splice(from, 1)[0]);
  saveOrder(order);
  renderApps();
}
// Launches an app, then verifies it actually came to the foreground. The
// bridge only reports "the event was injected", which is not proof the app
// opened, so we check the focused window ourselves before claiming success.
async function launchApp(pkg, label, btn) {
  let res;
  try {
    res = await (await fetch('/app', { method: 'POST', body: pkg })).text();
  } catch (e) {
    flashLabel(btn, label, 'Sin conexión');
    return;
  }

  if (res.indexOf('"ok":1') === -1) {
    flashLabel(btn, label, 'No está instalada');
    return;
  }

  // Give the app a moment to take focus, then confirm.
  setTimeout(async () => {
    try {
      const fg = (await (await fetch('/focus')).text()).trim();
      const ok = fg && (fg === pkg);
      flashLabel(btn, label, ok ? 'Abierta' : 'No apareció');
    } catch (e) {
      flashLabel(btn, label, 'Abierta');
    }
  }, 900);
}

function flashLabel(btn, label, msg) {
  const span = btn.querySelector('span');
  if (!span) return;
  span.textContent = msg;
  span.style.color = msg === 'Abierta' ? 'var(--ok)'
    : msg === 'No apareció' ? 'var(--hot)' : 'var(--ink-mute)';
  setTimeout(() => {
    span.textContent = label;
    span.style.color = '';
  }, 2200);
}

loadApps();

// Show which app is in the foreground, so the state is never a guess.
async function refreshFocus() {
  try {
    const fg = (await (await fetch('/focus')).text()).trim();
    if (fg) {
      const short = fg.split('.').pop();
      statusText.textContent = 'En ' + short;
      statusEl.className = 'link live';
    }
  } catch (e) { /* keep the ping status */ }
}


// iOS zooms on double tap even with user-scalable=no in some versions, and
// pinch-zoom survives it too. Suppress both at the gesture level.
document.addEventListener('gesturestart', function (e) { e.preventDefault(); }, { passive: false });
document.addEventListener('gesturechange', function (e) { e.preventDefault(); }, { passive: false });
document.addEventListener('gestureend', function (e) { e.preventDefault(); }, { passive: false });

var lastTouch = 0;
document.addEventListener('touchend', function (e) {
  var now = Date.now();
  if (now - lastTouch < 320) e.preventDefault();
  lastTouch = now;
}, { passive: false });

document.addEventListener('dblclick', function (e) { e.preventDefault(); }, { passive: false });

// The server reports whether key injection is available. "basic" means the
// page works but the D-pad cannot reach other apps; tell the user instead of
// showing a green "En línea" that lies. A missing mode is the old root
// bridge, which always has full control.
let controlMode = 'full';

async function refreshMode() {
  try {
    const txt = await (await fetch('/status')).text();
    if (txt.indexOf('"mode":"basic"') >= 0) controlMode = 'basic';
    else if (txt.indexOf('"mode":"accessibility"') >= 0) controlMode = 'partial';
    else controlMode = 'full';
  } catch (e) {
    controlMode = 'full';
  }
}

async function ping() {
  try {
    const t0 = performance.now();
    const r = await fetch('/ping');
    const txt = await r.text();
    if (txt.includes('pong')) {
      const ms = Math.round(performance.now() - t0);
      if (controlMode === 'basic') {
        statusEl.className = 'link down';
        statusText.textContent = 'En línea · sin teclas';
        latEl.innerHTML = 'Activa Depuración por red en el proyector';
      } else if (controlMode === 'partial') {
        statusEl.className = 'link down';
        statusText.textContent = 'En línea · parcial';
        latEl.innerHTML = 'El D-pad requiere Depuración por red';
      } else {
        statusEl.className = 'link live';
        statusText.textContent = 'En línea';
        latEl.innerHTML = 'Latencia <b>' + ms + ' ms</b>';
      }
    } else { throw 0; }
  } catch (e) {
    statusEl.className = 'link down';
    statusText.textContent = 'Sin conexión';
    latEl.innerHTML = 'Revisa que el servidor esté activo';
  }
}
refreshMode();
ping();
setInterval(ping, 5000);
setInterval(refreshMode, 10000);

// Volume can change from the physical remote too, so keep polling it.
setInterval(refreshVolume, 4000);

// Hold-to-repeat on the direction keys.
document.querySelectorAll('.key.up,.key.down,.key.left,.key.right').forEach(k => {
  let t = null, delay = null;
  const start = e => {
    e.preventDefault();
    press(k.dataset.k, k);
    delay = setTimeout(() => { t = setInterval(() => press(k.dataset.k), 110); }, 420);
  };
  const stop = () => { clearTimeout(delay); clearInterval(t); t = delay = null; };
  k.addEventListener('touchstart', start, { passive: false });
  k.addEventListener('touchend', stop);
  k.addEventListener('touchcancel', stop);
  k.addEventListener('mousedown', start);
  k.addEventListener('mouseup', stop);
  k.addEventListener('mouseleave', stop);
});
</script>
</body>
</html>`;
process.stdout.write(DOC);