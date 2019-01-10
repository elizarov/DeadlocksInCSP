import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.selects.*

private const val N_WORKERS = 4

private fun CoroutineScope.downloader(
    references: ReceiveChannel<Reference>,
    locations: SendChannel<Location>,
    contents: ReceiveChannel<LocContent>
) = launch {
    val requested = mutableMapOf<Location, MutableList<Reference>>()
    while (true) {
        select<Unit> {
            contents.onReceive { (loc, content) ->
                val refs = requested.remove(loc)!!
                for (ref in refs) {
                    processContent(ref, content)
                }
            }
            references.onReceive { ref ->
                val loc = ref.resolveLocation()
                val refs = requested[loc]
                if (refs == null) {
                    requested[loc] = mutableListOf(ref)
                    locations.send(loc)
                } else {
                    refs.add(ref)
                }
            }
        }
    }
}

private fun CoroutineScope.worker(
    locations: ReceiveChannel<Location>,
    contents: SendChannel<LocContent>
) = launch {
    for (loc in locations) {
        val content = downloadContent(loc)
        contents.send(LocContent(loc, content))
    }
}

private fun CoroutineScope.processReferences(
    references: ReceiveChannel<Reference>
) {
    val locations = Channel<Location>()
    val contents = Channel<LocContent>(1)
    repeat(N_WORKERS) { worker(locations, contents) }
    downloader(references, locations, contents)
}

fun main() = runBlocking {
    withTimeout(3000) {
        val references = Channel<Reference>()
        processReferences(references)
        var index = 1
        while (true) {
            references.send(Reference(index++))
        }
    }
}