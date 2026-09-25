# Dilmun portal (Android)

The Dilmun portal as an Android app. It runs the real core:

- a signed, append-only log in SQLite
- arbiters whose state is rebuilt from that log at every startup
- the skill registry and the workspace map, steering every run
- directives with budgets and expiry
- the promotion gate, steward approval, and pause
- fork detection

It also runs a small language model on the phone's own processor, through llama.cpp. You can ask it questions with your memory in front of it, and the arbiters can deploy it to extract facts from your files. It proposes; the arbiters check every quote against the file and commit only what holds up.

The app declares no internet permission, so nothing it holds can leave the device. The model comes in as a file you download yourself.

## Get the APK (GitHub Actions)

1. Create a new repository on GitHub, for example `dilmun-portal`. It can be private.
2. Upload this folder's contents to it: drag the files into the GitHub web page, or run `git init`, `git add .`, `git commit`, `git push`. Make sure the hidden `.github` folder goes too, since it holds the build workflow. Finder hides it; press Cmd+Shift+. to show it.
3. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push; you can also start it with **Run workflow**.
4. When the run finishes (the first build compiles llama.cpp and takes around 20 to 30 minutes; later ones are similar), open it and download **dilmun-portal-apk** from the Artifacts section. It's a zip containing `app-debug.apk`.
5. Copy the APK to your phone and open it. Android asks you to allow installs from that app (Files, Chrome, and so on) the first time.

The workflow has two jobs. **test** runs the core tests, then builds the native model layer for Linux and tests it with a tiny random-weight model. **apk** builds the Android app. The APK is for 64-bit ARM phones, which is nearly every phone from the last several years.

### Keep one signing key (recommended)

Android only installs an update over an app signed with the same key. Without a key of your own, every build gets a new throwaway key. Then each update means uninstalling first, and uninstalling deletes the log and the portal's keys.

To sign every build with one key, make it once on a computer with Java (Android Studio includes it):

```
keytool -genkeypair -keystore dilmun.jks -alias dilmun -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Dilmun" -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Then add two repository secrets under **Settings → Secrets and variables → Actions**:

- `DILMUN_KEYSTORE_B64`: the file as base64.
  - macOS: `base64 -i dilmun.jks | pbcopy`
  - Linux: `base64 -w0 dilmun.jks`
  - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("dilmun.jks")) | Set-Clipboard`
- `DILMUN_KEYSTORE_PASSWORD`: the password.

Keep `dilmun.jks` somewhere safe and out of the repository (`.gitignore` already excludes it). The build reads the key from the environment only.

## Add the model

The app recommends **LFM2-1.2B** by Liquid AI, in the GGUF format that llama.cpp reads. On the verifier bench it made the fewest false agreements of the small models tried (below). Any GGUF model works: MiniCPM5-1B, the earlier default, still does.

1. On your phone, open the **Ask** tab and tap **Open the LFM2-1.2B download page**. It opens in your browser; the app itself never goes online.
2. Download the plain **Q4_K_M** file (`LFM2-1.2B-Q4_K_M.gguf`, about 0.7 GB), a good balance of size and quality. Larger files (Q8_0) are a little better and slower.
3. Back on **Ask**, tap **Import model file** and pick the file from your downloads. The app:
   - checks that it's a GGUF file
   - copies it into its private storage and computes its SHA-256 fingerprint
   - loads it
4. Ask a question. With **Use memory** on, the facts that match your question are put in front of the model, numbered, and the answer cites them like [1]:
   - **settled** facts first: the ones that passed the gate (2 sources, or your approval)
   - then facts still **held** at the gate, marked *unconfirmed* so the model (and you) can tell them apart. With a single textbook almost everything is held, so this is what makes memory useful before a second source arrives.

   Each fact goes in with its quote from the source. Tap **Memory given** under an answer to see exactly which facts it had, and which were unconfirmed. **Let it think first** lets a reasoning model think before it answers; it's slower. With memory on, the app writes the start of that thinking for the model: the question, then every fact it was given, then "going through them one at a time". So the model reasons from your memory fact by fact, instead of treating the facts as optional references. You see this opening at the top of **Reasoning**. **Stop** ends an answer early.

Nothing said on the Ask tab is written to the log. Asking reads memory and never writes it: it is a read, not a directive, so no agent is deployed and the arbiters commit nothing. The read is traced, so the map and the activity card show it.

The **activity card** sits between the conversation and the input box. Folded, it's one line: what the app is doing now (a bulk run's source and passage, or the latest event). Open, it lists recent events: sources read, each passage, each fact kept or refused with the reason, model loads, errors. An error opens it by itself. It remembers whether you left it open.

