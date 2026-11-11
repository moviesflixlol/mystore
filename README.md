# Nuratix Store — Android App Store

A fast, lightweight, and modern alternative Android application store with verified package downloads, Split APK support, and zero emojis.

![Nuratix Store Logo](app/src/main/assets/header-logo-tight.png)

## Features

- **Ultra Lightweight (~2.5 MB)**: Powered by clean Android System WebView, eliminating 300MB+ Chromium bloat.
- **Split APK Support**: Seamlessly discovers, downloads, and handoffs base APKs and configuration splits ().
- **Circular Download Progress Bar**: Visual feedback on every download with smooth progress animations.
- **App Updates Manager**: Automatically scans installed/downloaded packages against the API and prompts updates.
- **Dark Modern Shell**: Floating atmospheric smoke, crisp vector SVG branding (Google, Microsoft, Meta, WhatsApp, Spotify, etc.).
- **Zero Emojis**: 100% clean vector icons only.

## Automated Cloud Build (GitHub Actions)

This repository includes a GitHub Actions workflow that automatically builds and signs the release APK in the cloud under 2 minutes:

1. Push code to the repository.
2. Go to the **Actions** tab to view live build progress.
3. Download the signed APK from **Releases** or **Artifacts**.

## License

Open source and free to use.
