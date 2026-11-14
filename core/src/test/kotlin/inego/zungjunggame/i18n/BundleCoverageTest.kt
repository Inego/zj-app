package inego.zungjunggame.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The structural contract for the shipped UI bundles — the guard that replaced the
 * ~27 tests which pinned literal copy (see [TestBundle] for the rule and the story).
 *
 * These fail when a *translation is missing or broken*, never when copy is reworded.
 * A locale may omit a key only if the key is marked English-only in
 * `assets/i18n/untranslated-keys.txt`; other locales then fall back to the English
 * base. Adding an unmarked key to one language and not the others, or blanking a
 * value, is expected to break this.
 */
class BundleCoverageTest {

    @Test
    fun everyShippedLocaleDeclaresEveryKey() = TestBundle.assertBundlesDeclareEveryKey()

    @Test
    fun untranslatedKeysListStaysHonest() = TestBundle.assertUntranslatedListIsNotStale()

    @Test
    fun noValueIsBlankOrEchoesItsKey() = TestBundle.assertNoBlankOrKeyEchoingValues()

    @Test
    fun templatesKeepTheirPlaceholdersInEveryLocale() = TestBundle.assertTemplatesKeepTheirPlaceholders()

    @Test
    fun everyShippedBundleIsReachableFromThePicker() {
        for (tag in TestBundle.shippedTags()) {
            // Throws unless an AppLocale entry maps to this bundle's language.
            TestBundle.localeFor(tag)
        }
    }

    /** Stops the guards above passing vacuously if the bundles are renamed or truncated. */
    @Test
    fun theBundlesAreWorthGuarding() {
        val base = TestBundle.declared(TestBundle.BASE_TAG)
        assertTrue(base.size > 100, "base bundle looks truncated: ${base.size} keys")
        assertTrue(TestBundle.shippedTags().size >= 2, "expected at least two shipped locales")
    }
}
