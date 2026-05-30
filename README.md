<div align="center">
  <img src="assets/logo.png" width="120" />
  <h1>JUNKIE</h1>
  <p>your silent FoodPanda budget bodyguard</p>
</div>

---

## what it does

open FoodPanda, junkie does the rest — no buttons, no effort.

- **silent auto-scan** — when you open FoodPanda, junkie navigates to your order history on its own and tallies up what you've spent this period
- **floating dot** — always visible while FoodPanda is open: 🟢 chill · 🟡 slowing down · 🔴 you're done
- **budget blocking** — tries to place an order when you're over budget? blocked before it goes through
- **custom date ranges** — not just monthly. set any period you want
- **3 smart scenarios** — handles fresh periods, almost-over warnings, and expired periods cleanly without conflicting
- **dark themed overlays** — styled notifications with colored bodies (red 🚨 / blue 😇 / green) that don't look like system toasts

---

## how it works

junkie runs as an Android accessibility service. it never connects to the internet, never leaves your phone. 100% local.

```
1. open junkie → set budget + date range
2. open FoodPanda like normal
3. junkie auto-scans your order history silently
4. dot tells you where you stand in real time
5. over budget → next order is blocked. done.
```

---

## scenarios

| situation | what junkie does |
|-----------|-----------------|
| period not configured | blue banner — reminds you to set up |
| period active, fresh start | auto-scan → "fresh start!" message |
| period active, almost over (≤20%) | orange warning overlay on checkout |
| period active, budget blown | red block overlay + order stopped |
| period expired | blue banner — prompts new period, FoodPanda stays open |

---

## tech

- Kotlin
- Android Accessibility Service
- WindowManager overlay (TYPE_APPLICATION_OVERLAY)
- GestureDescription for gesture-based navigation
- SharedPreferences for local storage
- Righteous font (Google Fonts)
- Material Design 3 / dark theme with token system
- Min SDK: Android 8.0 (API 26)

---

## run it yourself

```bash
git clone https://github.com/Maryam593/Junkieee.git
cd Junkieee
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

then: enable the accessibility service in **Settings → Accessibility → Junkie**.

---

built for people who say "just one more order" and then check their account at the end of the month 💀

made with ♥ by Maryam
