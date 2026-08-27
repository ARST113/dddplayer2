#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="$ROOT/.justplus-upstream"

rm -rf "$UPSTREAM"
git clone --depth 1 --branch v1.2.0 https://github.com/just-plus-player/just-plus-player.git "$UPSTREAM"

export DDD_ROOT="$ROOT"
python3 <<'PY'
from pathlib import Path
import os
import re

root = Path(os.environ["DDD_ROOT"])
up = root / ".justplus-upstream"

# ---- Patch Just+ Gradle identity/signing/dependency ----
build = up / "app/build.gradle"
s = build.read_text()
s = s.replace('applicationId "com.justplus.player"', 'applicationId "top.rootu.dddplayer"')
s = s.replace('minSdkVersion 23', 'minSdkVersion 24')
s = s.replace('versionCode tagVersionCode', 'versionCode (project.findProperty("versionCodeOverride") ?: "1500000").toString().toInteger()')
s = s.replace('versionName tagVersionName', 'versionName (project.findProperty("versionNameOverride") ?: "0.0.15").toString()')

signing = '''    signingConfigs {\n        release {\n            def dddKeystore = rootProject.file("app/dddplayer-release.jks")\n            if (dddKeystore.exists()) {\n                storeFile dddKeystore\n                storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")\n                keyAlias System.getenv("ANDROID_KEY_ALIAS")\n                keyPassword System.getenv("ANDROID_KEY_PASSWORD")\n            }\n        }\n    }\n'''
if 'def dddKeystore' not in s:
    s = s.replace('    buildTypes {\n', signing + '    buildTypes {\n', 1)
s = s.replace('        release {\n            minifyEnabled false',
              '        release {\n            signingConfig signingConfigs.release\n            minifyEnabled false', 1)
if "implementation project(path: ':legacy')" not in s:
    s = s.replace('dependencies {\n', "dependencies {\n    implementation project(path: ':legacy')\n", 1)
if "androidx.documentfile:documentfile" not in s:
    s = s.replace('dependencies {\n', "dependencies {\n    implementation \'androidx.documentfile:documentfile:1.1.0\'\n", 1)
if "media3-datasource-okhttp" not in s:
    s = s.replace('dependencies {\n', "dependencies {\n    implementation \"androidx.media3:media3-datasource-okhttp:1.11.0-beta01\"\n", 1)
build.write_text(s)

# Visible identity: Android package/signature stay DDD-compatible, UI becomes Just Player.
strings = up / "app/src/main/res/values/strings.xml"
s = strings.read_text()
s = re.sub(r'(<string\s+name="app_name"[^>]*>).*?(</string>)', r'\1Just Player 2.1\2', s, count=1)
strings.write_text(s)

# Keep self-update/links on the same repository used by installed DDD 0.0.14.
for p in (up / "app/src/main/java").rglob("*.java"):
    text = p.read_text()
    changed = text.replace("just-plus-player/just-plus-player", "ARST113/dddplayer2")
    if changed != text:
        p.write_text(changed)

# Future self-updates need package-installer permission under the inherited DDD application id.
manifest = up / "app/src/main/AndroidManifest.xml"
s = manifest.read_text()
if 'android.permission.REQUEST_INSTALL_PACKAGES' not in s:
    s = s.replace('<uses-permission android:name="android.permission.INTERNET" />',
                  '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />', 1)
manifest.write_text(s)

# ---- Patch Just+ PlayerActivity: DDD bridge hooks + clean control metadata + HEVC fallback ----
pa = up / "app/src/main/java/com/brouken/player/PlayerActivity.java"
s = pa.read_text()

interop_import = 'import top.rootu.dddplayer.compat.JustPlusBridgeInterop;\n'
if interop_import not in s:
    marker = 'import java.util.concurrent.TimeUnit;\n'
    if marker not in s:
        raise SystemExit('PlayerActivity import marker not found')
    s = s.replace(marker, marker + '\n' + interop_import, 1)

