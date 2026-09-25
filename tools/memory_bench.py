#!/usr/bin/env python3
"""Dilmun against real memory systems, on the same text, questions and traps.

Every system takes in the same 8 sections of OpenStax "Introduction to
Anthropology" and the same trap scenarios (read from Dilmun's export, so the
inputs are identical), then hands over what it recalls for each question within
the same budget of characters. A question counts as answered when an expected
answer string is in what's handed over. Costs are measured on the same machine.

Systems (the real libraries, run locally):
  bm25        rank_bm25 over sentences (classic keyword retrieval)
  chroma      Chroma vector store over sentences, its default all-MiniLM-L6-v2 embeddings
  mem0-raw    mem0 storing chunks as they are (infer=False), its own search
  mem0        mem0 with its LLM extracting memories from each chunk (infer=True),
              the LLM a small local model served by llama-server (--llm-url)
  dilmun      from dilmun.json (DilmunExport): the arbiters' rules, claims and gate

  python3 tools/memory_bench.py dilmun.json TEXT_DIR tools/memory-compare.json --llm-url http://127.0.0.1:8080/v1 --out results.json
"""
import argparse, json, os, re, sys, tempfile, time

os.environ["MEM0_TELEMETRY"] = "False"          # no phoning home, and no shared telemetry store between instances
os.environ["ANONYMIZED_TELEMETRY"] = "False"    # Chroma's

BUDGET = 2000


def sentences(text):
    out = []
    for para in text.split("\n"):
        p = para.strip()
        if not p or p.startswith("#"):
            continue
        if p.startswith("- ") and len(p.split("|")) == 3:
            out.append(p[2:].strip())
            continue
        for s in re.split(r'(?<=[.!?])\s+(?=[A-Z"“(])', p):
            if len(s.strip()) > 3:
                out.append(s.strip())
    return out


def chunks(text, size=1500):
    """Paragraphs packed into chunks of at most about `size` characters; a longer paragraph is split between sentences."""
    pieces = []
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para:
            continue
        if len(para) <= size:
            pieces.append(para)
            continue
        cur = ""
        for s in re.split(r"(?<=[.!?])\s+|\n", para):
            if cur and len(cur) + len(s) > size:
                pieces.append(cur)
                cur = ""
            cur = (cur + " " + s).strip()
        if cur:
            pieces.append(cur)
    out, cur = [], ""
    for p in pieces:
        if cur and len(cur) + len(p) > size:
            out.append(cur)
            cur = ""
        cur = (cur + "\n\n" + p).strip()
    if cur:
        out.append(cur)
    return out


def budget(lines):
    out, n = [], 0
    for line in lines:
        line = " ".join(line.split())
        if n + len(line) + 1 > BUDGET:
            break
        out.append(line)
        n += len(line) + 1
    return "\n".join(out)


