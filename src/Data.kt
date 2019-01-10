import kotlinx.coroutines.*
import java.text.*
import java.util.*

data class Reference(val index: Int)
data class Location(val index: Int)
data class Content(val index: Int)
data class LocContent(val loc: Location, val content: Content)

fun Reference.resolveLocation(): Location {
    log("Resolving location for $this")
    return Location(index)
}

suspend fun downloadContent(loc: Location): Content {
    log("Downloading $loc")
    delay(10)
    return Content(loc.index)
}

fun processContent(ref: Reference, content: Content) {
    log("Processing $ref $content")
}

private fun log(msg: String) {
    val time = SimpleDateFormat("HH:mm:ss.sss").format(Date())
    println("$time [${Thread.currentThread().name}] $msg")
}

