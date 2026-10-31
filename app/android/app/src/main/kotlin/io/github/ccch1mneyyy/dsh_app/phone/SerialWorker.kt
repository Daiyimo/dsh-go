package io.github.ccch1mneyyy.dsh_app.phone

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Every phone action runs here, one at a time, in a single serial queue. Each call has its own
 * time budget that excludes the time spent waiting for the person ([Scope.userWait]: confirmations,
 * pause, handoff), plus an overall cap below DSH's 660 s MCP timeout. A call that overruns is
 * interrupted; if its thread doesn't come back, the worker thread is abandoned and replaced so the
 * queue never wedges behind a stuck driver.
 */
class SerialWorker(private val name: String) {

    class Scope internal constructor() {
        internal val waited = AtomicLong()
        @Volatile internal var waitingSince = 0L

        /** Runs [block] (which waits for the person) without charging it to the call's own budget. */
        fun <T> userWait(block: () -> T): T {
            val start = now()
            waitingSince = start
            try {
                return block()
            } finally {
                waited.addAndGet(now() - start)
                waitingSince = 0L
            }
        }

        internal fun charged(startedAt: Long, at: Long): Long {
            val since = waitingSince
            return at - startedAt - waited.get() - (if (since > 0) at - since else 0)
        }
    }

    sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        class Failed(val error: Throwable) : Outcome<Nothing>
        /** Ran past its budget ([queued]: never got to start). */
        class TimedOut(val queued: Boolean) : Outcome<Nothing>
        /** Interrupted by [interruptCurrent] (stop). */
        object Interrupted : Outcome<Nothing>
    }

    private val generation = AtomicLong()
    @Volatile private var executor: ExecutorService = newExecutor()
    private val current = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val retirementLock = Any()
    private var retirementEpoch = 0L

    private fun newExecutor(): ExecutorService {
        val gen = generation.incrementAndGet()
        return Executors.newSingleThreadExecutor { r -> Thread(r, "$name-$gen").apply { isDaemon = true } }
    }

    /** Runs [job] on the worker; blocks the caller until it finishes or overruns. */
    fun <T> run(budgetMs: Long, maxTotalMs: Long, job: (Scope) -> T): Outcome<T> {
        val admittedEpoch = synchronized(retirementLock) { retirementEpoch }
        val scope = Scope()
        val submitted = now()
        val startedAt = AtomicLong(0)
        val exec = executor
        val future: Future<T> = try {
            exec.submit(Callable {
                startedAt.set(now())
                val self = Thread.currentThread()
                try {
                    synchronized(retirementLock) {
                        if (admittedEpoch != retirementEpoch) throw InterruptedException()
                        current.set(self)
                    }
                    job(scope)
                } finally {
                    synchronized(retirementLock) {
                        current.compareAndSet(self, null)
                        Thread.interrupted() // don't leak an interrupt into the next job
                    }
                }
            })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            return Outcome.Failed(e)
        }
        var lastPoll = now()
        while (true) {
            try {
                return Outcome.Done(future.get(POLL_MS, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                val t = now()
                // This phone's vendor freezer stops the whole process (StepOSFreeze) for 5–100 s at a
                // time in the middle of a task. While that happens no poll arrives, and charging the
                // gap to the call made a perfectly healthy call answer 「超时」 the instant it thawed.
                // A skipped poll is time the call did not get to use, so it goes to the same bucket
                // as waiting for the person.
                val gap = stallGap(lastPoll, t)
                if (gap > 0) {
                    scope.waited.addAndGet(gap)
                    PhoneLog.i("frozen ${gap}ms inside $name (the system stopped this app; not charged to the call)")
                }
                lastPoll = t
                val started = startedAt.get()
                if (started == 0L) {
                    if (t - submitted > maxTotalMs) {
                        future.cancel(false)
                        return Outcome.TimedOut(queued = true)
                    }
                    continue
                }
                if (scope.charged(started, t) > budgetMs || t - submitted > maxTotalMs) {
                    future.cancel(true)
                    abandonIfStuck(exec, future)
                    return Outcome.TimedOut(queued = false)
                }
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                return if (cause is InterruptedException) Outcome.Interrupted else Outcome.Failed(cause)
            } catch (_: CancellationException) {
                return Outcome.Interrupted
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                return Outcome.Interrupted
            }
        }
    }

    /** Retires already-admitted calls for this engine; later calls can use the same worker. */
    fun retire() = synchronized(retirementLock) {
        retirementEpoch++
        interruptCurrent()
    }

    /** Interrupts the job that is running now, if any (the stop button). Gates treat it as STOP. */
    fun interruptCurrent() {
        current.get()?.interrupt()
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** A cancelled future reports done at once; the thread is free only once [current] clears. */
    private fun abandonIfStuck(exec: ExecutorService, future: Future<*>) {
        val stuck = current.get() ?: return
        val deadline = now() + 1000
        while (current.get() === stuck && now() < deadline) Thread.sleep(20)
        if (current.get() === stuck && executor === exec) {
            current.compareAndSet(stuck, null)
            executor = newExecutor()
            exec.shutdownNow()
        }
    }

    companion object {
        private const val POLL_MS = 50L

        /** A poll later than this did not happen: the process was stopped by the system freezer. */
        private const val STALL_MS = 400L

        /**
         * Time between two polls that the call never got to use, because the process was not running.
         *
         * This phone's vendor freezer stops the whole process for seconds at a time in the middle of a
         * task; the poll loop only notices afterwards, as a gap between two ticks. A normal tick
         * ([POLL_MS] plus scheduling noise) is zero here; a 30 s freeze is ~30 s.
         */
        internal fun stallGap(lastPoll: Long, now: Long): Long {
            val gap = now - lastPoll - POLL_MS
            // Below [STALL_MS] this is ordinary scheduling noise, not a stopped process.
            return if (gap > STALL_MS) gap else 0L
        }
        internal fun now(): Long = System.nanoTime() / 1_000_000
    }
}
