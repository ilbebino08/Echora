package com.echora.app.privileged

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper

/**
 * Context wrapper che dichiara l'attribuzione per uid 0 (root) o uid 2000 (shell).
 * Usato per far sì che l'AudioRecord venga creato con l'attribuzione corretta.
 */
class ShellContext(
    base: Context,
    private val isRoot: Boolean
) : ContextWrapper(base) {

    override fun getOpPackageName(): String {
        return if (isRoot) "uid:0" else "uid:2000"
    }

    override fun getAttributionSource(): AttributionSource {
        val uid = if (isRoot) 0 else 2000
        val packageName = if (isRoot) "android" else "com.android.shell"
        return AttributionSource.Builder(uid)
            .setPackageName(packageName)
            .build()
    }
}