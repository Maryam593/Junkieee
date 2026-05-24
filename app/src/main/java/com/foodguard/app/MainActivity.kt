package com.foodguard.app

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var budget: BudgetManager
    private val handler = Handler(Looper.getMainLooper())
    private val sdf = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private val startCal = Calendar.getInstance()
    private val endCal = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        budget = BudgetManager(this)

        requestOverlayPermission()

        // Load saved period dates into local calendars
        if (budget.periodStartDate > 0) startCal.timeInMillis = budget.periodStartDate
        if (budget.periodEndDate > 0) endCal.timeInMillis = budget.periodEndDate
        updateDateDisplays()

        updateUI()

        // ── Budget Save ──────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSaveBudget).setOnClickListener {
            val input = findViewById<EditText>(R.id.etBudget).text.toString()
            val amount = input.toFloatOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "Sahi amount daalo!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            budget.monthlyBudget = amount
            updateUI()
            Toast.makeText(this, "Budget set! Rs. ${amount.toInt()} / period", Toast.LENGTH_LONG).show()
        }

        // ── Manual Spent Save ────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSetSpent).setOnClickListener {
            val input = findViewById<EditText>(R.id.etManualSpent).text.toString()
            val amount = input.toFloatOrNull()
            if (amount == null || amount < 0) {
                Toast.makeText(this, "Sahi amount daalo!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            budget.spentAmount = amount
            budget.spendingInitialized = true
            findViewById<EditText>(R.id.etManualSpent).text.clear()
            updateUI()
            Toast.makeText(this, "Kharch set! Rs. ${amount.toInt()} — ab FoodPanda chalao! ✓", Toast.LENGTH_LONG).show()
        }

        // ── Scan Start ───────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnStartScan).setOnClickListener {
            val service = FoodGuardAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Pehle FoodGuard Service enable karo!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            sendBroadcast(Intent("com.foodguard.START_SCAN"))
            Toast.makeText(this, "Ab khud FoodPanda kholo aur My Orders pe jao!", Toast.LENGTH_LONG).show()
        }

        // ── Scan Finish ──────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnFinishScan).setOnClickListener {
            val service = FoodGuardAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Service enable karo pehle!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            service.finishScan()
            handler.postDelayed({ updateUI() }, 1000)
        }

        // ── Service Enable ───────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Budget Period Date Pickers ───────────────────────────────────
        findViewById<TextView>(R.id.tvStartDate).setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                startCal.set(y, m, d, 0, 0, 0)
                updateDateDisplays()
            }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        findViewById<TextView>(R.id.tvEndDate).setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                endCal.set(y, m, d, 23, 59, 59)
                updateDateDisplays()
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        // ── Period Save ──────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSavePeriod).setOnClickListener {
            if (startCal.timeInMillis >= endCal.timeInMillis) {
                Toast.makeText(this, "End date, start date ke baad honi chahiye!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            budget.setPeriod(startCal.timeInMillis, endCal.timeInMillis)
            updateUI()
            Toast.makeText(
                this,
                "Period set!\n${sdf.format(startCal.time)} → ${sdf.format(endCal.time)}\nAb pehle is period ka kharch daalo!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun updateDateDisplays() {
        findViewById<TextView>(R.id.tvStartDate).text = sdf.format(startCal.time)
        findViewById<TextView>(R.id.tvEndDate).text = sdf.format(endCal.time)
    }

    private fun updateUI() {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val serviceOn = enabledServices.contains("com.foodguard.app", ignoreCase = true)

        findViewById<TextView>(R.id.tvBudget).text =
            "Monthly Budget: Rs. ${budget.monthlyBudget.toInt()}"
        findViewById<TextView>(R.id.tvSpent).text =
            "Kharch hua: Rs. ${budget.spentAmount.toInt()}"
        findViewById<TextView>(R.id.tvRemaining).text =
            "Bacha hai: Rs. ${budget.remaining.toInt()}"

        // Period + initialization status
        val periodText = if (budget.periodStartDate > 0L) {
            val initStatus = if (budget.spendingInitialized) "✓ Ready" else "⚠ Kharch set karo!"
            "Period: ${budget.periodDisplay}   $initStatus"
        } else {
            "Period: Set nahi hua (neeche set karo)"
        }
        findViewById<TextView>(R.id.tvPeriod).text = periodText
        val periodColor = when {
            budget.periodStartDate == 0L -> 0xFF9E9E9E.toInt()
            budget.spendingInitialized -> 0xFF43A047.toInt()
            else -> 0xFFF57C00.toInt()
        }
        findViewById<TextView>(R.id.tvPeriod).setTextColor(periodColor)

        if (serviceOn) {
            findViewById<TextView>(R.id.tvStatus).text = "Service: ON - FoodPanda monitor ho raha hai"
            findViewById<TextView>(R.id.tvStatus).setTextColor(0xFF43A047.toInt())
            findViewById<MaterialButton>(R.id.btnEnableService).text = "Service Enabled ✓"
        } else {
            findViewById<TextView>(R.id.tvStatus).text = "Service: OFF - Neeche button dabao!"
            findViewById<TextView>(R.id.tvStatus).setTextColor(0xFFE53935.toInt())
            findViewById<MaterialButton>(R.id.btnEnableService).text = "FoodGuard Enable Karo"
        }
    }
}