**Threads** on the model card: *Automatic* uses a safe default for the phone (at most 4); you can pick up to the number of cores. A loaded model reloads with the new count. **Diagnostics** show what llama.cpp read from the file (architecture, tokenizer and pre-tokenizer, whether the file has a chat template) and the CPU features in use: the first things to check when a model writes nonsense.

With a model loaded, **Extract** on the Sources tab deploys the model instead of the pattern agent (you can turn that off on the Ask tab). The model reads the file a passage at a time and proposes facts, each with a quote. The arbiters keep only the facts whose quote is really in the file, word for word. The rest are recorded as refused, with the reason. Every result names the exact model file by its fingerprint, and the model's raw output is kept in the episode, so a result can always be explained without running the model again.

On a recent flagship phone, a 1B model at Q4 reads a prompt at a few hundred tokens a second and writes at around 15 to 30. Each answer shows its own speed. (Tested: Q4_K_M from OpenBMB on a Xiaomi Pad 7 writes about 17 tokens a second. An F16 file produced garbled text on the same tablet; use Q4_K_M.) Importing checks for free space first: the copy needs the file's size plus a little room.

## Extract many sources

On **Sources**, tick sources, or type part of a name (for example `01-`) to select every match, then tap **Extract selected**. **Extract all** does every source.

- Sources run **one at a time**. Each gets its own directive, issued only when its turn comes, and its own signed result. A long queue never meets the limit of 8 open directives or a directive's 10-minute expiry, and a refusal in one source never affects another.
- **Skip sources already extracted** (on by default) skips any source that has a result for exactly what the file holds now. Edit a file and rescan, and it's extracted again.
- **Pause** and **Cancel** take effect between sources: the source being read finishes and commits first. What was committed stays.
- The queue is saved after every source. If the app closes mid-run, the run comes back as *interrupted*, ready to **Resume** from where it stopped.
- The run pauses by itself if the steward pauses the arbiters, or if three sources fail in a row.
- The screen stays on while a run is going. Work still needs the app open; a foreground service comes with the background slice.
- A question on Ask waits for the source being read to finish, then runs before the next one.

Each file should take less than 10 minutes to extract, or its directive expires and that file's run is refused. With the model at about 20 to 40 seconds per 2,400-character passage, keep files under about 25,000 characters (one textbook section).

## Live map

The map plays every step the engine takes as it happens: a packet on the edge it travels, a glow on the part that receives it, and a line in the **Live activity** feed. That covers requests, steering, signing, commits, the agent's deployment and reads, **each passage as the model reads it** (and what it proposed, and how many quotes were really in the text), **each fact as the arbiters keep or refuse it**, the gate, bulk runs, model loads, and Ask reading memory. Steps that arrive in a burst are played in order, faster when many wait. The trace lives in memory only (the newest 400 steps); nothing about it is written to the log. **Tour** walks one question through the system; the map offers it once.

## Try it

1. **Sources**: tap **Add sample texts**, or **Choose folder** to pick a folder of `.txt` or `.md` files. The workspace map records each file's content hash.
2. Tap **Extract** on a source. The arbiters:
   - issue a signed directive that pins the skill version, the source's hash, a budget and an expiry
   - run the agent on that source only
   - check every quote against the file, every attribute against the schema, and every tool against the skill
   - commit one signed result, with the refusals and their reasons
3. **Work**: tap **Run the gate**. Facts that two distinct sources agree on are copied into culture. The rest are held, and **Approve** signs a steward approval that lets one through.
4. **Memory**: the settled facts, K.
5. **Log**: every transaction, newest first. **Verify the log** re-reads everything from disk and re-checks every hash, signature and chain link.
6. **Ask**: the model client (see above).
7. **Map**: the interactive 3D map of the system. Each step the engine takes plays live on it (see *Live map*), and each part's panel shows live numbers from this device.

Try editing a file in your folder after mapping it: an extract is refused until you rescan. Pause on the Work tab and try to extract: the arbiters refuse.

## How the model works for the arbiters

The model never writes to memory. It works under the arbiters, like this:

1. **Enlisting.** When the model loads, it goes before the arbiters. They sign an `enlist` entry into the log: the exact model file, and a hash of each briefing it will be given. Every later result can be traced to those instructions.
2. **Extraction.** For each passage the arbiters send an agent built on the model, with a briefing:
   - the sentences, numbered, each with a lettered **menu** of the things it names (cut by rule)
   - an **output grammar** built from the schema: the model can only write `sentence | letter | relation | letter`, so it can't invent a value or a relation
   - two or three facts the rules already verified in the passage, as examples of the form
   - the arbiters' **notes** on its last results: how many of its facts were kept, and why the rest were refused
