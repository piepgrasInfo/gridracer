package info.piepgras.gridracer

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import info.piepgras.gridracer.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Opt-in wrapper around the Sentry/Bugsink crash reporter. The manifest disables Sentry's
 * auto-init, so by default no crash reports are sent anywhere; [init] turns it on (reading
 * the DSN and options already declared in AndroidManifest.xml) and [close] turns it back off.
 * [MainActivity] drives this from the live [info.piepgras.gridracer.engine.AppSettings]
 * crash-reporting toggle so the user's choice takes effect immediately and persists across
 * launches without Sentry ever starting on its own.
 */
object CrashReporting {

    // Supplied at build time from `sentry.dsn` in local.properties (see app/build.gradle.kts),
    // and empty in any build made from the published source. That is deliberate rather than
    // secretive - a DSN is a write-only identifier that ships inside every APK anyway - but a
    // fork's crashes are not the upstream maintainer's to receive, so a source build reports
    // nowhere at all. [isConfigured] is false then, and the Settings toggle is hidden.
    //
    // Kept out of the manifest on purpose: with the DSN in the manifest, the Sentry SDK could
    // find it and start itself from its own ContentProvider before the user has had any chance
    // to refuse, if io.sentry.auto-init were ever accidentally re-enabled. Passing it here
    // instead means the SDK exists in the APK but does nothing until init() is called.
    private val DSN: String = BuildConfig.SENTRY_DSN

    /** Whether this build has an endpoint to report to at all. False in a source build. */
    val isConfigured: Boolean get() = DSN.isNotEmpty()

    @Volatile
    private var initialized = false

    /**
     * Starts the crash reporter, if this build has an endpoint and it is not already running.
     * Called only once the user has opted in; a no-op in a source build.
     */
    fun init(context: Context) {
        if (initialized || !isConfigured) return
        SentryAndroid.init(context) { options ->
            options.dsn = DSN

            // Name the build, or Bugsink cannot tell which source a stack trace belongs to.
            // The 2026-08 triage matched line numbers to HEAD by luck; this makes it method.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = PackageInfoCompat.getLongVersionCode(info)
            options.release = "${context.packageName}@${info.versionName}+$versionCode"
            options.dist = versionCode.toString()

            // Bugsink does not ingest traces or profiles, so sending them would be waste on the
            // wire and noise on the server.
            options.tracesSampleRate = 0.0
            options.profilesSampleRate = 0.0

            // Release health pings on every foreground/background transition. An offline game has
            // no reason to tell a server it was opened.
            options.isEnableAutoSessionTracking = false

            // Bugsink does not handle client reports; sending them is wasted requests.
            options.isSendClientReports = false

            // No PII: self-hosting changes who holds the data, not whether
            // it is personal data, and this app's whole position is that
            // it collects none.
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.setDiagnosticLevel(SentryLevel.ERROR)

            // Clear anything that could identify the installation before
            // the event goes out, matching WorkTimeTracker's approach.
            options.setBeforeSend { event, _ ->
                event.user = null
                event.serverName = null
                event
            }
        }
        initialized = true
    }

    /**
     * Stops the crash reporter, when the user turns reporting off. A no-op if it is not running.
     */
    fun close() {
        if (!initialized) return
        Sentry.close()
        initialized = false
    }
}
