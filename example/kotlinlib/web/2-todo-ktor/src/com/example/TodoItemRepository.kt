package com.example

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

interface TodoItemRepository {
    suspend fun save(item: TodoItem)

    suspend fun findById(id: UUID): TodoItem

    suspend fun findAll(): List<TodoItem>

    suspend fun deleteById(id: UUID)
}

class TodoItemNotFound(
    id: String,
) : Exception("TodoItem $id not found")

class TodoItemRepositoryImpl(
    database: Database,
) : TodoItemRepository {
    object TodoItems : Table() {
        val id = uuid("id")
        val title = varchar("title", length = 50)
        val completed = bool("completed")

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(TodoItems)
        }
    }

    override suspend fun save(item: TodoItem): Unit = query {
        TodoItems.upsert {
            it[id] = item.id
            it[title] = item.title
            it[completed] = item.completed
        }
    }

    override suspend fun findById(id: UUID): TodoItem = query {
        TodoItems
            .selectAll()
            .where(TodoItems.id eq id)
            .map { TodoItem(it[TodoItems.id], it[TodoItems.title], it[TodoItems.completed]) }
            .singleOrNull() ?: throw TodoItemNotFound(id.toString())
    }

    override suspend fun findAll(): List<TodoItem> = query {
        TodoItems
            .selectAll()
            .map { TodoItem(it[TodoItems.id], it[TodoItems.title], it[TodoItems.completed]) }
    }

    override suspend fun deleteById(id: UUID): Unit = query {
        TodoItems.deleteWhere { TodoItems.id eq id }
    }

    private suspend fun <T> query(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
}