3. **Rules in the same pass.** The rules read what they can read for certain: "E is a V", "E, a V", "V is called E", "V such as A, B and C", "the primatologist Carel van Schaik". These cost no model time.
4. **Checks.** Every fact, from the rules or the model, is checked against its sentence by rule (see `Grounding`). The value must be in the sentence as a whole phrase. The sentence must say the relation with a word for it ("is a", "causes", "part of", "in"). Entity and value must be things, not pronouns, clauses or adjectives. Refusals are kept with their reasons.
5. **Concepts.** Facts that hold up are written as concept keys: "Anthropology is a vast field of study" becomes `anthropology | is_a | field_of_study`. Every source that says it lands on the same key, so support adds up at the gate.
6. **Reading.** Asked a question, the model first gets the arbiters' reading briefing, which says how the store is laid out. Only then does it see the facts it may use. With **Let it think first** on, its reasoning starts from those facts, one by one.

### Claims, and the checks the arbiters delegate

Most of what a text says doesn't fit a few relations. So the arbiters also keep **claims**: every sentence that states something becomes a claim, with its exact text and the concepts it's about. Questions, headings, references and sentences addressed to the reader are left out. The arbiters write claims from the text themselves, with no model, so they're the same on every phone. On four sections of an anthropology textbook that's 325 claims, against 42 facts.

A claim is settled at the gate once two independent sources stand behind it:
- the same sentence appears in both, or
- two sentences from different sources are judged to say the same thing, or
- you approve it.

To judge that, the arbiters go through the held claims (**Check claims** on the Work tab). They pick pairs from different sources whose words overlap, rare words counting more, and **delegate** each check to the loaded model. Each delegation is signed into the log: the two claims, the question, the briefing it goes under, and an expiry. The model gets a short briefing and answers one word, `yes` or `no`, under an output grammar. The arbiters refuse any other answer and sign the **verdict**.

A small model tends to say yes, so the arbiters guard against it:
- **Both orders:** each pair is asked twice, with the sentences swapped. Only yes both times is an agreement; an answer that changes with the order is inconsistent and counts for nothing. (An opposite question, "do they state different things?", was tried first: every small model tested read it as "are they worded differently?".)
- **Controls:** each review includes pairs the arbiters know don't agree (different sources, no shared concept, hardly a shared word). If the model says one agrees, or if no control could be asked, none of its agreements in that review count, and the verdicts say why.

Which model to use: on a 30-pair test (`tools/verify-bench.json`, the **Verifier bench** workflow), asked in both orders, LFM2-1.2B (0.73 GB at Q4_K_M) caught 11 of 12 real matches with 2 false agreements in 18, the best of the small models tried; MiniCPM5-1B had 4 false agreements and Qwen3-1.7B 5. All three passed their controls. On the first question alone, Qwen2.5-1.5B made 9 false agreements, and SmolLM2-1.7B said no to nearly everything.

A counted agreement is a second source for both claims; the model never settles anything itself. Deny works on claims as on facts.

Asked a question, the model gets the matching claims beside the facts, as the source's own sentences, marked settled or unconfirmed.

At the gate you can **Approve**, **Edit** or **Deny** any held fact, and Edit or Deny any settled one. A denied fact is never promoted, however many sources agree. An edited fact is settled as you wrote it, with the original's quote. Both are signed with your key.

### Safeguards: what can go wrong with a memory built like a brain

A memory that settles what several sources repeat, and fades what nobody repeats, has the brain's failure modes too: false consensus, silent overwriting, forgetting and suggestibility. The arbiters guard against each:

- **Copied sources count once (lineages).** "Two sources" means two *independent* sources. Each source is fingerprinted by sampling hashes of its 8-word runs; two sources sharing enough of them are one lineage, so notes copied from a textbook can't confirm the textbook. Each claim also keeps its **frame**, the heading it appeared under.
- **Contradictions are held, not overwritten.** For a relation with one value (like `date`), a new value against a settled one is held and marked **contested**, and so are two different values ready at the same time. Nothing is replaced silently. Contested items go to the **Needs you** queue on the Work tab; asked a question, the model is told to give both sides.
- **Maintenance (re-grounding).** **Maintain** on the Work tab re-checks every settled fact and claim against today's rules and its own quote. What no longer holds is signed into the log as a `contest` and leaves recall until you decide. The log keeps everything, so this is reversible.
- **Canon and archive, not deletion.** A settled fact's strength fades with a half-life of a year, slower the more sources it has. Weak facts move to the **archive**: never deleted, still shown in Memory with a badge, and recall keeps one slot for the best archive match, so old knowledge still surfaces.
- **Injected instructions are quarantined.** A sentence that speaks to an AI ("ignore previous instructions", "you must…", "tell the user…") gives no facts, and its claim is quarantined until you approve it. Everything the model reads from memory is fenced as quoted data, not instructions.
- **What the source said is kept.** When a fact is stored under a concept name (`field_of_study`), its original words are kept beside it, so every change from source to memory can be checked. A fact with no quote isn't promoted.

