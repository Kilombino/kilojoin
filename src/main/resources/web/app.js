// Kilojoin web page: plain JS, no framework, no outside requests (everything goes to this node).
"use strict";
const $ = (s) => document.querySelector(s);
const app = $("#app");
const sats = (v) => Number(v).toLocaleString("en").replace(/,/g, " ");
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const MIN = 10000, MAX = 100000000;
let st = null, pools = [], view = "home", joining = null, chosen = null, msg = "", received = null, copied = false;

async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
  return j;
}
function left(at) {
  const s = Math.floor(at - Date.now() / 1000);
  if (s <= 0) return "now";
  return s < 3600 ? Math.floor(s / 60) + " min" : Math.floor(s / 3600) + " h " + Math.floor((s % 3600) / 60) + " min";
}
const feeNoChange = (rate) => Math.ceil(rate * (68 + 31 + 10.5 / 2));
const feeWithChange = (rate) => Math.ceil(rate * (68 + 31 + 31 + 10.5 / 2));

// ---------------------------------------------------------------- screens

function setupScreen() {
  app.innerHTML = `
  <div class="panel"><div class="label">Set up your coinjoin wallet</div>
    <p class="muted">This node holds a BTC (BLAKE2b) wallet to mix coins in the same pools as the Kilowallet app.
    Create new words or import a wallet you already have (12 or 24 words). The words are encrypted on this node
    with a password you choose; without it the file is useless.</p>
    <div class="tabs"><button id="m-create">CREATE NEW WORDS</button><button class="soft" id="m-import">IMPORT WORDS</button></div>
    <div id="importBox" class="hidden"><label>Your 12 or 24 words</label><input id="words" autocomplete="off" spellcheck="false"></div>
    <div id="countBox"><label>Number of words</label><select id="count"><option>12</option><option>24</option></select></div>
    <label>Passphrase (optional — leave empty if you don't use one)</label><input id="pp" autocomplete="off">
    <label>Password to encrypt the words on this node (8+ characters)</label><input id="pw" type="password">
    <label>Repeat the password</label><input id="pw2" type="password">
    <p id="err" class="bad"></p>
    <button id="go">SET UP</button>
  </div>`;
  let mode = "create";
  $("#m-create").onclick = () => { mode = "create"; $("#importBox").classList.add("hidden"); $("#countBox").classList.remove("hidden"); $("#m-create").className = ""; $("#m-import").className = "soft"; };
  $("#m-import").onclick = () => { mode = "import"; $("#importBox").classList.remove("hidden"); $("#countBox").classList.add("hidden"); $("#m-import").className = ""; $("#m-create").className = "soft"; };
  $("#go").onclick = async () => {
    if ($("#pw").value !== $("#pw2").value) return ($("#err").textContent = "The passwords do not match.");
    try {
      const r = await api("/api/setup", { mode, words: $("#words") ? $("#words").value : "", count: Number($("#count").value), passphrase: $("#pp").value, password: $("#pw").value });
      if (r.words) {
        app.innerHTML = `<div class="panel"><div class="label">Write these words down</div>
          <p class="warn">They are the only backup of this wallet. Anyone with them can take the coins. They will not be shown again.</p>
          <div class="words">${esc(r.words)}</div><p></p><button id="done">I WROTE THEM DOWN</button></div>`;
        $("#done").onclick = load;
      } else load();
    } catch (e) { $("#err").textContent = e.message; }
  };
}

function unlockScreen() {
  app.innerHTML = `<div class="panel"><div class="label">Unlock</div>
    <label>Wallet password</label><input id="pw" type="password" autofocus><p id="err" class="bad"></p><button id="go">UNLOCK</button></div>`;
  const go = async () => { try { await api("/api/unlock", { password: $("#pw").value }); load(); } catch (e) { $("#err").textContent = e.message; } };
  $("#go").onclick = go; $("#pw").onkeydown = (e) => { if (e.key === "Enter") go(); };
}

