package com.foodguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.util.*

private const val TAG = "JUNKIE"

class FoodGuardAccessibilityService : AccessibilityService() {

    private val budget by lazy { BudgetManager(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var overlayShown = false
    private var lastDetectedAmount = 0f
    private var foodPandaInForeground = false

    // Floating dot
    private var dotView: android.view.View? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    // Scan mode
    var isScanMode = false
    private var isAutoScan = false
    private val seenOrderKeys = mutableSetOf<String>()
    private var scanTotal = 0f
    private var noNewItemsCount = 0
    private var navigationAttempted = false

    // Order tracking — capture on click, deduct only when checkout screen disappears
    private var pendingOrderAmount = 0f
    private var waitingForConfirmation = false
    private var lastScanTime = 0L
    private var lastSwipeTime = 0L

    // Marquee banner shown during scan
    private var scanMarqueeView: TextView? = null

    // Scan timeout runnable
    private val scanTimeoutRunnable = Runnable {
        if (isScanMode) {
            Log.d(TAG, "Scan timeout — force finishing")
            finishScan()
        }
    }

    // Every 1s: if FoodPanda is no longer active window, hide dot
    private val dotCheckRunnable = object : Runnable {
        override fun run() {
            if (foodPandaInForeground) {
                val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
                if (activePkg != "com.global.foodpanda.android") {
                    foodPandaInForeground = false
                    hideBudgetDot()
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    // Periodic scroll — fires every 2.5s during scan even if events stop
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (!isScanMode) return
            val root = rootInActiveWindow ?: run {
                handler.postDelayed(this, 2500)
                return
            }
            val allText = extractAllText(root)
            val onOrdersScreen = allText.contains("past orders", ignoreCase = true) ||
                    allText.contains("order history", ignoreCase = true) ||
                    (allText.contains("delivered on", ignoreCase = true) && allText.contains("Rs.", ignoreCase = true))
            if (onOrdersScreen) {
                gestureSwipeUp()
            } else if (isAutoScan && !navigationAttempted) {
                navigationAttempted = true
                tryNavigateToOrders(root)
            }
            handler.postDelayed(this, 2500)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.foodguard.START_SCAN") startManualScan()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(scanReceiver, IntentFilter("com.foodguard.START_SCAN"), RECEIVER_NOT_EXPORTED)
        handler.postDelayed(dotCheckRunnable, 1000)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (pkg == "com.global.foodpanda.android") {
            if (!foodPandaInForeground) {
                foodPandaInForeground = true
                Log.d(TAG, "FoodPanda foreground. budget=${budget.monthlyBudget} periodActive=${budget.isPeriodActive} initialized=${budget.spendingInitialized} scanMode=$isScanMode")
                if (budget.monthlyBudget > 0f && budget.isPeriodActive && !budget.spendingInitialized && !isScanMode) {
                    Log.d(TAG, "Auto-scan scheduled in 1.5s")
                    handler.postDelayed({ startAutoScan() }, 1500)
                }
            }
            showBudgetDot()
        } else {
            // Koi aur app foreground mein — dot hatao
            if (foodPandaInForeground) {
                foodPandaInForeground = false
                hideBudgetDot()
            }
            return
        }

        // Budget over — ghar bhejo
        if (!isScanMode && budget.isBudgetOver) {
            showOverlay(BudgetManager.DADDY_JOKES.random(), Color.parseColor("#C62828"), true)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val root = rootInActiveWindow ?: return
            val allText = extractAllText(root)

            if (isScanMode) {
                val onOrdersScreen = allText.contains("my orders", ignoreCase = true) ||
                        allText.contains("past orders", ignoreCase = true) ||
                        allText.contains("order history", ignoreCase = true)
                if (onOrdersScreen) {
                    scanOrderHistory(root, allText)
                } else if (isAutoScan && !navigationAttempted) {
                    tryNavigateToOrders(root)
                }
            } else {
                blockPlaceOrderIfNeeded(root)

                // If user just clicked "Place Order", check if checkout screen is now gone
                if (waitingForConfirmation && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val checkoutKeywords = listOf("place order", "pay now", "ادائیگی", "place my order")
                    val checkoutStillVisible = checkoutKeywords.any { allText.contains(it, ignoreCase = true) }
                    if (!checkoutStillVisible && pendingOrderAmount > 0f) {
                        val a = pendingOrderAmount
                        pendingOrderAmount = 0f
                        waitingForConfirmation = false
                        budget.deduct(a)
                        budget.spendingInitialized = true
                        Log.d(TAG, "Order confirmed (checkout gone): Rs.$a | remaining=${budget.remaining}")
                        showBudgetDot()
                        if (budget.isBudgetOver) {
                            showOverlay("Rs. ${a.toInt()} ka order!\nBudget KHATAM! 🔒\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), true)
                        } else {
                            showOverlay("Order track!\nRs. ${a.toInt()} deduct\nBacha: Rs. ${budget.remaining.toInt()}", Color.parseColor("#2E7D32"), false)
                        }
                    }
                } else {
                    checkForOrderConfirmation(allText)
                }
            }
        }

        if (!isScanMode && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickText = event.text?.joinToString(" ")?.lowercase() ?: ""
            val placeOrderWords = listOf("place order", "confirm", "pay now", "proceed", "checkout", "ادائیگی", "order now", "place my order", "submit order")
            if (placeOrderWords.any { clickText.contains(it) }) {
                if (budget.isBudgetOver) {
                    showOverlay("BOOM! Budget khatam!\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), true)
                } else {
                    // Capture amount now but DON'T deduct — wait for checkout screen to disappear
                    val root2 = rootInActiveWindow
                    if (root2 != null) {
                        val amount = extractTotalAmount(extractAllText(root2))
                        if (amount != null && amount > 100f) {
                            pendingOrderAmount = amount
                            waitingForConfirmation = true
                            Log.d(TAG, "Place order clicked: Rs.$amount captured — waiting for confirmation screen")
                            // Auto-cancel after 45s if no confirmation arrives
                            handler.postDelayed({
                                if (waitingForConfirmation) {
                                    waitingForConfirmation = false
                                    pendingOrderAmount = 0f
                                    Log.d(TAG, "Order confirmation timeout — cancelled (user may have gone back)")
                                }
                            }, 45000)
                        }
                    }
                }
            }
        }
    }

    // ─── Floating Budget Dot ───────────────────────────────────────────

    private fun showBudgetDot() {
        val color = when {
            budget.isBudgetOver -> Color.parseColor("#F44336")
            budget.remaining < budget.monthlyBudget * 0.2f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#4CAF50")
        }
        handler.post {
            if (dotView == null) {
                val dot = android.view.View(this)
                val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(3, Color.WHITE) }
                dot.background = circle
                val params = WindowManager.LayoutParams(
                    32, 32,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; x = 0; y = 80 }
                try { wm.addView(dot, params); dotView = dot } catch (_: Exception) {}
            } else {
                val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(3, Color.WHITE) }
                dotView?.background = circle
            }
        }
    }

    private fun hideBudgetDot() {
        handler.post {
            try { dotView?.let { wm.removeView(it) } } catch (_: Exception) {}
            dotView = null
        }
    }

    // ─── Place Order Block ─────────────────────────────────────────────

    private fun blockPlaceOrderIfNeeded(root: AccessibilityNodeInfo) {
        if (!budget.isBudgetOver) return
        val keywords = listOf("place order", "confirm order", "pay now", "proceed to pay",
            "checkout", "ادائیگی", "order now", "pay", "confirm", "submit", "proceed")
        if (findButtonByText(root, keywords)) {
            showOverlay("Budget khatam!\nOrder nahi ho sakta 🔒\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), false)
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500)
        }
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (node.isClickable && keywords.any { text.contains(it) || desc.contains(it) }) return true
        for (i in 0 until node.childCount) {
            if (findButtonByText(node.getChild(i) ?: continue, keywords)) return true
        }
        return false
    }

    // ─── Order Confirmation Detection ─────────────────────────────────

    private fun checkForOrderConfirmation(allText: String) {
        // Already handled via order-click detection
        if (pendingOrderAmount > 0f) return

        val isConfirmScreen =
            allText.contains("order confirmed", ignoreCase = true) ||
            allText.contains("order placed", ignoreCase = true) ||
            allText.contains("order received", ignoreCase = true) ||
            allText.contains("your order is on its way", ignoreCase = true) ||
            allText.contains("order is confirmed", ignoreCase = true) ||
            allText.contains("thank you for your order", ignoreCase = true) ||
            allText.contains("being prepared", ignoreCase = true) ||
            allText.contains("order tracking", ignoreCase = true) ||
            allText.contains("آپ کا آرڈر", ignoreCase = true) ||
            allText.contains("آرڈر موصول", ignoreCase = true)

        if (!isConfirmScreen) return
        val amount = extractTotalAmount(allText) ?: return
        if (amount == lastDetectedAmount) return
        lastDetectedAmount = amount

        budget.deduct(amount)
        budget.spendingInitialized = true
        showBudgetDot()
        Log.d(TAG, "Confirmation screen detected: Rs.$amount deducted")

        if (budget.isBudgetOver) {
            showOverlay("Rs. ${amount.toInt()} ka order!\nBudget KHATAM! 🔒\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), true)
        } else {
            showOverlay("Order tracked!\nRs. ${amount.toInt()} deduct\nBacha: Rs. ${budget.remaining.toInt()}", Color.parseColor("#2E7D32"), false)
        }
    }

    // ─── Auto Navigation ──────────────────────────────────────────────

    // Account tab click karo, phir 2s baad My Orders click karo
    private fun tryNavigateToOrders(root: AccessibilityNodeInfo) {
        navigationAttempted = true
        // Tap Account tab — only search in bottom 25% of screen (nav bar area)
        val tapped = tapBottomNavAccount(root)
        Log.d(TAG, "navigateToOrders: account tab tap=$tapped")
        scheduleOrdersClick(attempt = 0)
    }

    // Tap Account tab by finding "account"/"me" node only in the bottom navigation area
    private fun tapBottomNavAccount(root: AccessibilityNodeInfo): Boolean {
        val minY = (resources.displayMetrics.heightPixels * 0.75f).toInt()
        return gestureClickInArea(root, listOf("account", "profile", "me", "akun", "my account"), minY = minY)
    }

    // gestureClick with y-position filter — only tap nodes at or below minY
    private fun gestureClickInArea(node: AccessibilityNodeInfo, keywords: List<String>, minY: Int): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            var target: AccessibilityNodeInfo? = node
            repeat(5) {
                val b = Rect()
                target?.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0 && b.centerY() >= minY) {
                    gestureTap(b.centerX().toFloat(), b.centerY().toFloat())
                    Log.d(TAG, "gestureClickInArea: tapped (${b.centerX()},${b.centerY()}) text='$text' minY=$minY")
                    return true
                }
                target = target?.parent
            }
        }
        for (i in 0 until node.childCount) {
            if (gestureClickInArea(node.getChild(i) ?: continue, keywords, minY)) return true
        }
        return false
    }

    private fun scheduleOrdersClick(attempt: Int) {
        if (!isScanMode || attempt >= 10) {
            Log.d(TAG, "scheduleOrdersClick: giving up after $attempt attempts")
            return
        }
        val delay = if (attempt == 0) 3000L else 2000L
        handler.postDelayed({
            if (!isScanMode) return@postDelayed
            val r = rootInActiveWindow ?: run { scheduleOrdersClick(attempt + 1); return@postDelayed }
            val allText = extractAllText(r)

            // Already on Orders screen?
            val onOrdersScreen = allText.contains("past orders", ignoreCase = true) ||
                    allText.contains("order history", ignoreCase = true) ||
                    (allText.contains("delivered on", ignoreCase = true) && allText.contains("Rs.", ignoreCase = true))
            if (onOrdersScreen) {
                Log.d(TAG, "scheduleOrdersClick: on orders screen ✓ (attempt $attempt)")
                return@postDelayed
            }

            Log.d(TAG, "scheduleOrdersClick: attempt=$attempt screen='${allText.take(150)}'")

            // Home page check first (takes priority)
            val onHomePage = allText.contains("pandamart", ignoreCase = true) ||
                    allText.contains("homechefs", ignoreCase = true) ||
                    allText.contains("pick-up", ignoreCase = true) ||
                    allText.contains("new restaurants", ignoreCase = true)

            // Account page — FoodPanda shows Favourites, Wallet, pandapro on profile page
            val onAccountPage = !onHomePage && (
                    allText.contains("favourites", ignoreCase = true) ||
                    allText.contains("wallet", ignoreCase = true) ||
                    allText.contains("pandapro", ignoreCase = true) ||
                    allText.contains("log out", ignoreCase = true) ||
                    allText.contains("view profile", ignoreCase = true))

            if (onAccountPage) {
                // On Account page — tap Orders item
                val tapped = gestureClickNode(r, listOf("orders", "my orders", "order history"))
                        || findAndClickNode(r, listOf("orders", "my orders", "order history"))
                Log.d(TAG, "scheduleOrdersClick: Account page → Orders tap=$tapped")
                if (!tapped) scheduleOrdersClick(attempt + 1)
            } else {
                // Home/other — tap Account tab (bottom nav only)
                val tapped = tapBottomNavAccount(r)
                Log.d(TAG, "scheduleOrdersClick: home/other → Account tab tap=$tapped onHome=$onHomePage")
                scheduleOrdersClick(attempt + 1)
            }
        }, delay)
    }

    // Coordinate-based tap — works even when accessibility ACTION_CLICK fails
    private fun gestureClickNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            // Try this node's bounds, then walk up parents if 0x0
            var target: AccessibilityNodeInfo? = node
            repeat(5) {
                val b = Rect()
                target?.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0) {
                    gestureTap(b.centerX().toFloat(), b.centerY().toFloat())
                    Log.d(TAG, "gestureClick: tapped (${b.centerX()},${b.centerY()}) text='$text'")
                    return true
                }
                target = target?.parent
            }
            Log.d(TAG, "gestureClick: found '$text' but all bounds=0x0")
        }
        for (i in 0 until node.childCount) {
            if (gestureClickNode(node.getChild(i) ?: continue, keywords)) return true
        }
        return false
    }

