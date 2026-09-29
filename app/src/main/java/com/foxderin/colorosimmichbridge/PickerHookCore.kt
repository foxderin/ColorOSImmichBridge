package com.foxderin.colorosimmichbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Hook logic shared by both Xposed entry points ([BridgeModule], [LegacyBridgeModule]).
 *
 * The ColorOS picker's 文件 tab sources (蓝牙/下载/微信/…) are `pl.b` beans held
 * in a list field of `MainExpandableAdapter` (populated before the group list
 * reaches `parentchild.b#t/s`). We append our own bean there (native row
 * rendering), intercept `PickerCategoryFragment$i.onSuperAppItemClick` for our
 * package, and forward the pick result through the host activity to the caller.
 */
object PickerHookCore {

    const val TAG = "ColorOSImmichBridge"

    /** Base adapter whose t/s deliver the group list right before rendering. */
    const val PARENT_CHILD_ADAPTER = "com.oplus.filemanager.parentchild.adapter.b"

    /** Renders the 来源 rows (receives the pl.b list last before display). */
    const val EXPANDABLE_ADAPTER = "com.oplus.filemanager.main.adapter.MainExpandableAdapter"

    /** Dispatches source-row clicks in the picker. */
    const val SUPER_APP_CLICK = "com.oplus.filemanager.picker.category.PickerCategoryFragment\$i"
    const val SUPER_APP_BEAN = "pl.b"
    const val FRAGMENT_ACTIVITY = "androidx.fragment.app.FragmentActivity"

    /** Request code between the ColorOS picker and our Immich picker activity. */
    const val REQUEST_IMMICH = 0xB1D6

    const val MODULE_PACKAGE = "com.foxderin.colorosimmichbridge"
    const val IMMICH_PICKER_ACTIVITY = "com.foxderin.colorosimmichbridge.ImmichPickerActivity"

    const val PICKER_ACTIVITY = "com.oplus.filemanager.picker.PickerActivity"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** Which media kinds the caller accepts, from the picker's incoming intent. */
    class MediaSpec(val images: Boolean, val videos: Boolean) {
        fun offersMedia() = images || videos

        companion object {
            const val EXTRA_IMAGES = "immich.extra.ALLOW_IMAGES"
            const val EXTRA_VIDEOS = "immich.extra.ALLOW_VIDEOS"

            fun from(intent: Intent?): MediaSpec {
                var images = false
                var videos = false
                var constrained = false
                fun add(mime: String?) {
                    mime ?: return
                    constrained = true
                    when {
                        mime == "*/*" -> { images = true; videos = true }
                        mime.startsWith("image/") -> images = true
                        mime.startsWith("video/") -> videos = true
                    }
                }
                val extras = intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                val type = intent?.type
                if (!extras.isNullOrEmpty()) {
                    // Platform convention: setType("*/*") + EXTRA_MIME_TYPES
                    // carries the real constraints; a wildcard placeholder
                    // type must not widen them.
                    extras.forEach { add(it) }
                    if (type != null && type != "*/*") add(type)
                } else {
                    add(type)
                }
                // No type constraint (e.g. ACTION_PICK without type) -> allow both.
                return if (constrained) MediaSpec(images, videos) else MediaSpec(true, true)
            }
        }
    }

    /** Spec of the picker session currently showing the source rows. */
    @Volatile
    private var currentSpec = MediaSpec(true, true)

    /** Hooked from PickerActivity#onCreate: captures the caller's MIME constraints. */
    fun onPickerCreated(activity: Activity?) {
        refreshSpec(activity?.intent)
    }

    /** Hooked from PickerActivity#onNewIntent: the activity's getIntent() is
     *  still the ORIGINAL intent inside onNewIntent, so read the argument. */
    fun onPickerNewIntent(intent: Intent?) {
        refreshSpec(intent)
    }

    private fun refreshSpec(intent: Intent?) {
        intent ?: return
        currentSpec = MediaSpec.from(intent)
        log("picker spec images=${currentSpec.images} videos=${currentSpec.videos} type=${intent.type}")
    }

    // --------------------------------------------------------------- registration

    /**
     * Appends our Immich bean to the adapter's `pl.b` row list (the 来源
     * rows), once per list instance. Called from parentchild.b#t/s hooks.
     */
    fun onSuperAppRows(adapter: Any, classLoader: ClassLoader) {
        if (!currentSpec.offersMedia()) return
        for (f in adapter.javaClass.declaredFields) {
            if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            val rows = f.get(adapter) as? ArrayList<*> ?: continue
            val first = rows.firstOrNull() ?: continue
            if (first.javaClass.name != SUPER_APP_BEAN) continue
            // The 来源 row list has package-like o() values; skip the tag list.
            if (callStringGetter(first, "o").isNullOrEmpty()) continue

            var present = false
            for (row in rows) {
                if (row != null && MODULE_PACKAGE == callStringGetter(row, "o")) {
                    present = true
                    break
                }
            }
            if (present) return

            val bean = makeSuperAppBean(classLoader) ?: return
            @Suppress("UNCHECKED_CAST")
            (rows as java.util.ArrayList<Any?>).add(bean)
            log("Registered Immich source row (field=${f.name}, size=${rows.size})")
            return
        }
    }

