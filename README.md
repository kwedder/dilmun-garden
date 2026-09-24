# Dilmun portal (Android)

This is the first working slice of the Dilmun portal as an Android app. It runs the real core: a signed, append-only log in SQLite; arbiters whose state is rebuilt from that log at every startup; the skill registry and workspace map steering every run; directives with budgets and expiry; the promotion gate; steward approval and pause; and fork detection.

A small pattern agent sits in the model's slot for now. It reads lines written as `entity | attribute | value`. The on-device model (MiniCPM5-1B) replaces it in the next slice through the same `Agent` interface. Everything around the agent is already the real thing.

The app declares no internet permission, so nothing it holds can leave the device.

## Get the APK (GitHub Actions)

1. Create a new repository on GitHub, for example `dilmun-portal`. It can be private.
2. Upload this folder's contents to it: drag the files into the GitHub web page, or run `git init`, `git add .`, `git commit`, `git push`. Make sure the hidden `.github` folder goes too, since it holds the build workflow. Finder hides it; press Cmd+Shift+. to show it.
3. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push; you can also start it with **Run workflow**.
4. When the run finishes (about 5 minutes), open it and download **dilmun-portal-apk** from the Artifacts section. It's a zip containing `app-debug.apk`.
5. Copy the APK to your phone and open it. Android asks you to allow installs from that app (Files, Chrome, and so on) the first time.

The workflow runs the core tests before it builds, so a failing test stops the build.

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
6. **Map**: the portal working live, in 3D. Every step the engine takes (a request, steering, a signature, a commit, the agent reading and proposing, the checks, the gate, a refusal) travels along the edge it really uses the moment it happens, lights up the part that receives it, and lands in the **Live activity** feed with its time. **Extract**, **Gate** and **Verify** on the feed let you act without leaving the map, and work started on any other tab plays when you come back (anything over a minute old goes straight to the feed). Tap a part for its live counts and its last activity.
   - **Tour** is the guided walkthrough of one question through the whole design, the way a game teaches a new screen. It's offered once on your first visit and is always one tap away; live activity waits while it runs.

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
  Engine.java              the portal: arbiters, gate, steward actions, reads,
                           and the live trace of each step for the map
  Agent.java               the agent interface, and the pattern agent
  Policy.java              budgets, expiry, the gate's k, default schema and skills
  src/test/.../CoreTest    47 checks, run by CI before every build
  src/test/.../DevServer   desktop preview of the screens against the real core

app/                       the Android shell
  MainActivity.java        one WebView, edge to edge, the folder picker
  Bridge.java              what the screens may call; answers as JSON
  KeystoreSigner.java      portal and steward keys in the Android Keystore
  SqliteBackend.java       the log on disk
  TreeSources.java         a folder picked with the system picker (read-only)
  AssetSources.java        the bundled sample texts
  assets/                  the screens (HTML, CSS, JS), the 3D map, three.js (MIT)
```

## How this differs from ARCHITECTURE.md, for now

- **Keys.** Signatures are ECDSA P-256 rather than Ed25519, so private keys can live in the Android Keystore on every device from Android 8. In this build, the device holds the steward's key as well as its own.
- **Storage.** The log is stored as transaction JSON. State is rebuilt in memory at startup. The datom tables, the four indexes and FTS5 come with the query slice.
- **Backups.** The log is excluded from backups and device transfer, because it's only valid with the keys, which never leave the Keystore.
- **Not yet in this build:**
  - the on-device model
  - sync between portals
  - excision
  - the dream cycle and D-space
  - Datalog
  - the background worker

## Next slices

1. **The model:** MiniCPM5-1B through llama.cpp (NDK), loaded on the first directive and unloaded when idle. It gets a real extraction skill that proposes facts with quotes.
2. **Background:** a foreground service for runs, and WorkManager for the dream cycle while the phone is charging and idle.
3. **Queries:** datom tables with EAVT/AEVT/AVET/VAET indexes, FTS5, and a small Datalog.
4. **Excision and sync,** as specified and tested in `dilmun-ref`.

## Desktop preview (optional)

With just a JDK:

```
javac -d build/core $(find core/src -name '*.java')
java -cp build/core app.dilmun.core.CoreTest
java -cp build/core app.dilmun.core.DevServer app/src/main/assets 8765
```

Then open http://127.0.0.1:8765/. The preview keeps keys and the log in memory, and "Choose folder" uses the sample texts.
