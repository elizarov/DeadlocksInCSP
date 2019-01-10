package verifier

import java.io.*
import java.util.*
import java.util.regex.*
import kotlin.math.*
import kotlin.system.*

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: CSPVerifier <file-name> ..")
        return
    }
    for (arg in args) {
        val file = File(arg)
        if (file.name.contains('*')) {
            val regex = Regex(file.name.split("*").map { Pattern.quote(it) }.joinToString(".*"))
            file.parentFile
                .listFiles { _, name -> regex.matches(name) }
                .sortedBy { it.name }
                .forEach { analyzeFile(it) }
        } else {
            analyzeFile(file)
        }
    }
}

fun analyzeFile(file: File) {
    println("====== Reading $file")
    val cfsm = LineNumberReader(file.reader()).use {
        try {
            it.readCFSM()
        } catch (e: Exception) {
            System.err.println("ERROR: ${file.absolutePath}: ${it.lineNumber}: ${e.message}")
            throw e
        }
    }
    println("-- Processes: $cfsm")
    println("--  Channels: ${cfsm.channels.joinToString(" ")}")
    val time = measureTimeMillis { cfsm.analyze() }
    println("Done in $time ms")
}

private fun CFSM.analyze() {
    val v = mutableMapOf<GlobalState, Transition?>()
    val initialState = GlobalState(procs.map { it.t.keys.first() }.toTypedArray())
    val queue = ArrayDeque<GlobalState>()
    v[initialState] = null
    queue.addLast(initialState)

    fun enqueue(next: GlobalState, transition: Transition) {
        if (next !in v) {
            v[next] = transition
            queue.addLast(next)
        }
    }

    val csMap = mutableMapOf<String, ChanState>()
    val pris = IntArray(procs.size)
    val hasPriorities = hasPriorities
    while (true) {
        // Get position to analyze from the queue
        val cur = queue.pollFirst() ?: break
        // Clear temp data
        for ((_, cs) in csMap) cs.clear()
        if (hasPriorities) pris.fill(Int.MAX_VALUE)
        // Analyze all desired operations on channels
        for (p in procs.withIndex()) {
            val fromState = cur.s[p.index]
            val actMap = procs[p.index].t[fromState] ?: continue
            for ((act, toState) in actMap) {
                val cs = csMap.getOrPut(act.chan) { ChanState() }
                when (act.op) {
                    Operation.SND -> cs.snd += ProcState(p.index, fromState, toState, act.pri)
                    Operation.RCV -> cs.rcv += ProcState(p.index, fromState, toState, act.pri)
                }
            }
        }
        // Find best priorities for all possible communications in each state
        if (hasPriorities) {
            for (p in procs.withIndex()) {
                val fromState = cur.s[p.index]
                val actMap = procs[p.index].t[fromState] ?: continue
                for ((act, _) in actMap) {
                    val cs = csMap.getOrPut(act.chan) { ChanState() }
                    val possible = when (act.op) {
                        Operation.SND -> cs.rcv.any { it.index != p.index }
                        Operation.RCV -> cs.snd.any { it.index != p.index }
                    }
                    if (possible) pris[p.index] = min(pris[p.index], act.pri)
                }
            }
        }
        // Find possible communications state transitions
        var active = false
        for ((chan, cs) in csMap) {
            for (snd in cs.snd) for (rcv in cs.rcv) {
                if (snd.index != rcv.index && snd.pri == pris[snd.index] && rcv.pri == pris[rcv.index]) {
                    // Note: Buffer processes perform truly atomic transitions
                    val sndToState = snd.toState.beginMoveIf(hasPriorities && !procs[snd.index].bufferProc)
                    val rcvToState = rcv.toState.beginMoveIf(hasPriorities && !procs[rcv.index].bufferProc)
                    val sndMove = Move(snd.index, snd.fromState, sndToState)
                    val rcvMove = Move(rcv.index, rcv.fromState, rcvToState)
                    enqueue(cur.next(sndMove, rcvMove), Comm(chan, sndMove, rcvMove))
                    active = true // active state, because it can move
                }
            }
        }
        // Find possible finishing state transition (if working with priorities)
        if (hasPriorities) {
            for (p in procs.withIndex()) {
                val fromState = cur.s[p.index]
                val toState = fromState.finishMove() ?: continue
                val move = Move(p.index, fromState, toState)
                enqueue(cur.next(move), move)
                active = true
            }
        }
        if (!active) {
            dumpDeadlock(cur, v) // found deadlock
            break
        }
    }
    println("Analyzed ${v.size} states")
}

private const val MOVING = '\''
private fun String.beginMoveIf(hasPriorities: Boolean) = if (hasPriorities) "$this$MOVING" else this
private fun String.finishMove() = if (endsWith(MOVING)) dropLast(1) else null

private fun CFSM.dumpDeadlock(
    cur: GlobalState,
    v: MutableMap<GlobalState, Transition?>
) {
    println("Found deadlock at ${cur.string()}")
    val ts = mutableListOf<Transition>()
    val prev = cur.next()
    while (true) {
        val t = v[prev] ?: break
        ts += t
        when (t) {
            is Comm -> {
                prev.s[t.snd.index] = t.snd.fromState
                prev.s[t.rcv.index] = t.rcv.fromState
            }
            is Move -> {
                prev.s[t.index] = t.fromState
            }
        }
    }
    ts.reverse()
    with(Layout()) {
        for (t in ts) println(t.string())
    }
}