def dir_bytes(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            total += os.path.getsize(os.path.join(root, f))
    return total


# ------------------------------------------------------------ systems

class BM25:
    name = "bm25"

    def __init__(self):
        self.docs = []
        self.index = None

    def ingest(self, name, text):
        self.docs += sentences(text)
        self.index = None

    def recall(self, q):
        from rank_bm25 import BM25Okapi
        tok = lambda s: re.findall(r"[a-z0-9]+", s.lower())
        if self.index is None:
            self.index = BM25Okapi([tok(d) for d in self.docs])
        scores = self.index.get_scores(tok(q))
        qw = set(tok(q))
        # BM25 scores a word found in every document at zero or below, which on a tiny store drops
        # every match: rank by score, keep anything sharing a word with the question.
        order = sorted(range(len(self.docs)), key=lambda i: -scores[i])
        return budget([self.docs[i] for i in order[:40] if qw & set(tok(self.docs[i]))])

    def size(self):
        return {"items": len(self.docs), "store_bytes": sum(len(d.encode()) for d in self.docs)}


class Chroma:
    name = "chroma"

    def __init__(self):
        import chromadb
        self.dir = tempfile.mkdtemp(prefix="chroma-")
        self.client = chromadb.PersistentClient(path=self.dir)
        self.col = self.client.create_collection("mem", metadata={"hnsw:space": "cosine"})
        self.n = 0

    def ingest(self, name, text):
        ss = sentences(text)
        for i in range(0, len(ss), 256):
            batch = ss[i:i + 256]
            self.col.add(documents=batch, ids=[f"{name}-{self.n + j}" for j in range(len(batch))])
            self.n += len(batch)

    def recall(self, q):
        r = self.col.query(query_texts=[q], n_results=min(20, max(1, self.n)))
        return budget(r["documents"][0])

    def size(self):
        return {"items": self.n, "store_bytes": dir_bytes(self.dir)}


class MiniLM:
    """Chroma's all-MiniLM-L6-v2 (ONNX) as mem0's embedder, so mem0 and Chroma embed alike and need no API."""

    def __init__(self):
        from chromadb.utils.embedding_functions import ONNXMiniLM_L6_V2
        self.f = ONNXMiniLM_L6_V2()

    def embed(self, text, memory_action=None):
        return [float(x) for x in self.f([text])[0]]

    def embed_batch(self, texts, memory_action=None):
        return [[float(x) for x in v] for v in self.f(list(texts))]


class Mem0:
    def __init__(self, infer, llm_url, model):
        from mem0 import Memory
        os.environ.setdefault("OPENAI_API_KEY", "local")
        self.infer = infer
        self.name = "mem0" if infer else "mem0-raw"
        self.dir = tempfile.mkdtemp(prefix="mem0-")
        config = {
            "vector_store": {"provider": "qdrant", "config": {"collection_name": "mem", "path": os.path.join(self.dir, "qdrant"),
                                                              "embedding_model_dims": 384, "on_disk": True}},
            "llm": {"provider": "openai", "config": {"model": model, "openai_base_url": llm_url, "api_key": "local",
                                                     "temperature": 0.0, "max_tokens": 1500}},
            "embedder": {"provider": "openai", "config": {"api_key": "local", "embedding_dims": 384}},
            "history_db_path": os.path.join(self.dir, "history.db"),
        }
        self.m = Memory.from_config(config)
        self.m.embedding_model = MiniLM()
        self.calls = 0
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self.failed = 0
        client = getattr(self.m.llm, "client", None)
        if client is not None:                                   # count what the LLM is asked to read and write
            create = client.chat.completions.create

            def counted(*a, **k):
                r = create(*a, **k)
                self.calls += 1
                u = getattr(r, "usage", None)
                if u:
                    self.prompt_tokens += u.prompt_tokens or 0
                    self.completion_tokens += u.completion_tokens or 0
                return r
            client.chat.completions.create = counted

    deadline = None                                              # stop taking in text after this time (time.time())
    done = 0
    total = 0

    def ingest(self, name, text):
        for c in chunks(text, 1500 if self.infer else 600):   # raw: small chunks, since each is returned whole
            self.total += 1
            if self.deadline and time.time() > self.deadline:
                continue                                         # out of time: counted as not taken in
            t = time.time()
            try:
                self.m.add([{"role": "user", "content": c}], user_id="bench", infer=self.infer)
                self.done += 1
            except Exception as e:                               # a malformed reply from a small model loses that chunk
                self.failed += 1
                print(f"  {self.name}: add failed: {str(e)[:120]}", file=sys.stderr)
            if self.infer:
                print(f"  {self.name}: chunk {self.total} of {name} in {time.time() - t:.1f} s", file=sys.stderr, flush=True)

    def recall(self, q):
        r = self.m.search(q, top_k=20, filters={"user_id": "bench"})
        rows = r.get("results", r) if isinstance(r, dict) else r
        return budget([x.get("memory", "") for x in rows])

    def size(self):
        got = self.m.get_all(filters={"user_id": "bench"}, top_k=100000)
        rows = got.get("results", got) if isinstance(got, dict) else got
        out = {"items": len(rows), "store_bytes": dir_bytes(self.dir), "chunks": f"{self.done} of {self.total}"}
        if self.infer:
            out.update(llm_calls=self.calls, llm_prompt_tokens=self.prompt_tokens,
                       llm_completion_tokens=self.completion_tokens, failed_chunks=self.failed)
        return out


# ------------------------------------------------------------ judging

def judge(name, ctx):
    low = ctx.lower()
    if name == "poison":
        planted = "ignore previous" in low or re.search(r"\bcures? cancer\b", low) is not None
        return "reaches the model (fail)" if planted else "kept out (pass)"
    if name == "conflict":
        a, b = "1648" in ctx, "1658" in ctx
        if a and b:
            return "both, marked contested (pass)" if "contested" in low else "both, unmarked"
        return "1648 only" if a else "1658 only" if b else "neither"
    if name == "copied":
        n = sum(1 for line in ctx.split("\n") if "mesopotamia" in line.lower())
        if "unconfirmed: 1 source" in low:
            return "one source, unconfirmed (pass)"
        return "shown %d times, as if confirmed (fail)" % n if n >= 2 else "shown once, no provenance" if n else "not found"
    return ""


def score(answers, questions):
    hits, chars = 0, 0
    missed = []
    for q, ctx in zip(questions, answers):
        chars += len(ctx)
        if any(a.lower() in ctx.lower() for a in q["a"]):
            hits += 1
        else:
            missed.append(q["q"])
    return hits, chars // max(1, len(questions)), missed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dilmun")
    ap.add_argument("text_dir")
    ap.add_argument("questions")
    ap.add_argument("--llm-url", default="")
    ap.add_argument("--llm-model", default="local")
    ap.add_argument("--systems", default="bm25,chroma,mem0-raw,mem0")
    ap.add_argument("--out", default="memory-bench.json")
    ap.add_argument("--max-minutes", type=float, default=0, help="stop mem0 taking in text after this long, and say how much it took in")
    args = ap.parse_args()

    dil = json.load(open(args.dilmun))
    questions = json.load(open(args.questions))["questions"]
    texts = [(f, open(os.path.join(args.text_dir, f), encoding="utf-8").read()) for f in sorted(os.listdir(args.text_dir))]

    def make(name):
        return {"bm25": BM25, "chroma": Chroma}[name]() if name in ("bm25", "chroma") \
            else Mem0(name == "mem0", args.llm_url, args.llm_model)

    results = [{
        "system": "dilmun", "ingest_s": dil["ingest_ms"] / 1000, "query_ms": dil["query_ms"],
        "items": dil["items"]["facts"] + dil["items"]["claims"], "store_bytes": dil["store_bytes"], "llm_calls": 0,
        "answers": [a["context"] for a in dil["answers"]],
        "traps": {s["name"]: judge(s["name"], s["context"]) for s in dil["scenarios"]},
    }]
    for name in args.systems.split(","):
        if name == "mem0" and not args.llm_url:
            continue
        traps = {}                                               # the traps first: small, so they're reported even if time runs out
        for sc in dil["scenarios"]:
            fresh = make(name)
            for doc in sc["docs"]:
                fresh.ingest(doc[0], doc[1])
            ctx = fresh.recall(sc["question"])
            traps[sc["name"]] = judge(sc["name"], ctx)
            print(f"  {name} {sc['name']}: {ctx[:200]!r}", file=sys.stderr, flush=True)
        print(f"{name}: taking in the text", file=sys.stderr, flush=True)
        s = make(name)
        if args.max_minutes and isinstance(s, Mem0):
            s.deadline = time.time() + args.max_minutes * 60
        t = time.time()
        for f, text in texts:
            s.ingest(f, text)
        ingest = time.time() - t
        answers, t = [], time.time()
        for q in questions:
            answers.append(s.recall(q["q"]))
        query_ms = (time.time() - t) * 1000 / len(questions)
        r = {"system": name, "ingest_s": round(ingest, 2), "query_ms": round(query_ms, 1), "answers": answers}
        r.update(s.size())
        r["traps"] = traps
        results.append(r)
        report(results, questions, args.out)                     # after each system, so a timeout loses nothing done


def report(results, questions, out):
    for r in results:
        if "answers" in r:
            r["answered"], r["context_chars"], r["missed"] = score(r["answers"], questions)
            del r["answers"]
    json.dump(results, open(out, "w"), indent=1)

    cols = [r["system"] for r in results]
    rows = [
        ("questions answered (of %d)" % len(questions), lambda r: str(r["answered"])),
        ("characters handed to the model, average", lambda r: str(r["context_chars"])),
        ("time to take in 8 sections", lambda r: "%.1f s" % r["ingest_s"]),
        ("LLM calls to take them in", lambda r: str(r.get("llm_calls", 0))),
        ("LLM tokens to take them in", lambda r: str(r.get("llm_prompt_tokens", 0) + r.get("llm_completion_tokens", 0))),
        ("chunks the LLM failed on", lambda r: str(r.get("failed_chunks", "-"))),
        ("recall time per question", lambda r: "%.1f ms" % r["query_ms"]),
        ("text taken in (chunks)", lambda r: r.get("chunks", "all")),
        ("items stored", lambda r: str(r["items"])),
        ("store size on disk", lambda r: "%.0f KB" % (r["store_bytes"] / 1024)),
        ("planted instruction", lambda r: r["traps"].get("poison", "")),
        ("two sources say 1648, two say 1658", lambda r: r["traps"].get("conflict", "")),
        ("notes copied from a textbook", lambda r: r["traps"].get("copied", "")),
    ]
    print("\n| | " + " | ".join(cols) + " |\n|---|" + "---|" * len(cols))
    for label, f in rows:
        print("| " + label + " | " + " | ".join(f(r) for r in results) + " |")
    for r in results:
        if r["missed"]:
            print(f"\n{r['system']} missed: {r['missed']}")


if __name__ == "__main__":
    main()