function phaseText(p) {
  switch (p.phase) {
    case "JOINING": return "Asking to join…";
    case "OPEN": return `Waiting for people · ${p.people}/${p.maxPeers} (min ${p.minPeers}) · closes in ${left(p.expiresAt)}`;
    case "VOTING": return `Close now with ${p.people} people?`;
    case "CLOSING": return `Closing with ${p.people} people · collecting outputs`;
    case "SIGNING": return `Ready: sign within ${left(p.phaseDeadline)}`;
    case "BROADCAST": return "Sent · waiting for the first confirmation";
    case "CONFIRMED": return "Confirmed ✅";
    case "ABORTED": return "Cancelled: " + p.reason + ". Your coin did not move.";
    case "REJECTED": return "Refused: " + p.reason;
  }
  return p.phase;
}

function myPool(p) {
  let actions = "";
  if (p.phase === "OPEN") actions = (p.people >= p.minPeers ? `<button data-a="close" data-p="${p.id}">ASK TO CLOSE NOW</button> ` : "") +
    `<button class="soft" data-a="leave" data-p="${p.id}">${p.creator ? "END POOL" : "LEAVE"}</button>`;
  if (p.phase === "VOTING") actions = (p.iAsked || p.voted
    ? `<p class="muted">Waiting for the others, ${left(p.voteDeadline)} left. Whoever has not answered by then is left out; if too few are left, the pool reopens without them.</p>`
    : `<p class="muted">Answer within ${left(p.voteDeadline)}.</p><button data-a="yes" data-p="${p.id}">ACCEPT</button> <button class="soft" data-a="no" data-p="${p.id}">NOT YET</button> `) +
    `<button class="soft" data-a="leave" data-p="${p.id}">${p.creator ? "END POOL" : "LEAVE"}</button>`;
  if (p.phase === "SIGNING") actions = p.signed ? `<span class="muted">Signed. Waiting for the others (${p.sigs}/${p.planPeople})…</span>` :
    `<p class="muted">Checked: ${p.planPeople} people, identical outputs of ${sats(p.amount)} sats, one of them yours; ${p.change > 0 ? "your change is right" : "no change (an exact coin)"}; total fee ${sats(p.planFee)} sats.</p><button data-a="sign" data-p="${p.id}">SIGN</button>`;
  if (p.txid) actions += `<p><code>${esc(p.txid)}</code></p>`;
  if (["CONFIRMED", "ABORTED", "REJECTED"].includes(p.phase)) actions += ` <button class="ghost" data-a="remove" data-p="${p.id}">remove</button>`;
  const cls = p.phase === "CONFIRMED" ? "good" : ["ABORTED", "REJECTED"].includes(p.phase) ? "bad" : ["VOTING", "SIGNING"].includes(p.phase) ? "warn" : "muted";
  return `<div class="panel"><div class="row"><b>${p.private ? "🔒 " : ""}${sats(p.amount)} sats</b><span class="faint" style="text-align:right">${p.creator ? "your pool" : "joined"}</span></div>
    <p class="${cls}">${esc(phaseText(p))}</p>
    <p class="faint">your coin ${sats(p.coinValue)} → ${sats(p.amount)} mixed${p.change > 0 ? " + " + sats(p.change) + " change" : ""} · fee ${sats(p.coinValue - p.amount - p.change)}</p>${actions}</div>`;
}

function coinPicker(amount, rate) {
  const min = amount + feeNoChange(rate);
  const coins = (st.coins || []).filter((c) => !c.inRound && c.confirmations > 0 && c.value >= min).sort((a, b) => (a.value === min ? -1 : b.value === min ? 1 : b.value - a.value));
  if (!coins.length) return `<p class="warn">No confirmed coin of at least ${sats(min)} sats. Send one to this wallet (receive below) and scan again.</p>`;
  return coins.map((c) => {
    const ch = c.value - amount - feeWithChange(rate);
    const note = c.value === min ? `<span class="good">★ EXACT · no change · fee ${sats(c.value - amount)}</span>` :
      ch > 294 ? `change ${sats(ch)} · fee ${sats(c.value - amount - ch)}` : `<span class="warn">no change, ${sats(c.value - min)} extra goes to the miners</span>`;
    return `<div class="pick ${chosen === c.outpoint ? "sel" : ""}" data-coin="${c.outpoint}"><b>${sats(c.value)} sats</b> <span class="faint">${c.confirmations} conf</span><br><span class="faint">${note}</span></div>`;
  }).join("");
}

