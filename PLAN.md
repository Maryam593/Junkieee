# Junkie App — Project Plan

## Idea
Ek Android app jo FoodPanda pe fazool kharchi rokay. Monthly budget set karo — app silently track kare, budget khatam ho to FoodPanda block ho jaye with daddy jokes.

## GitHub
**https://github.com/Maryam593/Junkie**

## Tech Stack
- Language: Kotlin
- Min SDK: API 26 (Android 8.0)
- Build: Gradle 8.9 + JVM 21
- Key API: Android Accessibility Service + WindowManager Overlay
- FoodPanda Package: `com.global.foodpanda.android`
- Storage: SharedPreferences (auto-resets every month)

## Project Location
`D:\Developer\FoodGuard\`

## Build Command
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\New\AppData\Local\Android\Sdk"
Set-Location "D:\Developer\FoodGuard"
& "D:\Developer\gradle-8.9\gradle-8.9\bin\gradle.bat" assembleDebug
```

## Install Command
```powershell
& "C:\Users\New\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "D:\Developer\FoodGuard\app\build\outputs\apk\debug\app-debug.apk"
```

---

## Features (Completed ✅)

### 1. Budget Setup
- User monthly food budget set karta hai
- Har mahine 1 tarikh ko automatically reset
- SharedPreferences mein persist hota hai (phone restart pe bhi safe)

### 2. Floating Budget Dot
- FoodPanda open hote hi screen pe dot appear (bottom center)
- GREEN = budget theek (80%+ bacha)
- YELLOW = budget khatam honay wala (20% se kam)
- RED = budget bilkul khatam
- FoodPanda band hote hi dot gayab
- FLAG_NOT_TOUCHABLE — clicks pass through hoti hain

### 3. My Orders Auto Scan
- "Scan Karo" button → scan mode ON
- FoodPanda My Orders screen automatically scan
- Auto-scroll karta hai khud
- "Scan Khatam" → budget mein add
- Limitation: duplicate prices Set se deduplicate hoti hain

### 4. Manual Spent Amount
- FoodPanda ki order history se total dekh ke manually enter karo
- Scan se zyada accurate option
- "Is Mahine Ka Kharch Set Karo" card

### 5. Order Confirmation Auto-Deduct
- Order confirm screen detect karo (accessibility)
- Budget se automatically deduct
- Dot color update hota hai

### 6. Place Order Block
- Budget khatam hone par FoodPanda kholne pe block
- "Place Order" button detect karo aur back karo
- Cart/checkout screen mein accessibility events block hain (FoodPanda security)
- Workaround: FoodPanda open hone pe hi block karo

### 7. Daddy Jokes (Decline Messages)
- "Budget khatam! Roti aur achar khao — personality banti hai!"
- "Error 404: Paisa Not Found. Ghar ka khana try karo!"
- "FoodPanda ne tumhara wallet dekha aur rona shuru kar diya"
- "Aaj ke liye bas! Mama ke haath ka khana free hota hai!"
- "Wallet empty, FoodPanda blocked. Sachchi dost yehi karti hai!"

### 8. App Icon
- Cute burger logo (Junkie branding)
- 5 sizes: mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi

### 9. GitHub
- Repo: github.com/Maryam593/Junkie
- README with logo, features, daddy jokes
- Git author: Maryam593 <maryams91101@gmail.com>

---

## Known Limitations

### FoodPanda Cart Screen Accessibility Block
- FoodPanda ka cart/checkout screen accessibility events block karta hai
- "Place Order" button text directly detect nahi hota
- Workaround: budget exceed hone par FoodPanda open hote hi home bhej deta hai

### Scan Duplicate Issue
- My Orders scan mein same price wale orders ek baar count hote hain (Set)
- Fix: Manual spent amount enter karo for accuracy

---

## Files Structure
```
FoodGuard/
├── assets/
│   └── logo.png                    — Junkie burger logo
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/foodguard/app/
│   │   ├── MainActivity.kt          — Budget UI + scan + manual spent
│   │   ├── BudgetManager.kt         — Budget logic + SharedPrefs + daddy jokes
│   │   └── FoodGuardAccessibilityService.kt — Core service + dot + block
│   └── res/
│       ├── layout/activity_main.xml
│       ├── drawable/card_background.xml
│       ├── drawable/input_background.xml
│       ├── mipmap-*/ic_launcher.png — App icon (5 sizes)
│       ├── values/strings.xml
│       └── xml/accessibility_service_config.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
├── local.properties
├── gradlew.bat
├── README.md
├── PLAN.md
└── gradle/wrapper/gradle-wrapper.properties
```

---

## Features (Completed ✅) — New

### 10. Budget Period (Date Range)
- User khud budget period set karta hai (maslan 1 May → 31 May)
- DatePicker se start date aur end date choose karo
- Period save hone par spending reset hoti hai
- Period khatam hone par auto-reset (agla period start hone tak)
- Fallback: agar period set nahi, purana month-based reset

### 11. Spending Initialized Guard + Auto-Scan
- Pehli baar FoodPanda kholne par (period mein):
  - Agar spending initialize nahi — auto-scan mode start
  - App khud "My Orders" dhundh kar click karne ki koshish karta hai
  - "Scanning orders..." banner dikhata hai
- Scan complete hone par `spendingInitialized = true` — phir normal use
- Period display summary mein: "✓ Ready" ya "⚠ Kharch set karo!"

### 12. Date-Aware Order Scanning
- Scan mein orders ki dates bhi detect hoti hain (e.g., "23 May", "May 23", "Today", "Yesterday")
- Sirf selected period ki dates wale orders count hote hain
- Duplicate fix: ab amount+date se dedup hoti hai (Rs. 1200 on May 10 ≠ Rs. 1200 on May 15)
- Auto-finish: scroll band hone par (bottom reached) automatically scan khatam

---

## Pending / Ideas

- [ ] Emergency unlock / budget badhao button
- [ ] Spending history screen (kitna kharch kab hua)
- [ ] Notification jab budget 20% bacha ho
- [ ] Signed release APK banao (Play Store ke liye)
- [ ] Better date detection if FoodPanda changes format
