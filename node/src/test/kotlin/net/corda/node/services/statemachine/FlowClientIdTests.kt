package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Suspendable
import co.paralleluniverse.strands.concurrent.Semaphore
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.FlowIORequest
import net.corda.core.serialization.MissingSerializerException
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.node.services.persistence.DBCheckpointStorage
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.node.InMemoryMessagingNetwork
import net.corda.testing.node.internal.DUMMY_CONTRACTS_CORDAPP
import net.corda.testing.node.internal.FINANCE_CONTRACTS_CORDAPP
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlowWithClientId
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import rx.Observable
import java.lang.IllegalStateException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowClientIdTests {

    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode

    @Before
    fun setUpMockNet() {
        mockNet = InternalMockNetwork(
            cordappsForAllNodes = listOf(DUMMY_CONTRACTS_CORDAPP, FINANCE_CONTRACTS_CORDAPP),
            servicePeerAllocationStrategy = InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin()
        )

        aliceNode = mockNet.createNode(InternalMockNodeParameters(legalName = ALICE_NAME))

    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
        ResultFlow.hook = null
        ResultFlow.suspendableHook = null
        UnSerializableResultFlow.firstRun = true
        SingleThreadedStateMachineManager.beforeClientIDCheck = null
        SingleThreadedStateMachineManager.onClientIDNotFound = null
        SingleThreadedStateMachineManager.onCallingStartFlowInternal = null
        SingleThreadedStateMachineManager.onStartFlowInternalThrewAndAboutToRemove = null
    }

    @Test(timeout=300_000)
    fun `no new flow starts if the client id provided pre exists`() {
        var counter = 0
        ResultFlow.hook = { counter++ }
        val clientId = UUID.randomUUID().toString()
        aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        Assert.assertEquals(1, counter)
    }

    @Test(timeout=300_000)
    fun `flow's result gets persisted if the flow is started with a client id`() {
        val clientId = UUID.randomUUID().toString()
        aliceNode.services.startFlowWithClientId(clientId, ResultFlow(10)).resultFuture.getOrThrow()

        aliceNode.database.transaction {
            assertEquals(1, findRecordsFromDatabase<DBCheckpointStorage.DBFlowResult>().size)
        }
    }

    @Test(timeout=300_000)
    fun `flow's result is retrievable after flow's lifetime, when flow is started with a client id - different parameters are ignored`() {
        val clientId = UUID.randomUUID().toString()
        val handle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        val clientId0 = handle0.clientId
        val flowId0 = handle0.id
        val result0 = handle0.resultFuture.getOrThrow()

        val handle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(10))
        val clientId1 = handle1.clientId
        val flowId1 = handle1.id
        val result1 = handle1.resultFuture.getOrThrow()

        Assert.assertEquals(clientId0, clientId1)
        Assert.assertEquals(flowId0, flowId1)
        Assert.assertEquals(result0, result1)
    }

    @Test(timeout=300_000)
    fun `if flow's result is not found in the database an IllegalStateException is thrown`() {
        val clientId = UUID.randomUUID().toString()
        val handle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        val flowId0 = handle0.id
        handle0.resultFuture.getOrThrow()

        // manually remove the checkpoint (including DBFlowResult) from the database
        aliceNode.database.transaction {
            aliceNode.internals.checkpointStorage.removeCheckpoint(flowId0)
        }

        assertFailsWith<IllegalStateException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        }
    }

    @Test
    fun `flow returning null gets retrieved after flow's lifetime when started with client id`() {
        val clientId = UUID.randomUUID().toString()
        aliceNode.services.startFlowWithClientId(clientId, ResultFlow(null)).resultFuture.getOrThrow()

        val flowResult = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(null)).resultFuture.getOrThrow()
        assertNull(flowResult)
    }

    @Test
    fun `flow returning Unit gets retrieved after flow's lifetime when started with client id`() {
        val clientId = UUID.randomUUID().toString()
        aliceNode.services.startFlowWithClientId(clientId, ResultFlow(Unit)).resultFuture.getOrThrow()

        val flowResult = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(Unit)).resultFuture.getOrThrow()
        assertEquals(Unit, flowResult)
    }

    @Test(timeout=300_000)
    fun `flow's result is available if reconnect after flow had retried from previous checkpoint, when flow is started with a client id`() {
        var firstRun = true
        ResultFlow.hook = {
            if (firstRun) {
                firstRun = false
                throw SQLTransientConnectionException("connection is not available")
            }
        }

        val clientId = UUID.randomUUID().toString()
        val result0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        val result1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        Assert.assertEquals(result0, result1)
    }

    @Test(timeout=300_000)
    fun `flow's result is available if reconnect during flow's retrying from previous checkpoint, when flow is started with a client id`() {
        var firstRun = true
        val waitForSecondRequest = Semaphore(0)
        val waitUntilFlowHasRetried = Semaphore(0)
        ResultFlow.suspendableHook = object : FlowLogic<Unit>() {
            @Suspendable
            override fun call() {
                if (firstRun) {
                    firstRun = false
                    throw SQLTransientConnectionException("connection is not available")
                } else {
                    waitUntilFlowHasRetried.release()
                    waitForSecondRequest.acquire()
                }
            }
        }

        var result1 = 0
        val clientId = UUID.randomUUID().toString()
        val handle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        waitUntilFlowHasRetried.acquire()
        val t = thread { result1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow() }

        Thread.sleep(1000)
        waitForSecondRequest.release()
        val result0 = handle0.resultFuture.getOrThrow()
        t.join()
        Assert.assertEquals(result0, result1)
    }

    @Ignore // this is to be unignored upon implementing CORDA-3681
    @Test(timeout=300_000)
    fun `flow's exception is available after flow's lifetime if flow is started with a client id`() {
        ResultFlow.hook = { throw IllegalStateException() }
        val clientId = UUID.randomUUID().toString()

        assertFailsWith<IllegalStateException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        }

        assertFailsWith<IllegalStateException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
        }
    }

    @Test(timeout=300_000)
    fun `flow's client id mapping gets removed upon request`() {
        val clientId = UUID.randomUUID().toString()
        var counter = 0
        ResultFlow.hook = { counter++ }
        val flowHandle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        flowHandle0.resultFuture.getOrThrow(20.seconds)
        val removed = aliceNode.smm.removeClientId(clientId)
        // On new request with clientId, after the same clientId was removed, a brand new flow will start with that clientId
        val flowHandle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        flowHandle1.resultFuture.getOrThrow(20.seconds)

        assertTrue(removed)
        Assert.assertNotEquals(flowHandle0.id, flowHandle1.id)
        Assert.assertEquals(flowHandle0.clientId, flowHandle1.clientId)
        Assert.assertEquals(2, counter)
    }

    @Test(timeout=300_000)
    fun `flow's client id mapping can only get removed once the flow gets removed`() {
        val clientId = UUID.randomUUID().toString()
        var tries = 0
        val maxTries = 10
        var failedRemovals = 0
        val semaphore = Semaphore(0)
        ResultFlow.suspendableHook = object : FlowLogic<Unit>() {
            @Suspendable
            override fun call() {
                semaphore.acquire()
            }
        }
        val flowHandle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))

        var removed = false
        while (!removed) {
            removed = aliceNode.smm.removeClientId(clientId)
            if (!removed) ++failedRemovals
            ++tries
            if (tries >= maxTries) {
                semaphore.release()
                flowHandle0.resultFuture.getOrThrow(20.seconds)
            }
        }

        assertTrue(removed)
        Assert.assertEquals(maxTries, failedRemovals)
    }

    @Test(timeout=300_000)
    fun `only one flow starts upon concurrent requests with the same client id`() {
        val requests = 2
        val counter = AtomicInteger(0)
        val resultsCounter = AtomicInteger(0)
        ResultFlow.hook = { counter.incrementAndGet() }
        //(aliceNode.smm as SingleThreadedStateMachineManager).concurrentRequests = true

        val clientId = UUID.randomUUID().toString()
        val threads = arrayOfNulls<Thread>(requests)
        for (i in 0 until requests) {
            threads[i] = Thread {
                val result = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5)).resultFuture.getOrThrow()
                resultsCounter.addAndGet(result)
            }
        }

        val beforeCount = AtomicInteger(0)
        SingleThreadedStateMachineManager.beforeClientIDCheck = {
            beforeCount.incrementAndGet()
        }

        val clientIdNotFound = Semaphore(0)
        val waitUntilClientIdNotFound = Semaphore(0)
        SingleThreadedStateMachineManager.onClientIDNotFound = {
            // Only the first request should reach this point
            waitUntilClientIdNotFound.release()
            clientIdNotFound.acquire()
        }

        for (i in 0 until requests) {
            threads[i]!!.start()
        }

        waitUntilClientIdNotFound.acquire()
        for (i in 0 until requests) {
            clientIdNotFound.release()
        }

        for (thread in threads) {
            thread!!.join()
        }
        Assert.assertEquals(1, counter.get())
        Assert.assertEquals(2, beforeCount.get())
        Assert.assertEquals(10, resultsCounter.get())
    }


    @Test(timeout=300_000)
    fun `on node start -running- flows with client id are hook-able`() {
        val clientId = UUID.randomUUID().toString()
        var noSecondFlowWasSpawned = 0
        var firstRun = true
        var firstFiber: Fiber<out Any?>? = null
        val flowIsRunning = Semaphore(0)
        val waitUntilFlowIsRunning = Semaphore(0)

        ResultFlow.suspendableHook = object : FlowLogic<Unit>() {
            @Suspendable
            override fun call() {
                if (firstRun) {
                    firstFiber = Fiber.currentFiber()
                    firstRun = false
                }

                waitUntilFlowIsRunning.release()
                try {
                    flowIsRunning.acquire() // make flow wait here to impersonate a running flow
                } catch (e: InterruptedException) {
                    flowIsRunning.release()
                    throw e
                }

                noSecondFlowWasSpawned++
            }
        }

        val flowHandle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        waitUntilFlowIsRunning.acquire()
        aliceNode.internals.acceptableLiveFiberCountOnStop = 1
        val aliceNode = mockNet.restartNode(aliceNode)
        // Blow up the first fiber running our flow as it is leaked here, on normal node shutdown that fiber should be gone
        firstFiber!!.interrupt()

        waitUntilFlowIsRunning.acquire()
        // Re-hook a running flow
        val flowHandle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        flowIsRunning.release()

        Assert.assertEquals(flowHandle0.id, flowHandle1.id)
        Assert.assertEquals(clientId, flowHandle1.clientId)
        Assert.assertEquals(5, flowHandle1.resultFuture.getOrThrow(20.seconds))
        Assert.assertEquals(1, noSecondFlowWasSpawned)
    }

