# Headless vault-sync daemon (W4) — run & deploy

A **Dropbox-style background sync process** (no Compose GUI) that runs the merged W4 sync engine
([`VaultSyncEngine`](../composeApp/src/jvmMain/kotlin/one/rarebit/heyarr/desktop/vault/VaultSyncEngine.kt))
on a JVM box, so a Linux laptop gets true bidirectional live sync of one local folder ⇄ one heyarr
vault space. See [`vault-sync.md`](vault-sync.md) for the engine/wire design; this doc is just how to
build, obtain and run the daemon.

## What it exposes (the machine contract)

- **Status file** `~/.cache/vault-sync.json` — rewritten atomically each pass and on every state
  change. `schema:1`, `phase` ∈ `off|preparing|running|paused|error`, plus `folder`, `space_id`,
  `controller`, `device`, `watching`, `last_sync_at`, `last_pass{pushed,pulled,conflicts,deleted,ms}`,
  `conflicts[]`, `last_error`.
- **Control socket** `~/.cache/vault-sync.sock` — unix stream, mode 0600, newline-delimited JSON,
  one request → one response:
  - `{"cmd":"status"}` → the full status object
  - `{"cmd":"sync-now"}` → `{"ok":true}`
  - `{"cmd":"pause"}` → `{"ok":true,"phase":"paused"}`
  - `{"cmd":"resume"}` → `{"ok":true,"phase":"running"}`
  - unknown/malformed → `{"ok":false,"error":"…"}`

  ```sh
  printf '{"cmd":"status"}\n' | nc -U ~/.cache/vault-sync.sock
  ```

These are the exact paths + shapes the homelab-ops MCP + Omarchy plugin already consumes.

## Custody (OPTION 1 — no phone gate)

Custody and API auth are **two different keys**:

- **Custody (unwrap the space key):** the Go voidbind device store this box was enrolled with
  (`voidbind pair-join`). The daemon reads the plaintext-hex enc seed in `~/.config/voidbind/device/`
  and unwraps the space's wrapped key — no phone, no second identity. `device_dir` configurable
  (default `~/.config/voidbind/device`). Unconditional.
