package com.foodguard.app

import android.app.DatePickerDialog
import android.content.Intent  // needed for Settings intents
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var budget: BudgetManager
    private val sdf = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private val startCal = Calendar.getInstance()
    private val endCal = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        budget = BudgetManager(this)

        requestOverlayPermission()

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

        // ── Service Enable ───────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Reset All ────────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnResetAll).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Sab Reset Karo?")
                .setMessage("Budget, period, aur sab kharch data delete ho jaayega. Pakka?")
                .setPositiveButton("Haan, Reset") { _, _ ->
                    budget.resetAll()
                    startCal.timeInMillis = System.currentTimeMillis()
                    endCal.timeInMillis = System.currentTimeMillis()
                    updateDateDisplays()
                    updateUI()
                    Toast.makeText(this, "Sab saaf! Fresh start.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Nahi", null)
                .show()
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
                "Period set! ${sdf.format(startCal.time)} → ${sdf.format(endCal.time)}\nAb FoodPanda kholo — Junkie baaki kaam karega!",
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
            "Rs. ${budget.monthlyBudget.toInt()}"
        findViewById<TextView>(R.id.tvSpent).text =
            "Rs. ${budget.spentAmount.toInt()}"
        findViewById<TextView>(R.id.tvRemaining).text =
            "Rs. ${budget.remaining.toInt()}"

        val periodText = if (budget.periodStartDate > 0L) {
            val initStatus = if (budget.spendingInitialized) "✓ Ready" else "⚠ Scan pending"
            "${budget.periodDisplay}   $initStatus"
        } else {
            "Period not set — configure below"
        }
        findViewById<TextView>(R.id.tvPeriod).text = periodText
        val periodColor = when {
            budget.periodStartDate == 0L -> 0xFF9E9E9E.toInt()
            budget.spendingInitialized -> 0xFF43A047.toInt()
            else -> 0xFFF57C00.toInt()
        }
        findViewById<TextView>(R.id.tvPeriod).setTextColor(periodColor)

        if (serviceOn) {
            findViewById<TextView>(R.id.tvStatus).text = "Service: ON — monitoring FoodPanda"
            findViewById<TextView>(R.id.tvStatus).setTextColor(0xFF32D74B.toInt())
            findViewById<MaterialButton>(R.id.btnEnableService).text = "ENABLED ✓"
        } else {
            findViewById<TextView>(R.id.tvStatus).text = "Service: OFF — tap below to enable"
            findViewById<TextView>(R.id.tvStatus).setTextColor(0xFFFF453A.toInt())
            findViewById<MaterialButton>(R.id.btnEnableService).text = "ENABLE JUNKIE"
        }
    }
}
