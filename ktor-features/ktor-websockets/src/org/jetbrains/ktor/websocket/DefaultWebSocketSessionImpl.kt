package org.jetbrains.ktor.websocket

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.channels.Channel
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.cio.*
import java.time.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.properties.*

internal class DefaultWebSocketSessionImpl(val raw: WebSocketSession,
                                           val hostContext: CoroutineContext,
                                           val userAppContext: CoroutineContext,
                                           val pool: ByteBufferPool
) : DefaultWebSocketSession, WebSocketSession by raw {

    private val pinger = AtomicReference<ActorJob<Frame.Pong>?>(null)
    private val closeReasonRef = ConflatedChannel<CloseReason>()
    private val filtered = Channel<Frame>(8)
    private val outgoingToBeProcessed = Channel<Frame>(8)

    override val incoming: ReceiveChannel<Frame> get() = filtered
    override val outgoing: SendChannel<Frame> get() = outgoingToBeProcessed

    override var timeout: Duration = Duration.ofSeconds(15)
    override val closeReason: CloseReason? get() = closeReasonRef.poll()
    override var pingInterval: Duration? by Delegates.observable<Duration?>(null, { _, _, _ ->
        runPinger()
    })

    suspend fun run(handler: suspend DefaultWebSocketSession.() -> Unit) {
        runPinger()
        val ponger = ponger(hostContext, this, NoPool)
        val closeSequence = closeSequence(hostContext, raw, { timeout }, { reason ->
            closeReasonRef.offer(reason ?: CloseReason(CloseReason.Codes.NORMAL, ""))
        })

        launch(Unconfined) {
            try {
                raw.incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Close -> closeSequence.send(CloseFrameEvent.Received(frame))
                        is Frame.Pong -> pinger.get()?.send(frame)
                        is Frame.Ping -> ponger.send(frame)
                        else -> filtered.send(frame)
                    }
                }
            } catch (ignore: ClosedSendChannelException) {
            } catch (t: Throwable) {
                filtered.close(t)
            } finally {
                ponger.close()
                filtered.close()
                closeSequence.close()
            }
        }

        launch(Unconfined) {
            try {
                outgoingToBeProcessed.consumeEach { frame ->
                    when (frame) {
                        is Frame.Close -> closeSequence.send(CloseFrameEvent.ToSend(frame))
                        else -> raw.outgoing.send(frame)
                    }
                }
            } catch (ignore: ClosedSendChannelException) {
            } catch (ignore: ClosedReceiveChannelException) {
            } catch (ignore: CancellationException) {
            } catch (t: Throwable) {
                raw.outgoing.close(t)
            } finally {
                raw.outgoing.close()
            }
        }

        launch(userAppContext) {
            val t = try {
                handler()
                null
            } catch (t: Throwable) {
                t
            }

            val reason = when (t) {
                null -> CloseReason(CloseReason.Codes.NORMAL, "OK")
                is ClosedReceiveChannelException, is ClosedSendChannelException -> null
                else -> CloseReason(CloseReason.Codes.UNEXPECTED_CONDITION, t.message ?: t.javaClass.name)
            }

            if (t != null) {
                application.log.error("Websocket handler failed", t)
            }

            cancelPinger()

            if (reason != null) {
                closeSequence.send(CloseFrameEvent.ToSend(Frame.Close(reason)))
            } else {
                closeSequence.close()
            }
        }

        try {
            closeReasonRef.receive()
            closeSequence.join()
        } finally {
            cancelPinger()
            raw.terminate()
        }
    }

    private fun runPinger() {
        if (closeReasonRef.poll() == null) {
            val newPinger = pingInterval?.let { interval -> pinger(hostContext, raw, interval, timeout, pool, raw.outgoing) }
            pinger.getAndSet(newPinger)?.cancel()
            newPinger?.start()
        } else {
            cancelPinger()
        }
    }

    private fun cancelPinger() {
        pinger.getAndSet(null)?.cancel()
    }
}