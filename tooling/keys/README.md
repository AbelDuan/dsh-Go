# dsh-Go release signing key

This is the key **every build of this fork is signed with**, so `pm install -r` keeps working
across builds (Android rejects a package whose signing certificate changed).

| | |
| --- | --- |
| store | `dsh-go-release.keystore` (PKCS12) |
| alias | `dshgo` |
| password | `password.txt` (store and key passwords are the same) |
| key | RSA 4096, SHA384withRSA, valid ~30 years, `CN=dsh-go` |
| created | 2026-11-25 |

`AbelDuan/dsh-go-ci` (the build repository) writes `app/android/key.properties` from these two
files before it builds, so the APK always comes out with exactly this certificate. The same pair
is also kept in that repository's secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) as a fallback.

It lives in the repository because the phone only accepts a build signed with the certificate it
already has; a fresh debug keystore on a CI runner breaks `pm install` every time. Anyone with
this key can sign an APK the phone would treat as an update of this app, so keep the repository
private or accept that trade-off.

Local signing of an already-built APK (the x86_64 `zipalign`/`apksigner` wrappers from
build-tools do not run on an arm64 phone; the jar does):

```sh
java -jar apksigner.jar sign --ks dsh-go-release.keystore --ks-key-alias dshgo \
  --ks-pass "pass:$(cat password.txt)" --key-pass "pass:$(cat password.txt)" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out signed.apk in.apk
```
