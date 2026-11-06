package com.ai.tinymodels.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import android.database.Cursor
import org.junit.Test

/**
 * The two release lines shipped their own v11 (one added isPinned, one added
 * model_files.format). MIGRATION_11_12 must add each column only if it is
 * missing — it must not crash a user upgrading from either.
 */
class MigrationsTest {

    private fun cursorWithColumns(vararg columns: String): Cursor = mockk {
        every { getColumnIndexOrThrow("name") } returns 0
        every { moveToNext() } returnsMany columns.map { true } + listOf(false)
        every { getString(0) } returnsMany columns.toList()
        every { close() } returns Unit
    }

    private fun db(modelFileColumns: List<String>, chatColumns: List<String>): SupportSQLiteDatabase = mockk {
        every { query("PRAGMA table_info(model_files)") } returns cursorWithColumns(*modelFileColumns.toTypedArray())
        every { query("PRAGMA table_info(chats)") } returns cursorWithColumns(*chatColumns.toTypedArray())
        every { execSQL(any()) } returns Unit
    }

    @Test
    fun `adds format when model_files lacks it (fork shipped isPinned path)`() {
        val db = db(
            modelFileColumns = listOf("modelId", "fileName"),
            chatColumns = listOf("id", "title", "isPinned")
        )
        Migrations.MIGRATION_11_12.migrate(db)
        verify { db.execSQL("ALTER TABLE model_files ADD COLUMN format TEXT NOT NULL DEFAULT 'LITERTLM'") }
        verify(exactly = 0) { db.execSQL(match { it.contains("isPinned") }) }
    }

    @Test
    fun `adds isPinned when chats lacks it (fork shipped format path)`() {
        val db = db(
            modelFileColumns = listOf("modelId", "fileName", "format"),
            chatColumns = listOf("id", "title")
        )
        Migrations.MIGRATION_11_12.migrate(db)
        verify { db.execSQL("ALTER TABLE chats ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0") }
        verify(exactly = 0) { db.execSQL(match { it.contains("model_files") }) }
    }

    @Test
    fun `adds both columns when neither is present (fresh v11 from main)`() {
        val db = db(
            modelFileColumns = listOf("modelId", "fileName"),
            chatColumns = listOf("id", "title")
        )
        Migrations.MIGRATION_11_12.migrate(db)
        verify { db.execSQL("ALTER TABLE model_files ADD COLUMN format TEXT NOT NULL DEFAULT 'LITERTLM'") }
        verify { db.execSQL("ALTER TABLE chats ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0") }
    }

    @Test
    fun `adds nothing when both columns already exist (fork that already ran the real v12)`() {
        val db = db(
            modelFileColumns = listOf("modelId", "fileName", "format"),
            chatColumns = listOf("id", "title", "isPinned")
        )
        Migrations.MIGRATION_11_12.migrate(db)
        verify(exactly = 0) { db.execSQL(any<String>()) }
    }
}
