// Kilojoin web page: plain JS, no framework, no outside requests (everything goes to this node).
"use strict";
const $ = (s) => document.querySelector(s);
const app = $("#app");
const sats = (v) => Number(v).toLocaleString("en").replace(/,/g, " ");
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const MIN = 10000, MAX = 100000000;
let st = null, pools = [], view = "home", joining = null, chosen = null, msg = "", received = null, copied = false, sending = null;

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
    return `<div class="pick ${chosen === c.outpoint ? "sel" : ""}" data-coin="${c.outpoint}"><b>${sats(c.value)} sats</b> <span class="faint">${c.confirmations} conf${c.label ? " · " + c.label : ""}</span><br><span class="faint">${note}</span></div>`;
  }).join("");
}

// The perfect coin for a pool: a payment to yourself of exactly amount + the no-change fee share.
function exactButton(amount, rate) {
  const exact = amount + feeNoChange(rate);
  if ((st.coins || []).some((c) => c.value === exact && !c.inRound)) return "";
  return `<button class="ghost" id="exact" data-exact="${exact}">＋ PREPARE AN EXACT COIN OF ${sats(exact)} SATS</button>`;
}

function sendPanel() {
  const sd = sending;
  const coins = (st.coins || []).filter((c) => !c.inRound);
  let h = `<div class="panel"><div class="label">${sd.exact ? "Exact coin · " + sats(sd.amount) + " sats to yourself" : "Send"}</div>`;
  if (sd.exact) h += `<p class="faint">A payment to a fresh address of this wallet. After one confirmation it joins with no change. Mixed coins are left out on purpose.</p>`;
  h += `<p class="faint">Tick the coins to spend (coin control). Never spend a mixed coin with other coins: that links them again.</p>`;
  h += coins.map((c) => `<label style="display:flex;gap:8px;align-items:center;margin:4px 0"><input type="checkbox" style="width:auto" data-sc="${c.outpoint}" ${sd.coins.includes(c.outpoint) ? "checked" : ""}>
      <span><b>${sats(c.value)}</b> sats <span class="faint">${c.confirmations} conf${c.label ? " · <span class='" + (c.label === "mixed" ? "good" : "muted") + "'>" + c.label + "</span>" : ""} · ${esc(c.address.slice(0, 10))}…</span></span></label>`).join("");
  if (!sd.exact) h += `<label>to address</label><input id="s-addr" value="${esc(sd.address || "")}" placeholder="bc1…">
    <label>amount in sats (empty = everything ticked, less the fee)</label><input id="s-amount" value="${sd.amount || ""}">`;
  h += `<label>fee (sat/vB)${sd.suggested ? " · your node suggests " + sd.suggested.toFixed(1) : " · your node has no estimate (quiet mempool)"}</label><input id="s-rate" value="${sd.feeRate}">`;
  if (sd.preview) {
    const p = sd.preview;
    h += `<div class="panel" style="margin:10px 0"><p>${sats(p.amount)} sats → <code>${esc(p.address)}</code></p>
      <p class="faint">from ${p.inputs} coin(s) of ${sats(p.total)} · fee ${sats(p.fee)} (${p.vbytes} vB at ${p.feeRate} sat/vB)${p.change > 0 ? " · change " + sats(p.change) + " back to you" : " · no change"}</p>
      ${p.warning ? `<p class="warn">⚠️ ${esc(p.warning)}</p>` : ""}</div>`;
  }
  if (sd.txid) h += `<p class="good">Sent ✓ <code>${esc(sd.txid)}</code></p>`;
  h += `<div class="row"><button class="soft" id="s-cancel">${sd.txid ? "CLOSE" : "CANCEL"}</button>` +
    (sd.txid ? "" : sd.preview ? `<button id="s-send">${sd.preview.warning ? "SEND ANYWAY" : "SEND"}</button>` : `<button id="s-preview">REVIEW</button>`) + `</div></div>`;
  return h;
}

