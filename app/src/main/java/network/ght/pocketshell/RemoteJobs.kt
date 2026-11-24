package network.ght.pocketshell

import java.util.Base64
import java.util.UUID

data class RemoteJob(
    val id: String, val name: String, val profile: SshProfile, val command: String, val directory: String,
    val created: Long, val observation: JobObservation? = null, val connectionError: String? = null,
    val notifiedResult: String? = null, val watching: Boolean = true,
) {
    fun validate(): RemoteJob {
        require(UUID.fromString(id).toString() == id)
        require(name.isNotBlank() && command.isNotBlank() && !command.contains('\u0000') && !directory.contains('\u0000'))
        require(directory.isBlank() || directory.startsWith("/")) { "Use an absolute working directory, or leave it blank for the remote home." }
        require(created > 0)
        profile.command()
        observation?.validate()
        return this
    }
}
data class JobObservation(
    val state: String, val started: Long, val finished: Long, val exit: Int?, val checked: Long, val remoteDirectory: String,
) {
    val terminal get() = state in setOf("SUCCEEDED", "FAILED", "INTERRUPTED")
    val resultKey get() = when (state) { "SUCCEEDED", "FAILED" -> "$finished:$exit"; "INTERRUPTED" -> "interrupted:$started"; else -> null }
    fun validate(): JobObservation {
        require(state in setOf("MISSING", "PREPARING", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "INTERRUPTED"))
        require(started >= 0 && finished >= 0 && checked > 0 && remoteDirectory.startsWith("/") && !remoteDirectory.contains('\u0000'))
        require(exit == null || exit in 0..255)
        require((state == "SUCCEEDED" && exit == 0 && finished > 0) ||
            (state == "FAILED" && exit != null && exit != 0 && finished > 0) ||
            (state !in setOf("SUCCEEDED", "FAILED") && exit == null))
        return this
    }
}
object RemoteJobs {
    private fun quoted(value: String) = CommandTemplate.quoteArgument(value)
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private const val HEADER = "__POCKET_JOBS_V1__"
    private const val END = "__POCKET_JOBS_END__"

    fun command(profile: SshProfile, script: String, timeoutSeconds: Long = 120, interactive: Boolean = false): String {
        val target = if (profile.remoteCommand.isBlank()) script else {
            RemoteWorkspaces.scope(profile)
            val host = profile.remoteCommand.trim().removePrefix("~/.local/bin/ght-hop ")
            require(host !in setOf("alientop", "laptop")) { "Remote jobs currently run on Linux servers. Use the laptop terminal for Windows commands." }
            "~/.local/bin/ght-hop " + host + " " + quoted("sh -c " + quoted(script))
        }
        return RemoteWorkspaces.ssh(profile, target, interactive, timeoutSeconds = timeoutSeconds)
    }

    private fun baseDirectory() = """root="${'$'}HOME/.local/state/pocketshell/jobs" """

    fun launchScript(job: RemoteJob): String {
        job.validate()
        val runner = """
            #!/bin/sh
            set -u
            umask 077
            jobdir=${'$'}1
            cd "${'$'}jobdir" || exit 72
            exec >>"${'$'}jobdir/output.log" 2>&1
            boot=${'$'}(cat /proc/sys/kernel/random/boot_id) || exit 69
            ticks=${'$'}(sed 's/.*) //' "/proc/${'$'}${'$'}/stat" | awk '{print ${'$'}20}')
            started=${'$'}(date +%s)
            printf '%s %s %s %s\n' "${'$'}${'$'}" "${'$'}boot" "${'$'}ticks" "${'$'}started" > "${'$'}jobdir/running.tmp"
            mv "${'$'}jobdir/running.tmp" "${'$'}jobdir/running" || exit 74
            finish() {
                code=${'$'}?
                trap - 0
                printf '%s %s\n' "${'$'}code" "${'$'}(date +%s)" > "${'$'}jobdir/result.tmp" &&
                    mv "${'$'}jobdir/result.tmp" "${'$'}jobdir/result"
                exit "${'$'}code"
            }
            trap finish 0
            trap 'exit 143' TERM
            trap 'exit 130' INT
            cd ${quoted(job.directory.ifBlank { "." })} || exit 72
            sh "${'$'}jobdir/command.sh"
            exit ${'$'}?
        """.trimIndent()
        val actualRunner = if (job.directory.isBlank()) runner.replace("cd '.' || exit 72", """cd "${'$'}HOME" || exit 72""") else runner
        return """
            set -eu
            umask 077
            [ "${'$'}(uname -s)" = Linux ] || { printf 'Remote jobs require a Linux server.\n' >&2; exit 69; }
            for tool in nohup setsid base64 awk sed; do command -v "${'$'}tool" >/dev/null || exit 69; done
            [ -r /proc/sys/kernel/random/boot_id ] || exit 69
            ${baseDirectory()}
            mkdir -p "${'$'}root"
            jobdir="${'$'}root/${job.id}"
            if mkdir "${'$'}jobdir" 2>/dev/null; then
                printf '%s' '${encoded(job.command)}' | base64 -d > "${'$'}jobdir/command.sh"
                printf '%s' '${encoded(actualRunner)}' | base64 -d > "${'$'}jobdir/runner.sh"
                printf '%s\n' 1 > "${'$'}jobdir/ready"
                nohup setsid sh "${'$'}jobdir/runner.sh" "${'$'}jobdir" >/dev/null 2>&1 </dev/null &
            fi
            ${statusScript(listOf(job.id))}
        """.trimIndent()
    }

