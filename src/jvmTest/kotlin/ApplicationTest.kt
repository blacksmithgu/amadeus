import io.meltec.amadeus.Amadeus
import io.meltec.amadeus.Database
import io.meltec.amadeus.YoutubeDownloader
import org.junit.Test
import org.sqlite.SQLiteConfig
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun registerDisplayName() {
        // TODO: Move this to a generic setup block.
        val database = Database.sqlite(
            "amadeus-test.db",
            SQLiteConfig().apply {
                enableRecursiveTriggers(true)
                enforceForeignKeys(true)
            }
        )
        val amadeus = Amadeus(database, YoutubeDownloader.createWithoutInit(database))

        // How's this for a deep and useful test?
        assertEquals(expected = true, actual = true)
    }
}