    /** A `pl.b` source bean for Immich, built reflectively in the host process. */
    private fun makeSuperAppBean(classLoader: ClassLoader): Any? {
        return try {
            val cls = classLoader.loadClass(SUPER_APP_BEAN)
            val bean = cls.getConstructor(
                Integer::class.java, String::class.java, Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Drawable::class.java, Integer::class.java,
            ).newInstance(null, "Immich", 0, 0L, 0L, loadModuleIcon(appContext()), null)
            cls.getMethod("I", String::class.java).invoke(bean, MODULE_PACKAGE)
            // Non-empty placeholder: the picker filters out beans with empty g() paths.
            cls.getMethod("A", Array<String>::class.java).invoke(bean, arrayOf("Immich"))
            // Cloud-source type id (renderers require r() in 3000000..4000000);
            // high value to avoid colliding with the OEM counter starting at 3000001.
            cls.getMethod("M", Integer::class.java).invoke(bean, Integer.valueOf(3999999))
            cls.getMethod("N", Boolean::class.javaPrimitiveType).invoke(bean, true)
            bean
        } catch (t: Throwable) {
            log("makeSuperAppBean failed: $t")
            null
        }
    }

    // --------------------------------------------------------------- diagnostics

    /**
     * Before-hook for the render entry (MainExpandableAdapter#L0): replaces
     * the incoming row list with a copy that includes our Immich bean. The
     * argument may be immutable or a fresh copy of the adapter's field, so
     * always build a new list instead of appending in place.
     */
    fun onRenderList(args: Array<Any?>, index: Int, classLoader: ClassLoader) {
        if (!currentSpec.offersMedia()) return
        val rows = args.getOrNull(index) as? List<*> ?: run {
            log("onRenderList: arg$index not a list")
            return
        }
        if (rows.firstOrNull()?.javaClass?.name != SUPER_APP_BEAN) return
        for (row in rows) {
            if (row != null && MODULE_PACKAGE == callStringGetter(row, "o")) return
        }
        val bean = makeSuperAppBean(classLoader) ?: return
        val copy = ArrayList<Any?>(rows.size + 1)
        copy.addAll(rows)
        copy.add(bean)
        args[index] = copy
        log("Registered Immich into render list (size=${copy.size})")
    }

    // --------------------------------------------------------------- clicks

    /**
     * Before-hook for PickerCategoryFragment$i#onSuperAppItemClick. Returns
     * true when the clicked bean is ours (picker launched, original call must
     * be skipped).
     */
    fun onSuperAppClick(inner: Any, data: Any?): Boolean {
        if (data == null || MODULE_PACKAGE != callStringGetter(data, "o")) return false
        // Stale row from an earlier session in a reused adapter list: swallow.
        if (!currentSpec.offersMedia()) {
            log("onSuperAppClick: caller accepts no media, ignoring")
            return true
        }
        return try {
            // The static inner class holds the fragment in an R8-renamed
            // synthetic field; find it by type, not by name.
            var activity: Activity? = null
            for (f in inner.javaClass.declaredFields) {
                if (!f.type.name.contains("PickerCategoryFragment")) continue
                f.isAccessible = true
                val fragment = f.get(inner) ?: continue
                activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                if (activity != null) break
            }
            if (activity == null) {
                log("onSuperAppClick: no activity")
                return false
            }
            val intent = Intent().setClassName(MODULE_PACKAGE, IMMICH_PICKER_ACTIVITY)
                .putExtra(MediaSpec.EXTRA_IMAGES, currentSpec.images)
                .putExtra(MediaSpec.EXTRA_VIDEOS, currentSpec.videos)
            activity.startActivityForResult(intent, REQUEST_IMMICH)
            log("Launched Immich picker from source row")
            true
        } catch (t: Throwable) {
            log("onSuperAppClick failed: $t")
            false
        }
    }

    /** Forwards our picker's result back to the app that invoked the ColorOS picker. */
    fun onPickerActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_IMMICH) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.setResult(Activity.RESULT_OK, data)
            log("Forwarded Immich pick result")
        } else {
            activity.setResult(Activity.RESULT_CANCELED)
        }
        activity.finish()
    }

    // --------------------------------------------------------------- helpers

    private fun callStringGetter(target: Any, name: String): String? {
        return try {
            target.javaClass.getMethod(name).invoke(target) as? String
        } catch (t: Throwable) {
            null
        }
    }


    private fun appContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        return at.getMethod("currentApplication").invoke(null) as Context
    }

    @Volatile
    private var moduleIcon: Drawable? = null

    /** Our launcher icon, loaded from the (unprivileged) module package context. */
    private fun loadModuleIcon(context: Context): Drawable? {
        moduleIcon?.let { return it }
        return try {
            val moduleContext = context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            val id = moduleContext.resources.getIdentifier("ic_launcher", "mipmap", MODULE_PACKAGE)
            if (id == 0) null else moduleContext.getDrawable(id).also { moduleIcon = it }
        } catch (t: Throwable) {
            log("loadModuleIcon failed: $t")
            null
        }
    }
}
