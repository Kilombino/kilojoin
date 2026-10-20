// Kilojoin web page: plain JS, no framework, no outside requests (everything goes to this node).
"use strict";
const $ = (s) => document.querySelector(s);
const app = $("#app");
const sats = (v) => Number(v).toLocaleString("en").replace(/,/g, " ");
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const MIN = 10000, MAX = 100000000;
let st = null, pools = [], view = "home", joining = null, chosen = [], msg = "", received = null, copied = false, sending = null;
// Coinjoin tabs (0 JOIN, 1 IN PROGRESS, 2 HISTORY; null: pick on first draw) and the round cards opened by hand.
let cjTab = null; const unfolded = {};

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
// Each person's fee share, as Kilowallet works it out: their inputs, their outputs, half the overhead.
// P2WPKH: input 68 vB, output 31. Taproot: input 57.75 (with the unified hash byte), output 43.
const inVb = (t) => (t === "p2tr" ? 57.75 : 68), outVb = (t) => (t === "p2tr" ? 43 : 31);
// o: mixed outputs each person gets (Kilojoin v4: 2 to 5; 1 before).
const feeNoChange = (rate, t = "p2wpkh", n = 1, o = 1) => Math.ceil(rate * (inVb(t) * n + outVb(t) * o + 10.5 / 2));
const feeWithChange = (rate, t = "p2wpkh", n = 1, o = 1) => Math.ceil(rate * (inVb(t) * n + outVb(t) * (o + 1) + 10.5 / 2));
// "4 × 100 000" for a pool of several outputs each, the plain amount otherwise.
const amountOf = (amount, o) => (o > 1 ? o + " × " + sats(amount) : sats(amount));
const kind = (t) => (t === "p2tr" ? "Taproot (bc1p)" : "native SegWit (bc1q)");
const badge = (t) => `<span class="${t === "p2tr" ? "good" : "muted"}">${t === "p2tr" ? "bc1p" : "bc1q"}</span>`;
// What a seat of these coins gives back: change, 0 (no change) or null (not enough).
function seatChange(total, amount, rate, t, n, o = 1) {
  const w = total - amount * o - feeWithChange(rate, t, n, o);
  if (w > 294) return w;
  return total - amount * o - feeNoChange(rate, t, n, o) >= 0 ? 0 : null;
}

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

function myPool(p, embedded) {
  let actions = "";
  if (p.phase === "OPEN") actions = (p.people >= p.minPeers ? `<button data-a="close" data-p="${p.id}">ASK TO CLOSE NOW</button> ` : "") +
    `<button class="soft" data-a="leave" data-p="${p.id}">${p.creator ? "END POOL" : "LEAVE"}</button>`;
  if (p.phase === "VOTING") actions = (p.iAsked || p.voted
    ? `<p class="muted">Waiting for the others, ${left(p.voteDeadline)} left. Whoever has not answered by then is left out; if too few are left, the pool reopens without them.</p>`
    : `<p class="muted">Answer within ${left(p.voteDeadline)}.</p><button data-a="yes" data-p="${p.id}">ACCEPT</button> <button class="soft" data-a="no" data-p="${p.id}">NOT YET</button> `) +
    `<button class="soft" data-a="leave" data-p="${p.id}">${p.creator ? "END POOL" : "LEAVE"}</button>`;
  if (p.phase === "SIGNING") actions = p.signed ? `<span class="muted">Signed. Waiting for the others (${p.sigs}/${p.planCoins} coins)…</span>` :
    `<p class="muted">Checked: ${p.planPeople} people (${p.planCoins} coins), identical outputs of ${sats(p.amount)} sats, ${p.outputs > 1 ? p.outputs + " of them" : "one of them"} yours; ${p.change > 0 ? "your change is right" : "no change (an exact coin)"}; total fee ${sats(p.planFee)} sats.</p><button data-a="sign" data-p="${p.id}">SIGN</button>`;
  if (p.txid) actions += `<p><code>${esc(p.txid)}</code></p>`;
  if (["CONFIRMED", "ABORTED", "REJECTED"].includes(p.phase)) actions += ` <button class="ghost" data-a="remove" data-p="${p.id}">remove</button>`;
  const cls = p.phase === "CONFIRMED" ? "good" : ["ABORTED", "REJECTED"].includes(p.phase) ? "bad" : ["VOTING", "SIGNING"].includes(p.phase) ? "warn" : "muted";
  const coinLine = `<p class="faint">${p.coins > 1 ? "your " + p.coins + " coins" : "your coin"} ${sats(p.coinValue)} → ${amountOf(p.amount, p.outputs)} mixed${p.change > 0 ? " + " + sats(p.change) + " change" : ""} · fee ${sats(p.coinValue - p.amount * p.outputs - p.change)}</p>`;
  if (embedded) return `<div class="detail">${coinLine}${actions}</div>`;
  return `<div class="panel"><div class="row"><b>${p.private ? "🔒 " : ""}${amountOf(p.amount, p.outputs)} sats${p.script === "p2tr" ? " · Taproot" : ""}</b><span class="faint" style="text-align:right">${p.creator ? "your pool" : "joined"}</span></div>
    <p class="${cls}">${esc(phaseText(p))}</p>
    <p class="faint">${p.coins > 1 ? "your " + p.coins + " coins" : "your coin"} ${sats(p.coinValue)} → ${amountOf(p.amount, p.outputs)} mixed${p.change > 0 ? " + " + sats(p.change) + " change" : ""} · fee ${sats(p.coinValue - p.amount * p.outputs - p.change)}</p>${actions}</div>`;
}

