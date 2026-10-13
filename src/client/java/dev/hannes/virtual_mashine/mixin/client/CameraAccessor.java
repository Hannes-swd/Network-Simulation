package dev.hannes.virtual_mashine.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a camera block put the main camera somewhere else for one picture. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void virtual_mashine$setPosition(Vec3 position);

    @Invoker("setRotation")
    void virtual_mashine$setRotation(float yRot, float xRot);

    @Invoker("setupPerspective")
    void virtual_mashine$setupPerspective(float zNear, float zFar, float fov, float width, float height);

    @Invoker("prepareCullFrustum")
    void virtual_mashine$prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos);

    @Accessor("detached")
    void virtual_mashine$setDetached(boolean detached);

    @Accessor("depthFar")
    float virtual_mashine$depthFar();
}
