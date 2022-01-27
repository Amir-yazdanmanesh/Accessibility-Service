package com.yazdanmanesh.urlrestriction

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Browser
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.set


/**
 * Checking accessibility conditions on events and
 * Triggering accessibility actions like "back button"
 */
object AccessibilityUtils {
    private const val REDIRECT_TO = "http://www.404.net"
    private val previousUrlDetections: HashMap<String, Long> = HashMap()
    var packageName: String = ""
    private var foregroundAppName: String? = null
    private var browserConfig: SupportedBrowserConfig? = null

    fun filterBrowserURL(
        event: AccessibilityEvent,
        myAccessibilityService: MyAccessibilityService,
        getSupportedBrowsers : List<SupportedBrowserConfig>
    ) {
        try {
            //get accessibility node info
            val parentNodeInfo = event.source ?: return
            if (event.packageName != null) {
                packageName = event.packageName.toString()
            }

            //get foreground app name
            val packageManager: PackageManager = myAccessibilityService.packageManager
            try {
                val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                foregroundAppName = packageManager.getApplicationLabel(applicationInfo) as String
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            //get all the child views from the nodeInfo
            getChild(parentNodeInfo)

            //fetch urls from different browsers
            browserConfig = null
            for (supportedConfig in getSupportedBrowsers) {
                if (supportedConfig.packageName == packageName) {
                    browserConfig = supportedConfig
                }
            }
            //this is not supported browser, so exit
            if (browserConfig == null) {
                return
            }
            val capturedUrl =
                    captureUrl(parentNodeInfo, browserConfig)
            parentNodeInfo.recycle()
            //we can't find a url. Browser either was updated or opened page without url text field
            if (capturedUrl == null) {
                return
            }
            val eventTime = event.eventTime
            val detectionId = "$packageName, and url $capturedUrl"
            val lastRecordedTime: Long? =
                    if (previousUrlDetections.containsKey(detectionId)) previousUrlDetections[detectionId] else 0
            //some kind of redirect throttling
            if (eventTime - lastRecordedTime!! > 2000) {
                previousUrlDetections[detectionId] = eventTime
                analyzeCapturedUrl(
                        myAccessibilityService,
                        capturedUrl,
                        browserConfig?.packageName ?: ""
                )
            }
        } catch (e: Exception) {
            //ignored
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun getChild(info: AccessibilityNodeInfo) {
        val i = info.childCount
        for (p in 0 until i) {
            val n = info.getChild(p)
            if (n != null) {
                n.viewIdResourceName
                if (n.text != null) {
                    n.text.toString()
                }
                getChild(n)
            }
        }
    }



    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun captureUrl(info: AccessibilityNodeInfo, config: SupportedBrowserConfig?): String? {
        if (config == null) return null
        val nodes = info.findAccessibilityNodeInfosByViewId(config.addressBarId)
        if (nodes == null || nodes.size <= 0) {
            return null
        }
        val addressBarNodeInfo = nodes[0]
        var url: String? = null
        if (addressBarNodeInfo.text != null) {
            url = addressBarNodeInfo.text.toString()
        }
        addressBarNodeInfo.recycle()
        return url
    }

    private fun analyzeCapturedUrl(
        serviceMy: MyAccessibilityService,
        capturedUrl: String,
        browserPackage: String
    ) {
        if (capturedUrl.lowercase().startsWith(MainActivity.getMyRestrictedAddress())) {
            val replaced = REDIRECT_TO
            performRedirect(serviceMy, replaced, browserPackage)
        }
    }

    // we just reopen the browser app with our redirect url using service context
    private fun performRedirect(
        serviceMy: MyAccessibilityService,
        redirectUrl: String,
        browserPackage: String
    ) {
        var url = redirectUrl
        if (!redirectUrl.startsWith("https://")) {
            url = "https://$redirectUrl"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.setPackage(browserPackage)
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, browserPackage)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            serviceMy.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // the expected browser is not installed
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            serviceMy.startActivity(i)
        }
    }


}
