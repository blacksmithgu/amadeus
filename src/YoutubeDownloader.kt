package io.meltec.amadeus

import CommandRunException
import kotlinx.serialization.json.*
import measureTimeMillis
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import randomAlphanumeric
import runCommand
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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

/** The format that we download music in. */
private const val FORMAT: String = "mp3"

/** Default target directory to store songs in. */
private val DEFAULT_TARGET_DIR = File("songs")
/** Default working directory that downloads occur in. */
private val DEFAULT_WORKING_DIR = File("work/downloader")
/** Default number of threads to use for the downloader. */
private const val DEFAULT_THREADS = 4

/** Metadata about a downloaded youtube file; this metadata may be incomplete for some youtube files. */
data class YoutubeMetadata(val title: String?, val artist: String?, val album: String?, val thumbnailUrl: String?, val lengthSeconds: Int?, val filename: String)

/**
 * A multi-threaded (and threadsafe) downloader which automatically downloads songs from download in the background;
 * songs requests (and completions) are durably stored in the database.
 */
class YoutubeDownloader private constructor(
    private val database: Database,
    threads: Int = DEFAULT_THREADS,
    private val targetDir: File = DEFAULT_TARGET_DIR,
    private val workingDir: File = DEFAULT_WORKING_DIR
) {
    /** The executor for the actual downloads. */
    val executor: Executor = Executors.newFixedThreadPool(threads)

    /** Queue a URL to be downloaded; this will add the queue request to the database and queue it in the download thread pool. */
    fun queue(url: String) = database.newQueuedDownload(url, LocalDateTime.now()).also {
        executor.execute { execDownload(it) }
    }

    /** Internal method which executes the download directly; this should only be run on the executor. */
    private fun execDownload(download: QueuedYoutubeDownload) {
        try {
            // Make the target directory and working directory if they don't exist.
            targetDir.takeUnless(File::exists)?.run(File::mkdirs)
            workingDir.takeUnless(File::exists)?.run(File::mkdirs)

            // Random location where the song will be stored.
            val file = songFile(FORMAT)
            val meta = downloadYoutube(download.url, file, workingDir)

            database.newSuccessfulCompletedDownload(download.url, download.requestTime, LocalDateTime.now(), meta)
        } catch (ex: CommandRunException) {
            database.newFailedCompletedDownload(download.url, download.requestTime, LocalDateTime.now(), ex.toString())
        } catch (ex: IOException) {
            database.newFailedCompletedDownload(download.url, download.requestTime, LocalDateTime.now(), ex.toString())
        } finally {
            database.deleteQueuedDownload(download.id)
        }
    }

    /** Create a name for a new song file. */
    private fun songFile(extension: String): File {
        var file: File
        do { file = File(targetDir, randomAlphanumeric(SONG_NAME_LENGTH, suffix = ".$extension")) } while (file.exists())

        return file
    }

    companion object {
        /** The number of characters in the random song name. */
        private const val SONG_NAME_LENGTH = 24

        /** Create a new downloader without loading old jobs from the database. */
        @JvmStatic
        fun createWithoutInit(
            database: Database, threads: Int = DEFAULT_THREADS,
            targetDir: File = DEFAULT_TARGET_DIR,
            workingDir: File = DEFAULT_WORKING_DIR): YoutubeDownloader
                = YoutubeDownloader(database, threads, targetDir, workingDir)

        /** Create a new downloader, loading old jobs from the database. */
        @JvmStatic
        fun create(database: Database, threads: Int = DEFAULT_THREADS,
                   targetDir: File = DEFAULT_TARGET_DIR,
                   workingDir: File = DEFAULT_WORKING_DIR): YoutubeDownloader {
            val downloader = YoutubeDownloader(database, threads, targetDir, workingDir)

            // Clear old working directory.
            workingDir.takeIf(File::exists)?.run { try { deleteRecursively() } catch (ex: IOException) { } }

            // Re-queue old queued jobs which were not finished.
            val jobs = database.allQueuedDownloads()
            log.info("Re-queueing {} old jobs which were not completed, and clearing the working directory", jobs.size)
            for (job in jobs) {
                downloader.executor.execute { downloader.execDownload(job) }
            }

            return downloader
        }
    }
}

/**
 * Attempt to download the audio of a single youtube file; downloads the video file to a temporary work directory,
 * extracts audio and youtube metadata, moves the audio to the given target file location, and returns the
 * relevant metadata.
 *
 * Throws a [CommandRunException] on failure.
 */
fun downloadYoutube(url: String, target: File, workDir: File): YoutubeMetadata {
    log.debug("Downloading '{}' to '{}' (working dir '{}')", url, target, workDir)

    val meta: YoutubeMetadata
    val timeTaken = measureTimeMillis {
        val (exitCode, output, errOutput) = runCommand(
            workDir,
            listOf("youtube-dl", "-x", "--audio-format", FORMAT, "--print-json", "--id", "--no-playlist", url)
        )

        // If the program failed due to a non-zero exit code,exit immediately.
        if (exitCode != 0) throw CommandRunException("Non-zero exit code: $errOutput")

        // Attempt to parse the JSON output from the program to obtain relevant information.
        val json = Json(JsonConfiguration.Stable)
        val parsed = try {
            json.parseJson(output).jsonObject
        } catch (ex: JsonException) {
            throw CommandRunException("Youtube-dl did not output JSON", ex)
        }

        // Find the original video file, and then do filename substitution to obtain the audio file (which has a [FORMAT] extension).
        val videoFile =
            parsed["_filename"]?.content ?: throw CommandRunException("youtube-dl did not provide a download filename")
        val audioFile = File(workDir, File(videoFile).nameWithoutExtension + "." + FORMAT)

        // Move the audio file to the target location, then parse any remaining metadata from the JSON and return it.
        try {
            audioFile.renameTo(target)
        } catch (ex: IOException) {
            throw CommandRunException("Failed to move result file to '$target'", ex)
        }

        meta = YoutubeMetadata(
            title = parsed["title"]?.content,
            artist = parsed["artist"]?.content,
            album = parsed["album"]?.content,
            thumbnailUrl = parsed["thumbnail"]?.content,
            lengthSeconds = parsed["duration"]?.int,
            filename = target.toString()
        )
    }

    log.info("Downloaded '{}' from '{}' to file '{}' (working dir '{}', {}s)",
        meta.title ?: url, url, target, workDir, timeTaken / 1000.0)

    return meta
}

