package com.foxderin.colorosimmichbridge

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern entry (libxposed API 102), declared in META-INF/xposed/java_init.list.
 */
class BridgeModule : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.coloros.filemanager") return
        PickerHookCore.log("Immich bridge (modern) loaded into ${param.packageName}")
        installHooks(param.defaultClassLoader)
    }

    private fun installHooks(classLoader: ClassLoader) {
        // Capture the caller's MIME constraints when the picker starts.
        hookExact(PickerHookCore.PICKER_ACTIVITY, "onCreate",
            arrayOf("android.os.Bundle"), classLoader) { chain ->
            PickerHookCore.onPickerCreated(chain.thisObject as? Activity)
            chain.proceed()
        }
        hookExact(PickerHookCore.PICKER_ACTIVITY, "onNewIntent",
            arrayOf("android.content.Intent"), classLoader) { chain ->
            PickerHookCore.onPickerNewIntent(chain.args.getOrNull(0) as? Intent)
            chain.proceed()
        }

        hookExact(PickerHookCore.PARENT_CHILD_ADAPTER, "t",
            arrayOf("java.util.List"), classLoader) { chain ->
            PickerHookCore.onSuperAppRows(chain.thisObject, classLoader)
            chain.proceed()
        }
        hookExact(PickerHookCore.PARENT_CHILD_ADAPTER, "s",
            arrayOf("java.util.List", "java.util.List"), classLoader) { chain ->
            PickerHookCore.onSuperAppRows(chain.thisObject, classLoader)
            chain.proceed()
        }
        // Inject our row at the render entry (L0 clears and re-adds per emission).
        hookExact(PickerHookCore.EXPANDABLE_ADAPTER, "L0",
            arrayOf("java.util.List"), classLoader) { chain ->
            val arr = chain.args.toTypedArray()
            PickerHookCore.onRenderList(arr, 0, classLoader)
            chain.proceed(arr)
        }

        // Route clicks on our source row to the Immich picker.
        hookExact(
            PickerHookCore.SUPER_APP_CLICK, "onSuperAppItemClick",
            arrayOf(PickerHookCore.SUPER_APP_BEAN), classLoader,
        ) { chain ->
            val handled = PickerHookCore.onSuperAppClick(chain.thisObject, chain.args.getOrNull(0))
            PickerHookCore.log("click hook fired, handled=$handled")
            if (handled) {
                null
            } else {
                chain.proceed()
            }
        }
        // Same row click outside the picker fragment: other t7.q implementers
        // (main UI, album set, single picker…) dispatch source-row clicks too.
        // The bean identity check in onSuperAppClick makes extra hooks harmless.
        var extraClickHooks = 0
        for (clickClass in PickerHookCore.EXTRA_CLICK_CLASSES) {
            extraClickHooks += hookExact(
                clickClass, "onSuperAppItemClick",
                arrayOf(PickerHookCore.SUPER_APP_BEAN), classLoader, quiet = true,
            ) { chain ->
                if (PickerHookCore.onSuperAppClick(chain.thisObject, chain.args.getOrNull(0))) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        PickerHookCore.log("hooked extra click classes x$extraClickHooks")
        // Forward our picker's result through the host activity to the caller.
        hookExact(PickerHookCore.FRAGMENT_ACTIVITY, "onActivityResult",
            arrayOf("int", "int", "android.content.Intent"), classLoader) { chain ->
            val activity = chain.thisObject as? Activity
            if (activity != null) {
                PickerHookCore.onPickerActivityResult(
                    activity,
                    chain.args[0] as Int,
                    chain.args[1] as Int,
                    chain.args[2] as? Intent,
                )
            }
            chain.proceed()
        }
    }

    /**
     * Hooks the most-derived implementation of [name] matching [paramTypeNames]
     * (exact match; empty array matches any overload), walking superclasses like
     * XposedHelpers.findAndHookMethod does — e.g. PickerActivity.onNewIntent is
     * declared in a superclass, so scanning only declaredMethods misses it.
     */
    private fun hookExact(
        className: String,
        name: String,
        paramTypeNames: Array<String>,
        classLoader: ClassLoader,
        quiet: Boolean = false,
        body: (XposedInterface.Chain) -> Any?,
    ): Int {
        val clazz = try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            if (!quiet) PickerHookCore.log("$className not found: $t")
            return 0
        }
        var current: Class<*>? = clazz
        var hooked = 0
        while (current != null && hooked == 0) {
            for (method in current.declaredMethods) {
                if (method.name != name) continue
                if (paramTypeNames.isNotEmpty() &&
                    method.parameterTypes.map { it.name } != paramTypeNames.toList()
                ) continue
                try {
                    hook(method).intercept(XposedInterface.Hooker { chain -> body(chain) })
                    hooked++
                } catch (t: Throwable) {
                    PickerHookCore.log("hook ${current.simpleName}#$name failed: $t")
                }
            }
            current = current.superclass
        }
        if (!quiet) PickerHookCore.log("hooked ${clazz.simpleName}#$name x$hooked")
        return hooked
    }
}