async function startSend(opts) {
  const f = await api("/api/fee").catch(() => ({}));
  const rate = f.suggested ? Math.max(1, Math.round(f.suggested * 10) / 10) : 1;
  sending = Object.assign({ coins: [], address: "", amount: "", feeRate: rate, suggested: f.suggested || null, preview: null, txid: null }, opts || {});
  homeScreen();
}

function readSend() {
  const sd = sending;
  sd.coins = [...document.querySelectorAll("[data-sc]")].filter((b) => b.checked).map((b) => b.dataset.sc);
  if ($("#s-addr")) sd.address = $("#s-addr").value.trim();
  if ($("#s-amount")) sd.amount = $("#s-amount").value.replace(/\D/g, "");
  sd.feeRate = parseFloat($("#s-rate").value) || 1;
}
const sendBody = () => ({ coins: sending.coins, address: sending.address, amount: parseInt(sending.amount) || 0, feeRate: sending.feeRate });

function homeScreen() {
  const mine = st.mine || [];
  const open = pools.filter((t) => !mine.some((m) => m.id === t.id && !["ABORTED", "REJECTED", "CONFIRMED", "BROADCAST"].includes(m.phase)));
  let h = "";
  if (msg) h += `<div class="panel warn">${esc(msg)} <button class="ghost" id="msgok">ok</button></div>`;
  h += `<div class="panel"><div class="label">Wallet</div><div class="big">${sats(st.balance || 0)} <span style="font-size:18px">sats</span></div>
    <p class="faint">height ${st.height} · ${st.scannedAt ? "scanned " + new Date(st.scannedAt).toLocaleTimeString() : "scanning the UTXO set… (can take a minute)"} · relay ${esc(st.relay)}</p>
    <div class="row"><button class="soft" id="scan">SCAN AGAIN</button><button class="soft" id="recv">RECEIVE ADDRESS</button><button class="soft" id="send">SEND</button></div>${received ? `<p><code id="addrtext">${esc(received.address)}</code><br><span class="faint">fresh address #${received.index}, used only once</span></p><div class="row"><button id="copy">${copied ? "COPIED ✓" : "COPY"}</button><button class="ghost" id="hideaddr">hide</button></div>` : ""}</div>`;
  if (sending) h += sendPanel();
  else if (joining) {
    h += `<div class="panel"><div class="label">${joining.create ? "Open a pool" : "Join · " + sats(joining.amount) + " sats"}</div>`;
    if (joining.create) h += `<div class="row"><div><label>amount per person (sats)</label><input id="c-amount" value="${joining.amount}"></div><div><label>fee (sat/vB)</label><input id="c-rate" value="${joining.feeRate}"></div></div>
      <div class="row"><div><label>fewest people</label><input id="c-min" value="2"></div><div><label>most people</label><input id="c-max" value="5"></div><div><label>open for (hours)</label><input id="c-hours" value="6"></div></div>
      <label>password (optional: makes it private 🔒)</label><input id="c-pw">`;
    else if (joining.private) h += `<label>🔒 pool password</label><input id="c-pw">`;
    h += `<p class="faint">Each person pays ${sats(feeWithChange(joining.feeRate))} sats with change, ${sats(feeNoChange(joining.feeRate))} without. Pick ONE confirmed coin:</p>
      <div id="coins">${coinPicker(joining.amount, joining.feeRate)}</div>${exactButton(joining.amount, joining.feeRate)}
      <div class="row"><button class="soft" id="cancel">CANCEL</button><button id="confirm" ${chosen ? "" : "disabled"}>${joining.create ? "OPEN POOL" : "JOIN"}</button></div></div>`;
  } else h += `<button id="create">＋ OPEN A POOL</button>`;
  if (mine.length) h += `<div class="label" style="margin-top:18px">Your pools</div>` + mine.map(myPool).join("");
  h += `<div class="row" style="margin-top:18px"><div class="label">Open pools</div><button class="ghost" id="refresh" style="flex:0">refresh</button></div>`;
  h += open.length ? open.map((t) => `<div class="panel"><div class="item"><div><b>${t.private ? "🔒 " : ""}${sats(t.amount)} sats</b><br>
      <span class="faint">${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${t.feeRate} sat/vB · closes in ${left(t.expiresAt)}<br>
      costs you ${sats(t.feeWithChange)} sats (${sats(t.feeNoChange)} with an exact coin of ${sats(t.amount + t.feeNoChange)})</span></div>
      <button class="soft" data-join="${t.id}" style="flex:0">JOIN</button></div></div>`).join("") : `<p class="muted">No open pools right now. Open one: phones with Kilowallet and other nodes are notified.</p>`;
  h += `<div class="panel"><div class="label">Notifications</div><label><input type="checkbox" id="notify" style="width:auto" ${st.notify_new_pools ? "checked" : ""}> notify new public pools (every 5 minutes)</label>
    <p class="small">Also to Telegram, with sound, through your own bot: make one with @BotFather, send it any message, and put its token and your chat id here (@userinfobot tells you your id).</p>
    <input id="tgToken" placeholder="${st.telegram && st.telegram.telegram_set ? "bot token (saved; type to replace)" : "bot token"}" autocomplete="off">
    <input id="tgChat" placeholder="your chat id" value="${(st.telegram && st.telegram.telegram_chat) || ""}">
    <button id="tgSave">SAVE</button> <button id="tgTest">SEND A TEST</button> <span id="tgMsg" class="small"></span></div>`;
  h += `<div class="panel"><div class="label">Sign on its own</div><label><input type="checkbox" id="autoSign" style="width:auto" ${st.auto_sign ? "checked" : ""}> accept close requests and sign by itself</label>
    <p class="small">So a round does not wait for you. It signs only when the final transaction checks out (your mixed output, your change and your share of the fee), and only while Kilojoin is unlocked; otherwise it does not sign and tells you why.</p></div>`;
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
  on("#send", () => startSend());
  on("#exact", async () => {
    const exact = parseInt($("#exact").dataset.exact);
    const r = await api("/api/receive");
    // Pay for it with unmixed coins only, the biggest first, until they cover it.
    const pool = (st.coins || []).filter((c) => !c.inRound && c.label !== "mixed" && c.confirmations > 0).sort((a, b) => b.value - a.value);
    const pick = []; let sum = 0;
    for (const c of pool) { if (sum >= exact + 500) break; pick.push(c.outpoint); sum += c.value; }
    joining = null; chosen = null;
    await startSend({ exact: true, address: r.address, amount: String(exact), coins: pick });
  });
  on("#s-cancel", () => { sending = null; homeScreen(); });
  on("#s-preview", async () => { readSend(); try { sending.preview = await api("/api/send/preview", sendBody()); } catch (e) { msg = e.message; } homeScreen(); });
  on("#s-send", async () => {
    try { const r = await api("/api/send", Object.assign(sendBody(), { acceptWarning: true })); sending.txid = r.txid; sending.preview = null; }
    catch (e) { msg = e.message; }
    homeScreen();
  });
  // Typing only updates the draft (redrawing would steal the focus); an old review is dropped.
  document.querySelectorAll("[data-sc],#s-addr,#s-amount,#s-rate").forEach((el) => (el.oninput = el.onchange = () => {
    readSend(); if (sending.preview) { sending.preview = null; homeScreen(); }
  }));
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
  const as = $("#autoSign"); if (as) as.onchange = () => api("/api/settings", { auto_sign: as.checked });
  const tgMsg = m => { const e = $("#tgMsg"); if (e) e.textContent = m; };
  const ts = $("#tgSave"); if (ts) ts.onclick = async () => {
    const body = { telegram_chat: $("#tgChat").value }; if ($("#tgToken").value) body.telegram_token = $("#tgToken").value;
    try { await api("/api/settings", body); tgMsg("saved"); } catch (e) { tgMsg(e.message); } };
  const tt = $("#tgTest"); if (tt) tt.onclick = async () => { try { await api("/api/telegram/test", {}); tgMsg("sent ✓"); } catch (e) { tgMsg(e.message); } };
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
setInterval(async () => { if (st && st.unlocked && !joining && !sending && !selecting()) { try { st = await api("/api/status"); homeScreen(); } catch (e) {} } }, 5000);
