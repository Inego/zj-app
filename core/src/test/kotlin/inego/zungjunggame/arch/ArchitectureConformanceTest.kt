package inego.zungjunggame.arch

import inego.zungjunggame.runtime.FeatureDeps
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.GameFeatures
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.SpaceProvider
import inego.zungjunggame.ui.stack.ContentPolicy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Source-scanning architecture conformance test for the component-architecture refactor.
 *
 * It reads the main sources from disk (Gradle's test working directory is the `core` module
 * directory), parses `package` and `import` lines plus comment-stripped code lines, and checks
 * the rules defined in [Rule]. It never touches GDX, so it is deterministic and fast.
 *
 * Two things are asserted:
 *  1. no file violates a rule unless it is explicitly listed in [ArchitectureRatchet.allowed];
 *  2. every allowlisted path still violates its rule (no stale entries), so fixing a violation
 *     forces the corresponding allowlist entry to be deleted.
 */
class ArchitectureConformanceTest {

    private val sourceRoot = File("src/main/kotlin/inego/zungjunggame")

    private val sources: List<SourceFile> by lazy { scan() }

    @Test
    fun uiIsPure() = assertNoUnallowlistedViolations(Rule.UI_PURE)

    @Test
    fun stateIsPure() = assertNoUnallowlistedViolations(Rule.STATE_PURE)

    @Test
    fun sessionHasNoConcreteDependencies() =
        assertNoUnallowlistedViolations(Rule.SESSION_NO_CONCRETE)

    @Test
    fun featuresDoNotImportApp() = assertNoUnallowlistedViolations(Rule.FEATURES_NO_APP)

    @Test
    fun renderDoesNotImportConcreteHosts() =
        assertNoUnallowlistedViolations(Rule.RENDER_NO_CONCRETE_HOST)

    @Test
    fun noSettableKind() = assertNoUnallowlistedViolations(Rule.NO_SETTABLE_KIND)

    @Test
    fun noHooksInterfaces() = assertNoUnallowlistedViolations(Rule.NO_HOOKS)

    @Test
    fun noComponentImplementsDisposable() =
        assertNoUnallowlistedViolations(Rule.NO_DISPOSABLE_COMPONENT)

    @Test
    fun tutorialDoesNotImportFeatureWidgets() =
        assertNoUnallowlistedViolations(Rule.TUTORIAL_NO_FEATURE_WIDGETS)

    @Test
    fun layoutDoesNotWriteQualifiedFields() =
        assertNoUnallowlistedViolations(Rule.NO_LAYOUT_OVERLAY_WRITES)

    /**
     * A route that declares [ContentPolicy.ProvidesSpace] must be backed by a
     * component implementing [SpaceProvider]. Route→component is a runtime fact
     * (`RouteSpecs` maps the route id, the feature registers the component), so
     * this one rule is checked against the installed registry rather than by a
     * source scan.
     */
    @Test
    fun providerRouteIsSpaceProvider() {
        val registry = FeatureRegistry().also {
            GameFeatures.install(it, glFreeDeps())
        }
        for (route in GameFeatures.FEATURE_ROUTES) {
            if (RouteSpecs.specFor(route).content !is ContentPolicy.ProvidesSpace) continue
            assertTrue(
                registry.create(route) is SpaceProvider,
                "ProvidesSpace route ${route.key.value} must be backed by a SpaceProvider",
            )
        }
    }

    private fun glFreeDeps(): FeatureDeps = FeatureDeps(
        table = { error("no GL in arch tests: the board table provider must stay lazy") },
        hintButtonOverlay = { error("no GL in arch tests: the hint overlay provider must stay lazy") },
    )

    @Test
    fun allowlistContainsOnlyCurrentViolations() {
        val violations = violationsByRule()
        val stale = ArchitectureRatchet.allowed.entries
            .sortedBy { it.key.ordinal }
            .flatMap { (rule, paths) ->
                (paths - violations[rule].orEmpty().toSet()).sorted().map { rule to it }
            }
        assertTrue(
            stale.isEmpty(),
            buildString {
                appendLine("Allowlist has stale entries; these paths no longer violate their rule:")
                stale.forEach { (rule, path) -> appendLine("  [$rule] $path") }
                appendLine("Delete them from ArchitectureRatchet.allowed.")
            },
        )
    }

