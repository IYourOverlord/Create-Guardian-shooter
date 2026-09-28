package com.yourname.cbcautotarget.util;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import net.minecraft.world.phys.Vec3;
import com.yourname.cbcautotarget.compat.SableCompat;

public final class ShipAimSolver {

    private ShipAimSolver() {}

    public static float[] toLocalAim(double worldYawDeg, double worldPitchDeg,
                                     ServerSubLevel ship) {
        if (ship == null) {
            return fallback(worldYawDeg, worldPitchDeg);
        }

        double yawRad   = Math.toRadians(worldYawDeg);
        double pitchRad = Math.toRadians(worldPitchDeg);
        double cosP = Math.cos(pitchRad);
        double wX = -Math.sin(yawRad) * cosP;
        double wY = Math.sin(pitchRad);
        double wZ = Math.cos(yawRad) * cosP;

        Vector3d axisX = new Vector3d(1, 0, 0);
        Vector3d axisY = new Vector3d(0, 1, 0);
        Vector3d axisZ = new Vector3d(0, 0, 1);
        ship.logicalPose().transformNormal(axisX);
        ship.logicalPose().transformNormal(axisY);
        ship.logicalPose().transformNormal(axisZ);

        double localX = wX * axisX.x + wY * axisX.y + wZ * axisX.z;
        double localY = wX * axisY.x + wY * axisY.y + wZ * axisY.z;
        double localZ = wX * axisZ.x + wY * axisZ.y + wZ * axisZ.z;
        double localYawDeg   = Math.toDegrees(Math.atan2(-localX, localZ));
        double localHoriz    = Math.sqrt(localX * localX + localZ * localZ);
        double localPitchDeg = Math.toDegrees(Math.atan2(localY, localHoriz));

        return new float[]{ (float) localYawDeg, (float) localPitchDeg };
    }

    private static float[] fallback(double worldYawDeg, double worldPitchDeg) {
        return new float[]{ (float) worldYawDeg, (float) worldPitchDeg };
    }
    public static Vec3 toWorldPosition(Vec3 localPos, ServerSubLevel ship) {
        if (ship == null) return localPos;
        return SableCompat.toWorldPos(ship, localPos);
    }


    public static Vec3 toWorldVelocity(Vec3 localVel, ServerSubLevel ship) {
        if (ship == null) return localVel;
        return SableCompat.toWorldVelocity(ship, localVel);
    }
}
