package com.foodguard.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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

    // Periodic scroll — fires every 2s during scan even if events stop
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (!isScanMode) return
            val root = rootInActiveWindow ?: run {
                handler.postDelayed(this, 2000)
                return
            }
            val allText = extractAllText(root)
            val onOrdersScreen = allText.contains("my orders", ignoreCase = true) ||
                    allText.contains("past orders", ignoreCase = true) ||
                    allText.contains("order history", ignoreCase = true)
            if (onOrdersScreen) {
                performAutoScroll(root)
            } else if (isAutoScan && !navigationAttempted) {
                navigationAttempted = true
                tryNavigateToOrders(root)
            }
            handler.postDelayed(this, 2000)
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
                checkForOrderConfirmation(allText)
            }
        }

        if (!isScanMode && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickText = event.text?.joinToString(" ")?.lowercase() ?: ""
            val placeOrderWords = listOf("place order", "confirm", "pay now", "proceed", "checkout", "ادائیگی", "order now")
            if (placeOrderWords.any { clickText.contains(it) } && budget.isBudgetOver) {
                showOverlay("BOOM! Budget khatam!\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), true)
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
        val isConfirmScreen =
            allText.contains("order confirmed", ignoreCase = true) ||
            allText.contains("order placed", ignoreCase = true) ||
            allText.contains("your order is on its way", ignoreCase = true) ||
            allText.contains("order is confirmed", ignoreCase = true) ||
            allText.contains("آپ کا آرڈر", ignoreCase = true)

        if (!isConfirmScreen) return
        val amount = extractTotalAmount(allText) ?: return
        if (amount == lastDetectedAmount) return
        lastDetectedAmount = amount

        budget.deduct(amount)
        showBudgetDot()

        if (budget.isBudgetOver) {
            showOverlay("Rs. ${amount.toInt()} ka order!\nBudget KHATAM! 🔒\n${BudgetManager.DADDY_JOKES.random()}", Color.parseColor("#C62828"), true)
        } else {
            showOverlay("Order track!\nRs. ${amount.toInt()} deduct\nBacha: Rs. ${budget.remaining.toInt()}", Color.parseColor("#2E7D32"), false)
        }
    }

    // ─── Auto Navigation ──────────────────────────────────────────────

    // Account tab click karo, phir 2s baad My Orders click karo
    private fun tryNavigateToOrders(root: AccessibilityNodeInfo) {
        navigationAttempted = true
        // Agar My Orders already visible hai (account page already open) to seedha click karo
        if (findAndClickNode(root, listOf("my orders", "order history"))) {
            Log.d(TAG, "navigateToOrders: My Orders found directly, clicked")
            return
        }
        // Warna Account/Profile tab click karo
        val wentToAccount = findAndClickNode(root, listOf("account", "profile", "my account", "me", "akun"))
        Log.d(TAG, "navigateToOrders: account click=$wentToAccount")
        if (wentToAccount) {
            handler.postDelayed({
                val r = rootInActiveWindow ?: return@postDelayed
                val found = findAndClickNode(r, listOf("my orders", "order history", "orders"))
                Log.d(TAG, "navigateToOrders: My Orders click after delay=$found")
            }, 2000)
        }
    }

    // Scan khatam hone ke baad Home tab pe wapas jao
    private fun tryNavigateToHome(root: AccessibilityNodeInfo) {
        val keywords = listOf("home", "discover", "explore", "feed", "restaurants")
        findAndClickNode(root, keywords)
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (node.isClickable && keywords.any { text.contains(it) || desc.contains(it) }) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
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
        showBanner("Orders check ho rahi hain...")
        handler.post { scrollRunnable.run() }
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
                return
            }
        }

        performAutoScroll(root)
    }

    private fun extractOrdersWithDates(allText: String): List<Pair<Float, Long>> {
        val months = mapOf(
            "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
            "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
        )
        val mp = months.keys.joinToString("|") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        val dateRegex = Regex("""(\d{1,2})\s+($mp)|($mp)\s+(\d{1,2})|(Today|Yesterday)""", RegexOption.IGNORE_CASE)
        val amountRegex = Regex("""(?:Rs\.?|PKR)\s*([\d,]+)""")

        val datePosns: List<Pair<Int, Long>> = dateRegex.findAll(allText).mapNotNull { m ->
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
            Pair(m.range.first, cal.timeInMillis)
        }.toList()

        val hasPeriod = budget.periodStartDate > 0L && budget.periodEndDate > 0L

        return amountRegex.findAll(allText).mapNotNull { m ->
            val amount = m.groupValues[1].replace(",", "").toFloatOrNull() ?: return@mapNotNull null
            if (amount < 100f) return@mapNotNull null
            val pos = m.range.first
            val closestDate = datePosns.filter { it.first < pos }.maxByOrNull { it.first }
            val dateMs = closestDate?.second ?: 0L
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

    private fun performAutoScroll(root: AccessibilityNodeInfo) {
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.isScrollable) {
                child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return
            }
            performAutoScroll(child)
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
        hideBudgetDot()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        instance = null
    }

    override fun onInterrupt() {}

    companion object {
        var instance: FoodGuardAccessibilityService? = null
    }
}