- **API auth (read/write the encrypted state):** a **write-scoped bearer token**. A headless
  **writer needs one** — an enrolled device credential authenticates only at the READ FLOOR
  (ADR-0067), so its first `POST …/changes` returns **403**. Give the daemon a token (same shape as
  the heyarr CLI's / the desktop weblogin session token) and it becomes the credential for every
  vault API call; the Go store stays the custody key. Sources, in order:
  - `token` (config.json) / `HEYARR_VAULT_TOKEN` / `--token`, else
  - `token_file` (config.json) / `HEYARR_VAULT_TOKEN_FILE` / `--token-file`, else
  - the default `~/.config/heyarr/cli.token` **if it exists** (auto-used).
  - With **no** token the daemon falls back to the read-only device credential (`voidbind identity
    credential -header`): reads/custody work but writes 403. For a real two-way sync, set a token.

## Build / obtain the fat JAR

CI (`desktop.yml`) builds a runnable **uber JAR** (all deps bundled, incl. `voidbind-client`) with
`:composeApp:packageUberJarForCurrentOS` and uploads it as the **`heyarr-vault-sync-jar`** artifact
on every push/PR to `main`.

- Download the latest from a green `desktop` run:
  ```sh
  gh run download -R rarebit-one/heyarr-kmp \
    -n heyarr-vault-sync-jar \
    $(gh run list -R rarebit-one/heyarr-kmp -w desktop -b main -s success -L1 --json databaseId -q '.[0].databaseId')
  ```
- Or build locally where the Android SDK + a GPR `read:packages` token are set up:
  ```sh
  ./gradlew :composeApp:packageUberJarForCurrentOS
  # → composeApp/build/compose/jars/heyarr-desktop-linux-<arch>-1.0.0.jar
  ```

The uber JAR's built-in `Main-Class` is the Compose GUI window, so the daemon is launched **by class
name** on the classpath (not `-jar`):

```sh
java -cp heyarr-desktop-linux-*.jar one.rarebit.heyarr.desktop.vault.daemon.DaemonMainKt \
    --folder "$HOME/Vault"
```

> The CI artifact is built on the runner's arch (linux-x64). The daemon is pure JVM and never loads
> the bundled skiko/JNA native libs (it starts no UI), so the x64-built JAR runs fine on the aarch64
> Asahi box under JDK 21. Build locally if you want an arch-matched JAR anyway.

## Configuration

Precedence: built-in defaults → `~/.config/heyarr-vault-sync/config.json` → env → CLI args (last
wins). Only `folder` has no default. Example config file:

```json
{
  "folder": "/home/alarm/Vault",
  "space_id": "01a0ae3b-ca68-7165-9dbb-527425e2f380",
  "controller": "https://heyarr.br.thesim.family:7777",
  "device_dir": "/home/alarm/.config/voidbind/device",
  "token_file": "/home/alarm/.config/heyarr/cli.token",
  "poll_ms": 30000
}
```

`token_file` is optional — `~/.config/heyarr/cli.token` is auto-used when present. Prefer keeping the
token out of `config.json` (use `token_file`/`HEYARR_VAULT_TOKEN`) so a shared config file holds no
secret.

Env vars: `HEYARR_VAULT_FOLDER`, `HEYARR_VAULT_SPACE_ID`, `HEYARR_VAULT_CONTROLLER`,
`HEYARR_VOIDBIND_DEVICE_DIR`, `HEYARR_VAULT_TOKEN`, `HEYARR_VAULT_TOKEN_FILE`, `HEYARR_VAULT_POLL_MS`,
`HEYARR_VAULT_RETRY_MS`, `HEYARR_VAULT_STATUS_FILE`, `HEYARR_VAULT_SOCKET`, `HEYARR_VAULT_INDEX`,
`HEYARR_VAULT_SYNC_CONFIG`.
CLI: `--folder`, `--space-id`, `--controller`, `--device-dir`, `--token`, `--token-file`,
`--poll-ms`, `--index-file`, `--config`, …

`index_file` / `HEYARR_VAULT_INDEX` / `--index-file` overrides the device-local sync-index path
(default: `FileSyncIndexStore`'s `$XDG_CONFIG_HOME/heyarr-desktop/vault-index.json`). The index
records what this device has synced, so it is **per-vault** state — when you run one daemon instance
per vault (e.g. the `heyarr-vault-sync@.service` template), give each its own `index_file` (alongside
its own `status_file` + `socket`) so instances don't share an index and mistake one vault's files for
the other's remote deletes. This replaces the earlier `XDG_CONFIG_HOME` workaround.

The daemon logs its API-auth mode on startup (`bearer write token` vs `device credential
(read-floor …)`), so `journalctl` immediately shows whether writes will work.

## Install as a `systemd --user` service

```sh
mkdir -p ~/.local/lib/heyarr-vault-sync ~/.config/systemd/user
cp heyarr-desktop-linux-*.jar ~/.local/lib/heyarr-vault-sync/      # any versioned name is fine
cp deploy/heyarr-vault-sync.service ~/.config/systemd/user/
# set the folder (edit the unit's Environment= line, or write the config file above)
systemctl --user daemon-reload
systemctl --user enable --now heyarr-vault-sync.service
loginctl enable-linger "$USER"        # so it runs without an active login session
journalctl --user -u heyarr-vault-sync -f
```

The unit ([`deploy/heyarr-vault-sync.service`](../deploy/heyarr-vault-sync.service)) launches java
from the mise shim (`~/.local/share/mise/shims/java`) with the `dir/*` classpath wildcard, so the
versioned JAR filename needs no rename. Adjust the java path if yours differs
(`readlink -f "$(mise which java)"`). `Restart=on-failure` keeps it alive across transient node/
network drops (the daemon reports `preparing`/`error` and retries on its own regardless).

### One daemon per vault (the template unit)

A **vault = one folder tree ⇄ one space** (access control = the space's recipient set), and a box
can hold several — a personal vault, a family vault, and so on. To run one daemon per vault, use the
**template unit** [`deploy/heyarr-vault-sync@.service`](../deploy/heyarr-vault-sync@.service): the
instance name selects a per-vault config `~/.config/heyarr-vault-sync/<instance>.json`, and each
config gives that vault its own `folder`, `space_id`, `status_file`, `socket`, and — crucially —
`index_file`, so the instances never share a sync index (a shared index would treat one vault's
files as the other's remote deletes).

```sh
cp deploy/heyarr-vault-sync@.service ~/.config/systemd/user/
# ~/.config/heyarr-vault-sync/personal.json:
#   { "folder": "…/Vault", "space_id": "…", "controller": "…",
#     "status_file": "…/.cache/vault-sync.json", "socket": "…/.cache/vault-sync.sock",
#     "index_file": "…/.config/heyarr-desktop/vault-index.json" }
# ~/.config/heyarr-vault-sync/family.json:
#   { …, "status_file": "…/.cache/vault-sync-family.json",
#        "socket": "…/.cache/vault-sync-family.sock",
#        "index_file": "…/.config/heyarr-desktop/vault-index-family.json" }
systemctl --user daemon-reload
systemctl --user enable --now heyarr-vault-sync@personal heyarr-vault-sync@family
```

Convention: the personal vault keeps the un-suffixed `vault-sync.json` / `vault-sync.sock` (so
existing consumers keep working), and each additional vault uses `vault-sync-<vault>.json` /
`.sock`. The homelab-ops heyarr-hub plugin + MCP discover `vault-sync*.json` and show every vault.
