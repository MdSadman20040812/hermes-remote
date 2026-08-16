package com.hermes.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.hermes.mobile.core.work.WorkModule
import com.hermes.mobile.domain.model.AgentTask
import com.hermes.mobile.domain.model.TaskStatus
import com.hermes.mobile.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TaskFilter(val label: String) {
    ALL("All"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepo: TaskRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _filter = MutableStateFlow(TaskFilter.ALL)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    val tasks: StateFlow<List<AgentTask>> =
        combine(taskRepo.observeTasks(), _filter) { tasks, filter ->
            when (filter) {
                TaskFilter.ALL -> tasks
                TaskFilter.RUNNING -> tasks.filter {
                    it.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING)
                }
                TaskFilter.COMPLETED -> tasks.filter { it.status == TaskStatus.COMPLETED }
                TaskFilter.FAILED -> tasks.filter {
                    it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun refresh() {
        WorkModule.enqueueSyncNow(workManager)
    }

    fun cancelPending() = viewModelScope.launch {
        taskRepo.cancelPendingTasks()
    }
}
