package com.bagridmaster.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ApplicationPermissionsTest {
    @Test fun foregroundServicesKeepRequiredPermissionsWithoutNotificationOptIn() {
        val manifest = sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map {
            nodes.item(it).attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue
        }.toSet()
        assertFalse("Notification runtime permission is not needed for these services",
            "android.permission.POST_NOTIFICATIONS" in permissions)
        assertTrue(permissions.containsAll(setOf(
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        )))
    }
}