# DDD control query/fragment metadata is for the integration layer and must never reach TorrServer/CDN.
create_marker = '        super.onCreate(savedInstanceState);\n'
if 'sanitizeDddLaunchIntent();' not in s:
    if create_marker not in s:
        raise SystemExit('PlayerActivity onCreate marker not found')
    s = s.replace(create_marker, create_marker + '        sanitizeDddLaunchIntent();\n', 1)

# Attach after Just+ has installed its own listeners and analytics hooks.
attach_marker = '        player.addAnalyticsListener(playbackInfoListener);\n'
if 'JustPlusBridgeInterop.attach(this, getIntent(), player);' not in s:
    if attach_marker not in s:
        raise SystemExit('PlayerActivity attach marker not found')
    s = s.replace(attach_marker,
                  attach_marker + '        JustPlusBridgeInterop.attach(this, getIntent(), player);\n', 1)

# DDD internal headers contain JSON/control information and make strict media endpoints reject the request.
headers_marker = '''                    if ("User-Agent".equalsIgnoreCase(name)) {\n                        userAgent = value;\n                    } else {\n                        headers.put(name, value);\n                    }'''
headers_replacement = '''                    if (name.regionMatches(true, 0, "X-Lampa-DDD-", 0, "X-Lampa-DDD-".length())) {\n                        continue;\n                    }\n                    if ("User-Agent".equalsIgnoreCase(name)) {\n                        userAgent = value;\n                    } else {\n                        headers.put(name, value);\n                    }'''
if 'name.regionMatches(true, 0, "X-Lampa-DDD-"' not in s:
    if headers_marker not in s:
        raise SystemExit('PlayerActivity headers marker not found')
    s = s.replace(headers_marker, headers_replacement, 1)

# Prefer Just+'s DV->HEVC recovery. If plain HEVC itself is what failed, hand the exact launch
# to the preserved DDD/libavcodec pipeline. This covers RExt/4:2:2/4:4:4 10-bit files such as
# hvc1.4.10.H120.9C.08 without changing the working AV1/HDR/DV paths.
dv_marker = '''            if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED\n                    && error instanceof ExoPlaybackException\n                    && recoverByForcingHevcForDolbyVision(error,\n                            ((ExoPlaybackException) error).rendererFormat)) {\n                return;\n            }'''
legacy_hook = dv_marker + '''\n            if (error instanceof ExoPlaybackException\n                    && recoverByLaunchingLegacyHevc(error, ((ExoPlaybackException) error).rendererFormat)) {\n                return;\n            }'''
if 'recoverByLaunchingLegacyHevc(error' not in s:
    if dv_marker not in s:
        raise SystemExit('PlayerActivity DV recovery marker not found')
    s = s.replace(dv_marker, legacy_hook, 1)

