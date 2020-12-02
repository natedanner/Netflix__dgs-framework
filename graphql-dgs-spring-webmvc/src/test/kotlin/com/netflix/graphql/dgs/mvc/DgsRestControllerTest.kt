package com.netflix.graphql.dgs.mvc

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.netflix.graphql.dgs.DgsQueryExecutor
import com.netflix.graphql.dgs.internal.DgsSchemaProvider
import graphql.ExecutionResultImpl
import graphql.execution.reactive.CompletionStageMappingPublisher
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus


@ExtendWith(MockKExtension::class)
class DgsRestControllerTest {
    @MockK
    lateinit var dgsQueryExecutor: DgsQueryExecutor

    @MockK
    lateinit var dgsSchemaProvider: DgsSchemaProvider

    @Test
    fun errorForSubscriptionOnGraphqlEndpoint() {
        val queryString = "subscription { stocks { name } }"
        val requestBody = """
            {
                "query": "$queryString"
            }
        """.trimIndent()

        every { dgsQueryExecutor.execute(queryString, emptyMap(), any(), any(), any()) } returns ExecutionResultImpl.newExecutionResult().data(CompletionStageMappingPublisher<String,String>(null, null)).build()

        val result = DgsRestController(dgsSchemaProvider, dgsQueryExecutor).graphql(requestBody, null, null, null, HttpHeaders())
        assertThat(result.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(result.body).isEqualTo("Trying to execute subscription on /graphql. Use /subscriptions instead!")
    }

    @Test
    fun normalFlow() {
        val queryString = "query { hello }"
        val requestBody = """
            {
                "query": "$queryString"
            }
        """.trimIndent()

        every { dgsQueryExecutor.execute(queryString, emptyMap(), any(), any(), any()) } returns ExecutionResultImpl.newExecutionResult().data(mapOf(Pair("hello", "hello"))).build()

        val result = DgsRestController(dgsSchemaProvider, dgsQueryExecutor).graphql(requestBody, null, null, null, HttpHeaders())
        val mapper = jacksonObjectMapper()
        val (data, errors) = mapper.readValue(result.body, GraphQLResponse::class.java)
        assertThat(errors.size).isEqualTo(0)
        assertThat(data["hello"]).isEqualTo("hello")
    }

    @Test
    fun `Passing a query with an operationName should execute the matching named query`() {
        val queryString = "query operationA{ hello } query operationB{ hi }"
        val requestBody = """
            {
                "query": "$queryString",
                "operationName": "operationB"
            }
        """.trimIndent()

        val capturedOperationName = slot<String>()
        every { dgsQueryExecutor.execute(queryString, emptyMap(), any(), any(), capture(capturedOperationName)) } returns ExecutionResultImpl.newExecutionResult().data(mapOf(Pair("hi", "there"))).build()

        val result = DgsRestController(dgsSchemaProvider, dgsQueryExecutor).graphql(requestBody, null, null, null, HttpHeaders())
        val mapper = jacksonObjectMapper()
        val (data, errors) = mapper.readValue(result.body, GraphQLResponse::class.java)
        assertThat(errors.size).isEqualTo(0)
        assertThat(data["hi"]).isEqualTo("there")

        assertThat(capturedOperationName.captured).isEqualTo("operationB")
    }
}


data class GraphQLResponse(val data: Map<String, Any> = emptyMap(), val errors: List<GraphQLError> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphQLError(val message: String)
