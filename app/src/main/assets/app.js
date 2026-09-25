(function () {
  "use strict";

  /* ------------------------------------------------------------ bridge */
  const B = window.Dilmun;
  const MODEL_PAGE = "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF";

  function call(name) {
    const args = Array.prototype.slice.call(arguments, 1);
    if (!B || typeof B[name] !== "function") return { error: "The app's engine isn't connected to this page." };
    if (name !== "trace") soonTrace();
    try { return JSON.parse(B[name].apply(B, args)); }
    catch (e) { return { error: String(e && e.message || e) }; }
  }

  /* ------------------------------------------------------------ live trace
     The engine records each step it takes (request, steer, sign, commit, read,
     check, promote...). We read everything since the last seq and hand it to
     the map, which plays it back as it happens. */
  let traceSeq = 0, traceSoon = 0, mapReady = false;
  const traceBacklog = [];
  function soonTrace() { if (!traceSoon) traceSoon = setTimeout(() => { traceSoon = 0; drainTrace(); }, 0); }
  function drainTrace() {
    const r = call("trace", traceSeq);
    const evs = r && r.ok;
    if (!evs || !evs.length) return;
    traceSeq = evs[evs.length - 1].seq;
    evs.forEach(e => traceBacklog.push(e));
    if (traceBacklog.length > 200) traceBacklog.splice(0, traceBacklog.length - 200);
    if (mapReady) postToMap({ type: "trace", events: evs });
    evs.forEach(activityFromTrace);
  }
  // Background work (a folder scan) nudges us through dilmunEvent; this catches anything else.
  setInterval(drainTrace, 1500);
  function ok(r, quiet) {
    if (r && r.error !== undefined) { if (!quiet) toast(r.error, true); return undefined; }
    return r ? r.ok : undefined;
  }

  /* Slow work runs as a job: the call returns its number at once, and the
     engine reports progress and the outcome through events. */
  const jobs = {};
  function job(name, handlers) {
    const args = Array.prototype.slice.call(arguments, 2);
    const id = ok(call.apply(null, [name].concat(args)));
    if (id === undefined) { if (handlers.error) handlers.error(null); return undefined; }
    jobs[id] = handlers;
    return id;
  }

  /* ------------------------------------------------------------ helpers */
  const $ = id => document.getElementById(id);
  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = String(text);
    return n;
  }
  function li() { return document.createElement("li"); }
  function empty(list, text) { list.appendChild(el("li", "empty", text)); }
  function plural(n, one, many) { return n + " " + (n === 1 ? one : many); }
  function fmtTime(ms) {
    const d = new Date(ms), now = new Date();
    const hm = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return d.toDateString() === now.toDateString() ? hm : d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + hm;
  }
  function short(id) { return id ? String(id).replace(/^(dir:|src:|sample:|e:|d:|k:)/, "").slice(0, 10) : ""; }
  function fmtBytes(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(2) + " GB";
    if (n >= 1e6) return (n / 1e6).toFixed(0) + " MB";
    return Math.max(0, n) + " B";
  }
  function fmtParams(n) { return n >= 1e9 ? (n / 1e9).toFixed(2) + "B" : (n / 1e6).toFixed(0) + "M"; }

  /* ------------------------------------------------------------ activity card
     On the Ask tab, between the conversation and the composer: what the app is
     doing (sources read, passages, facts kept or refused, model events, errors).
     Folded, it's one line; errors open it. Same stream as the map's feed. */
  const ACT_KEEP = 150;
  let actOpen = false, actCount = 0, actBad = false, actLast = "";
  try { actOpen = localStorage.getItem("dilmun.activity") === "open"; } catch (e) { /* storage may be unavailable */ }
  const ACT_STEPS = { scan: 1, read: 1, passage: 1, "passage-done": 1, fact: 1, check: 1, refuse: 1, promote: 1,
                      hold: 1, bulk: 1, model: 1, recall: 1, verify: 1 };
  function activityFromTrace(ev) {
    if (!ACT_STEPS[ev.step]) return;
    let tone = null;
    if (ev.step === "refuse" || (ev.step === "verify" && !ev.ok)) tone = "bad";
    else if (ev.step === "fact") tone = ev.more ? null : ev.kept ? "good" : "bad";
    else if (ev.step === "check") tone = ev.rejected > 0 && !ev.accepted ? "bad" : "good";
    else if (ev.step === "promote" || (ev.step === "bulk" && ev.event === "done")) tone = "good";
    // a refused fact is expected work, not an error: only a refused request opens the card
    activity(ev.text, tone, ev.t, ev.step === "refuse");
  }
  function activity(text, tone, t, alarm) {
    const list = $("activity-list");
    const li = el("li", tone || null);
    const d = new Date(t || Date.now());
    li.appendChild(el("span", "tm", [d.getHours(), d.getMinutes(), d.getSeconds()].map(x => String(x).padStart(2, "0")).join(":")));
    li.appendChild(el("span", null, text));
    list.prepend(li);
    while (list.children.length > ACT_KEEP) list.lastChild.remove();
    actCount++;
    actLast = text;
    if (alarm) { actBad = true; setActOpen(true, false); }
    paintActivity();
  }
  function paintActivity() {
    const box = $("activity");
    box.hidden = actCount === 0;
    const b = bulkState && (bulkState.state === "running" || bulkState.busy) ? bulkState : null;
    let line = actLast;
    if (b) {
      line = "Bulk: source " + Math.min(b.total, b.next + 1) + " of " + b.total + (b.current ? " · " + b.current : "")
        + (b.passages ? " · passage " + b.passage + " of " + b.passages : "") + " · " + b.kept + " kept, " + b.refused + " refused";
    } else if (activeExtract) {
      line = "Reading " + activeExtract.name + (activeExtract.passages ? " · passage " + activeExtract.passage + " of " + activeExtract.passages : "");
    }
    $("activity-line").textContent = line;
    $("activity-count").textContent = String(actCount);
    box.classList.toggle("live", !!(b || activeExtract));
    box.classList.toggle("bad", actBad);
    $("activity-head").setAttribute("aria-expanded", String(actOpen));
    $("activity-list").hidden = !actOpen;
  }
  function setActOpen(open, save) {
    actOpen = open;
    if (!open) actBad = false;
    if (save) { try { localStorage.setItem("dilmun.activity", open ? "open" : "closed"); } catch (e) { /* ignore */ } }
    paintActivity();
  }
  $("activity-head").addEventListener("click", () => setActOpen(!actOpen, true));

  let toastTimer = 0;
  function toast(text, bad, sticky, traced) {
    // On the Ask tab, messages go into the activity card instead of covering the chat.
    // (traced: the engine's trace already put this in the card.)
    if (!traced) activity(text, bad ? "bad" : null, Date.now(), !!bad);
    if (current === "ask") return;
    const t = $("toast");
    t.textContent = text;
    t.className = bad ? "bad" : "";
    t.hidden = false;
    clearTimeout(toastTimer);
    if (!sticky) toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 5200 : 2800);
  }

  /* ------------------------------------------------------------ tabs */
  const TABS = ["map", "ask", "sources", "work", "memory", "log"];
  let current = "map", summary = null;
  const tabButtons = document.querySelectorAll(".tabs button");
  tabButtons.forEach(b => b.addEventListener("click", () => show(b.dataset.tab)));

  function show(name) {
    current = name;
    tabButtons.forEach(b => b.setAttribute("aria-current", b.dataset.tab === name ? "page" : "false"));
    TABS.forEach(t => { $("tab-" + t).hidden = t !== name; });
    postToMap({ type: "visible", visible: name === "map" });
    render(name);
  }

  function postToMap(msg) {
    const f = $("map");
    if (f && f.contentWindow) f.contentWindow.postMessage(msg, "*");
  }

  /* ------------------------------------------------------------ top bar */
  function refreshSummary() {
    const r = call("summary");
    const s = ok(r, true);
    if (!s) {
      $("status").textContent = "error";
      $("status").className = "pill frozen";
      if (r && r.error) toast(r.error, true);
      return;
    }
    summary = s;
    const st = $("status");
    if (s.frozen) { st.textContent = "frozen"; st.className = "pill frozen"; }
    else if (s.paused) { st.textContent = "paused"; st.className = "pill paused"; }
    else { st.textContent = "running"; st.className = "pill ok"; }
    $("statehash").textContent = "state " + s.state;
    postToMap({ type: "stats", stats: s });
  }

  function render(name) {
    if (name === "ask") renderModel();
    else if (name === "sources") renderSources();
    else if (name === "work") renderWork();
    else if (name === "memory") renderMemory();
    else if (name === "log") renderLog(true);
  }

  function refresh() { refreshSummary(); render(current); }

  /* ------------------------------------------------------------ model card */
  let modelBusy = null;          // "import" | "load" while those run
  let modelCompact = false;

  function renderModel() {
    const m = ok(call("modelStatus"), true) || {};
    const card = $("model-card"), state = $("model-state");
    const file = m.file;
    $("model-title").textContent = file ? file.name : "No model yet";
    const bits = [];
    if (m.info) {
      bits.push(fmtParams(m.info.params) + " parameters");
      bits.push(fmtBytes(m.info.bytes));
      bits.push(m.info.n_ctx + "-token context");
      bits.push(m.threads + " threads");
    } else if (file) bits.push(fmtBytes(file.bytes));
    if (file) bits.push("fingerprint " + file.sha.slice(0, 12));
    $("model-meta").textContent = bits.join(" · ");
    if (modelBusy === "import") { state.textContent = "importing"; state.className = "pill busy"; }
    else if (modelBusy === "load" || m.loading) { state.textContent = "loading"; state.className = "pill busy"; }
    else if (m.loaded) { state.textContent = "loaded"; state.className = "pill loaded"; }
    else if (file) { state.textContent = "not loaded"; state.className = "pill"; }
    else { state.textContent = "none"; state.className = "pill"; }
    $("model-help").hidden = !!file;
    $("btn-import").textContent = file ? "Replace model file" : "Import model file";
    $("btn-import").className = file ? "small" : "primary small";
    $("btn-load").hidden = !file;
    $("btn-load").textContent = m.loaded ? "Unload" : "Load";
    $("btn-load").disabled = !!modelBusy;
    $("btn-import").disabled = !!modelBusy;
    $("btn-remove").hidden = !file;
    $("use-model").checked = !!m.useForExtraction;
    renderThreads(m);
    renderDiag(m);
    if (m.error && !modelBusy) $("model-meta").textContent += (bits.length ? " · " : "") + m.error;
    modelCompact = !!m.loaded && modelCompact;
    card.classList.toggle("compact", modelCompact);
    $("btn-send").disabled = false;
    if (!$("thread").children.length) emptyThread(m);
    return m;
  }

  $("model-card").addEventListener("click", e => {
    if (!modelCompact) return;
    modelCompact = false;
    $("model-card").classList.remove("compact");
    e.stopPropagation();
  });
  $("btn-get-model").addEventListener("click", () => { if (B) B.openLink(MODEL_PAGE); });
  $("btn-import").addEventListener("click", () => { if (B) B.importModel(); });
  $("btn-remove").addEventListener("click", () => {
    if (!confirmTwice($("btn-remove"), "Tap again to remove")) return;
    if (ok(call("removeModel")) !== undefined) { toast("Model file removed"); refresh(); }
  });
  $("btn-load").addEventListener("click", () => {
    const m = ok(call("modelStatus"), true) || {};
    if (m.loaded) { ok(call("unloadModel")); toast("Model unloaded"); renderModel(); refreshSummary(); return; }
    modelBusy = "load";
    renderModel();
    job("loadModel", {
      done: () => { modelBusy = null; modelCompact = true; toast("Model loaded"); renderModel(); refreshSummary(); },
      error: e => { modelBusy = null; if (e) toast(e, true); renderModel(); }
    });
  });
  /* Threads: automatic (a safe default for this phone), or a fixed count up to the core count. */
  function renderThreads(m) {
    const sel = $("threads");
    const cores = m.cores || 4, setting = m.threadsSetting || 0;
    const opts = [0].concat([1, 2, 4, 6, 8].filter(n => n <= cores));
    if (setting && opts.indexOf(setting) < 0) opts.push(setting);
    const key = opts.join(",") + "|" + setting + "|" + m.threadsAuto;
    if (sel.dataset.key !== key) {
      sel.dataset.key = key;
      sel.innerHTML = "";
      opts.forEach(n => {
        const o = el("option", null, n === 0 ? "Automatic (" + m.threadsAuto + ")" : String(n));
        o.value = String(n);
        sel.appendChild(o);
      });
    }
    sel.value = String(setting);
    sel.disabled = !!modelBusy;
    $("threads-note").textContent = m.loaded ? "running on " + m.threads + " of " + cores + " cores" : cores + " cores on this device";
  }
  $("threads").addEventListener("change", e => {
    const r = ok(call("setThreads", Number(e.target.value)));
    if (r === undefined) { renderModel(); return; }
    if (typeof r === "number") {                         // loaded: reloading with the new count
      modelBusy = "load";
      jobs[r] = {
        done: () => { modelBusy = null; toast("Model reloaded on " + (Number(e.target.value) || "the automatic number of") + " threads"); renderModel(); refreshSummary(); },
        error: err => { modelBusy = null; if (err) toast(err, true); renderModel(); }
      };
    } else toast("Saved: applies the next time the model loads");
    renderModel();
  });

  /* What llama.cpp says about the file and this device, for when the output looks wrong. */
  function renderDiag(m) {
    const box = $("model-diag"), dl = $("model-diag-list");
    const i = m.info;
    box.hidden = !i;
    if (!i) return;
    dl.innerHTML = "";
    const row = (k, v, bad) => { dl.appendChild(el("dt", null, k)); dl.appendChild(el("dd", bad ? "bad" : null, v)); };
    row("model", i.desc || "?");
    row("name", i.name || "(not set in the file)");
    row("architecture", i.arch || "?");
    row("tokenizer", (i.tokenizer || "?") + (i.pre ? " · pre-tokenizer " + i.pre : " · no pre-tokenizer named") + (i.n_vocab ? " · " + i.n_vocab + " tokens" : ""), !i.pre);
    row("chat template", i.template ? "from the file" : "not in the file: a generic format is used", !i.template);
    row("context", i.n_ctx + " tokens here · trained for " + i.n_ctx_train);
    const feats = String(i.system || "").split("|").map(x => x.trim()).filter(x => / = 1$/.test(x)).map(x => x.replace(/ = 1$/, "").replace(/^.*: /, ""));
    row("CPU features", feats.length ? feats.join(" ") : (i.system || "?"));
  }

  $("use-model").addEventListener("change", e => { ok(call("setUseModel", e.target.checked)); refreshSummary(); });

  /* Two taps for anything destructive, instead of a dialog. */
  function confirmTwice(btn, label) {
    if (btn.dataset.armed) { delete btn.dataset.armed; btn.textContent = btn.dataset.text; return true; }
    btn.dataset.text = btn.textContent; btn.dataset.armed = "1"; btn.textContent = label;
    setTimeout(() => { if (btn.dataset.armed) { delete btn.dataset.armed; btn.textContent = btn.dataset.text; } }, 3000);
    return false;
  }

  /* ------------------------------------------------------------ ask */
  const history = [];            // [role, content] turns sent back to the model
  let asking = null;             // {id, node, text}

  function emptyThread(m) {
    const t = $("thread");
    t.innerHTML = "";
    const p = el("p", "empty-chat");
    p.textContent = m && m.loaded
      ? "Ask about anything in your memory. The answer cites the settled facts it was given, like [1]. Nothing said here is written to the log."
      : "Load a model to ask questions. Answers come from the model on this phone, with the settled facts from Memory in front of it.";
    t.appendChild(p);
  }

  function citeHtml(node, text) {
    node.textContent = "";
    const parts = text.split(/(\[\d+\])/);
    parts.forEach(p => {
      if (/^\[\d+\]$/.test(p)) node.appendChild(el("span", "cite", p));
      else if (p) node.appendChild(document.createTextNode(p));
    });
  }

  function splitThinking(text) {
    const open = text.indexOf("<think>");
    if (open < 0) return { thinking: "", answer: text, open: false };
    const close = text.indexOf("</think>", open);
    if (close < 0) return { thinking: text.slice(open + 7), answer: text.slice(0, open), open: true };
    return { thinking: text.slice(open + 7, close).trim(), answer: (text.slice(0, open) + text.slice(close + 8)).trim(), open: false };
  }

  function paintAnswer(a, final) {
    const parts = splitThinking(a.text);
    a.body.textContent = "";
    const main = el("div");
    citeHtml(main, parts.answer || (parts.open ? "" : a.text));
    if (!final) main.appendChild(el("span", "caret"));
    a.body.appendChild(main);
    if (parts.thinking) {
      const d = el("details");
      d.appendChild(el("summary", null, parts.open ? "Thinking…" : "Reasoning"));
      d.appendChild(el("div", "thinking", parts.thinking));
      a.body.appendChild(d);
    }
    if (a.facts && a.facts.length) {
      const d = el("details");
      const unconfirmed = a.facts.filter(f => f.status === "held").length;
      d.appendChild(el("summary", null, "Memory given: " + plural(a.facts.length, "fact", "facts") + (unconfirmed ? " · " + unconfirmed + " unconfirmed" : "")));
      const box = el("div", "facts-used");
      a.facts.forEach((f, i) => {
        const held = f.status === "held";
        const how = held ? "unconfirmed, " + plural(f.support, "source", "sources") : f.support >= 2 ? plural(f.support, "source", "sources") : "approved";
        const line = el("div", held ? "held" : null, "[" + (i + 1) + "] " + f.entity + " " + String(f.a).replace(/_/g, " ") + " " + f.v + " · " + how);
        if (f.quote) line.title = "Quote: " + f.quote;
        box.appendChild(line);
      });
      d.appendChild(box);
      a.body.appendChild(d);
    } else if (a.facts && a.memory) {
      a.body.appendChild(el("div", "stats", "No facts in memory matched this question, so the model answered from general knowledge."));
    }
    if (a.stats) {
      const s = a.stats;
      const tps = s.gen_ms > 0 ? (s.gen_tokens / (s.gen_ms / 1000)).toFixed(1) : "–";
      a.body.appendChild(el("div", "stats", s.gen_tokens + " tokens · " + tps + " tokens/s · read " + s.prompt_tokens + " tokens in " + (s.prompt_ms / 1000).toFixed(1) + " s" + (s.stop === "stopped" ? " · stopped" : "")));
    }
    scrollThread();
  }

  function scrollThread() { const t = $("thread"); t.scrollTop = t.scrollHeight; }

  function setSending(on) {
    const b = $("btn-send");
    b.textContent = on ? "Stop" : "Send";
    b.classList.toggle("primary", !on);
  }

  $("composer").addEventListener("submit", e => {
    e.preventDefault();
    if (asking) { call("stop"); return; }
    const q = $("prompt").value.trim();
    if (!q) return;
    const t = $("thread");
    if (t.querySelector(".empty-chat")) t.innerHTML = "";
    t.appendChild(el("div", "msg user", q));
    const node = el("div", "msg bot");
    t.appendChild(node);
    const a = { body: node, text: "", facts: null, memory: $("opt-memory").checked, stats: null };
    paintAnswer(a, false);
    const req = JSON.stringify({ question: q, history: history.slice(-6), memory: a.memory, think: $("opt-think").checked });
    const id = job("ask", {
      progress: ev => { if (ev.facts) { a.facts = ev.facts; paintAnswer(a, false); } },
      token: text => { a.text += text; paintAnswer(a, false); },
      done: r => {
        a.text = r.text; a.facts = r.facts; a.stats = r.stats;
        paintAnswer(a, true);
        history.push(["user", q], ["assistant", splitThinking(r.text).answer]);
        asking = null; setSending(false);
      },
      error: e => {
        node.classList.add("err");
        node.textContent = e || "Something went wrong.";
        asking = null; setSending(false);
      }
    }, req);
    if (id === undefined) { t.removeChild(node); return; }
    asking = { id: id };
    $("prompt").value = "";
    autosize();
    setSending(true);
  });
  function autosize() { const p = $("prompt"); p.style.height = "auto"; p.style.height = Math.min(140, p.scrollHeight) + "px"; }
  $("prompt").addEventListener("input", autosize);
  $("prompt").addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey && window.matchMedia("(pointer:fine)").matches) { e.preventDefault(); $("composer").requestSubmit(); } });

  /* ------------------------------------------------------------ sources */
  const extracting = {};         // source id → true while its job runs
  let activeExtract = null;      // {id, name, passage, passages} for the one-source Extract
  let bulkState = null;          // the bulk run's status, from "bulk" events
  const selected = new Set();
  let sourceRows = [], doneIds = new Set();

  function renderSources() {
    const s = summary || {};
    const parts = [];
    if (s.folder) parts.push("Folder: " + s.folder);
    if (s.samples) parts.push("sample texts added");
    parts.push("Extract uses " + (s.agent && s.agent.indexOf("model:") === 0 ? "the model (" + s.model.name + ")" : "the pattern agent"));
    $("folder-line").textContent = parts.join(" · ");
    $("btn-samples").hidden = !!s.samples;
    $("schema-line").textContent = (s.schema || []).filter(a => a !== "name").join(" · ");
    if (!bulkState) bulkState = ok(call("bulkStatus"), true) || null;
    renderBulk();
    const list = $("source-list");
    list.innerHTML = "";
    sourceRows = ok(call("sources")) || [];
    doneIds = new Set(ok(call("extracted"), true) || []);
    Array.from(selected).forEach(id => { if (!sourceRows.some(x => x.id === id)) selected.delete(id); });
    $("select-bar").hidden = !sourceRows.length;
    if (!sourceRows.length) { empty(list, "Nothing mapped yet. Choose a folder of .txt or .md files, or add the sample texts."); paintSelection(); return; }
    const busyBulk = bulkBusy();
    sourceRows.forEach(x => {
      const row = li();
      const bulkHere = bulkState && bulkState.busy && bulkState.current === x.name;
      if (extracting[x.id] || bulkHere) row.className = "working";
      const pick = el("input", "pick");
      pick.type = "checkbox";
      pick.checked = selected.has(x.id);
      pick.setAttribute("aria-label", "Select " + x.name);
      pick.addEventListener("change", () => { if (pick.checked) selected.add(x.id); else selected.delete(x.id); paintSelection(); });
      const main = el("div", "main");
      main.appendChild(el("div", "t", x.name));
      let meta = "hash " + x.hash;
      if (activeExtract && activeExtract.id === x.id && activeExtract.passages) meta += " · passage " + activeExtract.passage + " of " + activeExtract.passages;
      if (bulkHere && bulkState.passages) meta += " · passage " + bulkState.passage + " of " + bulkState.passages;
      const m = el("div", "m", meta);
      m.dataset.src = x.id;
      main.appendChild(m);
      row.appendChild(pick);
      row.appendChild(main);
      if (doneIds.has(x.id)) row.appendChild(el("span", "badge done", "extracted"));
      const b = el("button", "small", "Extract");
      b.disabled = !!extracting[x.id] || busyBulk;
      b.addEventListener("click", () => startExtract(x, row, b));
      row.appendChild(b);
      list.appendChild(row);
    });
    paintSelection();
  }

  function paintSelection() {
    const n = selected.size;
    $("btn-extract-selected").textContent = n ? "Extract selected (" + n + ")" : "Extract selected";
    $("btn-extract-selected").disabled = !n || bulkBusy();
    $("btn-extract-all").disabled = !sourceRows.length || bulkBusy();
    const done = sourceRows.filter(x => doneIds.has(x.id)).length;
    $("select-line").textContent = plural(sourceRows.length, "source", "sources") + " · " + done + " already extracted" + (n ? " · " + n + " selected" : "");
  }

  function bulkBusy() { return !!(bulkState && (bulkState.state === "running" || bulkState.state === "paused" || bulkState.state === "interrupted" || bulkState.busy)); }

  function startExtract(x, row, b) {
    extracting[x.id] = true;
    activeExtract = { id: x.id, name: x.name, passage: 0, passages: 0 };
    row.className = "working"; b.disabled = true;
    paintActivity();
    const id = job("extract", {
      progress: ev => {
        if (!ev.passage) return;
        activeExtract.passage = ev.passage; activeExtract.passages = ev.passages;
        const m = document.querySelector('.m[data-src="' + CSS.escape(x.id) + '"]');
        if (m) m.textContent = "hash " + x.hash + " · passage " + ev.passage + " of " + ev.passages;
        paintActivity();
      },
      done: r => {
        delete extracting[x.id]; activeExtract = null;
        const refused = (r.rejected || []).length;
        toast(plural(r.accepted, "fact", "facts") + " committed from " + x.name + (refused ? " · " + refused + " refused" : ""));
        refresh();
      },
      error: e => { delete extracting[x.id]; activeExtract = null; if (e) toast(e, true); refresh(); }
    }, x.id);
    if (id === undefined) { delete extracting[x.id]; activeExtract = null; row.className = ""; b.disabled = false; paintActivity(); }
  }

  /* Bulk: one source after another, each with its own directive and result. */
  function startBulk(rows) {
    if (!rows.length) return;
    const req = JSON.stringify({ ids: rows.map(x => x.id), names: rows.map(x => x.name), skip: $("opt-skip").checked });
    const st = ok(call("bulkStart", req));
    if (st === undefined) return;
    bulkState = st;
    toast("Bulk run started: " + plural(rows.length, "source", "sources"), false, false, true);
    renderSources();
  }
  $("btn-extract-all").addEventListener("click", () => startBulk(sourceRows));
  $("btn-extract-selected").addEventListener("click", () => startBulk(sourceRows.filter(x => selected.has(x.id))));
  $("btn-select-all").addEventListener("click", () => { sourceRows.forEach(x => selected.add(x.id)); renderSources(); });
  $("btn-select-none").addEventListener("click", () => { selected.clear(); $("select-filter").value = ""; renderSources(); });
  $("select-filter").addEventListener("input", e => {
    const q = e.target.value.trim().toLowerCase();
    selected.clear();
    if (q) sourceRows.forEach(x => { if (x.name.toLowerCase().indexOf(q) >= 0) selected.add(x.id); });
    renderSources();
  });
  function bulkAction(name, msg) {
    const st = ok(call(name));
    if (st === undefined) return;
    bulkState = st;
    if (msg) toast(msg);
    renderSources();
  }
  $("btn-bulk-pause").addEventListener("click", () => bulkAction("bulkPause", "Pausing after the source being read"));
  $("btn-bulk-resume").addEventListener("click", () => bulkAction("bulkResume", "Bulk run resumed"));
  $("btn-bulk-cancel").addEventListener("click", () => { if (confirmTwice($("btn-bulk-cancel"), "Tap again to cancel")) bulkAction("bulkCancel", "Bulk run cancelled; what was committed stays"); });
  $("btn-bulk-clear").addEventListener("click", () => bulkAction("bulkClear", null));

  const BULK_LABEL = { running: "running", paused: "paused", interrupted: "interrupted", cancelled: "cancelled", done: "done", idle: "" };
  function renderBulk() {
    const b = bulkState;
    const card = $("bulk-card");
    if (!b || b.state === "idle" || !b.total) { card.hidden = true; paintActivity(); return; }
    card.hidden = false;
    const at = Math.min(b.total, b.next + (b.busy ? 1 : 0));
    $("bulk-title").textContent = b.state === "done" ? "Finished " + plural(b.total, "source", "sources")
      : b.busy && b.current ? "Source " + at + " of " + b.total + " · " + b.current
      : "Stopped at source " + (b.next + 1) + " of " + b.total;
    $("bulk-meta").textContent = b.extracted + " extracted · " + b.skipped + " skipped · " + b.failed + " failed · "
      + b.kept + " facts kept · " + b.refused + " refused" + (b.busy && b.passages ? " · passage " + b.passage + " of " + b.passages : "");
    const pill = $("bulk-state");
    pill.textContent = BULK_LABEL[b.state] || b.state;
    pill.className = "pill " + (b.state === "running" ? "running" : b.state === "done" ? "done" : b.state === "cancelled" ? "" : "paused");
    const frac = b.total ? (b.next + (b.busy && b.passages ? b.passage / b.passages : 0)) / b.total : 0;
    $("bulk-progress").firstChild.style.width = Math.min(100, 100 * frac) + "%";
    const errs = [];
    if (b.why) errs.push((b.state === "interrupted" ? "Interrupted: " : "Paused: ") + b.why);
    (b.errors || []).slice(-3).forEach(e => errs.push(e.source + ": " + e.error));
    $("bulk-errors").textContent = errs.join(" · ");
    $("btn-bulk-pause").hidden = b.state !== "running";
    $("btn-bulk-resume").hidden = !(b.state === "paused" || b.state === "interrupted");
    $("btn-bulk-cancel").hidden = !(b.state === "running" || b.state === "paused" || b.state === "interrupted");
    $("btn-bulk-clear").hidden = !(b.state === "done" || b.state === "cancelled");
    paintActivity();
  }
  function onBulk(st) {
    const was = bulkState;
    bulkState = st;
    const moved = !was || was.next !== st.next || was.state !== st.state || was.busy !== st.busy;
    if (current === "sources" && moved) renderSources();
    else if (current === "sources") {
      renderBulk();
      const m = st.busy && st.current ? sourceRows.find(x => x.name === st.current) : null;
      const node = m ? document.querySelector('.m[data-src="' + CSS.escape(m.id) + '"]') : null;
      if (node && st.passages) node.textContent = "hash " + m.hash + " · passage " + st.passage + " of " + st.passages;
    } else paintActivity();
    if (was && was.state === "running" && st.state === "done") { toast("Bulk run finished: " + st.extracted + " extracted, " + st.skipped + " skipped · " + st.kept + " facts kept", false, false, true); refreshSummary(); }
    if (moved && !st.busy) refreshSummary();
  }

  $("btn-folder").addEventListener("click", () => { if (B) B.pickFolder(); else toast("The folder picker needs the app.", true); });
  $("btn-samples").addEventListener("click", () => {
    const r = ok(call("useSamples"));
    if (r !== undefined) { toast("Mapped " + plural(r.total, "source", "sources")); refresh(); }
  });
  $("btn-rescan").addEventListener("click", () => {
    const r = ok(call("rescan"));
    if (r !== undefined) { toast(r.changed ? plural(r.changed, "source changed", "sources changed") + " · map updated" : "No changes since the last scan"); refresh(); }
  });

  /* ------------------------------------------------------------ work */
  function renderWork() {
    const s = summary || {};
    $("arb-line").textContent = s.frozen ? "Frozen: this portal's chain forked"
      : s.paused ? "Paused by the steward"
      : "Running · " + plural(s.open || 0, "open directive", "open directives");
    $("btn-pause").textContent = s.paused ? "Resume" : "Pause";
    $("gate-k").textContent = s.k || 2;

    const held = $("held-list"); held.innerHTML = "";
    const hs = ok(call("held")) || [];
    if (!hs.length) empty(held, "Nothing held.");
    hs.forEach(h => {
      const row = li(), main = el("div", "main");
      const t = el("div", "t");
      t.appendChild(el("b", null, h.entity)); t.appendChild(el("span", "a", h.a)); t.appendChild(document.createTextNode(h.v));
      main.appendChild(t);
      main.appendChild(el("div", "m", plural(h.support, "source", "sources") + (h.approved ? " · approved" : "")));
      if (h.quote) main.appendChild(el("div", "m quote", "“" + h.quote + "”"));
      row.appendChild(main);
      const acts = el("div", "row tight");
      if (!h.approved) {
        const b = el("button", "small", "Approve");
        b.addEventListener("click", () => {
          const r = ok(call("approve", h.key));
          if (r !== undefined) { toast("Approved and signed · " + plural(r, "fact", "facts") + " promoted"); refresh(); }
        });
        acts.appendChild(b);
      }
      factActions(acts, row, h);
      row.appendChild(acts);
      held.appendChild(row);
    });

    const dn = $("denied-list"); dn.innerHTML = "";
    const dns = ok(call("denied")) || [];
    $("denied-head").hidden = !dns.length;
    dns.forEach(d => {
      const row = li(), main = el("div", "main"), t = el("div", "t");
      t.appendChild(el("b", null, d.entity)); t.appendChild(el("span", "a", d.a)); t.appendChild(document.createTextNode(d.v));
      main.appendChild(t);
      if (d.quote) main.appendChild(el("div", "m quote", "“" + d.quote + "”"));
      row.appendChild(main);
      const b = el("button", "small", "Restore");
      b.addEventListener("click", () => {
        const r = ok(call("approve", d.key));
        if (r !== undefined) { toast("Restored as approved · " + plural(r, "fact", "facts") + " promoted"); refresh(); }
      });
      row.appendChild(b);
      dn.appendChild(row);
    });

    const dl = $("dir-list"); dl.innerHTML = "";
    const ds = ok(call("directives")) || [];
    if (!ds.length) empty(dl, "None open.");
    ds.forEach(d => {
      const row = li(), main = el("div", "main");
      main.appendChild(el("div", "t", d.name));
      main.appendChild(el("div", "m", short(d.id) + " · " + (d.stale ? "expired, waiting to be closed" : "expires " + fmtTime(d.expires))));
      row.appendChild(main); dl.appendChild(row);
    });

    const rl = $("result-list"); rl.innerHTML = "";
    const rs = ok(call("results", 20)) || [];
    if (!rs.length) empty(rl, "No results yet.");
    rs.forEach(r => {
      const row = li(), main = el("div", "main");
      main.appendChild(el("div", "t", r.name));
      const by = r.agent && r.agent.indexOf("model:") === 0 ? r.agent.split(":")[1] : "pattern agent";
      main.appendChild(el("div", "m", fmtTime(r.wall) + " · " + by + " · " + plural(r.accepted, "fact", "facts") + " committed" + (r.rejected.length ? " · " + r.rejected.length + " refused" : "")));
      const reasons = {};
      r.rejected.forEach(x => { reasons[x.reason] = (reasons[x.reason] || 0) + 1; });
      Object.keys(reasons).forEach(k => main.appendChild(el("div", "why", (reasons[k] > 1 ? reasons[k] + " × " : "") + k)));
      row.appendChild(main);
      row.addEventListener("click", () => openTx(r.tx));
      rl.appendChild(row);
    });
  }

  /* Edit and Deny, for a held or a settled fact. Both are steward actions, signed and logged. */
  function factActions(acts, row, f) {
    const edit = el("button", "small", "Edit"), deny = el("button", "small", "Deny");
    deny.addEventListener("click", () => {
      const r = ok(call("deny", f.key));
      if (r !== undefined) { toast("Denied and signed · the gate won't promote it"); refresh(); }
    });
    edit.addEventListener("click", () => {
      if (row.querySelector(".edit")) return;
      const form = el("div", "edit"), e = el("input"), a = el("select"), v = el("input");
      e.value = f.entity; v.value = f.v;
      ((summary && summary.schema) || []).filter(x => x !== "name").forEach(x => {
        const o = el("option", null, x); o.value = x; if (x === f.a) o.selected = true; a.appendChild(o);
      });
      const save = el("button", "small primary", "Save"), cancel = el("button", "small", "Cancel");
      cancel.addEventListener("click", () => form.remove());
      save.addEventListener("click", () => {
        const r = ok(call("correct", JSON.stringify({ key: f.key, entity: e.value, a: a.value, v: v.value })));
        if (r !== undefined) { toast("Edited and signed · the original is denied, your version is settled"); refresh(); }
      });
      const btns = el("div", "row tight"); btns.appendChild(save); btns.appendChild(cancel);
      [e, a, v, btns].forEach(x => form.appendChild(x));
      row.appendChild(form);
      e.focus();
    });
    acts.appendChild(edit); acts.appendChild(deny);
    row.classList.add("fact");
  }

  $("btn-pause").addEventListener("click", () => {
    const paused = summary && summary.paused;
    if (ok(call(paused ? "resume" : "pause")) !== undefined) { toast(paused ? "Resumed · signed by the steward" : "Paused · signed by the steward"); refresh(); }
  });
  $("btn-expire").addEventListener("click", () => {
    const n = ok(call("reconcile"));
    if (n !== undefined) { toast(n ? plural(n, "directive", "directives") + " expired" : "Nothing stale"); refresh(); }
  });
  $("btn-gate").addEventListener("click", () => {
    const n = ok(call("gate"));
    if (n !== undefined) { toast(n ? plural(n, "fact", "facts") + " promoted to culture" : "Nothing new has enough support"); refresh(); }
  });

  /* ------------------------------------------------------------ memory */
  let qTimer = 0;
  $("q").addEventListener("input", () => { clearTimeout(qTimer); qTimer = setTimeout(renderMemory, 160); });
  function renderMemory() {
    const list = $("mem-list"); list.innerHTML = "";
    const rows = ok(call("memory", $("q").value)) || [];
    $("mem-count").textContent = plural(rows.length, "settled fact", "settled facts");
    if (!rows.length) {
      empty(list, $("q").value ? "No match." : "Nothing settled yet. Extract from two sources that agree, then run the gate on the Work tab.");
      return;
    }
    const k = (summary && summary.k) || 2;
    rows.forEach(r => {
      const row = li(), main = el("div", "main"), t = el("div", "t");
      t.appendChild(el("b", null, r.entity)); t.appendChild(el("span", "a", r.a)); t.appendChild(document.createTextNode(r.v));
      main.appendChild(t);
      row.appendChild(main);
      row.appendChild(el("span", "badge" + (r.support < k ? " appr" : ""), r.edited ? "edited" : r.support < k ? "approved" : plural(r.support, "source", "sources")));
      const acts = el("div", "row tight");
      factActions(acts, row, r);
      row.appendChild(acts);
      list.appendChild(row);
    });
  }

  /* ------------------------------------------------------------ log */
  const STEWARD_KINDS = ["genesis", "admit", "schema", "approve", "deny", "correct", "pause", "resume", "skill"];
  let logOffset = 0;
  function renderLog(reset) {
    const list = $("log-list");
    if (reset) { list.innerHTML = ""; logOffset = 0; }
    const rows = ok(call("log", logOffset, 60)) || [];
    rows.forEach(r => {
      const row = li();
      const k = el("span", "kind " + (STEWARD_KINDS.indexOf(r.kind) >= 0 ? "steward" : r.kind), r.kind);
      const main = el("div", "main");
      main.appendChild(el("div", "t", "#" + r.n + " · " + (r.portal === "steward" ? "steward" : r.portal)));
      main.appendChild(el("div", "m", fmtTime(r.wall) + " · " + short(r.id) + (r.directive ? " · " + short(r.directive) : "")));
      row.appendChild(k); row.appendChild(main);
      row.addEventListener("click", () => openTx(r.id));
      list.appendChild(row);
    });
    logOffset += rows.length;
    $("btn-more").hidden = rows.length < 60;
  }
  $("btn-more").addEventListener("click", () => renderLog(false));
  $("btn-verify").addEventListener("click", () => {
    const v = ok(call("verify"));
    if (v === undefined) return;
    const line = $("verify-line");
    if (v.ok) line.textContent = "Re-read " + plural(v.count, "transaction", "transactions") + " from disk: every hash, signature and chain link checks out. State " + v.state + (v.matches ? ", same as the running state." : ", which differs from the running state.") + (v.forks ? " " + plural(v.forks, "fork proof", "fork proofs") + " held." : "");
    else line.textContent = "Verification failed: " + v.error;
  });

  /* ------------------------------------------------------------ transaction sheet */
  function openTx(id) {
    const t = ok(call("tx", id));
    if (!t) return;
    $("sheet-kicker").textContent = t.header.kind + " · " + (t.header.portal === "steward" ? "signed by the steward" : "signed by " + t.header.portal);
    $("sheet-title").textContent = short(t.id);
    $("sheet-body").textContent = JSON.stringify(t, null, 2);
    $("sheet").hidden = false;
  }
  function closeSheet() { $("sheet").hidden = true; }
  $("sheet-close").addEventListener("click", closeSheet);
  $("sheet").addEventListener("click", e => { if (e.target === $("sheet")) closeSheet(); });

  /* ------------------------------------------------------------ native hooks */
  window.dilmunBack = function () {
    if (!$("sheet").hidden) { closeSheet(); return true; }
    if (current !== "map") { show("map"); return true; }
    return false;
  };

  window.dilmunEvent = function (name, ev) {
    if (name === "trace") { drainTrace(); return; }
    if (name === "bulk") { onBulk(ev); return; }
    if (name === "token") {
      const h = jobs[ev.id];
      if (h && h.token) h.token(ev.text);
      return;
    }
    if (name !== "job") return;
    const h = jobs[ev.id];
    if (ev.state === "progress") {
      if (h && h.progress) h.progress(ev);
      else if (ev.kind === "import") {
        modelBusy = "import";
        const bar = $("model-progress");
        bar.hidden = false;
        bar.firstChild.style.width = ev.total > 0 ? Math.min(100, 100 * ev.bytes / ev.total) + "%" : "30%";
        $("model-state").textContent = "importing"; $("model-state").className = "pill busy";
        $("model-meta").textContent = "Copying and checking · " + fmtBytes(ev.bytes) + (ev.total > 0 ? " of " + fmtBytes(ev.total) : "");
      }
      return;
    }
    delete jobs[ev.id];
    if (ev.state === "done") {
      if (h && h.done) h.done(ev.result);
      else if (ev.kind === "import") { modelBusy = null; $("model-progress").hidden = true; modelCompact = true; toast("Model imported and loaded"); renderModel(); refreshSummary(); }
      else if (ev.kind === "scan") { toast("Folder mapped · " + plural(ev.result.total, "source", "sources") + (ev.result.changed ? " · " + ev.result.changed + " new or changed" : "")); refresh(); }
    } else {
      if (h && h.error) h.error(ev.error);
      else {
        if (ev.kind === "import") { modelBusy = null; $("model-progress").hidden = true; renderModel(); }
        toast(ev.error, true);
      }
    }
  };

  /* ------------------------------------------------------------ start */
  // Startup reconciliation: close directives that expired while the app was closed.
  call("reconcile");
  refreshSummary();
  $("map").addEventListener("load", () => { if (summary) postToMap({ type: "stats", stats: summary }); postToMap({ type: "visible", visible: current === "map" }); });
  // The map may have loaded before this script ran; ask it to say ready again.
  postToMap({ type: "hello" });

  /* ------------------------------------------------------------ the map asks */
  let nextSource = 0;
  window.addEventListener("message", ev => {
    if (ev.source !== ($("map") && $("map").contentWindow)) return;
    const d = ev.data || {};
    if (d.type === "ready") {
      if (mapReady) return;
      mapReady = true;
      if (summary) postToMap({ type: "stats", stats: summary });
      postToMap({ type: "visible", visible: current === "map" });
      postToMap({ type: "trace", events: traceBacklog.slice(), backlog: true });
      return;
    }
    if (d.type !== "act") return;
    if (d.name === "samples") {
      const r = ok(call("useSamples"));
      if (r !== undefined) toast("Mapped " + plural(r.total, "source", "sources"));
    } else if (d.name === "extract") {
      // the next source not yet extracted, else take turns; it runs as a job and the map shows each step
      const src = ok(call("sources")) || [];
      if (!src.length) { toast("Nothing mapped yet. Add the sample texts or choose a folder on the Sources tab.", true); return; }
      const done = new Set(ok(call("extracted"), true) || []);
      let pick = src.find(x => !done.has(x.id));
      if (!pick) pick = src[nextSource++ % src.length];
      activeExtract = { id: pick.id, name: pick.name, passage: 0, passages: 0 };
      extracting[pick.id] = true;
      job("extract", {
        progress: ev => { if (ev.passage) { activeExtract.passage = ev.passage; activeExtract.passages = ev.passages; paintActivity(); } },
        done: r => { delete extracting[pick.id]; activeExtract = null; toast(plural(r.accepted, "fact", "facts") + " committed from " + pick.name + ((r.rejected || []).length ? " · " + r.rejected.length + " refused" : "")); refresh(); },
        error: e => { delete extracting[pick.id]; activeExtract = null; if (e) toast(e, true); refresh(); }
      }, pick.id);
    } else if (d.name === "gate") {
      const n = ok(call("gate"));
      if (n !== undefined) toast(n ? plural(n, "fact", "facts") + " promoted to culture" : "Nothing new has enough support");
    } else if (d.name === "verify") {
      const v = ok(call("verify"));
      if (v !== undefined) toast(v.ok ? "Log verified · " + plural(v.count, "transaction", "transactions") : "Verification failed: " + v.error, !v.ok);
    }
    refresh();
  });
})();
