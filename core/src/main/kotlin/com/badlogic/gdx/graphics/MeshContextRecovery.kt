package com.badlogic.gdx.graphics

import com.badlogic.gdx.Application
import com.badlogic.gdx.graphics.glutils.VertexBufferObjectWithVAO
import com.badlogic.gdx.graphics.glutils.dropCachedAttributeLocations

/**
 * What [Mesh.invalidateAllMeshes] was supposed to do after a context loss, plus
 * the half libGDX 1.14.2 leaves out.
 *
 * [VertexBufferObjectWithVAO.invalidate] allocates a new buffer and a new VAO,
 * then leaves `cachedLocations` populated from the previous context. The next
 * bind sees attribute locations that still match the shader — a fresh
 * [com.badlogic.gdx.graphics.g2d.SpriteBatch] default shader gets the same
 * locations, 0/1/2 — and skips `glVertexAttribPointer`. Draws then submit
 * against an empty VAO: no GL error, no fragments.
 *
 * The TeaVM backend sets `Gdx.gl30`, so the one [com.badlogic.gdx.graphics.g2d.SpriteBatch]
 * this app draws with (menu and board both) is a `VertexBufferObjectWithVAO`.
 * Same-package access is the whole fix: `vertices` and `Mesh.meshes` are not
 * public, and the location cache is not either.
 */
internal fun invalidateMeshesAfterContextLoss(app: Application) {
    val managed = Mesh.meshes[app] ?: return
    for (element in managed) {
        element.vertices.invalidate()
        val vertices = element.vertices
        if (vertices is VertexBufferObjectWithVAO) {
            vertices.dropCachedAttributeLocations()
        }
        element.indices.invalidate()
    }
}
