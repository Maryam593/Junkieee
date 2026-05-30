<div align="center">
  <img src="assets/logo.svg" width="120" />
  <h1>JUNKIE</h1>
  <p>Your silent FoodPanda budget bodyguard.</p>
</div>

---

## What It Does

Open FoodPanda — Junkie handles the rest. No buttons, no manual input.

- **Silent auto-scan** — When you open FoodPanda, Junkie navigates to your order history automatically and calculates exactly how much you have spent this period.
- **Floating dot indicator** — Always visible while FoodPanda is open: green means you are fine, orange means slow down, red means you are over budget.
- **Order blocking** — If you try to place an order when your budget is exhausted, Junkie blocks it before it goes through.
- **Custom date ranges** — Set any period you want, not just monthly cycles.
- **Smart scenario handling** — Handles fresh periods, low-budget warnings, and expired periods cleanly, without triggering conflicting notifications.
- **Styled overlay notifications** — Dark-themed cards with colored backgrounds instead of plain system toasts. Red warnings get 🚨, info messages get 😇.

---

## How It Works

Junkie runs as an Android Accessibility Service entirely on your device. It never connects to the internet, never sends your data anywhere, and never stores anything outside your phone.

```
1. Open Junkie → set your budget and date range
2. Open FoodPanda as you normally would
3. Junkie silently scans your order history in the background
4. The floating dot tells you your status in real time
5. Budget blown → your next order is blocked
```

---

## Scenario Behaviour

| Situation | What Junkie Does |
|-----------|-----------------|
| Budget and period not configured | Blue banner prompting you to set up |
| Period active, no orders yet | Auto-scan → "Fresh start!" confirmation |
| Period active, under 20% budget remaining | Orange warning overlay at checkout |
| Period active, budget fully spent | Red block overlay, order is stopped |
| Period expired | Blue banner to set a new period; FoodPanda stays open |

---

## Tech Stack

- Kotlin
- Android Accessibility Service
- WindowManager overlay (`TYPE_APPLICATION_OVERLAY`)
- `GestureDescription` for gesture-based in-app navigation
- SharedPreferences for fully local storage
- Righteous font (Google Fonts)
- Material Design 3 with a custom dark theme token system
- Minimum SDK: Android 8.0 (API 26)

---

## Run It Yourself

```bash
git clone https://github.com/Maryam593/Junkieee.git
cd Junkieee
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installing, go to **Settings → Accessibility → Junkie** and enable the service.

---

*Built for everyone who says "just one more order" and then checks their bank balance at the end of the month. 💀*

Made with ♥ by Maryam
