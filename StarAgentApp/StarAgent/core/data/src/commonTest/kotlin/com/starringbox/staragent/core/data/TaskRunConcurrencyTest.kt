package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 界面心跳（每 30 秒一次）和系统闹钟都会调 [AppState.runDueTasks]。
 *
 * `runDueTasks` 先拍一张"到点任务"的快照（`due`），再**串行**挨个跑。单靠
 * [AppState.isBusy] 挡不住这整条口子：它只挡得住"同一个智能体正忙着"的那一个任务，
 * 挡不住快照里排在后面、还没轮到的**另一个**任务——那个任务在两边眼里都是"到点"的。
 *
 * 具体是这样被两路都挑中的：任务列表是 [Y（智能体 B）, X（智能体 A）]，两个都到点。
 * 1. 第一路拍到快照 [Y, X]，先跑 Y，卡在模型调用里。
 * 2. 第二路也拍到快照 [Y, X]；Y 正忙（isBusy(B)），跳过，转去跑 X，也卡住。
 * 3. 放开 X 这头：第二路跑完 X，把它排到下一期，智能体 A 不再忙。
 * 4. 放开 Y 这头：第一路跑完 Y，接着处理自己快照里的下一项——X。此时 X 已经不忙了
 *    （第二路跑完就清掉了），isBusy 这道闸拦不住，第一路把 X **又跑了一遍**。
 *
 * `dueTasksLock` 堵的正是这个口子：第二路要等第一路的 `withLock` 整段跑完才能拍自己的
 * 快照，那时候 X 已经被第一路跑过、排到了下一期，不会再出现在新快照里。
 *
 * 两个任务各给一道独立的闸（[CompletableDeferred]），逼着两路 runDueTasks 按上面的顺序
 * 真正交错——否则这条测试什么都证明不了。
 */
class TaskRunConcurrencyTest {

    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    /** 把任务的排期推到过去，等价于"时间到了"。 */
    private fun makeDue(state: AppState, taskId: String) {
        val index = state.tasks.indexOfFirst { it.id == taskId }
        state.tasks[index] = state.tasks[index].copy(status = TaskStatus.Active, nextRunAt = 1L)
    }

    @Test
    fun aTaskNotYetReachedByTheFirstCallIsNotRunTwice() = runTest {
        var callsForY = 0
        var callsForX = 0
        val yEntered = CompletableDeferred<Unit>()
        val yRelease = CompletableDeferred<Unit>()
        val xEntered = CompletableDeferred<Unit>()
        val xRelease = CompletableDeferred<Unit>()

        val state = AppState { _, history ->
            // 任务跑的时候，history 最后一条是 taskInstruction(task)，里面带着任务标题——
            // 借它分辨这次模型调用是为哪个任务打的。
            val instruction = history.lastOrNull()?.text.orEmpty()
            flow {
                when {
                    instruction.contains("任务Y") -> {
                        callsForY++
                        yEntered.complete(Unit)
                        yRelease.await()
                    }
                    instruction.contains("任务X") -> {
                        callsForX++
                        // 只有第一次调用才卡住——重现的是"第一路把它当新的一次又跑一遍"，
                        // 不需要每次都卡。
                        if (callsForX == 1) {
                            xEntered.complete(Unit)
                            xRelease.await()
                        }
                    }
                }
                emit(ChatStreamEvent.Delta("跑完了。"))
                emit(ChatStreamEvent.Completed)
            }
        }
        val index = state.providers.indexOfFirst { it.id == state.providers.first().id }
        state.providers[index] = state.providers[index].copy(
            apiKey = "test-key",
            models = listOf("test-model"),
        )

        // 两个智能体各挂一个到点任务。种子任务一律暂停掉，只留这两个，且顺序是 [Y, X]——
        // Y 在前，第一路会先挑到它；X 排在后面，正是"快照里还没轮到"的那一个。
        val agents = state.agents.map { it.id }.distinct()
        require(agents.size >= 2) { "种子里至少要两个智能体，这条用例才能各挂一个任务" }
        state.tasks.indices.forEach { i ->
            state.tasks[i] = state.tasks[i].copy(status = TaskStatus.Paused)
        }

        val taskX = state.newTask(agents[0])
        state.saveTask(
            taskX.copy(title = "任务X", recurrence = Recurrence.Daily, scheduleLabel = "每天 09:00"),
        )
        makeDue(state, taskX.id)

        val taskY = state.newTask(agents[1])
        state.saveTask(
            taskY.copy(title = "任务Y", recurrence = Recurrence.Daily, scheduleLabel = "每天 09:00"),
        )
        makeDue(state, taskY.id)

        // saveTask 把新任务插到列表最前面，所以此刻顺序是 [Y, X]——与上面注释的顺序一致。
        assertEquals(
            listOf(taskY.id, taskX.id),
            state.tasks.filter { it.id == taskX.id || it.id == taskY.id }.map { it.id },
            "这条用例要求任务顺序是 [Y, X]，不然就不是在测同一条竞态",
        )

        // 第一路：拍到 [Y, X]，先跑 Y，卡住。
        val first = launch { state.runDueTasks() }
        yEntered.await()

        // 第二路：没锁时会立刻拍到同一张快照 [Y, X]，Y 正忙会跳过，转去跑 X，也卡住；
        // 加了锁之后，它会直接卡在 withLock 上，连自己的快照都还没拍，X 根本不会被调用。
        // 两种情况都要应付，所以不假设 xEntered 一定会完成。
        val second = launch { state.runDueTasks() }

        // 探测当前是哪种情况：没锁时第二路这时已经卡在 X 里；有锁时 xEntered 永远不会完成。
        withTimeoutOrNull(1) { xEntered.await() }

        if (xEntered.isCompleted) {
            // 没有锁：先放开 X，让第二路跑完它、排到下一期、清掉智能体 A 的忙碌标记——
            // 这正是关键的时间点，第一路这时还卡在 Y 里，根本不知道 X 已经被跑过了。
            xRelease.complete(Unit)
            second.join()
        }

        // 放开 Y：第一路跑完 Y 后，接着处理它自己那张旧快照里的下一项——X。
        // 没有锁时，X 已经不忙了（第二路跑完就清掉了），isBusy 这道闸拦不住，第一路会把
        // 自己快照里那份"过时"的 X 又跑一遍（第二次调用不会再卡住，见上面的 callsForX == 1）。
        // 有锁时，第一路跑完 Y 接着跑 X（这是 X 的第一次调用，会卡在 xRelease 上），
        // 两个都跑完、排过期后才释放锁——第二路这才拿到锁、拍自己的快照，X 不会再被选中。
        yRelease.complete(Unit)
        // 有锁时第一路这会儿正卡在 X 的第一次调用里，这里放行；没有锁时 xRelease 早已放过，空操作。
        withTimeoutOrNull(1) { xEntered.await() }
        if (!xRelease.isCompleted) xRelease.complete(Unit)

        first.join()
        second.join()

        assertEquals(1, callsForY, "任务 Y 只该被跑一次，实际 $callsForY 次")
        assertEquals(
            1, callsForX,
            "任务 X 排在快照里靠后、还没轮到的位置，但也只该被跑一次——" +
                "被两路各跑一遍正是 dueTasksLock 要堵住的那个口子，实际 $callsForX 次",
        )
        val afterX = state.task(taskX.id)!!
        assertTrue((afterX.nextRunAt ?: 0L) > 1L, "跑完要把 X 的下一期排到将来")
    }
}