//    @Test(timeout=300_000)
//    fun `on node restart -paused- flows with client id are hook-able`() {
//        val clientId = UUID.randomUUID().toString()
//        var noSecondFlowWasSpawned = 0
//        var firstRun = true
//        var firstFiber: Fiber<out Any?>? = null
//        val flowIsRunning = Semaphore(0)
//        val waitUntilFlowIsRunning = Semaphore(0)
//
//        ResultFlow.suspendableHook = object : FlowLogic<Unit>() {
//            @Suspendable
//            override fun call() {
//                if (firstRun) {
//                    firstFiber = Fiber.currentFiber()
//                    firstRun = false
//                }
//
//                waitUntilFlowIsRunning.release()
//                try {
//                    flowIsRunning.acquire() // make flow wait here to impersonate a running flow
//                } catch (e: InterruptedException) {
//                    flowIsRunning.release()
//                    throw e
//                }
//
//                noSecondFlowWasSpawned++
//            }
//        }
//
//        val flowHandle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
//        waitUntilFlowIsRunning.acquire()
//        aliceNode.internals.acceptableLiveFiberCountOnStop = 1
//        // Pause the flow on node restart
//        val aliceNode = mockNet.restartNode(aliceNode,
//            InternalMockNodeParameters(
//                configOverrides = {
//                    doReturn(StateMachineManager.StartMode.Safe).whenever(it).smmStartMode
//                }
//            ))
//        // Blow up the first fiber running our flow as it is leaked here, on normal node shutdown that fiber should be gone
//        firstFiber!!.interrupt()
//
//        // Re-hook a paused flow
//        val flowHandle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
//
//        Assert.assertEquals(flowHandle0.id, flowHandle1.id)
//        Assert.assertEquals(clientId, flowHandle1.clientId)
//        aliceNode.smm.unPauseFlow(flowHandle1.id)
//        Assert.assertEquals(5, flowHandle1.resultFuture.getOrThrow(20.seconds))
//        Assert.assertEquals(1, noSecondFlowWasSpawned)
//    }

    @Test(timeout=300_000)
    fun `on node start -completed- flows with client id are hook-able`() {
        val clientId = UUID.randomUUID().toString()
        var counter = 0
        ResultFlow.hook = {
            counter++
        }

        val flowHandle0 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        flowHandle0.resultFuture.getOrThrow()
        val aliceNode = mockNet.restartNode(aliceNode)

        // Re-hook a completed flow
        val flowHandle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        val result1 = flowHandle1.resultFuture.getOrThrow(20.seconds)

        Assert.assertEquals(1, counter) // assert flow has run only once
        Assert.assertEquals(flowHandle0.id, flowHandle1.id)
        Assert.assertEquals(clientId, flowHandle1.clientId)
        Assert.assertEquals(5, result1)
    }

    @Test(timeout=300_000)
    fun `On 'startFlowInternal' throwing, subsequent request with same client id does not get de-duplicated and starts a new flow`() {
        val clientId = UUID.randomUUID().toString()
        var firstRequest = true
        SingleThreadedStateMachineManager.onCallingStartFlowInternal = {
            if (firstRequest) {
                firstRequest = false
                throw IllegalStateException("Yet another one")
            }
        }
        var counter = 0
        ResultFlow.hook = { counter++ }

        assertFailsWith<IllegalStateException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        }

        val flowHandle1 = aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
        flowHandle1.resultFuture.getOrThrow(20.seconds)

        assertEquals(clientId, flowHandle1.clientId)
        assertEquals(1, counter)
    }