## What's inside

```
core/                      plain Java, no Android: the part the invariants are about
  Json.java                canonical JSON (sorted keys, whole numbers only)
  Crypto.java              SHA-256, ECDSA P-256 signatures, hex
  Tx.java                  transactions, replay and load order
  Store.java               the log's own rules: signatures, roles, chains, forks,
                           directive lifecycle, skill versions
  State.java               everything derived by replay; K = C∘F; the state hash
  Engine.java              the portal: arbiters, gate, steward actions, reads, the live trace
  BulkRun.java             extract many sources in turn: pause, resume, cancel, skip, restore
  Agent.java               the agent interface, and the pattern agent
  ModelAgent.java          the model as an agent: passages, prompt, quote location
  Ask.java                 the prompt for a question, with numbered facts
  Llm.java, Llama.java     the model interface, and the JNI binding to llama.cpp
  Policy.java              budgets, expiry, the gate's k, default schema and skills
  src/test/.../CoreTest    87 checks, run by CI
  src/test/.../LlamaTest   the native layer: load, stream, stop, UTF-8, limits
  src/test/.../DevServer   desktop preview of the screens against the real core

app/src/main/cpp/          the native model layer
  dilmun_llm.cpp           JNI to llama.cpp: load, generate with streaming, stop
  CMakeLists.txt           fetches llama.cpp at a pinned release (b11160) and builds it

app/                       the Android shell
  MainActivity.java        one WebView, edge to edge, the file and folder pickers
  Bridge.java              what the screens may call; answers as JSON; slow work as jobs
  ModelHost.java           imports, fingerprints, loads and unloads the model file
  KeystoreSigner.java      portal and steward keys in the Android Keystore
  SqliteBackend.java       the log on disk
  TreeSources.java         a folder picked with the system picker (read-only)
  AssetSources.java        the bundled sample texts
  assets/                  the screens (HTML, CSS, JS), the 3D map, three.js (MIT)

tools/make_tiny_gguf.py    writes a tiny random-weight model for the native tests
```

## How this differs from ARCHITECTURE.md, for now

- **Keys.** Signatures are ECDSA P-256 rather than Ed25519, so private keys can live in the Android Keystore on every device from Android 8. In this build, the device holds the steward's key as well as its own.
- **Storage.** The log is stored as transaction JSON. State is rebuilt in memory at startup. The datom tables, the four indexes and FTS5 come with the query slice.
- **Backups.** The log is excluded from backups and device transfer, because it's only valid with the keys, which never leave the Keystore.
- **Retrieval for Ask** is a simple word-overlap score over settled and held facts and their quotes. BM25 and Datalog come with the query slice.
- **Not yet in this build:**
  - sync between portals
  - excision
  - the dream cycle and D-space
  - Datalog
  - the background worker

## Next slices

1. **Background:** a foreground service for runs, and WorkManager for the dream cycle while the phone is charging and idle.
2. **Queries:** datom tables with EAVT/AEVT/AVET/VAET indexes, FTS5, and a small Datalog.
3. **Excision and sync,** as specified and tested in `dilmun-ref`.

## Desktop preview (optional)

With just a JDK:

```
javac -d build/core $(find core/src -name '*.java')
java -cp build/core app.dilmun.core.CoreTest
java -cp build/core app.dilmun.core.DevServer app/src/main/assets 8765
```

Then open http://127.0.0.1:8765/. The preview keeps keys and the log in memory, and "Choose folder" uses the sample texts (or the folder given with `-Ddilmun.sources=<folder>`).

To try the model on a desktop, build the native layer against a llama.cpp checkout and point the preview at it and a GGUF file:

```
git clone --depth 1 --branch b11160 https://github.com/ggml-org/llama.cpp ../llama.cpp
cmake -S app/src/main/cpp -B build/native -DLLAMA_DIR=../llama.cpp -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --target dilmun_llm
export LD_LIBRARY_PATH=$(find build/native -name '*.so*' -printf '%h\n' | sort -u | paste -sd:)
java -Ddilmun.llm=$PWD/build/native/libdilmun_llm.so -Ddilmun.model=path/to/model.gguf \
     -cp build/core app.dilmun.core.DevServer app/src/main/assets 8765
```
