package com.arsstructura.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/** Multiplies alpha in every emitted vertex, including Forge's baked-quad bulk path. */
final class GhostVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final float alpha;
    @Nullable private final Matrix4f transform;
    @Nullable private final Matrix3f normals;

    GhostVertexConsumer(VertexConsumer delegate, float alpha) { this(delegate, alpha, null, null); }

    GhostVertexConsumer(VertexConsumer delegate, float alpha, @Nullable Matrix4f transform, @Nullable Matrix3f normals) {
        this.delegate = delegate;
        this.alpha = alpha;
        this.transform = transform;
        this.normals = normals;
    }

    @Override public VertexConsumer vertex(double x, double y, double z) {
        if (transform == null) delegate.vertex(x, y, z);
        else {
            Vector4f point = transform.transform(new Vector4f((float) x, (float) y, (float) z, 1));
            delegate.vertex(point.x(), point.y(), point.z());
        }
        return this;
    }
    @Override public VertexConsumer color(int r, int g, int b, int a) {
        delegate.color(r, g, b, Math.round(a * alpha)); return this;
    }
    @Override public VertexConsumer uv(float u, float v) { delegate.uv(u, v); return this; }
    @Override public VertexConsumer overlayCoords(int u, int v) { delegate.overlayCoords(u, v); return this; }
    @Override public VertexConsumer uv2(int u, int v) { delegate.uv2(u, v); return this; }
    @Override public VertexConsumer normal(float x, float y, float z) {
        if (normals == null) delegate.normal(x, y, z);
        else {
            Vector3f normal = normals.transform(new Vector3f(x, y, z));
            delegate.normal(normal.x(), normal.y(), normal.z());
        }
        return this;
    }
    @Override public void endVertex() { delegate.endVertex(); }
    @Override public void defaultColor(int r, int g, int b, int a) { delegate.defaultColor(r, g, b, Math.round(a * alpha)); }
    @Override public void unsetDefaultColor() { delegate.unsetDefaultColor(); }
}