//    @Test(timeout=300_000)
//    fun `On 'startFlowInternal' throwing, subsequent request with same client hits the time window in which the previous request was about to remove the client id mapping`() {
//        val clientId = UUID.randomUUID().toString()
//        var firstRequest = true
//        SingleThreadedStateMachineManager.onCallingStartFlowInternal = {
//            if (firstRequest) {
//                firstRequest = false
//                throw IllegalStateException("Yet another one")
//            }
//        }
//
//        val wait = Semaphore(0)
//        val waitForFirstRequest = Semaphore(0)
//        SingleThreadedStateMachineManager.onStartFlowInternalThrewAndAboutToRemove = {
//            waitForFirstRequest.release()
//            wait.acquire()
//            Thread.sleep(10000)
//        }
//        var counter = 0
//        ResultFlow.hook = { counter++ }
//
//        thread {
//            assertFailsWith<IllegalStateException> {
//                aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
//            }
//        }
//
//        waitForFirstRequest.acquire()
//        wait.release()
//        assertFailsWith<IllegalStateException> {
//            // the subsequent request will not hang on a never ending future, because the previous request ,upon failing, will also complete the future exceptionally
//            aliceNode.services.startFlowWithClientId(clientId, ResultFlow(5))
//        }
//
//        assertEquals(0, counter)
//    }

    // This test needs modification once CORDA-3681 is implemented to check that 'node_flow_exceptions' gets a row
    @Test(timeout=300_000)
    fun `if flow fails to serialize its result then the result gets converted to an exception result`() {
        val clientId = UUID.randomUUID().toString()
        assertFailsWith<MissingSerializerException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow<Observable<Unit>>(Observable.empty())).resultFuture.getOrThrow()
        }

        // flow has failed to serialize its result => table 'node_flow_results' should be empty, 'node_flow_exceptions' should get one row instead
        aliceNode.services.database.transaction {
            val checkpointStatus = findRecordsFromDatabase<DBCheckpointStorage.DBFlowCheckpoint>().single().status
            assertEquals(Checkpoint.FlowStatus.FAILED, checkpointStatus)
            assertEquals(0, findRecordsFromDatabase<DBCheckpointStorage.DBFlowResult>().size)
            // uncomment the below line once CORDA-3681 is implemented
            //assertEquals(1, findRecordsFromDatabase<DBCheckpointStorage.DBFlowException>().size)
        }

        assertFailsWith<MissingSerializerException> {
            aliceNode.services.startFlowWithClientId(clientId, ResultFlow<Observable<Unit>>(Observable.empty())).resultFuture.getOrThrow()
        }
    }

    // This test is now redundant since, upon error at serialization of result we error and propagate
//    @Test
//    fun `flow failing to serialize its result gets retried and succeeds if returning a different result`() {
//        val clientId = UUID.randomUUID().toString()
//        val result = aliceNode.services.startFlowWithClientId(clientId, UnSerializableResultFlow(5)).resultFuture.getOrThrow()
//        assertEquals(5, result)
//    }
}

internal class ResultFlow<A>(private val result: A): FlowLogic<A>() {
    companion object {
        var hook: (() -> Unit)? = null
        var suspendableHook: FlowLogic<Unit>? = null
    }

    @Suspendable
    override fun call(): A {
        hook?.invoke()
        suspendableHook?.let { subFlow(it) }
        return result
    }
}

internal class UnSerializableResultFlow(private val serializableObject: Int): FlowLogic<Any>() {
    companion object {
        var firstRun = true
    }

    @Suspendable
    override fun call(): Any {
        stateMachine.suspend(FlowIORequest.ForceCheckpoint, false)
        return if (firstRun) {
            firstRun = false
            Observable.empty<Any>()
        } else {
            serializableObject
        }
    }
}