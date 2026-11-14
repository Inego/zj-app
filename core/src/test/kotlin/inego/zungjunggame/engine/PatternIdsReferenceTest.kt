package inego.zungjunggame.engine

import inego.zungjunggame.render.reference.ReferenceCatalog
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Every [PatternIds] constant must resolve to a Scoring reference page so the
 * hand-end score breakdown can deep-link each line without a parallel map.
 */
class PatternIdsReferenceTest {

    @Before
    fun setUp() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
    }

    @After
    fun tearDown() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
    }

    @Test
    fun everyPatternIdHasScoringReferencePage() {
        val ids = patternIdValues()
        assertTrue("PatternIds should not be empty", ids.isNotEmpty())
        val missing = ids.filter { id ->
            ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, id) == null
        }
        assertTrue(
            "PatternIds missing from Scoring reference: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun resolveHrefFindsEveryPatternId() {
        for (id in patternIdValues()) {
            val target = ReferenceCatalog.resolveHref(id)
            assertNotNull("resolveHref($id) should find a page", target)
            assertTrue(
                "resolveHref($id) should be a page, was $target",
                target is ReferenceCatalog.LinkTarget.Page,
            )
            target as ReferenceCatalog.LinkTarget.Page
            assertTrue(
                "expected Scoring for $id, got ${target.section}",
                target.section == ReferenceCatalog.Section.Scoring,
            )
        }
    }

    private fun patternIdValues(): List<String> =
        PatternIds::class.java.declaredFields
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    Modifier.isFinal(it.modifiers) &&
                    it.type == String::class.java
            }
            .map { field ->
                field.isAccessible = true
                field.get(null) as String
            }
}
