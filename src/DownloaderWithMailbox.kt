import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

private const val N_WORKERS = 4

private fun CoroutineScope.downloader(
    mailbox: ReceiveChannel<Any>,
    locations: SendChannel<Location>
) = launch {
    val requested = mutableMapOf<Location, MutableList<Reference>>()
    for (msg in mailbox) {
        when (msg) {
            is Reference -> {
                val ref = msg
                val loc = ref.resolveLocation()
                val refs = requested[loc]
                if (refs == null) {
                    requested[loc] = mutableListOf(ref)
                    locations.send(loc)
                } else {
                    refs.add(ref)
                }
            }
            is LocContent -> {
                val (loc, content) = msg
                val refs = requested.remove(loc)!!
                for (ref in refs) {
                    processContent(ref, content)
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

private fun CoroutineScope.processReferences(): SendChannel<Reference> {
    val locations = Channel<Location>()
    val mailbox = Channel<Any>()
    repeat(N_WORKERS) { worker(locations, mailbox) }
    downloader(mailbox, locations)
    return mailbox
}

fun main() = runBlocking {
    withTimeout(3000) {
        val references = processReferences()
        var index = 1
        while (true) {
            references.send(Reference(index++))
        }
    }
}