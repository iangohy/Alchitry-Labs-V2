import helpers.SimpleLucidTester
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BoundsParserTests {
    @Test
    fun testBitSelectorConst() {
        var test = SimpleLucidTester("[0:0]")
        var tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(listOf(0..0), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasNoIssues)

        test = SimpleLucidTester("[5:0]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(listOf(0..5), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasNoIssues)

        test = SimpleLucidTester("[0:5]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)
        assert(test.hasNoWarnings)
        assert(test.hasNoSyntaxIssues)

        test = SimpleLucidTester("[bx0:5]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)
        assert(test.hasNoWarnings)
        assert(test.hasNoSyntaxIssues)

        test = SimpleLucidTester("[[0:5]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasSyntaxIssues)

        test = SimpleLucidTester("[1321612161321613216354132465162316516546546516513246:0]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)
        assert(test.hasNoWarnings)
        assert(test.hasNoSyntaxIssues)
    }

    @Test
    fun testBitSelectorFixedWidth() {
        var test = SimpleLucidTester("[0+:5]")
        var tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(listOf(0..4), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasNoIssues)

        test = SimpleLucidTester("[5-:5]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(listOf(1..5), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasNoIssues)

        test = SimpleLucidTester("[5-:2]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(listOf(4..5), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasNoIssues)

        test = SimpleLucidTester("[5+:0]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)

        test = SimpleLucidTester("[bx+:0]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)
    }

    @Test
    fun testArrayIndex() {
        var test = SimpleLucidTester("[5]")
        var tree = test.bitSelection()
        test.context.walk(tree)
        assert(test.hasNoIssues)
        assertEquals(listOf(5..5), test.context.bitSelection.resolve(tree).map { it.range })

        test = SimpleLucidTester("[0]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assert(test.hasNoIssues)
        assertEquals(listOf(0..0), test.context.bitSelection.resolve(tree).map { it.range })

        test = SimpleLucidTester("[bx]")
        tree = test.bitSelection()
        test.context.walk(tree)
        assertEquals(emptyList<IntRange>(), test.context.bitSelection.resolve(tree).map { it.range })
        assert(test.hasErrors)
    }
}