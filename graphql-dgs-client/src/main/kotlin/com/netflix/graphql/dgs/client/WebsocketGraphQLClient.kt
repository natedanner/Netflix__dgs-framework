/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.netflix.graphql.types.subscription.*
import graphql.GraphQLException
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Reactive client implementation using websockets and the subscription-transport-ws protocol:
 * https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md
 */
class WebsocketGraphQLClient(
    private val client: OperationMessageWebSocketClient,
    private val acknowledgementTimeout: Duration = DEFAULT_ACKNOWLEDGEMENT_TIMEOUT
) : ReactiveGraphQLClient {
    companion object {
        private val DEFAULT_ACKNOWLEDGEMENT_TIMEOUT = Duration.ofSeconds(30)
        private val CONNECTION_INIT_MESSAGE = OperationMessage(GQL_CONNECTION_INIT, null, null)
        private val MAPPER = jacksonObjectMapper()
    }

    constructor(
        url: String,
        client: WebSocketClient? = null,
        acknowledgementTimeout: Duration = DEFAULT_ACKNOWLEDGEMENT_TIMEOUT
    ) :
        this(OperationMessageWebSocketClient(url, client ?: ReactorNettyWebSocketClient()), acknowledgementTimeout)

    private val subscriptionCount = AtomicLong(0L)

    // The handshake represents a connection to the server, it is cached so that there is one per client instance.
    // The handshake only completes once the connection has been establishes and a GQL_CONNECTION_ACK message has been
    // recieved from the server
    private val handshake = Mono.defer {
        client.send(CONNECTION_INIT_MESSAGE)
        client.receive()
            .take(1)
            .map {
                if (it.type == GQL_CONNECTION_ACK)
                    it
                else
                    throw GraphQLException("Acknowledgement expected from server, received $it")
            }
            .timeout(acknowledgementTimeout)
            .then()
    }.cache()

    override fun reactiveExecuteQuery(
        query: String,
        variables: Map<String, Any>,
    ): Flux<GraphQLResponse> {
        return reactiveExecuteQuery(query, variables, null)
    }

    override fun reactiveExecuteQuery(
        query: String,
        variables: Map<String, Any>,
        operationName: String?,
    ): Flux<GraphQLResponse> {
        // Generate a unique number for each subscription in the same session.
        val subscriptionId = subscriptionCount
            .incrementAndGet()
            .toString()
        val queryMessage = OperationMessage(
            GQL_START,
            QueryPayload(variables, emptyMap(), operationName, query),
            subscriptionId
        )
        val stopMessage = OperationMessage(GQL_STOP, null, subscriptionId)

        // Because handshake is cached it should have only been done once, all subsequent calls to
        // reactiveExecuteQuery() will proceed straight to client.receive()
        return handshake
            .doOnSuccess { client.send(queryMessage) }
            .thenMany(
                client.receive()
                    .filter { it.id == subscriptionId }
                    .takeUntil { it.type == GQL_COMPLETE }
                    .doOnCancel {
                        client.send(stopMessage)
                    }
                    .flatMap(this::handleMessage)
            )
    }

    private fun handleMessage(
        message: OperationMessage
    ): Flux<GraphQLResponse> {
        when (message.type) {
            // Do nothing if no data provided
            GQL_CONNECTION_ACK, GQL_CONNECTION_KEEP_ALIVE, GQL_COMPLETE -> {
                return Flux.empty()
            }
            // Convert data to GraphQLResponse
            GQL_DATA -> {
                val payload = message.payload
                // Payload can be either QueryPayload or DataPayload
                if (payload is DataPayload)
                    return Flux.just(GraphQLResponse(MAPPER.writeValueAsString(payload)))
                else
                    throw GraphQLException(
                        "Message $message has type " +
                            "GQL_DATA but not a valid data payload"
                    )
            }
            // Convert errors received from the server into exceptions, does
            // not include GraphQL execution errors which are bundled in the
            // GraphQLResponse above.
            GQL_CONNECTION_ERROR, GQL_ERROR -> {
                val errorMessage = message.payload.toString()
                throw GraphQLException(errorMessage)
            }
            // The message is invalid according to the subscriptions transport
            // protocol so it should result in an exception
            else -> {
                throw GraphQLException(
                    "Unable to handle message of type " +
                        "${message.type}. Full message: $message"
                )
            }
        }
    }
}

/**
 * Wrapper around a WebSocketClient for sending/receiving OperationMessages
 */
class OperationMessageWebSocketClient(
    private val url: String,
    private val client: WebSocketClient
) {

    companion object {
        private val MAPPER = jacksonObjectMapper()
    }

    // Sinks are used as buffers, incoming messages from the server are
    // buffered in incomingSink before being consumed. Outgoing messages
    // for the server are buffered in outgoingSink before being sent.
    private val incomingSink = Sinks
        .many()
        .multicast()
        // Flag prevents the sink from auto-cancelling on the completion of a single subscriber, see:
        // https://stackoverflow.com/questions/66671636/why-is-sinks-many-multicast-onbackpressurebuffer-completing-after-one-of-t
        .onBackpressureBuffer<OperationMessage>(Queues.SMALL_BUFFER_SIZE, false)
    private val outgoingSink = Sinks
        .many()
        .unicast()
        .onBackpressureBuffer<OperationMessage>()
    private val conn = Mono
        .defer {
            val uri = URI(url)
            client.execute(uri) { exchange(incomingSink, outgoingSink, it) }
        }
        .cache()

    /**
     * Send a message to the server, the message is buffered for sending later if connection has not been established
     * @param message The OperationMessage to send
     */
    fun send(message: OperationMessage) {
        outgoingSink
            .tryEmitNext(message)
            .orThrow()
    }

    /**
     * Stream messages from the server, lazily establish connection
     * @return Flux of OperationMessages
     */
    fun receive(): Flux<OperationMessage> {
        return Flux.defer {
            conn.subscribe()
            incomingSink.asFlux()
        }
    }

    private fun exchange(
        incomingMessages: Sinks.Many<OperationMessage>,
        outgoingMessages: Sinks.Many<OperationMessage>,
        session: WebSocketSession
    ): Mono<Void> {
        // Create chains to handle de/serialization
        val incomingDeserialized = session
            .receive()
            .map(this::decodeMessage)
            .doOnNext(incomingMessages::tryEmitNext)
        val outgoingSerialized = session
            .send(
                outgoingMessages
                    .asFlux()
                    .map { createMessage(session, it) }
            )

        // Transfer the contents of the sinks to/from the server
        return Flux
            .merge(incomingDeserialized, outgoingSerialized)
            .then()
    }

    private fun createMessage(
        session: WebSocketSession,
        message: OperationMessage
    ): WebSocketMessage {

        return session.textMessage(MAPPER.writeValueAsString(message))
    }

    private fun decodeMessage(message: WebSocketMessage): OperationMessage {
        val messageText = message.payloadAsText
        val type = object : TypeReference<OperationMessage>() {}

        return MAPPER.readValue(messageText, type)
    }
}
