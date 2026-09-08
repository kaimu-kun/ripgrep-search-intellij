package com.github.ss.ripgrepsearch.search

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Key
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class RipgrepProcessRunner(
    private val parser: RipgrepJsonParser = RipgrepJsonParser(),
) {
    fun search(request: RipgrepSearchRequest): Flow<RipgrepSearchEvent> = callbackFlow {
        if (request.query.isBlank()) {
            trySend(RipgrepSearchEvent.Completed)
            close()
            return@callbackFlow
        }

        val rgExecutable = PathEnvironmentVariableUtil.findInPath("rg")?.absolutePath
        if (rgExecutable == null) {
            trySend(RipgrepSearchEvent.Failed("ripgrep (rg) was not found on PATH. Install ripgrep and make sure rg is available on PATH."))
            close()
            return@callbackFlow
        }
        val commandLine = GeneralCommandLine(rgExecutable)
            .withWorkDirectory(request.projectRoot.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(
                "--json",
                "--line-number",
                "--color=never",
                "--smart-case",
                "--max-columns=2000",
                "--max-columns-preview",
                "--",
                request.query,
                ".",
            )

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (t: Throwable) {
            trySend(RipgrepSearchEvent.Failed("Unable to start ripgrep: ${t.message ?: t.javaClass.simpleName}"))
            close()
            return@callbackFlow
        }

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        var resultCount = 0
        var capped = false

        fun handleOutput(text: String) {
            stdoutBuffer.append(text)
            while (true) {
                val newline = stdoutBuffer.indexOf("\n")
                if (newline < 0) return
                val line = stdoutBuffer.substring(0, newline).trimEnd('\r')
                stdoutBuffer.delete(0, newline + 1)
                if (line.isEmpty()) continue

                when (val parsed = parser.parseLine(line)) {
                    is ParseResult.Matches -> {
                        val remaining = request.maxResults - resultCount
                        if (remaining <= 0) {
                            if (!capped) {
                                capped = true
                                trySend(RipgrepSearchEvent.Capped(request.maxResults))
                                handler.destroyProcess()
                            }
                            return
                        }

                        val results = parsed.candidates
                            .asSequence()
                            .mapNotNull { it.toResult(request.projectRoot) }
                            .take(remaining)
                            .toList()
                        if (results.isNotEmpty()) {
                            resultCount += results.size
                            trySend(RipgrepSearchEvent.Results(results))
                        }
                        if (resultCount >= request.maxResults && !capped) {
                            capped = true
                            trySend(RipgrepSearchEvent.Capped(request.maxResults))
                            handler.destroyProcess()
                        }
                    }

                    ParseResult.Ignored, ParseResult.Malformed -> Unit
                }
            }
        }

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == com.intellij.execution.process.ProcessOutputTypes.STDERR) {
                    stderrBuffer.append(event.text)
                } else {
                    handleOutput(event.text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (stdoutBuffer.isNotBlank()) {
                    handleOutput("\n")
                }
                when {
                    capped -> Unit
                    event.exitCode == 0 -> trySend(RipgrepSearchEvent.Completed)
                    event.exitCode == 1 -> trySend(RipgrepSearchEvent.NoMatches)
                    event.exitCode == 2 -> {
                        val stderr = stderrBuffer.toString().trim().lineSequence().firstOrNull()
                        trySend(RipgrepSearchEvent.Failed(stderr ?: "ripgrep exited with an error"))
                    }
                    else -> trySend(RipgrepSearchEvent.Failed("ripgrep exited with code ${event.exitCode}"))
                }
                close()
            }
        })

        trySend(RipgrepSearchEvent.Started)
        handler.startNotify()

        awaitClose {
            if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
                handler.destroyProcess()
            }
        }
    }
}

private fun RipgrepResultCandidate.toResult(projectRoot: Path): RipgrepResult? {
    val relative = relativePath.removePrefix("./")
    val absolute = projectRoot.resolve(relative).normalize()
    val virtualFile: VirtualFile = LocalFileSystem.getInstance().findFileByIoFile(absolute.toFile()) ?: return null
    if (!virtualFile.isValid) return null

    return RipgrepResult(
        relativePath = relative,
        absolutePath = absolute,
        virtualFile = virtualFile,
        lineNumberOneBased = lineNumberOneBased,
        columnNumberOneBased = columnNumberOneBased,
        lineIndex = lineIndex,
        columnIndex = columnIndex,
        lineText = lineText,
        matchStartColumnIndex = matchStartColumnIndex,
        matchEndColumnIndex = matchEndColumnIndex,
    )
}
