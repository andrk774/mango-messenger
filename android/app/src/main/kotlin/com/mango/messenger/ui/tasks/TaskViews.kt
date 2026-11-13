package com.mango.messenger.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mango.core.designsystem.component.label
import com.mango.core.model.Task
import com.mango.core.model.TaskId
import com.mango.core.model.TaskStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal fun tasksOnDate(tasks: List<Task>, date: LocalDate, zone: ZoneId): List<Task> =
    tasks.filter { it.dueAt?.atZone(zone)?.toLocalDate() == date }

@Composable
internal fun TaskBoard(tasks: List<Task>, onOpen: (TaskId) -> Unit, onStatus: (TaskId, TaskStatus) -> Unit) {
    LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TaskStatus.entries) { status ->
            val column = tasks.filter { it.status == status }
            Column(Modifier.width(310.dp).fillMaxHeight()) {
                Text("${status.label()} · ${column.size}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                TaskList(column, onOpen, onStatus)
            }
        }
    }
}

@Composable
internal fun TaskCalendar(tasks: List<Task>, onOpen: (TaskId) -> Unit, onStatus: (TaskId, TaskStatus) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(selected)
    val month = YearMonth.from(date)
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = date.minusMonths(1).toString() }) { Text("←") }
            TextButton(onClick = { selected = LocalDate.now().toString() }) { Text(month.toString()) }
            TextButton(onClick = { selected = date.plusMonths(1).toString() }) { Text("→") }
        }
        Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f).padding(8.dp)) } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        repeat(weeks) { week ->
            Row {
                repeat(7) { weekday ->
                    val day = week * 7 + weekday - offset + 1
                    if (day in 1..month.lengthOfMonth()) {
                        val value = month.atDay(day)
                        val count = tasksOnDate(tasks, value, zone).size
                        TextButton(onClick = { selected = value.toString() }, modifier = Modifier.weight(1f)) {
                            Text("$day${if (count > 0) "•" else ""}", color = if (date == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("$date · без срока: ${tasks.count { it.dueAt == null }}", Modifier.padding(horizontal = 16.dp))
        val dated = tasksOnDate(tasks, date, zone)
        if (dated.isEmpty()) Text("На этот день задач нет", Modifier.padding(16.dp))
        else TaskList(dated, onOpen, onStatus)
    }
}
