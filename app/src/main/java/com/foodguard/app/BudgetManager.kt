package com.foodguard.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

class BudgetManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("food_guard", Context.MODE_PRIVATE)

    var monthlyBudget: Float
        get() = prefs.getFloat("monthly_budget", 0f)
        set(value) = prefs.edit().putFloat("monthly_budget", value).apply()

    var spentAmount: Float
        get() {
            checkReset()
            return prefs.getFloat("spent_amount", 0f)
        }
        set(value) = prefs.edit().putFloat("spent_amount", value).apply()

    var periodStartDate: Long
        get() = prefs.getLong("period_start", 0L)
        set(value) = prefs.edit().putLong("period_start", value).apply()

    var periodEndDate: Long
        get() = prefs.getLong("period_end", 0L)
        set(value) = prefs.edit().putLong("period_end", value).apply()

    // Has user set spending for this period (manual or scan)?
    var spendingInitialized: Boolean
        get() = prefs.getBoolean("spending_initialized", false)
        set(value) = prefs.edit().putBoolean("spending_initialized", value).apply()

    val remaining: Float
        get() = (monthlyBudget - spentAmount).coerceAtLeast(0f)

    val isBudgetOver: Boolean
        get() = monthlyBudget > 0f && spentAmount >= monthlyBudget

    val isPeriodActive: Boolean
        get() {
            if (periodStartDate == 0L || periodEndDate == 0L) return false
            val now = System.currentTimeMillis()
            return now in periodStartDate..periodEndDate
        }

    val periodDisplay: String
        get() {
            if (periodStartDate == 0L || periodEndDate == 0L) return "Set nahi hua"
            val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
            return "${fmt.format(Date(periodStartDate))} — ${fmt.format(Date(periodEndDate))}"
        }

    fun deduct(amount: Float) {
        spentAmount += amount
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    // Set new period — spending resets, needs re-initialization
    fun setPeriod(startMs: Long, endMs: Long) {
        prefs.edit()
            .putLong("period_start", startMs)
            .putLong("period_end", endMs)
            .putFloat("spent_amount", 0f)
            .putBoolean("spending_initialized", false)
            .apply()
    }

    private fun checkReset() {
        if (periodStartDate > 0L && periodEndDate > 0L) {
            // Period-based reset: jab period khatam ho, auto reset
            val now = System.currentTimeMillis()
            val lastResetEnd = prefs.getLong("last_reset_period_end", 0L)
            if (now > periodEndDate && lastResetEnd != periodEndDate) {
                prefs.edit()
                    .putFloat("spent_amount", 0f)
                    .putBoolean("spending_initialized", false)
                    .putLong("last_reset_period_end", periodEndDate)
                    .apply()
            }
        } else {
            // Fallback: old month-based reset (jab period set nahi)
            val currentMonth = SimpleDateFormat("MM-yyyy", Locale.getDefault()).format(Date())
            val savedMonth = prefs.getString("saved_month", "")
            if (currentMonth != savedMonth) {
                prefs.edit()
                    .putFloat("spent_amount", 0f)
                    .putString("saved_month", currentMonth)
                    .apply()
            }
        }
    }

    companion object {
        val DADDY_JOKES = listOf(
            "Budget khatam! Roti aur achar khao — personality banti hai! 😂",
            "Error 404: Paisa Not Found. Ghar ka khana try karo!",
            "FoodPanda ne tumhara wallet dekha aur rona shuru kar diya 😭",
            "Aaj ke liye bas! Mama ke haath ka khana free hota hai!",
            "Wallet empty, FoodPanda blocked. Sachchi dost yehi karti hai! 🔒"
        )
    }
}
