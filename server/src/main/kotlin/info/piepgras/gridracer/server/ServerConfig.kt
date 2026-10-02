package info.piepgras.gridracer.server

import java.nio.file.Paths
import java.util.UUID

/**
 * Everything the server reads from its environment, resolved once at startup.
 *
 * [fromEnv] takes the lookup as a parameter rather than calling [System.getenv] directly so
 * configuration can be tested without touching the real environment.
 */
data class ServerConfig(
    /**
     * Address to bind to (`HOST`). `127.0.0.1` keeps the server local, for running behind a proxy.
     */
    val host: String,
    /** Port to listen on (`PORT`). */
    val port: Int,
    /**
     * Whether to believe X-Forwarded-For. Off by default: a directly-connected client can
     * send that header itself, so trusting it without a proxy in front hands out free
     * identities to anything rate-limited per IP.
     */
    val trustProxy: Boolean,
    /** Path to the SQLite database file. ":memory:" runs without persistence (tests). */
    val databasePath: String,
    /**
     * A stable identifier for this server deployment, used to namespace cross-server track
     * UUIDs so a track uploaded to one server can never collide with one uploaded to another.
     *
     * Defaults to a UUIDv5 of the absolute database path, so a given deployment is stable
     * across restarts without requiring configuration. Explicitly set via
     * `GRIDRACER_SERVER_ID` when multiple servers share a database path (rare) or for
     * readability. In-memory test databases derive a fresh id per process, which is fine
     * because tests do not check cross-server stability unless they set it.
     */
    val serverId: String,
    /** Human-readable server name shown alongside track authorship. */
    val serverName: String,
    /**
     * Whether finished races are written to disk so they can be replayed later.
     *
     * On by default, because a race nobody recorded cannot be settled afterwards. Set
     * `GRIDRACER_RACE_LOG=off` to turn it off entirely - the server then keeps no record of
     * what was played beyond the leaderboard totals.
     */
    val raceLogEnabled: Boolean,
    /** Where race logs are written. Relative paths resolve against the working directory. */
    val raceLogDirectory: String,
    /**
     * How many days a race log is kept before the housekeeping sweep deletes it. 0 disables
     * the sweep, which means logs accumulate forever - deliberate, but say so on purpose.
     */
    val raceLogRetentionDays: Long,
) {
    companion object {
        /** Port used when `PORT` is unset or not a valid port number. */
        const val DEFAULT_PORT = 8080
        /** Bind address used when `HOST` is unset: every interface. */
        const val DEFAULT_HOST = "0.0.0.0"
        /**
         * SQLite file used when `GRIDRACER_DB_PATH` is unset, relative to the working directory.
         */
        const val DEFAULT_DATABASE_PATH = "gridracer.sqlite"
        /** Server name used when `GRIDRACER_SERVER_NAME` is unset. */
        const val DEFAULT_SERVER_NAME = "GridRacer"
        /** Race-log directory used when `GRIDRACER_RACE_LOG_DIR` is unset. */
        const val DEFAULT_RACE_LOG_DIR = "races"
        /** Retention used when `GRIDRACER_RACE_LOG_RETENTION_DAYS` is unset or malformed. */
        const val DEFAULT_RACE_LOG_RETENTION_DAYS = 30L

        /**
         * Reads the configuration from environment variables, falling back to the defaults above.
         *
         * A malformed value falls back to its default rather than failing the boot, and for the
         * race log the fallback is always the safer one: a typo in `GRIDRACER_RACE_LOG` leaves
         * logging on, and a malformed retention keeps the 30-day limit rather than keeping logs
         * forever.
         *
         * @param env Looks a variable up by name; [System.getenv] by default, a map in tests.
         * @return The resolved configuration.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig {
            val resolvedDbPath = env("GRIDRACER_DB_PATH")?.takeIf { it.isNotBlank() } ?: DEFAULT_DATABASE_PATH
            return ServerConfig(
                host = env("HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
                // A malformed PORT falls back to the default rather than failing to boot: the
                // server is more useful listening somewhere than not at all.
                port = env("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
                trustProxy = env("GRIDRACER_TRUST_PROXY")?.equals("true", ignoreCase = true) ?: false,
                databasePath = resolvedDbPath,
                // Derive the server id from the *resolved* database path so the default (no
                // env) is stable, matching the path the server actually opens.
                serverId = env("GRIDRACER_SERVER_ID")?.takeIf { it.isNotBlank() } ?: defaultServerId(resolvedDbPath),
                serverName = env("GRIDRACER_SERVER_NAME")?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_NAME,
                // Opt-*out*: anything but an explicit off-ish value leaves logging on, so a
                // typo in this variable cannot silently stop recording races.
                raceLogEnabled = env("GRIDRACER_RACE_LOG")?.let { it.trim().lowercase() !in OFF_VALUES } ?: true,
                raceLogDirectory = env("GRIDRACER_RACE_LOG_DIR")?.takeIf { it.isNotBlank() } ?: DEFAULT_RACE_LOG_DIR,
                // A malformed retention falls back to the default rather than to "keep
                // forever": silently unbounded growth of personal data is the worse failure.
                raceLogRetentionDays = env("GRIDRACER_RACE_LOG_RETENTION_DAYS")
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: DEFAULT_RACE_LOG_RETENTION_DAYS,
            )
        }

        /** Values that mean "off" for a boolean environment variable. */
        private val OFF_VALUES = setOf("off", "false", "0", "no", "disabled")

        /**
         * Derives a stable server id from the database path. ":memory:" and blank paths get a
         * fresh random UUID per process (tests); a real file path is resolved to its absolute
         * form and hashed into a UUIDv5 so the same deployment is stable across restarts.
         */
        private fun defaultServerId(dbPath: String?): String {
            val path = dbPath?.takeIf { it.isNotBlank() && it != ":memory:" } ?: return UUID.randomUUID().toString()
            val absolute = runCatching { Paths.get(path).toAbsolutePath().toString() }.getOrElse { path }
            return UUID.nameUUIDFromBytes(absolute.toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