// The pool's coins: only its address type; one coin each, or up to maxInputs ticked together.
function coinPicker(amount, rate, t, maxInputs, o = 1) {
  const exact = amount * o + feeNoChange(rate, t, 1, o);
  const coins = (st.coins || []).filter((c) => !c.inRound && c.confirmations > 0 && c.type === t && (maxInputs > 1 || c.value >= exact))
    .sort((a, b) => (a.value === exact ? -1 : b.value === exact ? 1 : b.value - a.value));
  let h = `<p class="faint">Only ${kind(t)} coins can join this pool${maxInputs > 1 ? `; tick up to ${maxInputs}` : "; pick ONE"}. Your other coins can still pay for an exact coin of this type below.</p>`;
  if (!coins.length) return h + `<p class="warn">No confirmed ${kind(t)} coin${maxInputs > 1 ? "" : " of at least " + sats(exact) + " sats"}. Prepare an exact coin, or receive one and scan again.</p>`;
  h += coins.map((c) => `<div class="pick ${chosen.includes(c.outpoint) ? "sel" : ""}" data-coin="${c.outpoint}"><b>${sats(c.value)} sats</b>${c.value === exact ? ' <span class="good">★ EXACT</span>' : ""}
      <span class="faint">${c.confirmations} conf${c.label ? " · " + c.label : ""} · ${esc(c.address.slice(0, 10))}…${esc(c.address.slice(-6))}</span></div>`).join("");
  if (chosen.length) {
    const total = (st.coins || []).filter((c) => chosen.includes(c.outpoint)).reduce((a, c) => a + c.value, 0);
    const ch = seatChange(total, amount, rate, t, chosen.length, o);
    h += `<p class="${ch === null ? "warn" : "good"}">${chosen.length} coin(s), ${sats(total)} sats → ` + (ch === null ? `${sats(amount * o + feeNoChange(rate, t, chosen.length, o) - total)} sats short`
      : ch === 0 ? (total === exact ? "★ exact: no change" : `no change, ${sats(total - amount * o - feeNoChange(rate, t, chosen.length, o))} extra to the miners`)
      : `${amountOf(amount, o)} mixed + ${sats(ch)} change · fee ${sats(total - amount * o - ch)}`) + `</p>`;
    if (ch > 0) h += `<p class="warn">Tip: an exact coin of ${sats(exact)} sats joins with no change at all, so nothing ties your mixed output to anything.</p>`;
  }
  return h;
}
const pickOk = () => {
  if (!joining || !chosen.length) return false;
  const total = (st.coins || []).filter((c) => chosen.includes(c.outpoint)).reduce((a, c) => a + c.value, 0);
  return seatChange(total, joining.amount, joining.feeRate, joining.script, chosen.length, joining.outputs) !== null;
};

