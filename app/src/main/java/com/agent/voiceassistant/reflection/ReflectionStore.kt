package com.agent.voiceassistant.reflection

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter

class ReflectionStore(context: Context) {
    @Serializable
    private data class State(val records: List<TurnReflectionRecord> = emptyList())

    private val file = AtomicFile(File(context.filesDir, "turn-reflections.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun records(): List<TurnReflectionRecord> = synchronized(FILE_LOCK) {
        readState().records.sortedByDescending(TurnReflectionRecord::createdAt)
    }

    fun save(record: TurnReflectionRecord) = synchronized(FILE_LOCK) {
        val records = readState().records.toMutableList()
        val index = records.indexOfFirst { it.turnId == record.turnId }
        if (index >= 0) records[index] = record else records.add(record)
        writeState(State(records.sortedByDescending(TurnReflectionRecord::createdAt).take(MAX_RECORDS)))
    }

    fun delete(turnId: String): Boolean = synchronized(FILE_LOCK) {
        val state = readState()
        val remaining = state.records.filterNot { it.turnId == turnId }
        if (remaining.size == state.records.size) return@synchronized false
        writeState(State(remaining))
        true
    }

    private fun readState(): State = runCatching {
        file.openRead().bufferedReader().use { json.decodeFromString<State>(it.readText()) }
    }.getOrDefault(State())

    private fun writeState(state: State) {
        val output = file.startWrite()
        try {
            OutputStreamWriter(output).apply {
                write(json.encodeToString(state))
                flush()
            }
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val MAX_RECORDS = 500
        val FILE_LOCK = Any()
    }
}