function homeScreen() {
  const mine = st.mine || [];
  const open = pools.filter((t) => !mine.some((m) => m.id === t.id && !["ABORTED", "REJECTED", "CONFIRMED", "BROADCAST"].includes(m.phase)));
  let h = "";
  if (msg) h += `<div class="panel warn">${esc(msg)} <button class="ghost" id="msgok">ok</button></div>`;
  h += `<div class="panel"><div class="label">Wallet</div><div class="big">${sats(st.balance || 0)} <span style="font-size:18px">sats</span></div>
    <p class="faint">height ${st.height} · ${st.scannedAt ? "scanned " + new Date(st.scannedAt).toLocaleTimeString() : "scanning the UTXO set… (can take a minute)"} · relay ${esc(st.relay)}</p>
    <div class="row"><button class="soft" id="scan">SCAN AGAIN</button><button class="soft" id="recv">RECEIVE ADDRESS</button></div>${received ? `<p><code id="addrtext">${esc(received.address)}</code><br><span class="faint">fresh address #${received.index}, used only once</span></p><div class="row"><button id="copy">${copied ? "COPIED ✓" : "COPY"}</button><button class="ghost" id="hideaddr">hide</button></div>` : ""}</div>`;
  if (joining) {
    h += `<div class="panel"><div class="label">${joining.create ? "Open a pool" : "Join · " + sats(joining.amount) + " sats"}</div>`;
    if (joining.create) h += `<div class="row"><div><label>amount per person (sats)</label><input id="c-amount" value="${joining.amount}"></div><div><label>fee (sat/vB)</label><input id="c-rate" value="${joining.feeRate}"></div></div>
      <div class="row"><div><label>fewest people</label><input id="c-min" value="2"></div><div><label>most people</label><input id="c-max" value="5"></div><div><label>open for (hours)</label><input id="c-hours" value="6"></div></div>
      <label>password (optional: makes it private 🔒)</label><input id="c-pw">`;
    else if (joining.private) h += `<label>🔒 pool password</label><input id="c-pw">`;
    h += `<p class="faint">Each person pays ${sats(feeWithChange(joining.feeRate))} sats with change, ${sats(feeNoChange(joining.feeRate))} without. Pick ONE confirmed coin:</p>
      <div id="coins">${coinPicker(joining.amount, joining.feeRate)}</div>
      <div class="row"><button class="soft" id="cancel">CANCEL</button><button id="confirm" ${chosen ? "" : "disabled"}>${joining.create ? "OPEN POOL" : "JOIN"}</button></div></div>`;
  } else h += `<button id="create">＋ OPEN A POOL</button>`;
  if (mine.length) h += `<div class="label" style="margin-top:18px">Your pools</div>` + mine.map(myPool).join("");
  h += `<div class="row" style="margin-top:18px"><div class="label">Open pools</div><button class="ghost" id="refresh" style="flex:0">refresh</button></div>`;
  h += open.length ? open.map((t) => `<div class="panel"><div class="item"><div><b>${t.private ? "🔒 " : ""}${sats(t.amount)} sats</b><br>
      <span class="faint">${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${t.feeRate} sat/vB · closes in ${left(t.expiresAt)}<br>
      costs you ${sats(t.feeWithChange)} sats (${sats(t.feeNoChange)} with an exact coin of ${sats(t.amount + t.feeNoChange)})</span></div>
      <button class="soft" data-join="${t.id}" style="flex:0">JOIN</button></div></div>`).join("") : `<p class="muted">No open pools right now. Open one: phones with Kilowallet and other nodes are notified.</p>`;
  h += `<div class="panel"><div class="label">Notifications</div><label><input type="checkbox" id="notify" style="width:auto" ${st.notify_new_pools ? "checked" : ""}> notify new public pools (every 5 minutes)</label></div>`;
  app.innerHTML = h;
  bind();
}

