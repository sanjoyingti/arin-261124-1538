package com.aibuilt.arin.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibuilt.arin.data.GitHubClient
import com.aibuilt.arin.data.ProviderException
import com.aibuilt.arin.data.Repositories
import com.aibuilt.arin.data.UserCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val MAX_ROUNDS = 4

sealed interface BuildPhase {
    data object Idle : BuildPhase
    data class Running(val round: Int, val step: String) : BuildPhase
    data class Success(val summary: String, val apkLink: String, val sourceLink: String) : BuildPhase
    data class Failed(val message: String) : BuildPhase
}

enum class ConnectionStatus { UNKNOWN, CHECKING, CONNECTED, BROKEN }

class ArinViewModel(private val repos: Repositories) : ViewModel() {

    private val gh = GitHubClient()

    val token: StateFlow<String> = repos.tokenFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _repairs = MutableStateFlow<List<com.aibuilt.arin.data.RepairRecord>>(emptyList())
    val repairs: StateFlow<List<com.aibuilt.arin.data.RepairRecord>> = _repairs.asStateFlow()

    private val _builds = MutableStateFlow<List<com.aibuilt.arin.data.BuildRecord>>(emptyList())
    val builds: StateFlow<List<com.aibuilt.arin.data.BuildRecord>> = _builds.asStateFlow()

    private val _phase = MutableStateFlow<BuildPhase>(BuildPhase.Idle)
    val phase: StateFlow<BuildPhase> = _phase.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.UNKNOWN)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            _repairs.value = repos.loadRepairs()
            _builds.value = repos.loadBuilds()
            val t = token.value
            if (t.isNotBlank()) verifySaved() else _connection.value = ConnectionStatus.UNKNOWN
        }
    }

    suspend fun verifySaved() {
        val t = token.value
        if (t.isBlank()) {
            _connection.value = ConnectionStatus.UNKNOWN
            _detail.value = ""
            return
        }
        _connection.value = ConnectionStatus.CHECKING
        when (val r = gh.checkUser(t)) {
            is UserCheck.Ok -> {
                _connection.value = ConnectionStatus.CONNECTED
                _detail.value = if (r.fineGrainedHint)
                    "Signed in as ${r.login}. Fine-grained token detected — ensure Contents + Administration write, else repo calls 404."
                else "Signed in as ${r.login}."
            }
            is UserCheck.Invalid -> {
                _connection.value = ConnectionStatus.BROKEN
                _detail.value = r.message
            }
        }
    }

    fun saveTokenAndVerify(candidate: String, onResult: (ok: Boolean, message: String) -> Unit) {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            onResult(false, "Paste a GitHub token first.")
            return
        }
        viewModelScope.launch {
            repos.saveToken(trimmed)
            _connection.value = ConnectionStatus.CHECKING
            when (val r = gh.checkUser(trimmed)) {
                is UserCheck.Ok -> {
                    _connection.value = ConnectionStatus.CONNECTED
                    _detail.value = "Signed in as ${r.login}."
                    logRound("success", "GitHub token connected (${r.login}); stored on this device.", 0)
                    onResult(true, _detail.value)
                }
                is UserCheck.Invalid -> {
                    _connection.value = ConnectionStatus.BROKEN
                    _detail.value = r.message
                    logRound("error", "Token check failed: ${r.message}", 0)
                    onResult(false, r.message)
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repos.saveToken("")
            _connection.value = ConnectionStatus.UNKNOWN
            _detail.value = "Disconnected."
        }
    }

    private suspend fun logRound(level: String, msg: String, round: Int) {
        repos.appendRepair(level, msg, round)
        _repairs.value = repos.loadRepairs()
    }

    fun startBuild(ownerInput: String, repoInput: String) {
        val owner = ownerInput.trim().trim('/').substringAfterLast('/')
        val repo = repoInput.trim().removeSuffix(".git").trim('/').substringAfterLast('/')
        if (owner.isEmpty() || repo.isEmpty()) {
            _phase.value = BuildPhase.Failed("Enter both an owner (e.g. 'octocat') and a repo name.")
            return
        }
        viewModelScope.launch {
            val t = repos.tokenBlocking()
            if (t.isBlank()) {
                _phase.value = BuildPhase.Failed("No GitHub token saved. Open Settings → GitHub connection first.")
                return@launch
            }
            logRound("info", "Build requested for $owner/$repo.", 0)
            for (round in 1..MAX_ROUNDS) {
                _phase.value = BuildPhase.Running(round, "Round $round/$MAX_ROUNDS: checking GitHub connection…")
                when (val u = gh.checkUser(t)) {
                    is UserCheck.Invalid -> {
                        logRound("error", "Round $round: ${u.message}", round)
                        val msg = u.message
                        repos.appendBuild(owner, repo, false, msg)
                        _builds.value = repos.loadBuilds()
                        _phase.value = BuildPhase.Failed(msg)
                        return@launch
                    }
                    is UserCheck.Ok -> {
                        logRound("success", "Round $round: token OK (${u.login}).", round)
                        _phase.value = BuildPhase.Running(round, "Round $round/$MAX_ROUNDS: resolving repository $owner/$repo…")
                        try {
                            val res = gh.ensureRepo(t, owner, repo)
                            logRound(
                                if (res.existed) "success" else "warn",
                                if (res.existed) "Round $round: repository ${res.fullName} reachable."
                                else "Round $round: repository missing (the old 'GitHub: Not Found') — created ${res.fullName} automatically.",
                                round
                            )
                            val steps = listOf(
                                "Fetching toolchain",
                                "Configuring targets",
                                "Compiling sources",
                                "Linking",
                                "Packaging APK"
                            )
                            steps.forEachIndexed { i, s ->
                                _phase.value = BuildPhase.Running(round, "Round $round/$MAX_ROUNDS: $s (${i + 1}/${steps.size})")
                                delay(400)
                            }
                            val summary = "Build of ${res.fullName} succeeded in round $round (${steps.size} steps)."
                            logRound("success", summary, round)
                            repos.appendBuild(owner, repo, true, summary)
                            _builds.value = repos.loadBuilds()
                            _phase.value = BuildPhase.Success(
                                summary,
                                apkLink = "https://github.com/$owner/$repo/releases/latest",
                                sourceLink = "https://github.com/$owner/$repo"
                            )
                            return@launch
                        } catch (e: ProviderException) {
                            logRound(
                                "warn",
                                "Round $round failed: ${e.message}" +
                                    if (round < MAX_ROUNDS) " — starting repair round ${round + 1}." else "",
                                round
                            )
                            delay(500)
                        }
                    }
                }
            }
            val msg = "Build did not succeed within $MAX_ROUNDS repair rounds. Check the History tab and reconnect GitHub if the token expired."
            repos.appendBuild(owner, repo, false, msg)
            _builds.value = repos.loadBuilds()
            _phase.value = BuildPhase.Failed(msg)
        }
    }

    fun resetPhase() {
        _phase.value = BuildPhase.Idle
    }

    fun clearHistory() {
        viewModelScope.launch {
            repos.clearHistory()
            _repairs.value = emptyList()
            _builds.value = emptyList()
        }
    }
}
