package io.github.mangi.eta.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageChooserContractTest {
    @Test
    fun multipleImagesContractRoutesDirectlyToGooglePhotosWhenInstalled() {
        val context = RuntimeEnvironment.getApplication()
        registerGooglePhotosPicker(context)

        val intent = ChooseMultipleImagesContract().createIntent(
            context = context,
            input = Unit,
        )

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals("com.google.android.apps.photos", intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun multipleImagesContractFallsBackToActionPickChooserWhenGooglePhotosNotInstalled() {
        val intent = ChooseMultipleImagesContract().createIntent(
            context = RuntimeEnvironment.getApplication(),
            input = Unit,
        )

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val target = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            ?: error("缺少 EXTRA_INTENT")
        assertEquals(Intent.ACTION_PICK, target.action)
        assertEquals("image/*", target.type)
        assertNull(target.`package`)
        assertTrue(target.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun singleImageContractRoutesDirectlyToGooglePhotosWithoutMultipleFlagWhenInstalled() {
        val context = RuntimeEnvironment.getApplication()
        registerGooglePhotosPicker(context)

        val intent = ChooseSingleImageContract().createIntent(
            context = context,
            input = Unit,
        )

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals("com.google.android.apps.photos", intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun multipleImagesContractParsesBothClipDataAndSingleDataUriDeduplicated() {
        val resolver = org.robolectric.RuntimeEnvironment.getApplication().contentResolver
        val uri1 = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/mediakey/1")
        val uri2 = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/mediakey/2")
        val resultIntent = Intent().apply {
            data = uri1
            clipData = ClipData.newUri(resolver, "first", uri1).apply {
                addItem(ClipData.Item(uri2))
            }
        }

        val parsed = ChooseMultipleImagesContract().parseResult(Activity.RESULT_OK, resultIntent)

        assertEquals(listOf(uri1, uri2), parsed)
    }

    private fun registerGooglePhotosPicker(context: android.content.Context) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(
            "com.google.android.apps.photos",
            "com.google.android.apps.photos.picker.external.ExternalPickerActivity",
        )
        shadowPm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                applicationInfo = ApplicationInfo().apply {
                    packageName = component.packageName
                }
            },
        )
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/*")
            },
        )
    }
}