function bind() {
  $("#lock").classList.remove("hidden");
  const on = (id, f) => { const e = $(id); if (e) e.onclick = f; };
  on("#msgok", () => { msg = ""; homeScreen(); });
  on("#scan", async () => { $("#scan").disabled = true; $("#scan").textContent = "scanning…"; st = await api("/api/scan", {}); homeScreen(); });
  // The address stays on screen (the 5-second refresh redraws the page) until hidden.
  on("#recv", async () => { received = await api("/api/receive"); copied = false; homeScreen(); });
  on("#hideaddr", () => { received = null; homeScreen(); });
  on("#copy", async () => {
    try { await navigator.clipboard.writeText(received.address); }
    catch (e) { const r = document.createRange(); r.selectNodeContents($("#addrtext")); const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); document.execCommand("copy"); }
    copied = true; homeScreen();
  });
  on("#refresh", async () => { pools = await api("/api/pools"); homeScreen(); });
  on("#create", () => { joining = { create: true, amount: MIN, feeRate: 2 }; chosen = null; homeScreen(); });
  on("#cancel", () => { joining = null; chosen = null; homeScreen(); });
  document.querySelectorAll("[data-join]").forEach((b) => (b.onclick = () => { const t = pools.find((p) => p.id === b.dataset.join); joining = { create: false, id: t.id, amount: t.amount, feeRate: t.feeRate, private: t.private }; chosen = null; homeScreen(); }));
  document.querySelectorAll("[data-coin]").forEach((d) => (d.onclick = () => { chosen = d.dataset.coin; homeScreen(); }));
  if (joining && joining.create) ["#c-amount", "#c-rate"].forEach((id) => ($(id).onchange = () => {
    joining.amount = Math.max(MIN, Math.min(MAX, parseInt($("#c-amount").value) || MIN)); joining.feeRate = Math.max(1, parseFloat($("#c-rate").value) || 2); chosen = null; homeScreen(); }));
  on("#confirm", async () => {
    try {
      if (joining.create) await api("/api/create", { coin: chosen, amount: joining.amount, feeRate: joining.feeRate, minPeers: parseInt($("#c-min").value) || 2,
        maxPeers: parseInt($("#c-max").value) || 5, hours: parseInt($("#c-hours").value) || 6, password: $("#c-pw").value });
      else await api("/api/join", { pool: joining.id, coin: chosen, password: $("#c-pw") ? $("#c-pw").value : "" });
      joining = null; chosen = null; await load();
    } catch (e) { msg = e.message; homeScreen(); }
  });
  document.querySelectorAll("[data-a]").forEach((b) => (b.onclick = async () => {
    const a = b.dataset.a, p = b.dataset.p;
    if ((a === "leave") && !confirm("Leave / end this pool? Nothing was signed, so no coin moves.")) return;
    try {
      if (a === "close") await api("/api/close", { pool: p });
      if (a === "yes" || a === "no") await api("/api/vote", { pool: p, accept: a === "yes" });
      if (a === "sign") await api("/api/sign", { pool: p });
      if (a === "leave") await api("/api/leave", { pool: p });
      if (a === "remove") await api("/api/remove", { pool: p });
      await load();
    } catch (e) { msg = e.message; homeScreen(); }
  }));
  const n = $("#notify"); if (n) n.onchange = () => api("/api/settings", { notify_new_pools: n.checked });
}

$("#lock").onclick = async () => { await api("/api/lock", {}); location.reload(); };

async function load() {
  try {
    st = await api("/api/status");
    if (!st.setup) return setupScreen();
    if (!st.unlocked) return unlockScreen();
    if (!pools.length) pools = await api("/api/pools").catch(() => []);
    homeScreen();
  } catch (e) { app.innerHTML = `<p class="bad">${esc(e.message)}</p>`; }
}
load();
// Keep the round moving on screen (not while a form is open).
// Not while text is selected either, so an address can be copied by hand.
const selecting = () => { const s = getSelection(); return s && !s.isCollapsed; };
setInterval(async () => { if (st && st.unlocked && !joining && !selecting()) { try { st = await api("/api/status"); homeScreen(); } catch (e) {} } }, 5000);
