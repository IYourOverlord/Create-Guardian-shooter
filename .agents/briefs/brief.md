# Brief: Fix targeting issues, solver instability, and coordinate translations in ControllerBlockEntity and BallisticSolver
_Generated: 2026-06-29_

## Context (read-only, no changes needed)
- Key files involved:
  - `src/main/java/com/yourname/cbcautotarget/blockentity/ControllerBlockEntity.java`
  - `src/main/java/com/yourname/cbcautotarget/util/BallisticSolver.java`
- Relevant classes:
  - `ControllerBlockEntity` (manages cannon state, targeting, and aiming/firing logic)
  - `BallisticSolver` (solves projectile trajectory yaw and pitch)

## Actions

### ACTION 1 — MODIFY src/main/java/com/yourname/cbcautotarget/util/BallisticSolver.java
FIND (exact string, ≥3 lines of context):
```java
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
```
REPLACE WITH:
```java
    private static Double solvePitch(double horizDist, double dY, double muzzleSpeed,
                                     double gravity, double drag) {
        if (horizDist < 0.001) return Math.toRadians(90.0);

        if (drag == 0.0) {
            double lo = Double.NaN, hi = Double.NaN;
            double prevF = Double.NaN;
            double prevTheta = Double.NaN;

            for (int deg = -89; deg <= 89; deg++) {
                double theta = Math.toRadians(deg);
                double cosTheta = Math.cos(theta);
                double T = horizDist / (muzzleSpeed * cosTheta);
                double f = horizDist * Math.tan(theta) + gravity * T * (T - 1) / 2.0 - dY;
                if (!Double.isNaN(prevF) && prevF * f < 0) {
                    lo = prevTheta;
                    hi = theta;
                    break;
                }
                prevF = f;
                prevTheta = theta;
            }

            if (Double.isNaN(lo)) return null;

            return brentSolve(lo, hi, theta -> {
                double cosTheta = Math.cos(theta);
                double T = horizDist / (muzzleSpeed * cosTheta);
                return horizDist * Math.tan(theta) + gravity * T * (T - 1) / 2.0 - dY;
            });
        }

        double prevF = Double.NaN;
```

### ACTION 2 — MODIFY src/main/java/com/yourname/cbcautotarget/blockentity/ControllerBlockEntity.java
FIND (exact string, ≥3 lines of context):
```java
    private Vec3 computeRealMuzzlePos(PitchOrientedContraptionEntity c) {
        Vec3 base = c.position();
        if (controllerSubLevel != null) base = SableCompat.toWorldPos(controllerSubLevel, base);
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) return base;
        // c.pitch is raw (CBC internal). For inverted cannons (sgn=-1) the physical
        // barrel direction is opposite to raw pitch, so we must use worldPitch = raw * sgn.
        CannonMountBlockEntity mount = findMountBlockEntity();
        float sgn = (mount != null) ? getContraptionSign(mount) : 1.0f;
        double yawRad   = Math.toRadians(-c.yaw + 90.0);
        double pitchRad = Math.toRadians(c.pitch * sgn);   // world-space pitch
        double cosP = Math.cos(pitchRad);
        return base.add(cosP * Math.cos(yawRad) * len,
                Math.sin(pitchRad) * len,
                cosP * Math.sin(yawRad) * len);
    }

    private Vec3 computeTargetMuzzlePos(PitchOrientedContraptionEntity c, float yaw, float pitch) {
        Vec3 base = c.position();
        if (controllerSubLevel != null) base = SableCompat.toWorldPos(controllerSubLevel, base);
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) return base;
        double yawRad   = Math.toRadians(-yaw + 90.0);
        double pitchRad = Math.toRadians(pitch);   // pitch is already in world-space
        double cosP = Math.cos(pitchRad);
        return base.add(cosP * Math.cos(yawRad) * len,
                Math.sin(pitchRad) * len,
                cosP * Math.sin(yawRad) * len);
    }
```
REPLACE WITH:
```java
    private Vec3 computeRealMuzzlePos(PitchOrientedContraptionEntity c) {
        Vec3 localBase = c.position();
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) {
            return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, localBase) : localBase;
        }
        CannonMountBlockEntity mount = findMountBlockEntity();
        float sgn = (mount != null) ? getContraptionSign(mount) : 1.0f;
        double yawRad   = Math.toRadians(-c.yaw + 90.0);
        double pitchRad = Math.toRadians(c.pitch * sgn);
        double cosP = Math.cos(pitchRad);
        Vec3 localMuzzle = localBase.add(cosP * Math.cos(yawRad) * len,
                Math.sin(pitchRad) * len,
                cosP * Math.sin(yawRad) * len);
        return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, localMuzzle) : localMuzzle;
    }

    private Vec3 computeTargetMuzzlePos(PitchOrientedContraptionEntity c, float localYaw, float localPitch) {
        Vec3 localBase = c.position();
        double len = CBCAutoTargetConfig.BARREL_LENGTH.get();
        if (len <= 0.0) {
            return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, localBase) : localBase;
        }
        double yawRad   = Math.toRadians(-localYaw + 90.0);
        double pitchRad = Math.toRadians(localPitch);
        double cosP = Math.cos(pitchRad);
        Vec3 localMuzzle = localBase.add(cosP * Math.cos(yawRad) * len,
                Math.sin(pitchRad) * len,
                cosP * Math.sin(yawRad) * len);
        return (controllerSubLevel != null) ? SableCompat.toWorldPos(controllerSubLevel, localMuzzle) : localMuzzle;
    }
```

## Validation checklist
- [ ] WRITE WORK CANCEL
