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
        double wX = cosP * Math.cos(yawRad);
        double wY = Math.sin(pitchRad);
        double wZ = cosP * Math.sin(yawRad);

        Vector3d axisX = new Vector3d(1, 0, 0);
        Vector3d axisY = new Vector3d(0, 1, 0);
        Vector3d axisZ = new Vector3d(0, 0, 1);
        ship.logicalPose().transformNormal(axisX);
        ship.logicalPose().transformNormal(axisY);
        ship.logicalPose().transformNormal(axisZ);

        double localX = wX * axisX.x + wY * axisX.y + wZ * axisX.z;
        double localY = wX * axisY.x + wY * axisY.y + wZ * axisY.z;
        double localZ = wX * axisZ.x + wY * axisZ.y + wZ * axisZ.z;
        double localYawDeg   = Math.toDegrees(Math.atan2(localZ, localX));
        double localHoriz    = Math.sqrt(localX * localX + localZ * localZ);
        double localPitchDeg = Math.toDegrees(Math.atan2(localY, localHoriz));

        return toCBC(localYawDeg, localPitchDeg);
    }


    private static float[] fallback(double worldYawDeg, double worldPitchDeg) {
        return toCBC(worldYawDeg, worldPitchDeg);
    }


    private static float[] toCBC(double mathYawDeg, double elevationDeg) {
        float yaw = (float) wrap360(mathYawDeg - 90.0);
        if (yaw > 180f)  yaw -= 360f;
        if (yaw < -180f) yaw += 360f;

        float pitch = (float) elevationDeg;

        return new float[]{ yaw, pitch };
    }

    private static double wrap360(double deg) {
        deg %= 360.0;
        if (deg < 0.0) deg += 360.0;
        return deg;
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
