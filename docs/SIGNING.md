# Internal signing key

Android only installs an APK over an existing app when both are signed with the same key. CI
debug APKs get a new throw-away key every run, so they cannot update each other. The optimised
**release APK** built by CI can use one fixed key instead, kept as GitHub secrets. Then every build
installs over the previous one.

This key is for the owner's own builds ("internal"). It is not the future public release key.

## One-time setup (on the owner's VM)

1. Make the key. Pick a long random password when asked and use the same one for the key; keep it
   in your password manager:

   ```
   keytool -genkeypair -v -keystore shelldon-internal.jks -storetype PKCS12 \
     -alias shelldon-internal -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Shelldon SSH internal builds"
   ```

2. Back up `shelldon-internal.jks` and its password somewhere safe and **not in the repo**. If they
   are lost, the next build gets a new key and has to be installed fresh once.

3. Add three secrets in GitHub: repository, Settings, Secrets and variables, Actions, New
   repository secret.

   | Name | Value |
   |---|---|
   | `SHELLDON_KEYSTORE_BASE64` | the output of `base64 -w0 shelldon-internal.jks` |
   | `SHELLDON_KEYSTORE_PASSWORD` | the password from step 1 |
   | `SHELLDON_KEY_ALIAS` | `shelldon-internal` |

   With the GitHub CLI on the VM instead: `base64 -w0 shelldon-internal.jks | gh secret set SHELLDON_KEYSTORE_BASE64 --repo thowt/shelldon`,
   then the same for the other two (`gh secret set NAME --repo thowt/shelldon` and paste the value).

4. Delete the local copies you do not need (`rm` the `.b64` file if you made one).

## How to check it works

Release APKs are built on every push to `main`, and on demand: Actions -> Build -> Run workflow,
pick any branch and leave "Which APK to build" on `release`. Pull request runs build only the debug
APK. Each release run prints the signing certificate fingerprint (SHA-256) in the job summary of
the "Inspect the release APK" step. It must be the same on every run, and the summary must say
`Stable signing key in use: true`.

## Rules

- Never commit the keystore or the password.
- Only the "Prepare the internal signing key" and "Build optimised release APK" steps see the
  secrets, and they run only for the release variant (push to `main` or manual run). Pull request
  runs, including ones from forks, never read the secrets.
- Rotating the key means one fresh install on the phone.
