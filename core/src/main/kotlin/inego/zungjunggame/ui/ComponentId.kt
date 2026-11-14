package inego.zungjunggame.ui

/**
 * Stable string identity for a component (`"board"`, `"gameMenu"`, `"reference"`, …).
 *
 * Carries no ordering, so paint and
 * input precedence come from stack position instead of declaration order.
 */
@JvmInline
value class ComponentId(val value: String)
