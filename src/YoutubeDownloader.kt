package io.meltec.amadeus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonException
import kotlinx.serialization.json.content
import kotlinx.serialization.json.int
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.*
import kotlin.random.asKotlinRandom
import kotlin.streams.asSequence

/**
 * Full youtube song downloader; provides simple blocking methods for downloading Youtube songs from given URLS
 * (as well as fetching some basic metadata about the song).
 */

// TODO: Detect when youtube-dl is not installed, or when youtube-dl does not work due to a lack of ffmpeg or ffprobe.
// TODO: Download the thumbnail URL automatically if present, and copy it over too.
// TODO: Generally just make this more robust and less hacky.
// TODO: By changing runCommand to poll, we can actually use coroutines in YoutubeDownloader instead of a thread pool,
// and eliminate any blocking. Consider doing this as a nice future optimization. A secondary benefit of this is that
// we could make ease suspendable 'downloadDirect()' methods which directly allow a song to be downloaded (and the
// resulting song returned) without any intermediate queueing step.
private val log: Logger = LoggerFactory.getLogger("youtube-dl")

/**
 * A multi-threaded (and threadsafe) downloader which automatically downloads songs from download in the background;
 * songs requests (and completions) are durably stored in the database.
 */
class YoutubeDownloader(val database: Database, val threads: Int = 4,
                        val targetDir: File = File("songs"),
                        val workingDir: File = File("work/downloader")) {
    /** The executor for the actual downloads. */
    val executor: Executor = Executors.newFixedThreadPool(threads)

    /** Queue a URL to be downloaded; this will add the queue request to the database and queue it in the download thread pool. */
    fun queue(url: String): QueuedYoutubeDownload {
        val queueTime = LocalDateTime.now()
        val download = database.newQueuedDownload(url, queueTime)

        executor.execute {
            try {
                // Random location where the song will be stored.
                val file = songFile()
                val meta = downloadSingle(url, file, workingDir)

                database.newSuccessfulCompletedDownload(url, queueTime, LocalDateTime.now(), meta)
            } catch (ex: CommandRunException) {
                database.newFailedCompletedDownload(url, queueTime, LocalDateTime.now(), ex.toString())
            } finally {
                database.deleteQueuedDownload(download.id)
            }
        }

        return download
    }

    /** Available characters to name the song. */
    private val SONG_CHARACTER_POOL: List<Char> = ('a' .. 'z') + ('A' .. 'Z') + ('0' .. '9')
    /** The number of characters in the random song name. */
    private val SONG_NAME_LENGTH: Int = 24

    /** Create a name for a new song file. */
    private fun songFile(): File {
        var file = File(targetDir, randomName())
        while (file.exists()) {
            file = File(targetDir, randomName())
        }

        return file
    }

    /** Generate a random [SONG_LENGTH_NAME]-length string from [SONG_CHARACTER_POOL]. */
    private fun randomName(): String =
        ThreadLocalRandom.current().ints(SONG_NAME_LENGTH.toLong(), 0, SONG_CHARACTER_POOL.size)
            .asSequence()
            .map(SONG_CHARACTER_POOL::get)
            .joinToString("")
}

/**
 * Attempt to download the audio of a single youtube file; downloads the video file to a temporary work directory,
 * extracts audio and youtube metadata, moves the audio to the given target file location, and returns the
 * relevant metadata.
 *
 * Throws a [CommandRunException] on failure.
 */
@kotlinx.serialization.UnstableDefault // TODO: Why do we need this? It's needed by Json.parseJson.
private fun downloadSingle(url: String, target: File, workDir: File): YoutubeMetadata {
    log.debug("Downloading '{}' to '{}' (working dir '{}')", url, target, workDir)

    val startTime = System.currentTimeMillis()
    val (exitCode, output, errOutput) = runCommand(workDir, listOf(
        "youtube-dl", "-x", "--audio-format", "mp3", "--print-json", "--id", url
    ))

    // If the program failed due to a non-zero exit code,exit immediately.
    if (exitCode != 0) throw CommandRunException("Non-zero exit code: $errOutput")

    // Attempt to parse the JSON output from the program to obtain relevant information.
    val parsed = try { Json.parseJson(output).jsonObject } catch(ex: JsonException) { throw CommandRunException("Youtube-dl did not output JSON", ex) }

    // Find the original video file, and then do filename substitution to obtain the audio file (which has a .mp3 extension).
    val videoFile = parsed.get("_filename")?.content ?: throw CommandRunException("youtube-dl did not provide a download filename")
    val audioFile = File(File(videoFile).nameWithoutExtension + ".mp3")

    // Move the audio file to the target location, then parse any remaining metadata from the JSON and return it.
    try { audioFile.renameTo(target) } catch(ex: IOException) { throw CommandRunException("Failed to move result file to '$target'", ex) }

    val meta = YoutubeMetadata(
        title = parsed.get("title")?.content,
        artist = parsed.get("artist")?.content,
        album = parsed.get("album")?.content,
        thumbnailUrl = parsed.get("thumbnail")?.content,
        lengthSeconds = parsed.get("duration")?.int,
        filename = target.toString()
    )
    val endTime = System.currentTimeMillis()

    log.info("Downloaded '{}' from '{}' to file '{}' (working dir '{}', {}s)",
        meta.title ?: url, url, target, workDir, (endTime - startTime) / 1000.0)

    return meta
}

/** Utility method which runs a command with the given arguments and work directory. */
private fun runCommand(workDir: File, command: List<String>): CommandRunResult {
    log.debug("Running command '{}'", command.joinToString(" "))

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
        val output = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        // TODO: Pass as function argument for configurability.
        proc.waitFor(2, TimeUnit.MINUTES)

        return CommandRunResult(proc.exitValue(), output, err)
    } catch (ex: IOException) {
        throw CommandRunException("Command failed to run: $command", ex)
    }
}

/** Metadata about a downloaded youtube file; this metadata may be incomplete for some youtube files. */
data class YoutubeMetadata(val title: String?, val artist: String?, val album: String?, val thumbnailUrl: String?, val lengthSeconds: Int?, val filename: String)

/** Result of running the raw youtube-dl executable; returns raw text. */
data class CommandRunResult(val exitCode: Int, val output: String, val errOutput: String)

/** An invocation of youtube-dl failed with some specific exception. */
open class CommandRunException(reason: String, underlying: Exception? = null) : Exception(reason, underlying)
/** YoutubeDl does not appear to be installed. */
class CommandNotFoundException(reason: String, underlying: Exception? = null) : CommandRunException(reason, underlying)
