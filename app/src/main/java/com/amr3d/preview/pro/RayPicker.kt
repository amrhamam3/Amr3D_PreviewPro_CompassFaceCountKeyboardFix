package com.amr3d.preview.pro

import android.opengl.GLU
import android.opengl.Matrix

/**
 * Converts a 2D screen tap into a 3D point on the model's surface using
 * ray-casting + Möller–Trumbore ray/triangle intersection.
 *
 * This is what makes the measurement tool place points ON the part's surface
 * (e.g. an edge or face) rather than at an arbitrary depth.
 */
object RayPicker {

    data class Ray(val origin: FloatArray, val direction: FloatArray)

    /**
     * Unprojects a screen point into a world-space ray, using the current
     * MVP-related matrices from the renderer.
     */
    fun screenPointToRay(
        screenX: Float,
        screenY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ): Ray {
        // OpenGL viewport Y is bottom-up; Android touch Y is top-down.
        val glY = viewportHeight - screenY

        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)

        val nearPoint = FloatArray(4)
        val farPoint = FloatArray(4)

        GLU.gluUnProject(
            screenX, glY, 0f,
            mvMatrix, 0, projectionMatrix, 0,
            intArrayOf(0, 0, viewportWidth, viewportHeight), 0,
            nearPoint, 0
        )
        GLU.gluUnProject(
            screenX, glY, 1f,
            mvMatrix, 0, projectionMatrix, 0,
            intArrayOf(0, 0, viewportWidth, viewportHeight), 0,
            farPoint, 0
        )

        val ox = nearPoint[0] / nearPoint[3]
        val oy = nearPoint[1] / nearPoint[3]
        val oz = nearPoint[2] / nearPoint[3]

        val fx = farPoint[0] / farPoint[3]
        val fy = farPoint[1] / farPoint[3]
        val fz = farPoint[2] / farPoint[3]

        val dx = fx - ox
        val dy = fy - oy
        val dz = fz - oz
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

        return Ray(
            origin = floatArrayOf(ox, oy, oz),
            direction = floatArrayOf(dx / len, dy / len, dz / len)
        )
    }

    /**
     * Finds the closest ray/triangle intersection point across the whole mesh.
     * Returns null if the ray doesn't hit any triangle (e.g. user tapped empty space).
     *
     * Note: this is an O(triangles) brute-force scan. For typical CNC/print parts
     * (tens of thousands of triangles) this remains fast enough for an on-tap query.
     */
    fun findClosestIntersection(ray: Ray, model: STLModel): FloatArray? {
        val v = model.vertices
        var closestT = Float.MAX_VALUE
        var hit: FloatArray? = null

        var i = 0
        while (i < v.size) {
            val x1 = v[i]; val y1 = v[i + 1]; val z1 = v[i + 2]
            val x2 = v[i + 3]; val y2 = v[i + 4]; val z2 = v[i + 5]
            val x3 = v[i + 6]; val y3 = v[i + 7]; val z3 = v[i + 8]

            val t = intersectTriangle(ray, x1, y1, z1, x2, y2, z2, x3, y3, z3)
            if (t != null && t < closestT) {
                closestT = t
                hit = floatArrayOf(
                    ray.origin[0] + ray.direction[0] * t,
                    ray.origin[1] + ray.direction[1] * t,
                    ray.origin[2] + ray.direction[2] * t
                )
            }
            i += 9
        }

        return hit
    }

    private const val EPSILON = 1e-6f

    /**
     * Möller–Trumbore intersection algorithm. Returns the ray parameter t (distance
     * along the ray direction) if it intersects the triangle, otherwise null.
     */
    private fun intersectTriangle(
        ray: Ray,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float
    ): Float? {
        val e1x = x2 - x1; val e1y = y2 - y1; val e1z = z2 - z1
        val e2x = x3 - x1; val e2y = y3 - y1; val e2z = z3 - z1

        val dx = ray.direction[0]; val dy = ray.direction[1]; val dz = ray.direction[2]

        val hx = dy * e2z - dz * e2y
        val hy = dz * e2x - dx * e2z
        val hz = dx * e2y - dy * e2x

        val a = e1x * hx + e1y * hy + e1z * hz
        if (a > -EPSILON && a < EPSILON) return null

        val f = 1.0f / a
        val sx = ray.origin[0] - x1
        val sy = ray.origin[1] - y1
        val sz = ray.origin[2] - z1

        val u = f * (sx * hx + sy * hy + sz * hz)
        if (u < 0f || u > 1f) return null

        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x

        val vCoef = f * (dx * qx + dy * qy + dz * qz)
        if (vCoef < 0f || u + vCoef > 1f) return null

        val t = f * (e2x * qx + e2y * qy + e2z * qz)
        return if (t > EPSILON) t else null
    }
}
