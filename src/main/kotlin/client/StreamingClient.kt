package org.polarmeet.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.*
import org.polarmeet.proto.StreamRequest
import org.polarmeet.proto.StreamResponse
import org.polarmeet.proto.StreamingServiceGrpc
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import io.grpc.stub.StreamObserver
import java.time.ZoneOffset
import java.util.*

class StreamingClient {
    companion object {
        private const val SERVER_HOST = "localhost"
        private const val SERVER_PORT = 9090
        private const val TOTAL_CONNECTIONS = 100000
        private const val CONNECTIONS_PER_BATCH = 100  // Reduced batch size
        private const val BATCH_DELAY_MS = 500L       // Increased delay
        private const val MONITORING_INTERVAL_SECONDS = 5L
        private const val CHANNEL_SHUTDOWN_TIMEOUT_SECONDS = 10L
        private const val MESSAGE_INTERVAL_MS = 5000L  // Interval for sending messages
    }

    // Initialize timezone explicitly
    init {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
        // Set default timezone for Java 8 time API
        java.time.ZoneId.systemDefault()
    }

    private val activeConnections = AtomicInteger(0)
    private val successfulConnections = AtomicInteger(0)
    private val failedConnections = AtomicInteger(0)
    private val receivedMessages = AtomicInteger(0)
    private val sentMessages = AtomicInteger(0)

    // Use a fixed thread pool instead of virtual threads for better stability
    private val clientDispatcher = Executors.newFixedThreadPool(32).asCoroutineDispatcher()
    private val clientScope = CoroutineScope(clientDispatcher + SupervisorJob())
    // Thread-safe channel management
    private val channels = Collections.synchronizedList(mutableListOf<ManagedChannel>())
    private val streamObservers = Collections.synchronizedList(mutableListOf<StreamObserver<StreamRequest>>())

    init {
        clientScope.launch {
            monitorConnections()
        }
    }

    private suspend fun monitorConnections() {
        while (true) {
            delay(MONITORING_INTERVAL_SECONDS.seconds)
            println("""
                |=== Client Statistics ===
                |Active Connections: ${activeConnections.get()}
                |Successful Connections: ${successfulConnections.get()}
                |Failed Connections: ${failedConnections.get()}
                |Messages Received: ${receivedMessages.get()}
                |Message Rate: ${receivedMessages.get() / MONITORING_INTERVAL_SECONDS}/s
                |Memory Usage: ${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB
                |======================
            """.trimMargin())
        }
    }

    suspend fun startMassConnections() {
        println("🚀 Starting mass connection simulation...")

        // Create channel with better configuration
        val channel = ManagedChannelBuilder.forAddress(SERVER_HOST, SERVER_PORT)
            .usePlaintext()
            .maxInboundMessageSize(1024 * 1024)     // 1MB
            .maxInboundMetadataSize(1024 * 64)      // 64KB
            .keepAliveWithoutCalls(true)
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build()

        channels.add(channel)

        try {
            repeat(TOTAL_CONNECTIONS / CONNECTIONS_PER_BATCH) { batchIndex ->
                val batchStart = batchIndex * CONNECTIONS_PER_BATCH

                // Create connections in smaller batches
                val jobs = (0 until CONNECTIONS_PER_BATCH).map { index ->
                    clientScope.launch {
                        createStreamingConnection(channel, batchStart + index)
                    }
                }

                // Wait for batch completion with timeout
                withTimeout(30_000) {
                    jobs.forEach { it.join() }
                }

                delay(BATCH_DELAY_MS)

                // Check if we should continue
                if (failedConnections.get() > 1000) {
                    println("Too many failed connections, stopping connection creation")
                    shutdown()
                }
            }

            // Start periodic message sending
            startPeriodicMessageSending()

        } catch (e: Exception) {
            println("Error during connection creation: ${e.message}")
        }
    }

    private fun startPeriodicMessageSending() {
        clientScope.launch {
            while (true) {
                delay(MESSAGE_INTERVAL_MS)
                // Randomly select some clients to send messages
                val activeStreamObservers = streamObservers.toList()
                activeStreamObservers.shuffled().take(100).forEach { observer ->
                    try {
                        val message = StreamRequest.newBuilder()
                            .setClientId("client_${System.currentTimeMillis()}")
                            .setConnectionTimestamp(System.currentTimeMillis())
                            .setMessage("Hello from client at ${System.currentTimeMillis()}")
                            .build()
                        observer.onNext(message)
                        sentMessages.incrementAndGet()
                    } catch (e: Exception) {
                        println("Error sending message: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun createStreamingConnection(
        channel: ManagedChannel,
        connectionId: Int
    ) = coroutineScope {
        try {
            val stub = StreamingServiceGrpc.newStub(channel)

            val request = StreamRequest.newBuilder()
                .setClientId("client_$connectionId")
                .setConnectionTimestamp(System.currentTimeMillis())
                .build()

            val responseHandler = object : StreamObserver<StreamResponse> {
                override fun onNext(response: StreamResponse) {
                    receivedMessages.incrementAndGet()
                    println("✅ Client $connectionId received message: ${response.data}")
                }

                override fun onError(t: Throwable) {
                    failedConnections.incrementAndGet()
                    activeConnections.decrementAndGet()
                    println("❌ Error in connection $connectionId: ${t.message}")
                }

                override fun onCompleted() {
                    activeConnections.decrementAndGet()
                    println("❎ Connection $connectionId completed")
                }
            }

            // Create bidirectional stream
            val requestObserver = stub.streamData(responseHandler)
            streamObservers.add(requestObserver)

            // Send initial message
            val initialRequest = StreamRequest.newBuilder()
                .setClientId("client_$connectionId")
                .setConnectionTimestamp(System.currentTimeMillis())
                .setMessage("Initial connection")
                .build()

            withContext(Dispatchers.IO) {
                requestObserver.onNext(initialRequest)
            }

            activeConnections.incrementAndGet()
            successfulConnections.incrementAndGet()

        } catch (e: Exception) {
            failedConnections.incrementAndGet()
            println("❌ Failed to create connection $connectionId: ${e.message}")
        }
    }

    fun shutdown() {
        println("Shutting down client...")
        clientScope.cancel()

        // Proper channel shutdown
        channels.forEach { channel ->
            try {
                channel.shutdown()
                if (!channel.awaitTermination(CHANNEL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    // If graceful shutdown fails, force immediate shutdown
                    channel.shutdownNow()
                    // Wait a bit more for forced shutdown to complete
                    channel.awaitTermination(5, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                println("Error during channel shutdown: ${e.message}")
            }
        }

        // Shutdown thread pool
        (clientDispatcher.executor as java.util.concurrent.ExecutorService).shutdown()
    }
}

fun main() = runBlocking {
    // Set system properties for better network handling
    System.setProperty("io.netty.leakDetection.level", "disabled")
    System.setProperty("io.netty.recycler.maxCapacity", "0")
    System.setProperty("io.netty.allocator.numDirectArenas", "0")

    val client = StreamingClient()

    try {
        client.startMassConnections()
        // Keep the main thread alive
        while (true) {
            delay(1000)
        }
    } catch (e: Exception) {
        println("Main loop error: ${e.message}")
    } finally {
        client.shutdown()
    }
}