    private fun gestureTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun gestureSwipeUp() {
        val now = System.currentTimeMillis()
        if (now - lastSwipeTime < 2000L) return
        lastSwipeTime = now
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, m.heightPixels * 0.75f)
            lineTo(cx, m.heightPixels * 0.25f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.d(TAG, "gestureSwipeUp performed")
    }

    // Scan khatam hone ke baad Home tab pe wapas jao
    private fun tryNavigateToHome(root: AccessibilityNodeInfo) {
        val keywords = listOf("home", "discover", "explore", "feed", "restaurants")
        findAndClickNode(root, keywords)
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            // Try clicking this node directly (works even if isClickable=false in some impls)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            // If not clickable, try walking up to find clickable parent (up to 3 levels)
            var p = node.parent
            repeat(3) {
                if (p != null) {
                    if (p!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    p = p?.parent
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (findAndClickNode(node.getChild(i) ?: continue, keywords)) return true
        }
        return false
    }

    // ─── My Orders Scan ───────────────────────────────────────────────

    private fun startManualScan() {
        isScanMode = true
        isAutoScan = false
        seenOrderKeys.clear()
        scanTotal = 0f
        noNewItemsCount = 0
        navigationAttempted = false
        handler.post { scrollRunnable.run() }
        showOverlay("Scan shuru!\nFoodPanda mein My Orders pe jao\nMain khud scan karunga", Color.parseColor("#1565C0"), false)
    }

    private fun startAutoScan() {
        if (isScanMode) return
        Log.d(TAG, "startAutoScan()")
        isScanMode = true
        isAutoScan = true
        seenOrderKeys.clear()
        scanTotal = 0f
        noNewItemsCount = 0
        navigationAttempted = false
        showScanMarquee()
        handler.post { scrollRunnable.run() }
        // 90s timeout — agar navigate nahi hua toh bhi scan band ho
        handler.postDelayed(scanTimeoutRunnable, 90000)
        val root = rootInActiveWindow
        if (root != null) tryNavigateToOrders(root)
    }

    private fun scanOrderHistory(root: AccessibilityNodeInfo, allText: String) {
        val orders = extractOrdersWithDates(allText)
        Log.d(TAG, "scanOrderHistory: found ${orders.size} orders on screen, seenSoFar=${seenOrderKeys.size}, total=${scanTotal}")
        var newFound = false

        for ((amount, dateMs) in orders) {
            val key = "${amount.toLong()}_$dateMs"
            if (seenOrderKeys.add(key)) {
                scanTotal += amount
                newFound = true
                Log.d(TAG, "  NEW order: Rs.$amount date=$dateMs")
            }
        }

        if (newFound) {
            noNewItemsCount = 0
            showBanner("Scanning... Rs. ${scanTotal.toInt()} (${seenOrderKeys.size} orders)")
        } else {
            noNewItemsCount++
            Log.d(TAG, "  No new items, count=$noNewItemsCount/6")
            if (noNewItemsCount >= 6 && isScanMode) {
                noNewItemsCount = 0
                handler.postDelayed({ if (isScanMode) finishScan() }, 800)
            }
        }
    }

    private fun extractOrdersWithDates(allText: String): List<Pair<Float, Long>> {
        val months = mapOf(
            "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
            "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
        )
        val mp = months.keys.joinToString("|") { it.replaceFirstChar { c -> c.uppercaseChar() } }

        // Only match "Delivered on DD MMM" or "Delivered on Today/Yesterday" — ignore all other dates
        val deliveredRegex = Regex(
            """Delivered on (?:(\d{1,2})\s+($mp)|($mp)\s+(\d{1,2})|(Today|Yesterday))""",
            RegexOption.IGNORE_CASE
        )
        val amountRegex = Regex("""(?:Rs\.?|PKR)\s*([\d,]+(?:\.\d+)?)""")

        // Only "Delivered on" dates — positions mapped to timestamps
        val deliveredDates: List<Pair<Int, Long>> = deliveredRegex.findAll(allText).mapNotNull { m ->
            val cal = Calendar.getInstance()
            when {
                m.groupValues[5].isNotEmpty() -> {
                    if (m.groupValues[5].equals("yesterday", ignoreCase = true))
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                    cal.set(Calendar.HOUR_OF_DAY, 12)
                }
                m.groupValues[1].isNotEmpty() -> {
                    val day = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val month = months[m.groupValues[2].lowercase()] ?: return@mapNotNull null
                    cal.set(Calendar.MONTH, month); cal.set(Calendar.DAY_OF_MONTH, day); cal.set(Calendar.HOUR_OF_DAY, 12)
                }
                else -> {
                    val day = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                    val month = months[m.groupValues[3].lowercase()] ?: return@mapNotNull null
                    cal.set(Calendar.MONTH, month); cal.set(Calendar.DAY_OF_MONTH, day); cal.set(Calendar.HOUR_OF_DAY, 12)
                }
            }
            // Zero out sub-hour fields so same date always gives same key
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            Pair(m.range.first, cal.timeInMillis)
        }.toList()

        val hasPeriod = budget.periodStartDate > 0L && budget.periodEndDate > 0L

        return amountRegex.findAll(allText).mapNotNull { m ->
            val amount = m.groupValues[1].replace(",", "").toFloatOrNull() ?: return@mapNotNull null
            if (amount < 100f) return@mapNotNull null
            val pos = m.range.first

            // Skip cancelled orders — "Cancelled on" appears within 300 chars after amount
            val afterAmount = allText.substring(pos, minOf(allText.length, pos + 300))
            if (afterAmount.contains("Cancelled on", ignoreCase = true)) return@mapNotNull null

            // Closest "Delivered on" date within ±500 chars of the amount
            val closestDelivered = deliveredDates
                .filter { it.first in (pos - 500)..(pos + 500) }
                .minByOrNull { Math.abs(it.first - pos) }
            val dateMs = closestDelivered?.second ?: 0L

            Log.d(TAG, "  amount=Rs.$amount pos=$pos deliveredDate=$dateMs inPeriod=${if (hasPeriod && dateMs > 0L) dateMs in budget.periodStartDate..budget.periodEndDate else true}")

            if (hasPeriod && dateMs > 0L && dateMs !in budget.periodStartDate..budget.periodEndDate) return@mapNotNull null
            Pair(amount, dateMs)
        }.toList()
    }

    fun finishScan() {
        Log.d(TAG, "finishScan: total=Rs.$scanTotal orders=${seenOrderKeys.size}")
        isScanMode = false
        isAutoScan = false
        noNewItemsCount = 0
        handler.removeCallbacks(scrollRunnable)
        handler.removeCallbacks(scanTimeoutRunnable)
        hideScanMarquee()

        if (scanTotal > 0f) {
            budget.spentAmount = scanTotal
            budget.spendingInitialized = true
            showBudgetDot()
            val isOver = budget.isBudgetOver
            showOverlay(
                if (isOver) "Scan done! Rs. ${scanTotal.toInt()}\nBudget KHATAM! 🔒\n${BudgetManager.DADDY_JOKES.random()}"
                else "Scan complete!\nIs period: Rs. ${scanTotal.toInt()}\nBacha: Rs. ${budget.remaining.toInt()}",
                if (isOver) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"),
                isOver
            )
            // Scan ho gaya — wapas Home pe jao
            if (!isOver) {
                handler.postDelayed({
                    val root = rootInActiveWindow
                    if (root != null) tryNavigateToHome(root)
                }, 2000)
            }
        } else {
            showOverlay("Koi order nahi mila is period mein", Color.parseColor("#F57C00"), false)
        }
        seenOrderKeys.clear()
        scanTotal = 0f
    }

    // ─── Scan Marquee Banner ──────────────────────────────────────────

    private fun showScanMarquee() {
        handler.post {
            if (scanMarqueeView != null) return@post
            val tv = TextView(this).apply {
                text = "      Orders track ho rahi hain... please wait      Orders track ho rahi hain...      "
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DD1565C0"))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
                setPadding(0, 14, 0, 14)
                gravity = Gravity.CENTER_VERTICAL
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }
            try { wm.addView(tv, params); scanMarqueeView = tv } catch (_: Exception) {}
        }
    }

    private fun hideScanMarquee() {
        handler.post {
            try { scanMarqueeView?.let { wm.removeView(it) } } catch (_: Exception) {}
            scanMarqueeView = null
        }
    }

    // ─── Overlay Helpers ──────────────────────────────────────────────

    private fun showBanner(msg: String) {
        handler.post {
            val wm2 = getSystemService(WINDOW_SERVICE) as WindowManager
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC1565C0"))
                setPadding(30, 15, 30, 15)
            }
            layout.addView(TextView(this).apply {
                text = msg; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            try { wm2.addView(layout, params) } catch (_: Exception) {}
            handler.postDelayed({ try { wm2.removeView(layout) } catch (_: Exception) {} }, 1800)
        }
    }

    private fun showOverlay(message: String, bgColor: Int, isDecline: Boolean) {
        if (overlayShown) return
        overlayShown = true
        handler.post {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor); setPadding(48, 48, 48, 48)
            }
            layout.addView(TextView(this).apply {
                text = message; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
            })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }
            try { wm.addView(layout, params) } catch (_: Exception) {}
            handler.postDelayed({
                try { wm.removeView(layout) } catch (_: Exception) {}
                overlayShown = false
                if (isDecline) performGlobalAction(GLOBAL_ACTION_HOME)
            }, if (isDecline) 3500L else 2500L)
        }
    }

    private fun extractTotalAmount(text: String): Float? {
        val totalPattern = Regex("""(?:total|grand total|order total)[^\d]{0,20}(?:Rs\.?|PKR)?\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        totalPattern.find(text)?.groupValues?.get(1)?.replace(",", "")?.toFloatOrNull()?.let { if (it > 50f) return it }
        return Regex("""(?:Rs\.?|PKR)\s*([\d,]+)""")
            .findAll(text).mapNotNull { it.groupValues[1].replace(",", "").toFloatOrNull() }
            .filter { it > 100f }.maxOrNull()
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) sb.append(extractAllText(node.getChild(i) ?: continue))
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scrollRunnable)
        handler.removeCallbacks(dotCheckRunnable)
        handler.removeCallbacks(scanTimeoutRunnable)
        hideBudgetDot()
        hideScanMarquee()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        instance = null
    }

    override fun onInterrupt() {}

    companion object {
        var instance: FoodGuardAccessibilityService? = null
    }
}
