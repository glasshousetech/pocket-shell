package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Test

class TypedCaseTest {
    @Test fun keyboardCannotCapitalizeTheFirstLetterOfACommand() {
        assertEquals("ls ", TypedCase.keep("ls", "Ls "))
        assertEquals("git", TypedCase.keep("git", "Git"))
        assertEquals("i ", TypedCase.keep("i", "I "))
    }

    @Test fun keyboardCannotChangeCaseInEitherDirectionOrMidWord() {
        assertEquals("LS", TypedCase.keep("LS", "ls"))
        assertEquals("github", TypedCase.keep("github", "GitHub"))
        assertEquals("README.md", TypedCase.keep("README.md", "Readme.md"))
    }

    @Test fun typedCapitalsAndNewTextAreKept() {
        // The user's own shift and anything past what was typed are untouched.
        assertEquals("eE", TypedCase.keep("", "eE"))
        assertEquals("lsA", TypedCase.keep("ls", "lsA"))
        assertEquals("ls -la", TypedCase.keep("ls", "Ls -la"))
        assertEquals("Ls", TypedCase.keep("Ls", "Ls"))
    }

    @Test fun realCorrectionsStillApplyAfterTheFirstDifference() {
        assertEquals("the ", TypedCase.keep("teh", "The "))
        assertEquals("he", TypedCase.keep("hello", "He"))
        assertEquals("hello!", TypedCase.keep("hello", "Hello!"))
        assertEquals("", TypedCase.keep("ls", ""))
        assertEquals("cd", TypedCase.keep("ls", "cd"))
    }

    @Test fun unicodeIsComparedByCodePoint() {
        assertEquals("über ", TypedCase.keep("über", "Über "))
        assertEquals("a🚀b", TypedCase.keep("a🚀b", "A🚀B"))
        assertEquals("ß", TypedCase.keep("ß", "ß"))
        // Supplementary-plane letters with case (Deseret) are not split into surrogates.
        assertEquals("𐐨", TypedCase.keep("𐐨", "𐐀"))
    }
}
