<div align="center">
  <img src="assets/logo.png" width="180" />
</div>

# Junkieee 🛡️

your personal foodpanda bodyguard. set a budget, forget about it — junkieee handles the rest silently.

---

## what it does

- opens foodpanda? junkieee silently scans your order history on its own — no buttons, no effort
- tiny dot on screen: green = chill, yellow = slow down, red = you're cooked
- budget blown? order gets blocked before it goes through
- set a custom date range, not just monthly
- location change won't reset your spending (we know the tricks)

---

## how it works

junkieee runs as an accessibility service in the background. it never touches your data, never leaves your phone. everything stays local.

```
1. set budget + date range in junkieee
2. open foodpanda like normal
3. junkieee scans orders silently
4. dot tells you where you stand
5. over budget → blocked. that's it.
```

---

## tech

- Kotlin
- Android Accessibility Service
- WindowManager overlay
- SharedPreferences
- Min SDK: Android 8.0 (API 26)

---

## for devs

```bash
git clone https://github.com/Maryam593/Junkieee.git
cd Junkieee
./gradlew assembleDebug
```

---

built for people who say "just one order" and then check their bank statement at the end of the month 💀

made with ❤️