class GlobalState(val s: Array<String>) {
    override fun equals(other: Any?): Boolean = (other as? GlobalState)?.s?.contentEquals(s) ?: false
    override fun hashCode(): Int = s.contentHashCode()
    fun next(vararg moves: Move) = GlobalState(s.clone()).apply {
        moves.forEach { s[it.index] = it.toState }
    }
}

sealed class Transition
data class Comm(val chan: String, val snd: Move, val rcv: Move) : Transition()
data class Move(val index: Int, val fromState: String, val toState: String) : Transition()

class ChanState {
    val snd = mutableListOf<ProcState>()
    val rcv = mutableListOf<ProcState>()

    fun clear() {
        snd.clear()
        rcv.clear()
    }
}

data class ProcState(val index: Int, val fromState: String, val toState: String, val pri: Int)

fun LineNumberReader.readCFSM() = CFSM().apply {
    val bufChans = mutableSetOf<String>() // buffered channels
    val seenChans = mutableSetOf<String>() // already seen channels
    while (true) {
        val procLine = nextLine() ?: break
        val procName = procLine.substringBefore(" ")
        val procCountStr = procLine.substringAfter(" ", "1")
        if (procCountStr.startsWith("%")) {
            // create channel buffer coroutine
            val n = procCountStr.drop(1).toInt()
            check(procName !in seenChans) { "Channel buffer specification must precede channel usage '$procName' "}
            seenChans += procName
            bufChans += procName
            addProc(procName, createBufferProc(procName, n))
        } else {
            val procCount = procCountStr.toInt()
            check(procCount >= 1) { "Invalid process count $procCount" }
            val proc = readProc(bufChans)
            if (procCount == 1) {
                addProc(procName, proc)
            } else {
                repeat(procCount) { addProc(procName + it, proc) }
            }
            seenChans += proc.t.values.flatMap { it.keys.map { it.chan } }
        }
    }
}

private fun createBufferProc(name: String, n: Int) =
    Proc(bufferProc = true).apply {
        for (i in 0..n) {
            if (i > 0) addAction(i.toString(), Action("$name-", Operation.SND), (i - 1).toString())
            if (i < n) addAction(i.toString(), Action("$name+", Operation.RCV), (i + 1).toString())
        }
    }


private fun CFSM.addProc(procName: String, proc: Proc) {
    check(procName !in names) { "Duplicate process name '$procName'" }
    names += procName
    procs += proc
}

val SPACES = Regex("\\s+")

fun LineNumberReader.readProc(bufChans: Set<String>): Proc = Proc().apply {
    while (true) {
        val line = nextLine().takeIf { it != "." } ?: break
        val s = line.split(SPACES, limit = 3)
        check(s.size == 3) { "Transition line shall have format: <from-state> <action> <to-state>" }
        val (fromState, actStr, toState) = s
        val act = actStr.toAction(bufChans)
        addAction(fromState, act, toState)
    }
}

private fun Proc.addAction(fromState: String, act: Action, toState: String) {
    val actMap = t.getOrPut(fromState) { mutableMapOf() }
    check(act !in actMap) { "Duplicate action '$act' at state '$fromState'" }
    actMap[act] = toState
}

fun LineNumberReader.nextLine(): String? {
    while (true) {
        val line = readLine() ?: return null
        return line.substringBefore("#").trim().takeIf { it.isNotEmpty() } ?: continue
    }
}

class CFSM {
    val names = mutableListOf<String>()
    val procs = mutableListOf<Proc>()

    val hasPriorities get() = procs.any { it.t.values.any { it.keys.any { it.pri != 0 } } }
    val channels: Set<String> get() = procs.flatMap { it.t.values.flatMap { it.keys.map { it.chan } } }.toSet()

    override fun toString(): String = names.withIndex().joinToString(" ") {
        "${it.value}[${procs[it.index].t.size}]"
    }

    fun GlobalState.string() = names.withIndex().joinToString(" ") {
        "${it.value}.${s[it.index]}"
    }

    inner class Layout {
        private val nl = names.maxBy { it.length }!!.length
        private val sl = procs.flatMap { it.t.keys }.maxBy { it.length }!!.length + if (hasPriorities) 1 else 0
        private val cl = channels.maxBy { it.length }!!.length

        val String.p get() = padStart(nl)
        val String.s get() = padEnd(sl)
        val String.c get() = padStart(cl)

        fun Transition.string(): String {
            return when (this) {
                is Comm -> "${names[snd.index].p}.(${snd.fromState.s} ${chan.c}! ${snd.toState.s})" +
                        "   ${names[rcv.index].p}.(${rcv.fromState.s} ${chan.c}? ${rcv.toState.s})"
                is Move -> "${names[index].p}.(${fromState.s} ${"-".c}> ${toState.s})"
            }
        }
    }
}

class Proc(val bufferProc: Boolean = false) {
    val t = mutableMapOf<String, MutableMap<Action, String>>()
}

data class Action(val chan: String, val op: Operation, val pri: Int = 0) {
    override fun toString(): String = chan + op.ch
}

enum class Operation(val ch: Char) {
    SND('!'),
    RCV('?')
}

fun String.toAction(bufChans: Set<String>): Action {
    require(length > 1) { "Invalid action '$this'" }
    val chanOp = substringBefore('@')
    val pri = substringAfter('@', "0").toInt()
    val chan = chanOp.dropLast(1)
    val op = chanOp.last().toOperation()
    return when {
        chan !in bufChans -> Action(chan, op, pri)
        op == Operation.SND -> Action("$chan+", op, pri)
        else -> Action("$chan-", op, pri)
    }
}

fun Char.toOperation() = when (this) {
    '!' -> Operation.SND
    '?' -> Operation.RCV
    else -> error("Unrecognized operation char '$this'")
}

