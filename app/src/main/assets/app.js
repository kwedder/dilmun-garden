(function () {
  "use strict";

  /* ------------------------------------------------------------ bridge */
  const B = window.Dilmun;

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
  }
  // Background work (a folder scan) nudges us through dilmunEvent; this catches anything else.
  setInterval(drainTrace, 1500);
  function ok(r, quiet) {
    if (r && r.error !== undefined) { if (!quiet) toast(r.error, true); return undefined; }
    return r ? r.ok : undefined;
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
  function empty(list, text) { const n = el("li", "empty", text); list.appendChild(n); }
  function plural(n, one, many) { return n + " " + (n === 1 ? one : many); }
  function fmtTime(ms) {
    const d = new Date(ms), now = new Date();
    const hm = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return d.toDateString() === now.toDateString() ? hm : d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + hm;
  }
  function short(id) { return id ? String(id).replace(/^(dir:|src:|sample:|e:|d:|k:)/, "").slice(0, 10) : ""; }

  let toastTimer = 0;
  function toast(text, bad) {
    const t = $("toast");
    t.textContent = text;
    t.className = bad ? "bad" : "";
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 5200 : 2600);
  }

  /* ------------------------------------------------------------ tabs */
  let current = "map", summary = null;
  const tabs = document.querySelectorAll(".tabs button");
  tabs.forEach(b => b.addEventListener("click", () => show(b.dataset.tab)));

  function show(name) {
    current = name;
    tabs.forEach(b => b.setAttribute("aria-current", b.dataset.tab === name ? "page" : "false"));
    ["map", "sources", "work", "memory", "log"].forEach(t => { $("tab-" + t).hidden = t !== name; });
    postToMap({ type: "visible", visible: name === "map" });
    render(name);
  }

  function postToMap(msg) {
    const f = $("map");
    if (f && f.contentWindow) f.contentWindow.postMessage(msg, "*");
  }

  /* ------------------------------------------------------------ top bar */
  function refreshSummary() {
    const s = ok(call("summary"), true);
    if (!s) {
      $("status").textContent = "error";
      $("status").className = "pill frozen";
      const r = call("summary");
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
    if (name === "sources") renderSources();
    else if (name === "work") renderWork();
    else if (name === "memory") renderMemory();
    else if (name === "log") renderLog(true);
  }

  function refresh() { refreshSummary(); render(current); }

  /* ------------------------------------------------------------ sources */
  function renderSources() {
    const s = summary || {};
    const parts = [];
    if (s.folder) parts.push("Folder: " + s.folder);
    if (s.samples) parts.push("sample texts added");
    $("folder-line").textContent = parts.length ? parts.join(" · ") : "No folder chosen yet.";
    $("btn-samples").hidden = !!s.samples;
    $("schema-line").textContent = (s.schema || []).filter(a => a !== "name").join(" · ");
    const list = $("source-list");
    list.innerHTML = "";
    const src = ok(call("sources")) || [];
    if (!src.length) { empty(list, "Nothing mapped yet. Choose a folder of .txt or .md files, or add the sample texts."); return; }
    src.forEach(x => {
      const row = li();
      const main = el("div", "main");
      main.appendChild(el("div", "t", x.name));
      main.appendChild(el("div", "m", "hash " + x.hash));
      const b = el("button", "small", "Extract");
      b.addEventListener("click", () => {
        b.disabled = true;
        const r = ok(call("extract", x.id));
        b.disabled = false;
        if (r !== undefined) {
          const refused = (r.rejected || []).length;
          toast(plural(r.accepted, "fact", "facts") + " committed from " + x.name + (refused ? " · " + refused + " refused" : ""));
          refresh();
        }
      });
      row.appendChild(main);
      row.appendChild(b);
      list.appendChild(row);
    });
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
      main.appendChild(el("div", "m", fmtTime(r.wall) + " · " + plural(r.accepted, "fact", "facts") + " committed" + (r.rejected.length ? " · " + r.rejected.length + " refused" : "")));
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
    rows.forEach(r => {
      const row = li(), main = el("div", "main"), t = el("div", "t");
      t.appendChild(el("b", null, r.entity)); t.appendChild(el("span", "a", r.a)); t.appendChild(document.createTextNode(r.v));
      main.appendChild(t);
      row.appendChild(main);
      row.appendChild(el("span", "badge" + (r.support < (summary && summary.k || 2) ? " appr" : ""),
        r.support < (summary && summary.k || 2) ? "approved" : plural(r.support, "source", "sources")));
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
  window.dilmunEvent = function (name, r) {
    if (name === "trace") { drainTrace(); return; }
    if (name === "scanned") {
      if (r && r.error !== undefined) toast(r.error, true);
      else if (r) toast("Folder mapped · " + plural(r.ok.total, "source", "sources") + (r.ok.changed ? " · " + r.ok.changed + " new or changed" : ""));
      refresh();
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
      // the next source that has no result yet, else take turns
      const src = ok(call("sources")) || [];
      if (!src.length) { toast("Nothing mapped yet. Add the sample texts or choose a folder on the Sources tab.", true); return; }
      const done = {};
      (ok(call("results", 200), true) || []).forEach(x => { done[x.source] = true; });
      let pick = src.find(x => !done[x.id]);
      if (!pick) pick = src[nextSource++ % src.length];
      const r = ok(call("extract", pick.id));
      if (r !== undefined) toast(plural(r.accepted, "fact", "facts") + " committed from " + pick.name + ((r.rejected || []).length ? " · " + r.rejected.length + " refused" : ""));
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