    private fun assertNoUnallowlistedViolations(rule: Rule) {
        val violations = violationsByRule()[rule].orEmpty()
        val allowed = ArchitectureRatchet.allowed[rule].orEmpty()
        val unexpected = (violations.toSet() - allowed).sorted()
        if (unexpected.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("$rule violated: ${rule.description}")
                    appendLine("${unexpected.size} file(s) are not in ArchitectureRatchet.allowed:")
                    unexpected.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Undo the new dependency, or temporarily add the path(s) above to")
                    appendLine("ArchitectureRatchet.allowed[$rule] (the allowlist may only shrink).")
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------------------------------

    private data class SourceFile(
        val path: String,
        val packageName: String?,
        val imports: List<String>,
        val code: List<String>,
    )

    private fun scan(): List<SourceFile> {
        check(sourceRoot.isDirectory) {
            "Main source root not found at ${sourceRoot.absolutePath}; Gradle test workdir is " +
                "expected to be the core module directory."
        }
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val code = stripComments(file.readText(Charsets.UTF_8).lines())
                SourceFile(
                    path = file.relativeTo(sourceRoot).invariantSeparatorsPath,
                    packageName = code.asSequence()
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("package ") }
                        ?.removePrefix("package ")
                        ?.trim(),
                    imports = code.asSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("import ") }
                        .map { it.removePrefix("import ").trim() }
                        .toList(),
                    code = code,
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    // ---------------------------------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------------------------------

    private fun violationsByRule(): Map<Rule, List<String>> {
        val byRule = mapOf(
            Rule.UI_PURE to sources.filter { under(it.path, "ui/") && it.imports.any(::forbiddenByUiOrState) },
            Rule.STATE_PURE to sources.filter { under(it.path, "state/") && it.imports.any(::forbiddenByUiOrState) },
            Rule.SESSION_NO_CONCRETE to sources.filter {
                under(it.path, "session/") && it.imports.any(::forbiddenBySession)
            },
            Rule.FEATURES_NO_APP to sources.filter {
                under(it.path, "features/") && it.imports.any(::forbiddenByFeatures)
            },
            Rule.RENDER_NO_CONCRETE_HOST to sources.filter {
                under(it.path, "render/") && it.imports.any(::importsConcreteHost)
            },
            Rule.NO_SETTABLE_KIND to sources.filter { it.code.any { line -> line.contains("setKind(") } },
            Rule.NO_HOOKS to sources.filter {
                isHostPackage(it.path) && it.code.any(::declaresOrImplementsHooks)
            },
            Rule.NO_DISPOSABLE_COMPONENT to sources.filter {
                implementsBoth(it.code, "Component", "Disposable")
            },
            Rule.TUTORIAL_NO_FEATURE_WIDGETS to sources.filter {
                under(it.path, "tutorial/") && it.imports.any(::importsFeatureWidget)
            },
            Rule.NO_LAYOUT_OVERLAY_WRITES to sources.filter {
                layoutWritesQualifiedField(it.code)
            },
        )
        return byRule.mapValues { (_, files) -> files.map { it.path }.sorted() }
    }

    private val uiOrStateForbidden = listOf(
        "app", "runtime", "features", "session", "render", "online", "tutorial", "platform", "input", "net",
    )

    private fun forbiddenByUiOrState(import: String): Boolean =
        uiOrStateForbidden.any { import.matchesPackage("inego.zungjunggame.$it") }

    private val sessionForbidden = listOf("render", "online", "tutorial", "features", "app")

    private fun forbiddenBySession(import: String): Boolean =
        sessionForbidden.any { import.startsWith("inego.zungjunggame.$it.") }

    private fun forbiddenByFeatures(import: String): Boolean =
        import.matchesPackage("inego.zungjunggame.app")

    private val concreteHosts = listOf(
        "session.OnlinePlayHost",
        "session.FreePlayHost",
        "tutorial.TutorialHost",
        "online.OnlineCoordinator",
    )

    private fun importsConcreteHost(import: String): Boolean =
        concreteHosts.any { import.endsWith(it) }

    private val hooksInterfaceRegex = Regex("""\binterface\b.*\bHooks\b""")
    private val hooksSupertypeRegex = Regex(""":\s*Hooks\b""")
    private val hooksBraceRegex = Regex("""\bHooks\s*\{""")

    private fun declaresOrImplementsHooks(line: String): Boolean =
        hooksInterfaceRegex.containsMatchIn(line) ||
            hooksSupertypeRegex.containsMatchIn(line) ||
            hooksBraceRegex.containsMatchIn(line)

    /** True when a `class` / `object` declaration's supertype list names both [a] and [b]. */
    private fun implementsBoth(code: List<String>, a: String, b: String): Boolean {
        val text = code.joinToString("\n")
        return classHeaderRegex.findAll(text).any { match ->
            val header = match.groupValues[1]
            Regex("""\b$a\b""").containsMatchIn(header) &&
                Regex("""\b$b\b""").containsMatchIn(header)
        }
    }

    private val classHeaderRegex = Regex("""\b(?:class|object)\s+\w+([\s\S]*?)\{""")

    /** True when a `class` / `object` header names [token] as a supertype. */
    private fun declaresType(code: List<String>, token: String): Boolean {
        val text = code.joinToString("\n")
        val tokenRegex = Regex("""\b$token\b""")
        return classHeaderRegex.findAll(text).any { tokenRegex.containsMatchIn(it.groupValues[1]) }
    }

    private fun declaresComponent(code: List<String>): Boolean = declaresType(code, "Component")

    private val featureWidgets = listOf(
        "TutorialCoachOverlay",
        "TutorialQuizOverlay",
        "TutorialShell",
        "ReferenceBrowserOverlay",
    )

    private fun importsFeatureWidget(import: String): Boolean =
        (import.startsWith("inego.zungjunggame.features.tutorial.") ||
            import.startsWith("inego.zungjunggame.features.reference.")) &&
            featureWidgets.any { import.endsWith(".$it") }

    /**
     * True when a `Component.layout(` body assigns to a qualified property
     * (`a.b = …`), which is a widget write from a phase that must be pure. Only
     * files that declare a `Component` are scanned; plain widget helpers that have
     * their own `layout` are not components.
     */
    private fun layoutWritesQualifiedField(code: List<String>): Boolean {
        if (!declaresComponent(code)) return false
        val text = code.joinToString("\n")
        for (decl in layoutDeclRegex.findAll(text)) {
            val open = text.indexOf('{', decl.range.last)
            if (open < 0) continue
            val close = matchingBrace(text, open) ?: continue
            val body = text.substring(open + 1, close)
            if (qualifiedAssignmentRegex.containsMatchIn(body)) return true
        }
        return false
    }

    private val layoutDeclRegex = Regex("""\bfun\s+layout\s*\(""")
    private val qualifiedAssignmentRegex = Regex("""\b[A-Za-z_]\w*\.[A-Za-z_]\w*\s*=(?!=)""")

    /** Index of the `}` matching the `{` at [open], or null. */
    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun under(path: String, prefix: String) = path.startsWith(prefix)

    private fun isHostPackage(path: String) =
        under(path, "session/") || under(path, "online/") || under(path, "tutorial/")

    /** Matches an import of [base] itself or of any type/wildcard directly in [base]. */
    private fun String.matchesPackage(base: String) = this == base || startsWith("$base.")

    /**
     * Removes `//` line comments and `/* ... */` block comments while preserving line structure.
     * String literals containing comment markers are not special-cased; the rules only look for
     * identifiers in code, so the simple stripper is sufficient and stable.
     */
    private fun stripComments(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlock = false
        for (raw in lines) {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                if (inBlock) {
                    val end = raw.indexOf("*/", i)
                    if (end < 0) {
                        i = raw.length
                    } else {
                        inBlock = false
                        i = end + 2
                    }
                } else {
                    val lineComment = raw.indexOf("//", i)
                    val blockComment = raw.indexOf("/*", i)
                    when {
                        lineComment >= 0 && (blockComment !in 0..lineComment) -> i = raw.length
                        blockComment >= 0 -> {
                            inBlock = true
                            i = blockComment + 2
                        }
                        else -> {
                            sb.append(raw, i, raw.length)
                            i = raw.length
                        }
                    }
                }
            }
            out.add(sb.toString())
        }
        return out
    }
}
