package com.github.ss.ripgrepsearch.search

import com.github.ss.ripgrepsearch.settings.RipgrepSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
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

        val rgExecutable = resolveRipgrepExecutable()
        if (rgExecutable is RipgrepExecutableResolution.Failure) {
            trySend(RipgrepSearchEvent.Failed(rgExecutable.message))
            close()
            return@callbackFlow
        }
        val commandLine = GeneralCommandLine((rgExecutable as RipgrepExecutableResolution.Success).executable)
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
                request.searchPath,
            )

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (t: Throwable) {
            trySend(RipgrepSearchEvent.Failed("Unable to start ripgrep at '${commandLine.exePath}': ${t.message ?: t.javaClass.simpleName}"))
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

    private fun resolveRipgrepExecutable(): RipgrepExecutableResolution {
        val configuredPath = RipgrepSettings.getInstance().state.rgPath.trim()
        if (configuredPath.isNotEmpty()) {
            val file = File(configuredPath)
            return if (file.isFile && file.canExecute()) {
                RipgrepExecutableResolution.Success(file.absolutePath)
            } else {
                RipgrepExecutableResolution.Failure("Configured ripgrep path is not executable: $configuredPath")
            }
        }

        val executable = PathEnvironmentVariableUtil.findInPath("rg")?.absolutePath
            ?: return RipgrepExecutableResolution.Failure("ripgrep (rg) was not found on PATH. Install ripgrep or configure its path in Settings | Tools | Ripgrep Search.")
        return RipgrepExecutableResolution.Success(executable)
    }
}

private sealed interface RipgrepExecutableResolution {
    data class Success(val executable: String) : RipgrepExecutableResolution
    data class Failure(val message: String) : RipgrepExecutableResolution
}

private fun RipgrepResultCandidate.toResult(projectRoot: Path): RipgrepResult? {
    val relative = relativePath.removePrefix("./")
    val absolute = projectRoot.resolve(relative).normalize()
    val virtualFile: VirtualFile = ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
        LocalFileSystem.getInstance().findFileByIoFile(absolute.toFile())
    } ?: return null
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
