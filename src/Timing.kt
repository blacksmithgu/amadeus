import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.system.measureTimeMillis as systemMeasureTimeMillis

/**
 * Executes the given [block] and returns elapsed time in milliseconds.
 */
@OptIn(ExperimentalContracts::class)
inline fun measureTimeMillis(block: () -> Unit): Long {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return systemMeasureTimeMillis(block)
}
