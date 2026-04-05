package org.fossify.contacts.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import org.fossify.contacts.extensions.config

object PrivacyGuard {
    private val trustedPackagePrefixes = setOf(
        "org.fossify.contacts",
        "org.fossify.phone",
        "org.fossify.messages"
    )

    fun isCallerAllowed(context: Context, callingPackage: String?, privacyProtectionEnabled: Boolean): Boolean {
        if (!privacyProtectionEnabled) {
            return true
        }

        val packageManager = context.packageManager
        val callerPackages = buildSet {
            callingPackage?.takeIf { it.isNotBlank() }?.let(::add)
            packageManager.getPackagesForUid(Binder.getCallingUid())?.forEach(::add)

            if (Binder.getCallingUid() == Process.myUid()) {
                add(context.packageName)
            }
        }

        if (callerPackages.isEmpty()) {
            return false
        }

        val hasMatchingSignature = callerPackages.any {
            packageManager.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH
        }

        val isWhitelisted = callerPackages.any { caller ->
            trustedPackagePrefixes.any { prefix -> caller == prefix || caller.startsWith("$prefix.") } ||
                caller in context.config.privacyAllowedPackages
        }

        return hasMatchingSignature && isWhitelisted
    }
}
