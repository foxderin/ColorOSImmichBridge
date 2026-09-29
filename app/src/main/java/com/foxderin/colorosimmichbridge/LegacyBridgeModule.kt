package com.foxderin.colorosimmichbridge

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Legacy entry (de.robv.android.xposed api:82), declared in assets/xposed_init.
 * Used by frameworks that do not load META-INF/xposed/java_init.list.
 */
class LegacyBridgeModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.coloros.filemanager") return
        PickerHookCore.log("Immich bridge (legacy) loaded into ${lpparam.packageName}")
        installHooks(lpparam.classLoader)
    }

    private fun installHooks(classLoader: ClassLoader) {
        // Capture the caller's MIME constraints when the picker starts.
        try {
            XposedHelpers.findAndHookMethod(PickerHookCore.PICKER_ACTIVITY, classLoader,
                "onCreate", android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        PickerHookCore.onPickerCreated(param.thisObject as? Activity)
                    }
                })
            XposedHelpers.findAndHookMethod(PickerHookCore.PICKER_ACTIVITY, classLoader,
                "onNewIntent", Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        PickerHookCore.onPickerNewIntent(param.args.getOrNull(0) as? Intent)
                    }
                })
            PickerHookCore.log("hooked PickerActivity#onCreate")
        } catch (t: Throwable) {
            PickerHookCore.log("hook PickerActivity#onCreate failed: $t")
        }

        try {
            val base = XposedHelpers.findClass(PickerHookCore.PARENT_CHILD_ADAPTER, classLoader)
            val registrar = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    PickerHookCore.onSuperAppRows(param.thisObject, classLoader)
                }
            }
            XposedHelpers.findAndHookMethod(base, "t", java.util.List::class.java, registrar)
            XposedHelpers.findAndHookMethod(base, "s",
                java.util.List::class.java, java.util.List::class.java, registrar)
            PickerHookCore.log("hooked parentchild.b#t/s")
        } catch (t: Throwable) {
            PickerHookCore.log("hook parentchild.b failed: $t")
        }

        // Inject our row at the render entry (L0 clears and re-adds per emission).
        try {
            val expandable = XposedHelpers.findClass(PickerHookCore.EXPANDABLE_ADAPTER, classLoader)
            XposedHelpers.findAndHookMethod(expandable, "L0", java.util.List::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        PickerHookCore.onRenderList(param.args, 0, classLoader)
                    }
                })
            PickerHookCore.log("hooked MainExpandableAdapter#L0")
        } catch (t: Throwable) {
            PickerHookCore.log("hook L0 failed: $t")
        }

        // Route clicks on our source row to the Immich picker.
        try {
            val clickClass = XposedHelpers.findClass(PickerHookCore.SUPER_APP_CLICK, classLoader)
            val beanClass = XposedHelpers.findClass(PickerHookCore.SUPER_APP_BEAN, classLoader)
            XposedHelpers.findAndHookMethod(clickClass, "onSuperAppItemClick", beanClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (PickerHookCore.onSuperAppClick(param.thisObject, param.args.getOrNull(0))) {
                            param.result = null
                        }
                    }
                })
            PickerHookCore.log("hooked PickerCategoryFragment\$i#onSuperAppItemClick")
        } catch (t: Throwable) {
            PickerHookCore.log("hook onSuperAppItemClick failed: $t")
        }

        // Forward our picker's result through the host activity to the caller.
        try {
            val fragmentActivity = XposedHelpers.findClass(PickerHookCore.FRAGMENT_ACTIVITY, classLoader)
            XposedHelpers.findAndHookMethod(fragmentActivity, "onActivityResult",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        PickerHookCore.log("onActivityResult dispatched req=${param.args.getOrNull(0)} to ${param.thisObject?.javaClass?.simpleName}")
                        val activity = param.thisObject as? Activity ?: return
                        PickerHookCore.onPickerActivityResult(
                            activity,
                            param.args[0] as Int,
                            param.args[1] as Int,
                            param.args[2] as? Intent,
                        )
                    }
                })
            PickerHookCore.log("hooked ${PickerHookCore.FRAGMENT_ACTIVITY}#onActivityResult")
        } catch (t: Throwable) {
            PickerHookCore.log("onActivityResult hook failed: $t")
        }
    }
}
