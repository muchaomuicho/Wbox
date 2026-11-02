# Built-in node templates and rule snapshots

`resources/runtime/bundled/templates/` contains sanitized versions of the user's 2026-11-02 JSON and YAML templates. No original proxy, server credential, node-specific domain exception or address rewrite is shipped. `__WBOX_SELECTED__` is replaced by the active node; explicit dependencies are included only from its own subscription. Route ordering and general DNS/policy groups are retained. Platform adapters own OS-facing listeners, VPN and protected controller settings.

The live configuration at https://yybnb.wucnebssss.hidns.co/loveyybforever was read on 2026-11-02. Its 13 sing-box rule URLs matched the supplied JSON template. The YAML template adds the corresponding 13 mihomo rules. Exact URLs, snapshot date, sizes and SHA-256 digests are in `resources/runtime/bundled/rules/manifest.json`.

Only an exact engine/URL/format/behavior match uses the built-in snapshot. A custom URL with the same tag stays remote. Source configurations are never rewritten. Each core receives absolute paths inside its working directory. Valid hashed copies are reused; corrupt copies are atomically replaced from the package. These pinned snapshots update with application releases rather than downloading on every start.

Sources:

- anti-AD: https://github.com/privacy-protection-tools/anti-AD, MIT; license included as `resources/cores/licenses/LICENSE-anti-AD`. Original YAML is retained in `resources/source/rule-sets/`. The SRS includes compiled AdGuard rules which the official decompiler cannot export.
- perfect-panel/rules: https://github.com/perfect-panel/rules. SRS binaries are fetched through the exact CDN URLs supplied by the user. The GitHub repository does not declare a license. Decompilable SRS records are retained as JSON under `resources/source/rule-sets/`; attribution is preserved here without asserting a license grant.
- MetaCubeX/meta-rules-dat: https://github.com/MetaCubeX/meta-rules-dat, GPL-3.0; `resources/cores/licenses/meta-rules-dat-LICENSE`. MRS domain/IP records are exported as editable text under `resources/source/rule-sets/`.

Build assets are copied by the .NET project into `bundled/` and packaged by Gradle into `assets/bundled/`. `tools/fetch-rule-sets.py` retrieves and verifies the pinned artifacts, while `tools/verify-release.py` verifies both delivery packages against the manifest.

Startup sequencing follows mihomo v1.19.33 `hub/executor/executor.go`: `ApplyConfig` calls `OnInnerLoading` before initializing providers and `OnRunning` afterward. Wbox polls provider initialization timestamps and confirms them across successive probes instead of equating an open controller port with readiness.

Validation uses synthetic local services, not the credentials in the supplied templates. The Android template probe sends TCP through the selected SOCKS node and exercises private-address UDP via the template's direct rule, then checks stop recovery and reconnect. It does not assert public-Internet reachability for the user's actual nodes.
