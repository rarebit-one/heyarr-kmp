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

The daemon REUSES the Go voidbind device store this box was enrolled with (`voidbind pair-join`):
it unwraps the space key from the plaintext-hex enc seed in `~/.config/voidbind/device/` and mints
the controller credential by shelling out to `voidbind identity credential -header`. So the host
needs the `voidbind` CLI on PATH and an enrolled device store; the daemon is deliberately coupled to
both. The device dir is configurable (default `~/.config/voidbind/device`).

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
  "poll_ms": 30000
}
```

Env vars: `HEYARR_VAULT_FOLDER`, `HEYARR_VAULT_SPACE_ID`, `HEYARR_VAULT_CONTROLLER`,
`HEYARR_VOIDBIND_DEVICE_DIR`, `HEYARR_VAULT_POLL_MS`, `HEYARR_VAULT_RETRY_MS`,
`HEYARR_VAULT_STATUS_FILE`, `HEYARR_VAULT_SOCKET`, `HEYARR_VAULT_SYNC_CONFIG`.
CLI: `--folder`, `--space-id`, `--controller`, `--device-dir`, `--poll-ms`, `--config`, …

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
