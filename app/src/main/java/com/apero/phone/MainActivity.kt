package com.apero.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.apero.phone.data.usecase.impl.FetchSmsDetailUseCaseImpl
import com.apero.phone.data.usecase.impl.FetchSmsListUseCaseImpl
import com.apero.phone.data.usecase.impl.GetListContactUseCaseImpl
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSIONS_REQUEST_READ_CONTACTS = 100
        private const val PERMISSIONS_REQUEST_READ_SMS = 104
        private const val PERMISSIONS_REQUEST_PHONE = 105
        private const val REQUEST_CODE_SET_DEFAULT_DIALER = 106
    }

    private val getListContactUseCaseImpl by lazy { GetListContactUseCaseImpl(this) }
    private val fetchSmsListUseCase by lazy { FetchSmsListUseCaseImpl(this) }
    private val fetchSmsDetailUseCase by lazy { FetchSmsDetailUseCaseImpl(this) }

    // Add to your existing permissions
    private val PHONE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnGetContact = findViewById<AppCompatButton>(R.id.btnContact)
        val btnGetMessage = findViewById<AppCompatButton>(R.id.btnMessage)

        btnGetContact.setOnClickListener {
            requestContactPermission()
        }

        btnGetMessage.setOnClickListener {
            requestSmsPermission()
        }

        // Handle SMS/MMS compose intents
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SENDTO -> {
                val recipient = intent.dataString?.let {
                    it.replace("smsto:", "").replace("sms:", "")
                        .replace("mmsto:", "").replace("mms:", "")
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                
                if (!recipient.isNullOrEmpty()) {
                    // Launch your compose UI with the recipient and optional text
                    // You would typically start your compose activity/fragment here
                    Log.d("MainActivity", "Compose message to: $recipient")
                    Log.d("MainActivity", "Initial text: $text")
                }
            }
        }
    }

    private fun requestContactPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                PERMISSIONS_REQUEST_READ_CONTACTS
            )
        } else {
            getContacts()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_READ_CONTACTS -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permission granted
                    getContacts()
                } else {
                    // Permission denied
                    Toast.makeText(
                        this,
                        "Permission denied to read contacts",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            PERMISSIONS_REQUEST_READ_SMS -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    getSmsMessages()
                } else {
                    Toast.makeText(
                        this,
                        "Permission denied to read SMS",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            PERMISSIONS_REQUEST_PHONE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    requestDefaultDialerRole()
                } else {
                    Toast.makeText(
                        this,
                        "Permission denied to set as default dialer",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS),
                PERMISSIONS_REQUEST_READ_SMS
            )
        } else {
            getSmsMessages()
        }
    }

    private fun getContacts() {
        lifecycleScope.launch {
            val data = getListContactUseCaseImpl.getContact()
            Log.d("CONTACT_LIST", "$data")
        }
    }

    private fun getSmsMessages() {
        lifecycleScope.launch {
            // Get all SMS conversations
            val smsList = fetchSmsListUseCase.getSmsInbox()
            Log.d("SMS_LIST", smsList.toString())
        }
    }

    private fun requestPhonePermissions() {
        if (!hasPhonePermissions()) {
            ActivityCompat.requestPermissions(
                this,
                PHONE_PERMISSIONS,
                PERMISSIONS_REQUEST_PHONE
            )
        }
    }

    private fun hasPhonePermissions(): Boolean {
        return PHONE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestDefaultDialerRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        REQUEST_CODE_SET_DEFAULT_DIALER
                    )
                }
            }
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_DIALER)
        }
    }
}