// The perfect coin for a pool: a payment to yourself of exactly amount + the no-change fee share, of the pool's type.
function exactButton(amount, rate, t, o = 1) {
  const exact = amount * o + feeNoChange(rate, t, 1, o);
  if ((st.coins || []).some((c) => c.value === exact && c.type === t && !c.inRound)) return "";
  return `<button class="ghost" id="exact" data-exact="${exact}" data-type="${t}">＋ PREPARE AN EXACT COIN OF ${sats(exact)} SATS</button>`;
}

function sendPanel() {
  const sd = sending;
  const coins = (st.coins || []).filter((c) => !c.inRound);
  let h = `<div class="panel"><div class="label">${sd.exact ? "Exact coin · " + sats(sd.amount) + " sats to yourself" : "Send"}</div>`;
  if (sd.exact) h += `<p class="faint">A payment to a fresh ${kind(sd.type)} address of this wallet (<code>${esc(sd.address.slice(0, 10))}…${esc(sd.address.slice(-6))}</code>). After one confirmation it joins with no change${sd.after ? ", and Kilojoin " + (sd.after.create ? "opens the pool" : "joins the pool") + " with it by itself" : ""}. Pay it with any of your coins, bc1q and bc1p alike; mixed coins are left out on purpose.</p>`;
  h += `<p class="faint">Tick the coins to spend (coin control). Never spend a mixed coin with other coins: that links them again.</p>`;
  h += coins.map((c) => `<label style="display:flex;gap:8px;align-items:center;margin:4px 0"><input type="checkbox" style="width:auto" data-sc="${c.outpoint}" ${sd.coins.includes(c.outpoint) ? "checked" : ""}>
      <span><b>${sats(c.value)}</b> sats ${badge(c.type)} <span class="faint">${c.confirmations} conf${c.label ? " · <span class='" + (c.label === "mixed" ? "good" : "muted") + "'>" + c.label + "</span>" : ""} · ${esc(c.address.slice(0, 10))}…${esc(c.address.slice(-6))}</span></span></label>`).join("");
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
const sendBody = () => Object.assign({ coins: sending.coins, address: sending.address, amount: parseInt(sending.amount) || 0, feeRate: sending.feeRate },
  sending.exact && sending.after ? { after: sending.after, exactIndex: sending.index, exactType: sending.type } : {});

function homeScreen() {
  const mine = st.mine || [];
  const open = pools.filter((t) => !mine.some((m) => m.id === t.id && !["ABORTED", "REJECTED", "CONFIRMED", "BROADCAST"].includes(m.phase)));
  let h = "";
  if (msg) h += `<div class="panel warn">${esc(msg)} <button class="ghost" id="msgok">ok</button></div>`;
  h += `<div class="panel"><div class="label">Wallet</div><div class="big">${sats(st.balance || 0)} <span style="font-size:18px">sats</span></div>
    <p class="faint">height ${st.height} · ${st.scannedAt ? "scanned " + new Date(st.scannedAt).toLocaleTimeString() : "scanning the UTXO set… (can take a minute)"} · relay ${esc(st.relay)}</p>
    <div class="row"><button class="soft" id="scan">SCAN AGAIN</button><button class="soft" id="recv">RECEIVE bc1q</button>${st.taproot ? `<button class="soft" id="recvtr">RECEIVE bc1p</button>` : ""}<button class="soft" id="send">SEND</button></div>${received ? `<p><code id="addrtext">${esc(received.address)}</code><br><span class="faint">fresh ${kind(received.type)} address #${received.index}, used only once</span></p><div class="row"><button id="copy">${copied ? "COPIED ✓" : "COPY"}</button><button class="ghost" id="hideaddr">hide</button></div>` : ""}</div>`;
  if (sending) h += sendPanel();
  else if (joining) {
    h += `<div class="panel"><div class="label">${joining.create ? "Open a pool" : "Join · " + amountOf(joining.amount, joining.outputs) + " sats"}</div>`;
    if (joining.create) h += `<label>address type: every coin, mixed output and change of the pool is of it</label>
      <select id="c-type"><option value="p2wpkh" ${joining.script === "p2wpkh" ? "selected" : ""}>Native SegWit (bc1q)</option>${st.taproot ? `<option value="p2tr" ${joining.script === "p2tr" ? "selected" : ""}>Taproot (bc1p)</option>` : ""}</select>
      <label>mixed outputs per person (1–5)</label><input id="c-outputs" value="${joining.outputs}">
      <p class="faint">${joining.outputs > 1 ? `Each person gets ${joining.outputs} mixed outputs of the amount below, each to an unused address of their own (${sats(joining.amount * joining.outputs)} sats per person in all). Only Kilojoin 0.8 and Kilowallet 1.2.0 or newer (Kilojoin v4) see this pool.` : "One mixed output each: every Kilojoin v3 wallet can join it."}</p>
      <div class="row"><div><label>${joining.outputs > 1 ? "amount of each output (sats)" : "amount per person (sats)"}</label><input id="c-amount" value="${joining.amount}"></div><div><label>fee (sat/vB)</label><input id="c-rate" value="${joining.feeRate}"></div></div>
      <div class="row"><div><label>fewest people</label><input id="c-min" value="2"></div><div><label>most people</label><input id="c-max" value="5"></div><div><label>open for (hours)</label><input id="c-hours" value="6"></div></div>
      <label>most coins per person (1–10)</label><input id="c-inputs" value="${joining.maxInputs}">
      <p class="small">Blind outputs (Kilojoin v3): when the round closes, nobody, not even you as its creator, can tell whose mixed output is whose. Only Kilowallet 1.1.5+ and Kilojoin 0.7+ see this pool.</p>
      <label>password (optional: makes it private 🔒)</label><input id="c-pw">`;
    else if (joining.private) h += `<label>🔒 pool password</label><input id="c-pw">`;
    h += `<p class="faint">Each person pays ${sats(feeWithChange(joining.feeRate, joining.script, 1, joining.outputs))} sats with change, ${sats(feeNoChange(joining.feeRate, joining.script, 1, joining.outputs))} without, with one coin; each extra coin adds ${sats(Math.ceil(joining.feeRate * inVb(joining.script)))}.</p>
      <div id="coins">${coinPicker(joining.amount, joining.feeRate, joining.script, joining.maxInputs, joining.outputs)}</div>${exactButton(joining.amount, joining.feeRate, joining.script, joining.outputs)}
      <div class="row"><button class="soft" id="cancel">CANCEL</button><button id="confirm" ${pickOk() ? "" : "disabled"}>${joining.create ? "OPEN POOL" : "JOIN"}</button></div></div>`;
  } else {
    // JOIN · IN PROGRESS · HISTORY, as in the Kilowallet app: rounds going are shown first.
    const over = (p) => ["CONFIRMED", "ABORTED", "REJECTED"].includes(p.phase);
    const active = mine.filter((p) => !over(p)), finished = mine.filter(over), waiting = st.waitingExact || [];
    if (cjTab === null) cjTab = active.length || waiting.length ? 1 : 0;
    h += `<div class="cjtabs">${["JOIN", "IN PROGRESS", "HISTORY"].map((l, i) => `<button class="${cjTab === i ? "on" : ""}" data-cjtab="${i}">${l}${i === 1 && active.length ? `<span class="count">${active.length}</span>` : ""}</button>`).join("")}</div>`;
    if (cjTab === 1) {
      if (!active.length && !waiting.length) h += `<p class="faint">No rounds going. Join a pool, or open your own, in JOIN.</p>`;
      h += active.map(roundCard).join("");
      waiting.forEach((x) => { h += `<div class="panel"><div class="label">Waiting for the perfect coin</div><p class="faint">${sats(x.value)} sats to yourself: at its first confirmation it ${x.create ? "opens your pool" : "joins the pool"} by itself.</p></div>`; });
    } else if (cjTab === 2) {
      h += finished.length ? finished.map(roundCard).join("") + `<button class="ghost" id="removeAll">remove all</button>` : `<p class="faint">Confirmed and cancelled rounds show here, for a week.</p>`;
    } else {
      h += `<button id="create">＋ OPEN A POOL</button>`;
      h += `<div class="row" style="margin-top:18px"><div class="label">Open pools</div><button class="ghost" id="refresh" style="flex:0">refresh</button></div>`;
      h += open.length ? open.map((t) => `<div class="panel"><div class="item"><div><b>${t.private ? "🔒 " : ""}${amountOf(t.amount, t.outputs)} sats</b><br>
          <span class="${t.script === "p2tr" ? "good" : "muted"}">${kind(t.script)} coins only · ${t.maxInputs > 1 ? "up to " + t.maxInputs + " coins each" : "one coin each"}${t.outputs > 1 ? " · " + t.outputs + " outputs each (v4)" : t.version >= 3 ? " · blind outputs (v3)" : ""}</span><br>
          <span class="faint">${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${t.feeRate} sat/vB · closes in ${left(t.expiresAt)}<br>
          costs you ${sats(t.feeWithChange)} sats with one coin (${sats(t.feeNoChange)} with an exact coin of ${sats(t.amount * t.outputs + t.feeNoChange)})</span>
          ${t.script === "p2tr" && !st.taproot ? `<br><span class="warn">Unlock Kilojoin again to set up its Taproot account.</span>` : ""}</div>
          <button class="soft" data-join="${t.id}" style="flex:0" ${t.script === "p2tr" && !st.taproot ? "disabled" : ""}>JOIN</button></div></div>`).join("") : `<p class="muted">No open pools right now. Open one: phones with Kilowallet and other nodes are notified.</p>`;
      h += `<div class="panel"><div class="label">Notifications</div><label><input type="checkbox" id="notify" style="width:auto" ${st.notify_new_pools ? "checked" : ""}> notify new public pools (every 5 minutes)</label>
        <p class="small">Also to Telegram, with sound, through your own bot: make one with @BotFather, send it any message, and put its token and your chat id here (@userinfobot tells you your id).</p>
        <input id="tgToken" placeholder="${st.telegram && st.telegram.telegram_set ? "bot token (saved; type to replace)" : "bot token"}" autocomplete="off">
        <input id="tgChat" placeholder="your chat id" value="${(st.telegram && st.telegram.telegram_chat) || ""}">
        <button id="tgSave">SAVE</button> <button id="tgTest">SEND A TEST</button> <span id="tgMsg" class="small"></span>
        <label style="margin-top:10px;display:block"><input type="checkbox" id="beep" style="width:auto" ${soundOn() ? "checked" : ""}> sound and a notification in this browser, while this page is open</label></div>`;
      h += `<div class="panel"><div class="label">Sign on its own</div><label><input type="checkbox" id="autoSign" style="width:auto" ${st.auto_sign ? "checked" : ""}> accept close requests and sign by itself</label>
        <p class="small">Unlocking to open or join a pool is the only time it asks: the round goes on with this page closed, and signs only when the final transaction checks out (your mixed output, your change and your share of the fee). The keys of that round's coins stay in memory until it ends; if Kilojoin restarts, unlock it once to let it sign.</p></div>`;
    }
  }
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
  on("#recvtr", async () => { received = await api("/api/receive?type=p2tr"); copied = false; homeScreen(); });
  on("#hideaddr", () => { received = null; homeScreen(); });
  on("#copy", async () => {
    try { await navigator.clipboard.writeText(received.address); }
    catch (e) { const r = document.createRange(); r.selectNodeContents($("#addrtext")); const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); document.execCommand("copy"); }
    copied = true; homeScreen();
  });
  on("#send", () => startSend());
  on("#exact", async () => {
    const exact = parseInt($("#exact").dataset.exact), t = $("#exact").dataset.type;
    const r = await api("/api/receive" + (t === "p2tr" ? "?type=p2tr" : ""));
    // Suggested: unmixed coins of either type, the biggest first, until they cover it. Change them in the coin control.
    const pool = (st.coins || []).filter((c) => !c.inRound && c.label !== "mixed" && c.confirmations > 0).sort((a, b) => b.value - a.value);
    const pick = []; let sum = 0;
    for (const c of pool) { if (sum >= exact + 500) break; pick.push(c.outpoint); sum += c.value; }
    // Once it confirms, Kilojoin joins this pool (or opens the one being set up) by itself.
    const after = !joining ? null : joining.create ? { create: { amount: joining.amount, feeRate: joining.feeRate, script: joining.script, maxInputs: joining.maxInputs, outputs: joining.outputs,
        minPeers: parseInt($("#c-min").value) || 2, maxPeers: parseInt($("#c-max").value) || 5, hours: parseInt($("#c-hours").value) || 6, password: $("#c-pw").value } }
      : { pool: joining.id, poolPassword: $("#c-pw") ? $("#c-pw").value : "" };
    joining = null; chosen = [];
    await startSend({ exact: true, type: t, address: r.address, index: r.index, after, amount: String(exact), coins: pick });
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
  on("#create", () => { joining = { create: true, amount: MIN, feeRate: 1, script: "p2wpkh", maxInputs: 5, outputs: 1 }; chosen = []; homeScreen(); });
  on("#cancel", () => { joining = null; chosen = []; homeScreen(); });
  document.querySelectorAll("[data-join]").forEach((b) => (b.onclick = () => { const t = pools.find((p) => p.id === b.dataset.join); joining = { create: false, id: t.id, amount: t.amount, feeRate: t.feeRate, private: t.private, script: t.script, maxInputs: t.maxInputs, outputs: t.outputs || 1 }; chosen = []; homeScreen(); }));
  document.querySelectorAll("[data-coin]").forEach((d) => (d.onclick = () => {
    const op = d.dataset.coin;
    if (chosen.includes(op)) chosen = chosen.filter((x) => x !== op);
    else if (joining.maxInputs > 1) { if (chosen.length < joining.maxInputs) chosen = chosen.concat(op); }
    else chosen = [op];
    homeScreen();
  }));
  if (joining && joining.create) ["#c-amount", "#c-rate", "#c-type", "#c-inputs", "#c-outputs"].forEach((id) => ($(id).onchange = () => {
    joining.amount = Math.max(MIN, Math.min(MAX, parseInt($("#c-amount").value) || MIN)); joining.feeRate = Math.max(1, parseFloat($("#c-rate").value) || 2);
    joining.script = $("#c-type").value; joining.maxInputs = Math.max(1, Math.min(10, parseInt($("#c-inputs").value) || 1));
    joining.outputs = Math.max(1, Math.min(5, parseInt($("#c-outputs").value) || 1)); chosen = []; homeScreen(); }));
  on("#confirm", async () => {
    try {
      if (joining.create) await api("/api/create", { coins: chosen, script: joining.script, maxInputs: joining.maxInputs, outputs: joining.outputs, amount: joining.amount, feeRate: joining.feeRate, minPeers: parseInt($("#c-min").value) || 2,
        maxPeers: parseInt($("#c-max").value) || 5, hours: parseInt($("#c-hours").value) || 6, password: $("#c-pw").value });
      else await api("/api/join", { pool: joining.id, coins: chosen, password: $("#c-pw") ? $("#c-pw").value : "" });
      joining = null; chosen = []; await load();
    } catch (e) { msg = e.message; homeScreen(); }
  });
  document.querySelectorAll("[data-cjtab]").forEach((b) => (b.onclick = () => { cjTab = parseInt(b.dataset.cjtab); homeScreen(); }));
  document.querySelectorAll("[data-round]").forEach((d) => (d.onclick = () => { const id = d.dataset.round; unfolded[id] = !(id in unfolded ? unfolded[id] : d.dataset.open === "1"); homeScreen(); }));
  on("#removeAll", async () => { for (const p of (st.mine || []).filter((m) => ["CONFIRMED", "ABORTED", "REJECTED"].includes(m.phase))) await api("/api/remove", { pool: p.id }).catch(() => {}); await load(); });
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
  const bp = $("#beep"); if (bp) bp.onchange = () => {
    try { localStorage.setItem("kilojoin_sound", bp.checked ? "1" : "0"); } catch (e) {}
    if (bp.checked) { chime(); if (window.Notification && Notification.permission === "default") Notification.requestPermission(); }
  };
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

// Sound (and a browser notification) for every pool event while this page is open.
function soundOn() { try { return localStorage.getItem("kilojoin_sound") === "1"; } catch (e) { return false; } }
function chime() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    [880, 1320].forEach((f, i) => {
      const o = ctx.createOscillator(), g = ctx.createGain(), t = ctx.currentTime + i * 0.18;
      o.frequency.value = f; o.connect(g); g.connect(ctx.destination);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.25, t + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
      o.start(t); o.stop(t + 0.32);
    });
  } catch (e) {}
}
let lastEventAt = null;
setInterval(async () => {
  if (!st || !st.unlocked) return;
  try {
    const evs = await api("/api/events");
    const newest = evs.length ? evs[0].at : 0;
    if (lastEventAt === null) { lastEventAt = newest; return; } // what was there before this page opened
    const fresh = evs.filter(e => e.at > lastEventAt);
    lastEventAt = Math.max(lastEventAt, newest);
    if (!fresh.length || !soundOn()) return;
    chime();
    if (window.Notification && Notification.permission === "granted")
      fresh.slice(0, 3).forEach(e => new Notification(e.title, { body: e.text, tag: e.at + e.title }));
  } catch (e) {}
}, 5000);

// ------------------------------------------------------------------ round cards

// Where a round is, 1..5: people, closing vote, outputs and signatures, sent, confirmed (0: ended without).
const roundStep = (p) => ({ JOINING: 1, OPEN: 1, VOTING: 2, CLOSING: 3, SIGNING: 3, BROADCAST: 4, CONFIRMED: 5 }[p.phase] || 0);
// True while the round waits on you: an answer, or a signature by hand.
const roundNeedsMe = (p) => !st.auto_sign && ((p.phase === "VOTING" && !p.iAsked && !p.voted) || (p.phase === "SIGNING" && !p.signed));
const hhmm = (t) => new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

// One round at a glance: amount, the bar of its five steps, what is happening and where your coin is.
function roundCard(p) {
  const step = roundStep(p), ended = step === 0, needs = roundNeedsMe(p);
  const open = p.id in unfolded ? unfolded[p.id] : needs;
  const status = {
    JOINING: "Asking to join",
    OPEN: `Waiting for people · ${p.people}/${p.maxPeers} people · closes at ${hhmm(p.expiresAt)}`,
    VOTING: needs ? `Closing vote · your answer, by ${hhmm(p.voteDeadline)}` : `Closing vote · ${p.people} people`,
    CLOSING: `Closing · ${p.people} people · collecting outputs`,
    SIGNING: p.signed ? "Signed · waiting for the others" : needs ? "Ready to sign" : "Signing",
    BROADCAST: "Sent · waiting for a block", CONFIRMED: "Confirmed", ABORTED: "Cancelled", REJECTED: "Refused",
  }[p.phase] || p.phase;
  const where = p.phase === "BROADCAST" ? "Your coin left in the group transaction. Waiting for a block."
    : p.phase === "CONFIRMED" ? (p.outputs > 1 ? `Done: your ${p.outputs} mixed coins of ${sats(p.amount)} sats are in your wallet.` : `Done: your mixed coin of ${sats(p.amount)} sats is in your wallet.`)
    : ended ? `${esc((p.reason || "ended").replace(/^./, (c) => c.toUpperCase()))}. Your coin did not move.`
    : "Your coin is still in your wallet — it hasn't left.";
  const cls = ended ? "bad" : step === 5 ? "good" : "accent";
  let dots = "";
  for (let i = 1; i <= 5; i++) {
    dots += `<span class="dot${i < step || step === 5 ? " done" : i === step ? " now" : ""}"></span>`;
    if (i < 5) dots += `<span class="bar${i < step ? " done" : ""}"></span>`;
  }
  return `<div class="panel round${ended ? " ended" : ""}"><div class="tap" data-round="${p.id}" data-open="${needs ? 1 : 0}">
    <div class="row"><b>${p.private ? "🔒 " : ""}${amountOf(p.amount, p.outputs)} sats each${p.script === "p2tr" ? " · Taproot" : ""}</b><b class="${cls}" style="flex:0;white-space:nowrap">${ended ? "" : step + "/5 "}${open ? "⌄" : "›"}</b></div>
    ${ended ? "" : `<div class="steps">${dots}</div>`}
    <p class="${cls}" style="margin:4px 0">${status}</p><p class="muted" style="margin:0;font-size:13px">${where}</p></div>
    ${open ? myPool(p, true) : ""}</div>`;
}
