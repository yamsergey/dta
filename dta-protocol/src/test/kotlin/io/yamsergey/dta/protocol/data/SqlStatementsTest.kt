package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlStatementsTest {
    @Test
    fun `statements are routed by their first keyword, comments and space skipped`() {
        assertEquals(SqlKind.QUERY, SqlStatements.kind("select * from t"))
        assertEquals(SqlKind.QUERY, SqlStatements.kind("  -- note\n /* x */ WITH a AS (SELECT 1) SELECT * FROM a"))
        assertEquals(SqlKind.QUERY, SqlStatements.kind("PRAGMA table_info(t)"))
        assertEquals(SqlKind.QUERY, SqlStatements.kind("explain query plan select 1"))
        assertEquals(SqlKind.QUERY, SqlStatements.kind("VALUES (1),(2)"))
        assertEquals(SqlKind.UPDATE_DELETE, SqlStatements.kind("update t set a=1"))
        assertEquals(SqlKind.UPDATE_DELETE, SqlStatements.kind("DELETE FROM t"))
        assertEquals(SqlKind.INSERT, SqlStatements.kind("insert into t values (1)"))
        assertEquals(SqlKind.INSERT, SqlStatements.kind("REPLACE INTO t VALUES (1)"))
        assertEquals(SqlKind.OTHER, SqlStatements.kind("CREATE TABLE x (a)"))
        assertEquals(SqlKind.OTHER, SqlStatements.kind("drop table x"))
    }

    @Test
    fun `identifiers and literals are quoted for generated statements`() {
        assertEquals("\"we\"\"ird\"", SqlStatements.identifier("we\"ird"))
        assertEquals("'it''s'", SqlStatements.literal(Cell(CellType.TEXT, "it's")))
        assertEquals("42", SqlStatements.literal(Cell(CellType.INTEGER, "42")))
        assertEquals("NULL", SqlStatements.literal(Cell.NULL))
        assertEquals("X'0aff'", SqlStatements.literal(Cell(CellType.BLOB, "Cv8=", size = 2)))
    }
}
