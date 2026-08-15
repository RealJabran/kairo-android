# Contributing to Kairo

Thanks for helping improve Kairo. Keep changes focused, testable, and safe for existing offline libraries.

## Before opening an issue

- Search existing issues first.
- Include the Kairo version, Android version, device model, and exact reproduction steps.
- Remove API keys, personalized add-on URLs, local IP addresses, and media links from screenshots or logs.
- Describe whether the problem affects browsing, streaming, downloading, or local playback.

## Development workflow

1. Create a branch from `main`.
2. Make the smallest coherent change.
3. Run compilation, lint, and an APK build:

   ```bash
   ./build-apk.sh
   JAVA_HOME="$PWD/.build-tools/jdk-17.0.19+10" \
     ./.build-tools/gradle-9.1.0/bin/gradle :app:lintDebug
   ```

4. Explain the root cause and validation in the pull request.

Do not commit APKs, build directories, local SDKs, keystores, credentials, or personalized service configuration.

## Source adapters

Keep provider-specific behavior inside `AnimeSourceAdapter` implementations. A playable adapter must validate and implement its full browse → details → language → quality path. New network sources should use bounded timeouts and must not expose secrets in user-facing descriptions or logs.

## Downloads

Offline changes must preserve an existing completed file until a replacement is durable. HLS changes should cover adaptive playlists, encryption, byte ranges, initialization segments, and separate audio renditions where applicable.
