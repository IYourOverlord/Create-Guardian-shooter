package com.yourname.cbcautotarget.util;

import com.yourname.cbcautotarget.CBCAutoTargetConfig;
import net.minecraft.world.phys.Vec3;

public class BallisticSolver {
    public static final double YAW_TOLERANCE = 1.0;
    public static final double PITCH_TOLERANCE = 3.0;
    private static final int LEAD_ITERATIONS = 8;
    private static final int BRENT_MAX_ITER = 100;
    private static final double BRENT_EPS = 1e-6;

    public static double[] solve(Vec3 muzzlePos, Vec3 targetPos, Vec3 targetVel,
                                 double muzzleSpeed, double gravity, double drag,
                                 boolean quadraticDrag, float maxDepression, float maxElevation) {
        double T = muzzlePos.distanceTo(targetPos) / muzzleSpeed;
        double yawDeg = 0;
        double pitchDeg = 0;

        for (int iter = 0; iter < LEAD_ITERATIONS; iter++) {
            // Смещаем точку прицела по всем трём осям с учётом времени полёта T
            Vec3 aimPoint = targetPos.add(targetVel.scale(T));
            Vec3 delta = aimPoint.subtract(muzzlePos);

            double dX = delta.x;
            double dZ = delta.z;
            double dY = delta.y;
            double horizDist = Math.sqrt(dX * dX + dZ * dZ);

            double yawRad = Math.atan2(dZ, dX);
            yawDeg = Math.toDegrees(yawRad);

            // solvePitch возвращает радианы или null
            Double pitchRad = solvePitch(horizDist, dY, muzzleSpeed, gravity, drag);
            if (pitchRad == null) {
                pitchRad = Math.atan2(dY, horizDist); // fallback в радианах
            }

            pitchDeg = Math.toDegrees(pitchRad);
            pitchDeg = Math.max(-maxDepression, Math.min(maxElevation, pitchDeg));
            double pitchRadClamped = Math.toRadians(pitchDeg);

            double newT = simulateFlightTime(muzzleSpeed, pitchRadClamped, yawRad, horizDist, gravity, drag);
            if (Math.abs(newT - T) < 0.5) break;
            T = newT;
        }

        return new double[]{ yawDeg, pitchDeg };
    }

    private static Double solvePitch(double horizDist, double dY, double muzzleSpeed,
                                     double gravity, double drag) {
        if (horizDist < 0.001) return Math.toRadians(90.0);

        if (drag == 0.0) {
            // Итеративное уточнение: угол влияет на горизонтальную скорость,
            // которая влияет на время полёта, которое влияет на угол.
            double pitchRad = Math.atan2(dY, horizDist); // начальное приближение
            for (int i = 0; i < 16; i++) {
                double vHoriz = muzzleSpeed * Math.cos(pitchRad);
                if (vHoriz < 1e-6) break;
                double T = horizDist / vHoriz;
                // ИСПРАВЛЕНИЕ: CBC применяет позицию ДО гравитации (дискретная схема Эйлера).
                // Дискретный итог Y за T тиков: T*vy0 + g*T*(T-1)/2
                // Непрерывная формула 0.5*g*T² давала систематический перелёт +g*T/2 по высоте.
                double newPitch = Math.atan2(dY - gravity * T * (T - 1) / 2.0, horizDist);
                if (Math.abs(newPitch - pitchRad) < 1e-6) break;
                pitchRad = newPitch;
            }
            return pitchRad;
        }

           double prevF = Double.NaN;
        double prevTheta = Double.NaN;
        double lo = Double.NaN, hi = Double.NaN;

        for (int deg = -89; deg <= 89; deg++) {
            double theta = Math.toRadians(deg);
            double f = projectileYatX(muzzleSpeed, horizDist, theta, drag, gravity) - dY;
            if (!Double.isNaN(prevF) && prevF * f < 0) {
                lo = prevTheta;
                hi = theta;
                break;
            }
            prevF = f;
            prevTheta = theta;
        }

        if (Double.isNaN(lo)) return null;

        return brentSolve(lo, hi, theta -> projectileYatX(muzzleSpeed, horizDist, theta, drag, gravity) - dY);
    }

