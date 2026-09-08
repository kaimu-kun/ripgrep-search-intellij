package com.github.ss.ripgrepsearch.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RipgrepJsonParserTest {
    private val parser = RipgrepJsonParser()

    @Test
    fun parsesMatchWithPathContainingSpaces() {
        val result = parser.parseLine(
            """
            {"type":"match","data":{"path":{"text":"src/main/My File.kt"},"lines":{"text":"fun authentication(user)\n"},"line_number":47,"absolute_offset":100,"submatches":[{"match":{"text":"authentication"},"start":4,"end":18}]}}
            """.trim(),
        )

        val matches = assertIs<ParseResult.Matches>(result)
        assertEquals(1, matches.candidates.size)
        assertEquals("src/main/My File.kt", matches.candidates.single().relativePath)
        assertEquals(47, matches.candidates.single().lineNumberOneBased)
        assertEquals(4, matches.candidates.single().matchStartColumnIndex)
        assertEquals(18, matches.candidates.single().matchEndColumnIndex)
    }

    @Test
    fun doesNotSplitWindowsPathsOnColon() {
        val result = parser.parseLine(
            """
            {"type":"match","data":{"path":{"text":"C:\\work dir\\AuthService.kt"},"lines":{"text":"authentication(user)\n"},"line_number":1,"absolute_offset":0,"submatches":[{"match":{"text":"authentication"},"start":0,"end":14}]}}
            """.trim(),
        )

        val matches = assertIs<ParseResult.Matches>(result)
        assertEquals("C:/work dir/AuthService.kt", matches.candidates.single().relativePath)
    }

    @Test
    fun convertsUtf8ByteOffsetsToUtf16Columns() {
        val result = parser.parseLine(
            """
            {"type":"match","data":{"path":{"text":"unicode.kt"},"lines":{"text":"val s = \"é authentication 😀\"\n"},"line_number":3,"absolute_offset":0,"submatches":[{"match":{"text":"authentication"},"start":12,"end":26}]}}
            """.trim(),
        )

        val matches = assertIs<ParseResult.Matches>(result)
        assertEquals(11, matches.candidates.single().matchStartColumnIndex)
        assertEquals(25, matches.candidates.single().matchEndColumnIndex)
    }

    @Test
    fun createsOneCandidatePerSubmatch() {
        val result = parser.parseLine(
            """
            {"type":"match","data":{"path":{"text":"AuthService.kt"},"lines":{"text":"auth auth\n"},"line_number":10,"absolute_offset":0,"submatches":[{"match":{"text":"auth"},"start":0,"end":4},{"match":{"text":"auth"},"start":5,"end":9}]}}
            """.trim(),
        )

        val matches = assertIs<ParseResult.Matches>(result)
        assertEquals(2, matches.candidates.size)
        assertEquals(0, matches.candidates[0].matchStartColumnIndex)
        assertEquals(5, matches.candidates[1].matchStartColumnIndex)
    }

    @Test
    fun ignoresNonMatchMessages() {
        assertEquals(ParseResult.Ignored, parser.parseLine("{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"a.kt\"}}}"))
    }

    @Test
    fun reportsMalformedJson() {
        assertEquals(ParseResult.Malformed, parser.parseLine("not json"))
    }

    @Test
    fun convertsOneBasedToZeroBasedCoordinates() {
        val candidate = RipgrepResultCandidate(
            relativePath = "a.kt",
            lineNumberOneBased = 12,
            lineText = "hello",
            matchStartColumnIndex = 3,
            matchEndColumnIndex = 5,
        )

        assertEquals(11, candidate.lineIndex)
        assertEquals(3, candidate.columnIndex)
        assertEquals(4, candidate.columnNumberOneBased)
    }
}
