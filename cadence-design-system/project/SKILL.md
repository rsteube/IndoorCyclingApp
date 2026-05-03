---
name: indoor-bike-design
description: Use this skill to generate well-branded interfaces and assets for Indoor Bike App (an indoor cycling app for smart trainers — dark, telemetry-first, F-Droid friendly), either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for prototyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files (`colors_and_type.css`, `assets/`, `preview/`, `ui_kits/indoorbike_android/`).

If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code (Jetpack Compose / SwiftUI / Kotlin Multiplatform), copy assets and read the rules here to become an expert in designing with this brand.

Key non-negotiables for Indoor Bike App:
- Dark by default. F-Droid friendly: no Google Services, no proprietary analytics. Auth0/CleverCloud are opt-in only.
- Power zones are systematic (Z1–Z7, FTP-percent based). Never substitute a zone color for anything that isn't a zone.
- One brand accent: cyan `#00D9FF`, paired with azure `#2D6BFF` only in gradients.
- No emoji. Lucide icons @ 2px stroke. Tabular numerics for telemetry.
- Voice: a coach who trusts you. Direct, technical, no exclamation marks.

If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts _or_ production code, depending on the need.
