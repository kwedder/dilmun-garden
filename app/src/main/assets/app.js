(function () {
  "use strict";

  /* ------------------------------------------------------------ bridge */
  const B = window.Dilmun;
  const MODEL_PAGE = "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF";

  function call(name) {
    const args = Array.prototype.slice.call(arguments, 1);
    if (!B || typeof B[name] !== "function") return { error: "The app's engine isn't connected to this page." };
    try { return JSON.parse(B[name].apply(B, args)); }
    catch (e) { return { error: String(e && e.message || e) }; }
  }
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

  let toastTimer = 0;
  function toast(text, bad, sticky) {
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
      d.appendChild(el("summary", null, "Memory given: " + plural(a.facts.length, "fact", "facts")));
      const box = el("div", "facts-used");
      a.facts.forEach((f, i) => box.appendChild(el("div", null, "[" + (i + 1) + "] " + f.entity + " " + String(f.a).replace(/_/g, " ") + " " + f.v)));
      d.appendChild(box);
      a.body.appendChild(d);
    } else if (a.facts && a.memory) {
      a.body.appendChild(el("div", "stats", "No settled facts matched this question."));
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

  function renderSources() {
    const s = summary || {};
    const parts = [];
    if (s.folder) parts.push("Folder: " + s.folder);
    if (s.samples) parts.push("sample texts added");
    parts.push("Extract uses " + (s.agent && s.agent.indexOf("model:") === 0 ? "the model (" + s.model.name + ")" : "the pattern agent"));
    $("folder-line").textContent = parts.join(" · ");
    $("btn-samples").hidden = !!s.samples;
    $("schema-line").textContent = (s.schema || []).filter(a => a !== "name").join(" · ");
    const list = $("source-list");
    list.innerHTML = "";
    const src = ok(call("sources")) || [];
    if (!src.length) { empty(list, "Nothing mapped yet. Choose a folder of .txt or .md files, or add the sample texts."); return; }
    src.forEach(x => {
      const row = li();
      if (extracting[x.id]) row.className = "working";
      const main = el("div", "main");
      main.appendChild(el("div", "t", x.name));
      main.appendChild(el("div", "m", "hash " + x.hash));
      const b = el("button", "small", "Extract");
      b.disabled = !!extracting[x.id];
      b.addEventListener("click", () => startExtract(x, row, b));
      row.appendChild(main);
      row.appendChild(b);
      list.appendChild(row);
    });
  }

  function startExtract(x, row, b) {
    extracting[x.id] = true;
    row.className = "working"; b.disabled = true;
    const id = job("extract", {
      progress: ev => { if (ev.passage) toast("Reading " + x.name + ": passage " + ev.passage + " of " + ev.passages, false, true); },
      done: r => {
        delete extracting[x.id];
        const refused = (r.rejected || []).length;
        toast(plural(r.accepted, "fact", "facts") + " committed from " + x.name + (refused ? " · " + refused + " refused" : ""));
        refresh();
      },
      error: e => { delete extracting[x.id]; if (e) toast(e, true); refresh(); }
    }, x.id);
    if (id === undefined) { delete extracting[x.id]; row.className = ""; b.disabled = false; }
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
      row.appendChild(main);
      if (!h.approved) {
        const b = el("button", "small", "Approve");
        b.addEventListener("click", () => {
          const r = ok(call("approve", h.key));
          if (r !== undefined) { toast("Approved and signed · " + plural(r, "fact", "facts") + " promoted"); refresh(); }
        });
        row.appendChild(b);
      }
      held.appendChild(row);
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
      row.appendChild(el("span", "badge" + (r.support < k ? " appr" : ""), r.support < k ? "approved" : plural(r.support, "source", "sources")));
      list.appendChild(row);
    });
  }

  /* ------------------------------------------------------------ log */
  const STEWARD_KINDS = ["genesis", "admit", "schema", "approve", "pause", "resume", "skill"];
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
})();
