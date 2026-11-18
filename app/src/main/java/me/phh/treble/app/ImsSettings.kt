package me.phh.treble.app

import android.app.AlertDialog
import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.UserHandle
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ImsSettings : Settings {
    val requestNetwork = "key_ims_request_network"
    val createApn = "key_ims_create_apn"
    val forceEnableSettings = "key_ims_force_enable_setting"
    val installImsApk = "key_ims_install_apn"
    val useImsStack = "key_ims_use_imsstack"
    val imsStackPackage = "com.android.imsstack"
    val flossLangsdorffUrl = "https://github.com/clangsdorff/ims/releases/latest/download/FlossIms.apk"


    override fun enabled() = true
}

class ImsSettingsFragment : SettingsFragment() {
    override val preferencesResId = R.xml.pref_ims
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val createApn = findPreference<Preference>(ImsSettings.createApn)
        createApn!!.setOnPreferenceClickListener {
            Log.d("PHH", "Adding \"ims\" APN")

            val tm = activity.getSystemService(TelephonyManager::class.java)
            val operator = tm.simOperator
            if(tm.simOperator == null || tm.simOperator == "") {
                Log.d("PHH","No current carrier bailing out")
                return@setOnPreferenceClickListener true
            }

            val mcc = operator.substring(0, 3)
            val mnc = operator.substring(3, operator.length)
            Log.d("PHH", "Got mcc = $mcc and mnc = $mnc")

            val cr = activity.contentResolver

            val cursor = cr.query(
                    Uri.parse("content://telephony/carriers/current"),
                    arrayOf("name", "type", "apn", "carrier_enabled", "edited"),
                    "name = ?", arrayOf("PHH IMS"), null
            )

            if(cursor != null && cursor.moveToFirst()) {
                Log.d("PHH", "PHH IMS APN for this provider is already here with data $cursor")
                return@setOnPreferenceClickListener true
            }

            Log.d("PHH", "No APN called PHH IMS, adding our own")

            val cv = ContentValues()
            cv.put("name", "PHH IMS")
            cv.put("apn", "ims")
            cv.put("type", "ims")
            cv.put("edited", "1")
            cv.put("user_editable", "1")
            cv.put("user_visible", "1")
            cv.put("protocol", "IPV4V6")
            cv.put("roaming_protocol", "IPV6")
            cv.put("modem_cognitive", "1")
            cv.put("numeric", operator)
            cv.put("mcc", mcc)
            cv.put("mnc", mnc)

            val res = cr.insert(Uri.parse("content://telephony/carriers"), cv)
            Log.d("PHH", "Insert APN returned $res")

            return@setOnPreferenceClickListener true
        }

