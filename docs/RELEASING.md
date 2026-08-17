# Releasing

There is no Play Store in this picture. The GitHub release assets *are* the
distribution channel, and the app updates itself from them, so the pipeline has
to be trustworthy in a way a store would otherwise enforce for you.

## Cutting a release

Bump both numbers in [`android/app/build.gradle.kts`](../android/app/build.gradle.kts):

```kotlin
versionCode = 11        // must increase; this is what the updater compares
versionName = "0.2.4"   // must equal the tag without its leading v
```

Commit, then tag and push:

```bash
git tag v0.2.4 && git push origin main v0.2.4
```

That is the whole ritual. The `Release` workflow builds, signs, verifies and
publishes. It refuses to publish rather than publish something wrong:

| Check | Why it exists |
|---|---|
| Tag matches `versionName` | Otherwise `v0.2.4` ships an APK whose start screen says 0.2.3 |
| `versionCode` beats the published one | The updater compares numerically; a code that did not increase is an update nobody is ever offered |
| `apksigner` says the cert is not `CN=Android Debug` | A debug-signed release installs cleanly, so nothing looks wrong until a properly signed update is refused by every phone that took it |
| `core:test` | A tag can be pushed at a commit whose CI went red |

To rehearse without spending a version number, run the workflow by hand from the
**Actions** tab. It does everything except publish and leaves the signed APK as
a workflow artifact for 14 days.

## The signing key

Android identifies an app by its signing certificate. The key that signs 0.2.4
must sign every version after it, forever — **if it is lost, no one who
installed the app can ever be updated**; they have to uninstall, losing their
data, and install afresh. There is no recovery process and nobody to appeal to.

So: it is generated once, by you, on your machine. It never enters this repo
(`*.jks`, `*.p12` and `*.keystore` are gitignored so a stray copy cannot be
committed), and it is backed up somewhere that is not a git repository and not
only this laptop.

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\keys" | Out-Null
keytool -genkeypair -v -keystore "$env:USERPROFILE\keys\diveslate.jks" `
        -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 `
        -alias diveslate -dname "CN=Paul Charpentier, O=Dive Slate"
```

PowerShell, not Git Bash: Java's interactive password prompt misbehaves under
MSYS. **PKCS12 uses one password for both the store and the key** — there is no
second password to invent, which is why two of the secrets below hold the same
value.

10000 days of validity is about 27 years. A certificate that expires cannot sign
an update, and the app is expected to outlive the decision.

## Where the credentials live

Four names, read from the environment first and `local.properties` second — see
the comment on `releaseSigning` in `app/build.gradle.kts`. All four or none: a
partial set fails the build instead of quietly falling through to the debug key.

**CI** reads them from repository secrets:

| Secret | Value |
|---|---|
| `DIVESLATE_KEYSTORE_BASE64` | the `.jks`, base64-encoded |
| `DIVESLATE_STORE_PASSWORD` | the password you chose |
| `DIVESLATE_KEY_PASSWORD` | the same password (PKCS12) |
| `DIVESLATE_KEY_ALIAS` | `diveslate` |

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\keys\diveslate.jks")) |
    gh secret set DIVESLATE_KEYSTORE_BASE64 --repo paul-charp/Dive-Slate
gh secret set DIVESLATE_STORE_PASSWORD --repo paul-charp/Dive-Slate
gh secret set DIVESLATE_KEY_PASSWORD --repo paul-charp/Dive-Slate
gh secret set DIVESLATE_KEY_ALIAS --repo paul-charp/Dive-Slate --body diveslate
```

The workflow decodes the keystore into `RUNNER_TEMP`, never into the workspace,
so nothing archived or packaged can contain it.

**Locally**, put the same four in `keystore.properties` — gitignored, and looked
for both at the repository root and beside `android/`:

```properties
storeFile=C:/Users/Paul/.android/keys/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

A relative `storeFile` resolves against the properties file that named it, not
against the app module. `local.properties` is still read, last, but it is the
worse home for a password: it is also where the SDK path lives, so Android Studio
owns that file and rewrites it.

The build says which key it used and where the config came from:

```
Dive Slate: signing release with the key at C:\Users\Paul\.android\keys\release.jks,
configured by keystore.properties.
```

All of this is optional. Without it `assembleRelease` still produces an
installable APK signed with the shared debug key, and warns that it did.

## Reading the signature back

The build config claiming to have signed something is not evidence. The APK is:

```bash
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs -v android/app/build/outputs/apk/release/app-release.apk
```

A properly signed build names you in the certificate DN, rather than
`CN=Android Debug`.

**It is v3-signed only, and that is correct.** v3 is verified from API 28 and
`minSdk` is 29, so the signer drops the redundant v2 block even though the build
asks for both. A check asserting v2 specifically fails every good release — the
workflow made exactly that mistake, and it was caught by verifying a real APK
rather than by reasoning about it.

## What a release contains

| Asset | |
|---|---|
| `dive-slate-<version>.apk` | the signed app |
| `dive-slate-<version>.apk.sha256` | checksum, for anyone verifying a download by hand |
| `update.json` | what the in-app updater reads |
| `mapping-<version>.txt` | R8's deobfuscation map. Without it a stack trace off a phone is unreadable, and it cannot be reconstructed later |

`update.json` is fetched from
`https://github.com/paul-charp/Dive-Slate/releases/latest/download/update.json`,
a path GitHub always resolves to the newest published release. That is why the
app needs no API call, no token and no knowledge of tag names:

```json
{
  "versionCode": 11,
  "versionName": "0.2.4",
  "tag": "v0.2.4",
  "apkUrl": "https://github.com/paul-charp/Dive-Slate/releases/download/v0.2.4/dive-slate-0.2.4.apk",
  "apkSha256": "…",
  "apkSize": 1948466,
  "releaseUrl": "https://github.com/paul-charp/Dive-Slate/releases/tag/v0.2.4"
}
```

Both fields the app relies on are load-bearing: `versionCode` because the
comparison is numeric, and `apkSha256` because the app refuses to install a
download that does not match it.

"Latest" here means latest published, non-draft, non-prerelease. Marking a
release as a prerelease is therefore how you publish a build without offering it
to every installed copy.