    private static double projectileYatX(double speed, double dX, double thetaRad, double drag, double g) {
        double cosTheta = Math.cos(thetaRad);
        if (Math.abs(cosTheta) < 1e-9) return dX * Math.tan(thetaRad);
        double denom = drag * dX / (speed * cosTheta);
        if (denom >= 1.0) return dX * Math.tan(thetaRad);
        double logVal = Math.log(1.0 - denom);
        return dX * Math.tan(thetaRad) + dX * g / (drag * speed * cosTheta) + g * logVal / (drag * drag);
    }

    private static double brentSolve(double a, double b, java.util.function.DoubleUnaryOperator f) {
        double fa = f.applyAsDouble(a);
        double fb = f.applyAsDouble(b);
        if (fa * fb > 0) return (a + b) / 2.0;

        double c = a, fc = fa;
        double s = 0, fs;
        boolean mflag = true;
        double d = 0;

        for (int i = 0; i < BRENT_MAX_ITER; i++) {
            if (Math.abs(b - a) < BRENT_EPS) return s;
            if (fa != fc && fb != fc) {
                s = a * fb * fc / ((fa - fb) * (fa - fc))
                        + b * fa * fc / ((fb - fa) * (fb - fc))
                        + c * fa * fb / ((fc - fa) * (fc - fb));
            } else {
                s = b - fb * (b - a) / (fb - fa);
            }
            boolean cond1 = !((3.0 * a + b) / 4.0 < s && s < b || b < s && s < (3.0 * a + b) / 4.0);
            boolean cond2 = mflag && Math.abs(s - b) >= Math.abs(b - c) / 2.0;
            boolean cond3 = !mflag && Math.abs(s - b) >= Math.abs(c - d) / 2.0;
            boolean cond4 = mflag && Math.abs(b - c) < BRENT_EPS;
            boolean cond5 = !mflag && Math.abs(c - d) < BRENT_EPS;
            if (cond1 || cond2 || cond3 || cond4 || cond5) {
                s = (a + b) / 2.0;
                mflag = true;
            } else {
                mflag = false;
            }
            fs = f.applyAsDouble(s);
            d = c;
            c = b;
            fc = fb;
            if (fa * fs < 0) { b = s; fb = fs; }
            else { a = s; fa = fs; }
            if (Math.abs(fa) < Math.abs(fb)) {
                double tmp = a; a = b; b = tmp;
                double ftmp = fa; fa = fb; fb = ftmp;
            }
        }
        return s;
    }

    private static double simulateFlightTime(double muzzleSpeed, double pitchRad, double yawRad,
                                             double targetHorizDist, double gravity, double drag) {
        int maxTicks = CBCAutoTargetConfig.MAX_SIM_TICKS.get();
        double vx = muzzleSpeed * Math.cos(pitchRad) * Math.cos(yawRad);
        double vy = muzzleSpeed * Math.sin(pitchRad);
        double vz = muzzleSpeed * Math.cos(pitchRad) * Math.sin(yawRad);
        double px = 0, py = 0, pz = 0;
        for (int t = 0; t < maxTicks; t++) {
            px += vx; py += vy; pz += vz;
            vy += gravity;
            double factor = 1.0 - drag;
            vx *= factor; vy *= factor; vz *= factor;
            double horizDist = Math.sqrt(px * px + pz * pz);
            if (horizDist >= targetHorizDist) return t + 1;
        }
        return maxTicks;
    }

    public static double[] computeYawPitchDegrees(Vec3 muzzlePos, Vec3 targetPos,
                                                  double muzzleSpeed, double gravity, double drag,
                                                  boolean quadraticDrag, float maxDepression, float maxElevation) {
        Vec3 delta = targetPos.subtract(muzzlePos);
        double dX = delta.x;
        double dZ = delta.z;
        double dY = delta.y;
        double horizDist = Math.sqrt(dX * dX + dZ * dZ);

        double yawRad = Math.atan2(dZ, dX);
        double yawDeg = Math.toDegrees(yawRad);

        Double pitchRad = solvePitch(horizDist, dY, muzzleSpeed, gravity, drag);
        if (pitchRad == null) pitchRad = Math.atan2(dY, horizDist);
        double pitchDeg = Math.toDegrees(pitchRad);
        pitchDeg = Math.max(-maxDepression, Math.min(maxElevation, pitchDeg));

        return new double[]{ yawDeg, pitchDeg };
    }
}