/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.squareup.workflow

import com.squareup.workflow.WorkflowAction.Companion.emitOutput
import com.squareup.workflow.WorkflowAction.Companion.enterState
import com.squareup.workflow.testing.testFromStart
import com.squareup.workflow.util.ChannelUpdate
import com.squareup.workflow.util.ChannelUpdate.Closed
import com.squareup.workflow.util.ChannelUpdate.Value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class FlowSubscriptionsIntegrationTest {

  private class ExpectedException : RuntimeException()

  /**
   * Workflow that has a single Boolean state: whether to subscribe to [subject] or not.
   *
   * The initial value for the state is taken from the input. After that, the input is ignored.
   *
   * [render] returns a setter that will change the subscription state.
   */
  private class SubscriberWorkflow(
    subject: Flow<String>
  ) : StatefulWorkflow<Boolean, Boolean, ChannelUpdate<String>, (Boolean) -> Unit>() {

    var subscriptions = 0
      private set

    var disposals = 0
      private set

    private val flow = subject
        .onCollect { subscriptions++ }
        .onCancel { disposals++ }

    override fun initialState(
      input: Boolean,
      snapshot: Snapshot?,
      scope: CoroutineScope
    ): Boolean = input

    override fun render(
      input: Boolean,
      state: Boolean,
      context: WorkflowContext<Boolean, ChannelUpdate<String>>
    ): (Boolean) -> Unit {
      if (state) {
        context.onNext(flow) { update -> emitOutput(update) }
      }
      return context.onEvent { subscribe -> enterState(subscribe) }
    }

    override fun snapshotState(state: Boolean) = Snapshot.EMPTY
  }

  private val subject = BroadcastChannel<String>(capacity = 1)
  private val workflow = SubscriberWorkflow(subject.asFlow())

  @Test fun `flow subscribes`() {
    workflow.testFromStart(false) { host ->
      host.withNextRendering { setSubscribed ->
        assertEquals(0, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        setSubscribed(true)
      }

      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        // Should still only be subscribed once.
        setSubscribed(true)
      }

      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        setSubscribed(false)
      }

      assertEquals(1, workflow.subscriptions)
      assertEquals(1, workflow.disposals)
    }
  }

  @Test fun `flow unsubscribes`() {
    workflow.testFromStart(true) { host ->
      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        setSubscribed(false)
      }

      assertEquals(1, workflow.subscriptions)
      assertEquals(1, workflow.disposals)
    }
  }

  @Test fun `flow subscribes only once across multiple composes`() {
    workflow.testFromStart(true) { host ->
      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        // Should preserve subscriptions.
        setSubscribed(true)
      }

      host.withNextRendering {
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
      }
    }
  }

  @Test fun `flow resubscribes`() {
    workflow.testFromStart(true) { host ->
      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(0, workflow.disposals)
        setSubscribed(false)
      }

      host.withNextRendering { setSubscribed ->
        assertEquals(1, workflow.subscriptions)
        assertEquals(1, workflow.disposals)
        setSubscribed(true)
      }

      host.withNextRendering {
        assertEquals(2, workflow.subscriptions)
        assertEquals(1, workflow.disposals)
      }
    }
  }

  @Test fun `flow reports emissions`() {
    workflow.testFromStart(true) { host ->
      assertFalse(host.hasOutput)

      subject.offer("hello")
      assertEquals(Value("hello"), host.awaitNextOutput())
      assertFalse(host.hasOutput)

      subject.offer("world")
      assertEquals(Value("world"), host.awaitNextOutput())
      assertFalse(host.hasOutput)
    }
  }

  @Test fun `flow reports close`() {
    workflow.testFromStart(true) { host ->
      assertFalse(host.hasOutput)

      subject.close()
      assertEquals(Closed, host.awaitNextOutput())
      assertFalse(host.hasOutput)

      // Assert no further close events are received.
      assertFailsWith<TimeoutCancellationException> {
        host.awaitNextOutput(timeoutMs = 1)
      }
    }
  }

  @Test fun `flow reports close after emission`() {
    workflow.testFromStart(true) { host ->
      assertFalse(host.hasOutput)

      subject.offer("foo")
      assertEquals(Value("foo"), host.awaitNextOutput())

      subject.close()
      assertEquals(Closed, host.awaitNextOutput())
      assertFalse(host.hasOutput)
    }
  }

  @Test fun `flow reports error`() {
    assertFails {
      workflow.testFromStart(true) { host ->
        assertFalse(host.hasOutput)

        subject.cancel(CancellationException(null, ExpectedException()))
        assertSame(Closed, host.awaitNextOutput())
        assertFalse(host.hasOutput)
      }
    }.also { error ->
      // Search up the cause chain for the expected exception, since multiple CancellationExceptions
      // may be chained together first.
      val causeChain = generateSequence(error) { it.cause }
      assertEquals(
          1, causeChain.count { it is ExpectedException },
          "Expected cancellation exception cause chain to include original cause."
      )
    }
  }
}

// Additional operators for instrumenting Flow, emulating RxJava.

private fun <T> Flow<T>.onCollect(action: () -> Unit): Flow<T> = flow {
  action()
  collect(this)
}

private fun <T> Flow<T>.onCancel(action: () -> Unit): Flow<T> = flow {
  try {
    collect(this)
  } catch (e: CancellationException) {
    action()
    throw e
  }
}