methods = r'''
    /**
     * Transition-only compatibility path. Normal Main/Main10 HEVC stays in Just+/MediaCodec.
     * When the selected Android HEVC decoder fails, the same Intent is opened by the preserved
     * DDD activity, whose libavcodec fallback handles RExt 10-bit formats unsupported by MediaCodec.
     */
    private boolean recoverByLaunchingLegacyHevc(PlaybackException error, @Nullable Format failingFormat) {
        if (failingFormat == null || !MimeTypes.VIDEO_H265.equals(failingFormat.sampleMimeType)
                || !isDecoderFailure(error)) {
            return false;
        }
        final Intent current = getIntent();
        if (current != null && current.getBooleanExtra(JustPlusBridgeInterop.EXTRA_FORCE_LEGACY, false)) {
            return false;
        }
        final Intent legacy = current == null ? new Intent() : new Intent(current);
        final String originalUri = legacy.getStringExtra(JustPlusBridgeInterop.EXTRA_ORIGINAL_URI);
        if (originalUri != null && !originalUri.isEmpty()) {
            legacy.setData(Uri.parse(originalUri));
        }
        legacy.setClassName(getPackageName(), "top.rootu.dddplayer.ui.PlayerActivity");
        legacy.putExtra(JustPlusBridgeInterop.EXTRA_FORCE_LEGACY, true);
        if (player != null) {
            legacy.putExtra("position", player.getCurrentPosition());
        }
        JustPlusBridgeInterop.detach(this, "hevc_legacy_fallback");
        startActivity(legacy);
        finish();
        return true;
    }

    /** Strip DDD query/fragment control metadata while preserving it for bridge parsing. */
    private void sanitizeDddLaunchIntent() {
        final Intent launch = getIntent();
        if (launch == null || launch.getData() == null) {
            return;
        }
        final Uri data = launch.getData();
        final String original = data.toString();
        boolean changed = false;
        String cleanQuery = null;
        final String encodedQuery = data.getEncodedQuery();
        if (encodedQuery != null && !encodedQuery.isEmpty()) {
            final ArrayList<String> kept = new ArrayList<>();
            for (String part : encodedQuery.split("&")) {
                final String rawKey = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                final String key = Uri.decode(rawKey);
                if (key != null && key.toLowerCase(Locale.ROOT).startsWith("ddd_")) {
                    changed = true;
                } else {
                    kept.add(part);
                }
            }
            cleanQuery = kept.isEmpty() ? null : TextUtils.join("&", kept);
        }
        final String fragment = data.getFragment();
        final boolean dddFragment = fragment != null && fragment.toLowerCase(Locale.ROOT).contains("ddd_");
        changed |= dddFragment;
        if (!changed) {
            return;
        }
        final Uri.Builder builder = data.buildUpon();
        builder.encodedQuery(cleanQuery);
        if (dddFragment) {
            builder.fragment(null);
        }
        launch.putExtra(JustPlusBridgeInterop.EXTRA_ORIGINAL_URI, original);
        launch.setData(builder.build());
    }

'''
static_marker = '    private static boolean isDecoderFailure(PlaybackException error) {'
if 'private boolean recoverByLaunchingLegacyHevc' not in s:
    if static_marker not in s:
        raise SystemExit('PlayerActivity decoder failure marker not found')
    s = s.replace(static_marker, methods + static_marker, 1)

# Stop bridge observers/local socket with the Activity. attach() handles player rebuilds separately.
destroy_marker = '''        super.onDestroy();'''
if 'JustPlusBridgeInterop.detach(this, "activity_destroyed");' not in s:
    # There is a single Activity.onDestroy() super call in this source.
    if destroy_marker not in s:
        raise SystemExit('PlayerActivity onDestroy marker not found')
    s = s.replace(destroy_marker,
                  '        JustPlusBridgeInterop.detach(this, "activity_destroyed");\n' + destroy_marker, 1)

pa.write_text(s)

# ---- Patch old DDD PlayerActivity into a compatibility router ----
legacy_pa = root / "app/src/main/java/top/rootu/dddplayer/ui/PlayerActivity.kt"
s = legacy_pa.read_text()
router_marker = '        super.onCreate(savedInstanceState)\n'
router = '''        super.onCreate(savedInstanceState)\n\n        // 0.0.15 keeps this component name so existing Lampa integrations continue to work.\n        // Normal launches are forwarded to Just+; only decoder fallback explicitly stays here.\n        if (!intent.getBooleanExtra("ddd_force_legacy", false)) {\n            runCatching {\n                val forward = Intent(intent).apply {\n                    setClassName(packageName, "com.brouken.player.PlayerActivity")\n                }\n                startActivity(forward)\n                finish()\n                return\n            }.onFailure { Log.w("DDDPlayer/Compat", "Just+ router failed, staying on legacy player", it) }\n        }\n'''
if 'Just+ router failed' not in s:
    if router_marker not in s:
        raise SystemExit('Legacy PlayerActivity onCreate marker not found')
    s = s.replace(router_marker, router, 1)
legacy_pa.write_text(s)

print('Prepared Just+ v1.2.0 transition source')
PY