    fun statusScript(ids: List<String>): String {
        require(ids.isNotEmpty())
        ids.forEach { require(UUID.fromString(it).toString() == it) }
        return """
            ${baseDirectory()}
            printf '$HEADER\n'
            boot=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 69
            for id in ${ids.joinToString(" ") { quoted(it) }}; do
                jobdir="${'$'}root/${'$'}id"
                state=MISSING; started=0; finished=0; code=-
                if [ -d "${'$'}jobdir" ]; then state=PREPARING; fi
                if [ -f "${'$'}jobdir/ready" ]; then state=STARTING; fi
                if [ -f "${'$'}jobdir/running" ]; then
                    read -r pid oldboot ticks started < "${'$'}jobdir/running" || exit 74
                    case "${'$'}pid:${'$'}ticks:${'$'}started" in *[!0-9:]*|::*|:*:) exit 74 ;; esac
                    actual=${'$'}(sed 's/.*) //' "/proc/${'$'}pid/stat" 2>/dev/null | awk '{print ${'$'}1, ${'$'}20}')
                    state=INTERRUPTED
                    if [ "${'$'}boot" = "${'$'}oldboot" ]; then
                        case "${'$'}actual" in
                            "Z "*|"X "*|"x "*) ;;
                            *" ${'$'}ticks") state=RUNNING ;;
                        esac
                    fi
                fi
                if [ -f "${'$'}jobdir/result" ]; then
                    read -r code finished < "${'$'}jobdir/result" || exit 74
                    case "${'$'}code:${'$'}finished" in *[!0-9:]*|:*) exit 74 ;; esac
                    state=FAILED
                    if [ "${'$'}code" = 0 ]; then state=SUCCEEDED; fi
                fi
                location=${'$'}(printf '%s' "${'$'}jobdir" | base64 | tr -d '\n')
                printf '%s %s %s %s %s %s\n' "${'$'}id" "${'$'}state" "${'$'}started" "${'$'}finished" "${'$'}code" "${'$'}location"
            done
            printf '$END\n'
        """.trimIndent()
    }

    fun parse(result: GuestResult, ids: List<String>, now: Long = System.currentTimeMillis()): Map<String, JobObservation> {
        check(result.exitCode == 0) { "The remote job status was not confirmed. Check SSH and Linux tools before retrying." }
        val lines = result.output.lines().map { it.trimEnd('\r') }
        val start = lines.indexOf(HEADER); val end = lines.indexOfLast { it == END }
        check(start >= 0 && end > start) { "Job status was interrupted. Last verified results were kept." }
        val expected = ids.toSet()
        val values = linkedMapOf<String, JobObservation>()
        lines.subList(start + 1, end).forEach { line ->
            val fields = line.split(' ', limit = 6)
            check(fields.size == 6 && fields[0] in expected && fields[0] !in values) { "Invalid remote job response." }
            values[fields[0]] = JobObservation(fields[1], fields[2].toLong(), fields[3].toLong(),
                if (fields[4] == "-") null else fields[4].toInt(), now,
                String(Base64.getDecoder().decode(fields[5]), Charsets.UTF_8)).validate()
        }
        check(values.keys == expected) { "Some job statuses are missing. Last verified results were kept." }
        return values
    }

    fun outputScript(job: RemoteJob): String {
        job.validate()
        return baseDirectory() + "; file=\"\$root/" + job.id + "/output.log\"; " +
            "if [ ! -f \"\$file\" ]; then printf 'Output is not available yet.\\n' >&2; exit 66; fi; " +
            "if command -v less >/dev/null; then exec less -R -- \"\$file\"; else cat -- \"\$file\"; fi"
    }
}
