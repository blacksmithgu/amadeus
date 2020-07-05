package io.meltec.amadeus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonException
import kotlinx.serialization.json.content
import kotlinx.serialization.json.int
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Full youtube song downloader; provides simple blocking methods for downloading Youtube songs from given URLS
 * (as well as fetching some basic metadata about the song).
 */

// TODO: Detect when youtube-dl is not installed, or when youtube-dl does not work due to a lack of ffmpeg or ffprobe.
// TODO: Download the thumbnail URL automatically if present, and copy it over too.
// TODO: Generally just make this more robust and less hacky.
private val log: Logger = LoggerFactory.getLogger("youtube-dl")

/**
 * Attempt to download the audio of a single youtube file; downloads the video file to a temporary work directory,
 * extracts audio and youtube metadata, moves the audio to the given target file location, and returns the
 * relevant metadata.
 *
 * Throws a [CommandRunException] on failure.
 */
@kotlinx.serialization.UnstableDefault // TODO: Why do we need this? It's needed by Json.parseJson.
fun downloadSingle(url: String, target: File, workDir: File): YoutubeMetadata {
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
        lengthSeconds = parsed.get("duration")?.int
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
data class YoutubeMetadata(val title: String?, val artist: String?, val album: String?, val thumbnailUrl: String?, val lengthSeconds: Int?)

/** Result of running the raw youtube-dl executable; returns raw text. */
data class CommandRunResult(val exitCode: Int, val output: String, val errOutput: String)

/** An invocation of youtube-dl failed with some specific exception. */
open class CommandRunException(reason: String, underlying: Exception? = null) : Exception(reason, underlying)
/** YoutubeDl does not appear to be installed. */
class CommandNotFoundException(reason: String, underlying: Exception? = null) : CommandRunException(reason, underlying)
