package com.badlogic.gdx.graphics.glutils

/**
 * Drops the attribute-location cache on a VAO mesh.
 *
 * Lives in this package because [VertexBufferObjectWithVAO.cachedLocations] is
 * package-private and libGDX 1.14.2 has no other way to clear it. [invalidate]
 * allocates a new VAO and does not touch the cache; the next [bind] then treats
 * the old locations as still valid and skips `glVertexAttribPointer`. The new
 * VAO has no attributes, `glDrawElements` succeeds, and nothing rasterizes.
 */
internal fun VertexBufferObjectWithVAO.dropCachedAttributeLocations() {
    cachedLocations.clear()
}
