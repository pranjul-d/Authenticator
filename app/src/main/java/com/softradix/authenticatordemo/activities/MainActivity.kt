package com.softradix.authenticatordemo.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import com.softradix.authenticatordemo.R
import com.softradix.authenticatordemo.adapters.TokenListAdapter
import com.softradix.authenticatordemo.data.Token
import com.softradix.authenticatordemo.data.TokenDatabase
import com.softradix.authenticatordemo.databinding.ActivityMainBinding
import com.softradix.authenticatordemo.utils.TokenCalculator
import com.softradix.authenticatordemo.utils.Utilities.millisUntilNextUpdate
import com.softradix.authenticatordemo.utils.replaceAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.codec.binary.Base32


class MainActivity : AppCompatActivity() {

    private lateinit var listRefreshHandler: Handler
    private lateinit var adapter: TokenListAdapter
    private lateinit var tokenDatabase: TokenDatabase
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var binding: ActivityMainBinding
//    private const val REQUEST_CODE_SCANNER = 1

    private val tokens = mutableListOf<Token>()
    private val otpUpdate = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            millisUntilNextUpdate().let { millis ->
                listRefreshHandler.postDelayed(this, millis)
                startProgressBarAnimation(millis)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenDatabase = Room
            .databaseBuilder(applicationContext, TokenDatabase::class.java, "tokens")
            .build()

        adapter = TokenListAdapter(tokens, { v ->
            val itemPosition = binding.rvEntries.getChildLayoutPosition(v)
            val item = adapter.getItemAt(itemPosition)

            val code = TokenCalculator.TOTP_RFC6238(
                Base32().decode(item.secret),
                item.period ?: TokenCalculator.TOTP_DEFAULT_PERIOD,
                item.length,
                TokenCalculator.HashAlgorithm.valueOf(item.algorithm)
            )

            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", code))

            Toast.makeText(this, "Auth code copied to clipboard", Toast.LENGTH_SHORT).show()
        }, { v ->
            val itemPosition = binding.rvEntries.getChildLayoutPosition(v)

            val dialogView = layoutInflater.inflate(R.layout.dialog_add_token, null)
            val item = adapter.getItemAt(itemPosition)

            dialogView.findViewById<TextInputEditText>(R.id.etLabelInput).setText(item.label)
            dialogView.findViewById<TextInputEditText>(R.id.etIssuerInput).setText(item.issuer)
            dialogView.findViewById<TextInputEditText>(R.id.etKeyInput).setText(item.secret)

            MaterialAlertDialogBuilder(this)
                .setTitle("Edit account")
                .setView(dialogView)
                .setPositiveButton("Save") { _, _ ->
                    val newToken = adapter.getItemAt(itemPosition).copy(
                        label = dialogView.findViewById<TextInputEditText>(R.id.etLabelInput).text.toString(),
                        issuer = dialogView.findViewById<TextInputEditText>(R.id.etIssuerInput).text.toString(),
                        secret = dialogView.findViewById<TextInputEditText>(R.id.etKeyInput).text.toString()
                    )
                    updateToken(newToken, itemPosition)
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Delete") { _, _ ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Remove account")
                        .setMessage(
                            "Are you sure you want to delete the selected authentication token?\n\n" +
                                    "This will not remove 2FA from your account.\n" +
                                    "You will loose access to your account if you don't disable 2FA before deleting!"
                        )
                        .setPositiveButton("Delete") { _, _ ->
                            deleteToken(itemPosition)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .show()

            true
        })

        binding.rvEntries.adapter = adapter
        binding.rvEntries.layoutManager = LinearLayoutManager(this@MainActivity)

        listRefreshHandler = Handler(Looper.getMainLooper())

        binding.fab.addOnMenuItemClickListener { _, _, itemId ->
            when (itemId) {
                R.id.fab_scan -> {

                    Dexter.withContext(this).withPermission(Manifest.permission.CAMERA)
                        .withListener(object : PermissionListener {
                            override fun onPermissionGranted(p0: PermissionGrantedResponse?) {

                                activityResultLauncher.launch(
                                    Intent(
                                        this@MainActivity,
                                        ScannerActivity::class.java
                                    )
                                )

//                                startActivityForResult(
//                                    Intent(this@MainActivity, ScannerActivity::class.java),
//                                    1
//                                )
                            }

                            override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
                             p0?.requestedPermission
                            }

                            override fun onPermissionRationaleShouldBeShown(
                                p0: PermissionRequest?,
                                token: PermissionToken?
                            ) {
                                token?.continuePermissionRequest()
                            }
                        }).check()
                }
                R.id.fab_manual -> {
                    showManualInput()
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            tokens.addAll(tokenDatabase.tokenDao().getAll())
            binding.pbLoading.visibility = View.GONE

            adapter.notifyDataSetChanged()
        }

    }
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//
//        when (requestCode) {
//            1 -> {
//                if (resultCode == Activity.RESULT_OK && data != null) {
//                    try {
//                        addToken(Token.fromUri(Uri.parse(data.getStringExtra(ScannerActivity.EXTRA_STRING_URL))))
//                    } catch (e: Token.Companion.InvalidUriException) {
//                        MaterialAlertDialogBuilder(this)
//                            .setTitle("Invalid code")
//                            .setMessage("The code you scanned is invalid.\nError message: ${e.message}")
//                            .setPositiveButton("OK", null)
//                            .show()
//                    }
//                }
//            }
//        }
//    }
    override fun onPause() {
        super.onPause()
        listRefreshHandler.removeCallbacks(otpUpdate)
    }

    override fun onResume() {
        super.onResume()
        listRefreshHandler.post(otpUpdate)
    }

    private fun updateToken(token: Token, position: Int) {
        if (!token.isValid()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Invalid data")
                .setMessage("The item you want to add is invalid.\nPlease check your input values and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            tokenDatabase.tokenDao().update(token)

            CoroutineScope(Dispatchers.Main).launch {
                tokens.replaceAll(token) { it.id == token.id }

                adapter.notifyItemChanged(position)
            }
        }
    }

    var activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                try {
                    addToken(Token.fromUri(Uri.parse(result.data?.getStringExtra(ScannerActivity.EXTRA_STRING_URL))))
                } catch (e: Token.Companion.InvalidUriException) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Invalid code")
                        .setMessage("The code you scanned is invalid.\nError message: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }


        }

    private fun addToken(token: Token) {
        if (!token.isValid()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Invalid data")
                .setMessage("The item you want to add is invalid.\nPlease check your input values and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            tokenDatabase.tokenDao().insertAll(token)

            CoroutineScope(Dispatchers.Main).launch {
                tokens.add(token)
                adapter.notifyItemInserted(adapter.itemCount)
            }
        }
    }

    private fun deleteToken(position: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            tokenDatabase.tokenDao().delete(adapter.getItemAt(position))

            CoroutineScope(Dispatchers.Main).launch {
                tokens.removeAt(position)
                adapter.notifyItemRemoved(position)
            }
        }
    }

    private fun startProgressBarAnimation(duration: Long) {
        val durationScale: Float =
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)

        ObjectAnimator
            .ofInt(binding.pbInterval, "progress", (duration / 10).toInt(), 0).apply {
                setDuration((duration / durationScale).toLong())
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun showManualInput() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_token, null)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add account")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val newToken = Token(
                    label = dialogView.findViewById<TextInputEditText>(R.id.etLabelInput).text.toString(),
                    issuer = dialogView.findViewById<TextInputEditText>(R.id.etIssuerInput).text.toString(),
                    secret = dialogView.findViewById<TextInputEditText>(R.id.etKeyInput).text.toString()
                )
                addToken(newToken)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}