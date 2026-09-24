# Releasing

A release is a git tag. JitPack builds any tag on demand; the
`Publish to Maven Central` workflow publishes the same tag to Maven Central as
`app.localizeme:sdk:<tag>`.

1. Set `version` in `localizeme/build.gradle.kts` to the new version. The SDK
   reports it in every request, so it has to match the tag.
2. Commit, then tag and push: `git tag -a 0.1.0 -m 0.1.0 && git push origin 0.1.0`.
3. Mark the release on GitHub (`gh release create 0.1.0 --verify-tag`), with
   `--prerelease` for a beta.

## One-time Maven Central setup

The workflow skips publishing, with a warning, until these exist.

1. Create an account on the [Central Portal](https://central.sonatype.com).
2. Register the namespace `app.localizeme` there. The Portal gives a
   verification key to add as a DNS TXT record on `localizeme.app`.
3. Create a signing key and publish its public half:
   `gpg --quick-generate-key "LocalizeMe <sdk@localizeme.app>" rsa4096 sign never`,
   then `gpg --keyserver keyserver.ubuntu.com --send-keys <key id>`.
4. Generate a user token on the Central Portal (Account → Generate User Token).
5. Add four repository secrets under Settings → Secrets and variables → Actions:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | the token's username |
   | `MAVEN_CENTRAL_PASSWORD` | the token's password |
   | `SIGNING_KEY` | `gpg --export-secret-keys --armor <key id>` |
   | `SIGNING_KEY_PASSWORD` | the key's passphrase, if it has one |

6. Release a new version. The `0.1.0-beta.1` tag predates this workflow, so
   the first release on Maven Central is the next tag, for example
   `0.1.0-beta.2`.
7. Once it appears at https://central.sonatype.com/artifact/app.localizeme/sdk,
   replace the JitPack steps in `README.md` with the one dependency line
   `implementation("app.localizeme:sdk:<version>")`. Android projects list
   `mavenCentral()` already, so apps need no extra repository.
