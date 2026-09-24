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

The app is built around **MiniCPM5-1B** by OpenBMB, in the GGUF format that llama.cpp reads.

1. On your phone, open the **Ask** tab and tap **Open the MiniCPM5-1B download page**. It opens in your browser; the app itself never goes online.
2. Download one of the GGUF files. A **Q4_K_M** file is a good balance of size and quality, under 1 GB. Larger files (Q8_0) are a little better and slower.
3. Back on **Ask**, tap **Import model file** and pick the file from your downloads. The app:
   - checks that it's a GGUF file
   - copies it into its private storage and computes its SHA-256 fingerprint
   - loads it
4. Ask a question. With **Use memory** on, the settled facts that match your question are put in front of the model, numbered, and the answer cites them like [1]. Tap **Memory given** under an answer to see exactly which facts it had. **Let it think first** lets a reasoning model think before it answers; it's slower. **Stop** ends an answer early.

Nothing said on the Ask tab is written to the log. Asking reads memory and never writes it.

With a model loaded, **Extract** on the Sources tab deploys the model instead of the pattern agent (you can turn that off on the Ask tab). The model reads the file a passage at a time and proposes facts, each with a quote. The arbiters keep only the facts whose quote is really in the file, word for word. The rest are recorded as refused, with the reason. Every result names the exact model file by its fingerprint, and the model's raw output is kept in the episode, so a result can always be explained without running the model again.

On a recent flagship phone, a 1B model at Q4 reads a prompt at a few hundred tokens a second and writes at around 15 to 30. Each answer shows its own speed.

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
7. **Map**: the interactive 3D map of the system, with live numbers from this device in each part's panel.

Try editing a file in your folder after mapping it: an extract is refused until you rescan. Pause on the Work tab and try to extract: the arbiters refuse.

## What's inside

```
core/                      plain Java, no Android: the part the invariants are about
  Json.java                canonical JSON (sorted keys, whole numbers only)
  Crypto.java              SHA-256, ECDSA P-256 signatures, hex
  Tx.java                  transactions, replay and load order
  Store.java               the log's own rules: signatures, roles, chains, forks,
                           directive lifecycle, skill versions
  State.java               everything derived by replay; K = C∘F; the state hash
  Engine.java              the portal: arbiters, gate, steward actions, reads
  Agent.java               the agent interface, and the pattern agent
  ModelAgent.java          the model as an agent: passages, prompt, quote location
  Ask.java                 the prompt for a question, with numbered facts
  Llm.java, Llama.java     the model interface, and the JNI binding to llama.cpp
  Policy.java              budgets, expiry, the gate's k, default schema and skills
  src/test/.../CoreTest    52 checks, run by CI
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
- **Retrieval for Ask** is a simple word-overlap score over settled facts. BM25 and Datalog come with the query slice.
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

Then open http://127.0.0.1:8765/. The preview keeps keys and the log in memory, and "Choose folder" uses the sample texts.

To try the model on a desktop, build the native layer against a llama.cpp checkout and point the preview at it and a GGUF file:

```
git clone --depth 1 --branch b11160 https://github.com/ggml-org/llama.cpp ../llama.cpp
cmake -S app/src/main/cpp -B build/native -DLLAMA_DIR=../llama.cpp -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --target dilmun_llm
export LD_LIBRARY_PATH=$(find build/native -name '*.so*' -printf '%h\n' | sort -u | paste -sd:)
java -Ddilmun.llm=$PWD/build/native/libdilmun_llm.so -Ddilmun.model=path/to/model.gguf \
     -cp build/core app.dilmun.core.DevServer app/src/main/assets 8765
```
