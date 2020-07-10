import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

private val commandLog: Logger = LoggerFactory.getLogger("run-command")

/** Default time to wait for a command to terminate, in the worst case. */
private const val DEFAULT_WAIT_TIME_MINS = 2L

/** Available characters to name the song. */
private val SONG_CHARACTER_POOL = ('a'..'z') + ('A'..'Z') + ('0'..'9')

/** Generate a random 'length' string using alphanumeric characters (from [SONG_CHARACTER_POOL]), optionally appending a suffix. */
fun randomAlphanumeric(length: Int, suffix: String = ""): String =
    ThreadLocalRandom.current().ints(length.toLong(), 0, SONG_CHARACTER_POOL.size)
        .asSequence()
        .map(SONG_CHARACTER_POOL::get)
        .joinToString("", postfix = suffix)


/** Utility method which runs a command with the given arguments and work directory. */
fun runCommand(workDir: File, command: List<String>): CommandRunResult {
    commandLog.debug("Running command '{}'", command.joinToString(" "))

    val proc = try {
        ProcessBuilder(command)
            .directory(workDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
    } catch (ex: IOException) {
        throw CommandNotFoundException("Failed to start command: $command", ex)
    }

    try {
        proc.run {
            val output = inputStream.bufferedReader().readText()
            val err = errorStream.bufferedReader().readText()
            // TODO: Pass as function argument for configurability.
            waitFor(DEFAULT_WAIT_TIME_MINS, TimeUnit.MINUTES)

            return CommandRunResult(exitValue(), output, err)
        }
    } catch (ex: IOException) {
        throw CommandRunException("Command failed to run: $command", ex)
    }
}

/** Result of running the raw youtube-dl executable; returns raw text. */
data class CommandRunResult(val exitCode: Int, val output: String, val errOutput: String)

/** An invocation of youtube-dl failed with some specific exception. */
open class CommandRunException(reason: String, underlying: Exception? = null) : Exception(reason, underlying)

/** YoutubeDl does not appear to be installed. */
class CommandNotFoundException(reason: String, underlying: Exception? = null) : CommandRunException(reason, underlying)