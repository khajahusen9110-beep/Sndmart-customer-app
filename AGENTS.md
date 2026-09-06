# Sndmart — Base44 dev notes

## What this app is
Native **Android** app (Kotlin + Jetpack Compose, AGP 9.1.1, Gradle 9.3.1, Kotlin 2.2.10,
compileSdk 36 / minSdk 24). Backend is **Supabase** (REST + Auth) + Firebase (Messaging, AI).
It is NOT a web app — it cannot run interactively in the browser preview.

## How the preview works here
The Base44 preview is a web iframe on port 3000. Since this is a native Android app,
`docker-compose.base44.yml` builds the project and renders Compose UI through the
**Roborazzi** screenshot test (`GreetingScreenshotTest` → `app/src/test/screenshots/greeting.png`),
then serves that image gallery on port 3000 via `python3 -m http.server`. This is the
closest faithful "preview" achievable; the real app runs on a device/emulator.

## Build environment (`Dockerfile.base44`)
- Base: `eclipse-temurin:21-jdk-jammy` — **JDK 21 is required**: Robolectric's Android
  SDK 36 sandbox refuses to start on JDK 17 ("Android SDK 36 requires Java 21").
- Installs Android cmdline-tools + `platforms;android-36` + `build-tools;36.0.0`.
- Installs Gradle 9.3.1 directly (the repo has `gradle-wrapper.properties` but NO
  `gradlew` script or `gradle-wrapper.jar`, so we run `gradle` from PATH).
- Native libs for Robolectric/Roborazzi rendering: libgl1, libegl1, libgles2,
  libxkbcommon0, libwayland-client0, libglib2.0-0, libfontconfig1, libfreetype6.

## Verify it works
```
docker compose -f docker-compose.base44.yml up -d --build
docker compose -f docker-compose.base44.yml logs        # look for BUILD SUCCESSFUL
curl -sf -H "Host: x.example" http://localhost:3000/     # serves the screenshot gallery
```
Compile/test only:
```
docker compose -f docker-compose.base44.yml exec -T app gradle :app:compileDebugKotlin --no-daemon
docker compose -f docker-compose.base44.yml exec -T app gradle :app:testDebugUnitTest --no-daemon
```

## Secrets
None required to build. Supabase project ref + public anon key + Google web client id
are **hardcoded** in `app/src/main/java/com/example/data/remote/SupabaseConfig.kt`
(public-safe values). `GEMINI_API_KEY` is optional (commented in `.env.example`, wired
via the secrets-gradle-plugin) and only needed at runtime on a device for AI features.

## Architecture notes
- `MainActivity` owns navigation (NavHost) and the city-detection flow (location dialog,
  GPS, `find_city_for_location` RPC, manual `CityPickerSheet`).
- City detection runs **after login**, driven by `LaunchedEffect(userId)`: it first reads
  `profiles.city_id` (`SndmartRepository.resolveUserCity`); if set, skip to Home; if null,
  run first-time GPS detection. GPS is NOT re-run on every app open — only on first need,
  on a >30min backgrounded resume (lifecycle observer), or an explicit "Update my location".
- `SndmartRepository` is the single data layer; most calls fall back to `DemoCatalog` when
  the Supabase key is unconfigured/offline, except cities/city-detection which require the backend.
- Prices are always re-fetched fresh at render and before order placement (never cached).