        // the package ships disabled, so its enabled state is the source of truth, not the preference
        val useImsStack = findPreference<SwitchPreferenceCompat>(ImsSettings.useImsStack)
        useImsStack!!.isChecked = Ims.isImsStackEnabled(activity!!.packageManager)
        useImsStack.setOnPreferenceChangeListener { _, newValue ->
            val ctxt = activity!!.applicationContext
            val state = if (newValue as Boolean) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                try {
                    ctxt.packageManager.setApplicationEnabledSetting(ImsSettings.imsStackPackage, state, 0)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(ctxt, "AOSP IMS stack is not part of this build", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceChangeListener false
                }
                Ims.selectImsOverlay(ctxt)
                Toast.makeText(ctxt, "Reboot to apply the IMS change", Toast.LENGTH_LONG).show()
                true
        }

        val installIms = findPreference<Preference>(ImsSettings.installImsApk)

        Log.d("PHH", "MTK P radio = ${Ims.gotMtkP}")
        Log.d("PHH", "MTK Q radio = ${Ims.gotMtkQ}")
        Log.d("PHH", "MTK R radio = ${Ims.gotMtkR}")
        Log.d("PHH", "MTK S radio = ${Ims.gotMtkS}")
        Log.d("PHH", "MTK AIDL radio = ${Ims.gotMtkAidl}")
        Log.d("PHH", "Qualcomm HIDL radio = ${Ims.gotQcomHidl}")
        Log.d("PHH", "Qualcomm AIDL radio = ${Ims.gotQcomAidl}")

        // the phh signature is always trusted here, so only the resigned apks install
        val signSuffix = "-resigned"

        val (url, message) =
                when {
                    (Ims.gotMtkR || Ims.gotMtkS || Ims.gotMtkAidl) && Build.VERSION.SDK_INT >= 34
                        -> Pair("https://treble.phh.me/ims-mtk-u$signSuffix.apk", "MediaTek R+ vendor")
                    Ims.gotMtkP -> Pair("https://treble.phh.me/stable/ims-mtk-p$signSuffix.apk", "MediaTek P vendor")
                    Ims.gotMtkQ -> Pair("https://treble.phh.me/stable/ims-mtk-q$signSuffix.apk", "MediaTek Q vendor")
                    Ims.gotMtkR -> Pair("https://treble.phh.me/stable/ims-mtk-r$signSuffix.apk", "MediaTek R vendor")
                    Ims.gotMtkS -> Pair("https://treble.phh.me/stable/ims-mtk-s$signSuffix.apk", "MediaTek S vendor")
                    (Ims.gotQcomHidl || Ims.gotQcomAidl) && Build.VERSION.SDK_INT >= 34
                        -> Pair("https://treble.phh.me/ims-caf-u$signSuffix.apk", "Qualcomm vendor")
                    Ims.gotQcomHidlMoto -> Pair("https://treble.phh.me/stable/ims-caf-moto$signSuffix.apk", "Qualcomm pre-S vendor (Motorola)")
                    Ims.gotQcomHidl -> Pair("https://treble.phh.me/stable/ims-q.64$signSuffix.apk", "Qualcomm pre-S vendor")
                    Ims.gotQcomAidl -> Pair("https://treble.phh.me/stable/ims-caf-s$signSuffix.apk", "Qualcomm S+ vendor")
                    else -> Pair("https://treble.phh.me/floss-ims-resigned.apk", "phh FlossIMS")
                }

        installIms!!.title = "Install IMS APK for $message"
        installIms!!.setOnPreferenceClickListener {
            val choices = arrayOf(
                    Pair(url, message),
                    Pair(ImsSettings.flossLangsdorffUrl, "Alternative FlossIMS"))
            AlertDialog.Builder(activity)
                    .setTitle("Download and install IMS APK")
                    .setItems(choices.map { it.second }.toTypedArray()) { _, which -> downloadAndInstallIms(choices[which].first) }
                    .show()
            return@setOnPreferenceClickListener true
        }
    }

    // DownloadManager fails with FILE_ERROR on some devices, stream straight into the session
    private fun downloadAndInstallIms(url: String) {
        val ctxt = activity!!.applicationContext
        val main = Handler(Looper.getMainLooper())
        Toast.makeText(ctxt, "Downloading IMS APK...", Toast.LENGTH_SHORT).show()

        Thread {
            val pi = ctxt.packageManager.packageInstaller
            var sessionId = -1
            try {
                sessionId = pi.createSession(
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                )
                pi.openSession(sessionId).use { session ->
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        if (conn.responseCode != HttpURLConnection.HTTP_OK)
                            throw IOException("HTTP ${conn.responseCode}")

                            session.openWrite("ims.apk", 0, conn.contentLengthLong).use { output ->
                                conn.inputStream.use { input ->
                                    input.copyTo(output, 512 * 1024)
                                }
                                session.fsync(output)
                            }
                    } finally {
                        conn.disconnect()
                    }

                    Misc.safeSetprop("persist.vendor.vilte_support", "0")

                    ctxt.registerReceiver(
                        object : BroadcastReceiver() {
                            override fun onReceive(p0: Context, result: Intent) {
                                ctxt.unregisterReceiver(this)
                                val status = result.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE
                                )
                                Log.e("PHH", "Apk install received $result status $status")
                                if (status != PackageInstaller.STATUS_SUCCESS) {
                                    val msg = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                                    Toast.makeText(p0, "IMS apk install failed: $msg", Toast.LENGTH_LONG).show()
                                    return
                                }
                                Ims.selectImsOverlay(p0)
                                Toast.makeText(p0, "IMS apk installed! You may now reboot.", Toast.LENGTH_LONG).show()
                            }
                        },
                        IntentFilter("me.phh.treble.app.ImsInstalled"),
                                          Context.RECEIVER_NOT_EXPORTED
                    )

                    session.commit(
                        PendingIntent.getBroadcast(
                            ctxt,
                            1,
                            Intent("me.phh.treble.app.ImsInstalled").setPackage(ctxt.packageName),
                                                   PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
                        ).intentSender
                    )
                }
            } catch (e: Exception) {
                Log.e("PHH", "IMS download failed", e)
                if (sessionId != -1) pi.abandonSession(sessionId)
                    main.post {
                        Toast.makeText(ctxt, "IMS download failed: " + e.message, Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }
}
