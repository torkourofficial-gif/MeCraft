package mecraft;

/** Indev-style player acceleration, gravity, liquid drag and voxel collisions. */
final class PlayerPhysics {
    private static final float RADIUS = 0.30f;
    private static final float HEIGHT = 1.80f;
    private static final float EPSILON = 0.0001f;
    private float motionX;
    private float motionY;
    private float motionZ;
    private float fallDistance;
    private boolean onGround;
    private boolean horizontalCollision;

    void reset() {
        motionX = motionY = motionZ = fallDistance = 0.0f;
        onGround = false;
        horizontalCollision = false;
    }

    boolean isOnGround() { return onGround; }

    void clearVerticalMotion() {
        motionY = 0.0f;
        fallDistance = 0.0f;
    }

    boolean isInWater(VoxelWorld world) {
        return intersectsWater(world, world.playerX, world.playerY + 0.4f,
                world.playerZ, world.playerY + HEIGHT - 0.4f);
    }

    /** Returns Indev fall damage, if a landing occurred this tick. */
    int update(VoxelWorld world, float forward, float strafe, boolean jumping,
               boolean flying, boolean descending) {
        float inputLength = (float)Math.sqrt(forward * forward + strafe * strafe);
        if (inputLength > 1.0f) {
            forward /= inputLength;
            strafe /= inputLength;
        }

        if (flying) {
            motionY = jumping ? 0.12f : (descending ? -0.12f : 0.0f);
            float requestedY = motionY;
            float movedY = clipY(world, world.playerX, world.playerY, world.playerZ, requestedY);
            world.playerY += movedY;
            if (movedY != requestedY) motionY = 0.0f;
            onGround = false;
            fallDistance = 0.0f;
            float radians = world.yaw * (float)Math.PI / 180.0f;
            float sin = (float)Math.sin(radians);
            float cos = (float)Math.cos(radians);
            motionX += (forward * sin - strafe * cos) * 0.04f;
            motionZ += (forward * cos + strafe * sin) * 0.04f;
            moveHorizontally(world);
            motionX *= 0.91f;
            motionZ *= 0.91f;
            if (world.playerY < 1.0f) world.playerY = 1.0f;
            if (world.playerY > VoxelWorld.HEIGHT - HEIGHT) world.playerY = VoxelWorld.HEIGHT - HEIGHT;
            return 0;
        }

        boolean inWater = isInWater(world);
        if (jumping) {
            if (inWater) motionY += 0.04f;
            else if (onGround) motionY = 0.42f;
        }

        float radians = world.yaw * (float)Math.PI / 180.0f;
        float sin = (float)Math.sin(radians);
        float cos = (float)Math.cos(radians);
        float acceleration = inWater ? 0.02f : (onGround ? 0.10f : 0.02f);
        // The view uses yaw zero facing +Z, so its screen-right basis is -X.
        motionX += (forward * sin - strafe * cos) * acceleration;
        motionZ += (forward * cos + strafe * sin) * acceleration;

        float requestedY = motionY;
        float movedY = clipY(world, world.playerX, world.playerY, world.playerZ, requestedY);
        world.playerY += movedY;
        onGround = requestedY < 0.0f && movedY != requestedY;
        if (movedY != requestedY) motionY = 0.0f;
        if (onGround) {
            boolean landedInWater = isInWater(world);
            int damage = fallDistance > 3.0f ? (int)Math.ceil(fallDistance - 3.0f) : 0;
            fallDistance = 0.0f;
            if (inWater || landedInWater) damage = 0;
            moveHorizontally(world);
            applyDrag(inWater || landedInWater);
            return damage;
        }
        if (movedY < 0.0f) fallDistance -= movedY;

        moveHorizontally(world);
        boolean nowInWater = isInWater(world);
        if (nowInWater) fallDistance = 0.0f;
        applyDrag(nowInWater);
        if (world.playerY < 1.0f) {
            world.playerX = VoxelWorld.WIDTH / 2 + 0.5f;
            world.playerZ = VoxelWorld.DEPTH / 2 + 0.5f;
            world.playerY = world.surfaceY(VoxelWorld.WIDTH / 2, VoxelWorld.DEPTH / 2) + 1.0f;
            reset();
        }
        return 0;
    }

    private void moveHorizontally(VoxelWorld world) {
        float startX = world.playerX;
        float startY = world.playerY;
        float startZ = world.playerZ;
        float requestedX = motionX;
        float requestedZ = motionZ;
        float movedX = clipX(world, world.playerX, world.playerY, world.playerZ, motionX);
        world.playerX += movedX;
        boolean hitX = movedX != requestedX;
        float movedZ = clipZ(world, world.playerX, world.playerY, world.playerZ, motionZ);
        world.playerZ += movedZ;
        boolean hitZ = movedZ != requestedZ;
        horizontalCollision = hitX || hitZ;

        if (horizontalCollision && onGround) {
            float normalX = world.playerX;
            float normalY = world.playerY;
            float normalZ = world.playerZ;
            float normalDistance = (normalX - startX) * (normalX - startX) +
                    (normalZ - startZ) * (normalZ - startZ);
            world.playerX = startX;
            world.playerY = startY;
            world.playerZ = startZ;

            float stepUp = clipY(world, world.playerX, world.playerY, world.playerZ, 0.5f);
            world.playerY += stepUp;
            float stepX = clipX(world, world.playerX, world.playerY, world.playerZ, requestedX);
            world.playerX += stepX;
            float stepZ = clipZ(world, world.playerX, world.playerY, world.playerZ, requestedZ);
            world.playerZ += stepZ;
            float stepDown = clipY(world, world.playerX, world.playerY, world.playerZ, -stepUp);
            world.playerY += stepDown;
            float stepDistance = (world.playerX - startX) * (world.playerX - startX) +
                    (world.playerZ - startZ) * (world.playerZ - startZ);
            if (stepUp > 0.0f && stepDistance > normalDistance) {
                hitX = stepX != requestedX;
                hitZ = stepZ != requestedZ;
                onGround = stepDown != -stepUp;
            } else {
                world.playerX = normalX;
                world.playerY = normalY;
                world.playerZ = normalZ;
            }
        }

        horizontalCollision = hitX || hitZ;
        if (hitX) motionX = 0.0f;
        if (hitZ) motionZ = 0.0f;
        if (horizontalCollision && isInWater(world) && !intersectsSolid(world,
                world.playerX, world.playerY + 0.6f, world.playerZ)) motionY = 0.3f;
    }

    private void applyDrag(boolean inWater) {
        if (inWater) {
            motionX *= 0.8f;
            motionY *= 0.8f;
            motionZ *= 0.8f;
            motionY -= 0.02f;
        } else {
            motionX *= 0.91f;
            motionY *= 0.98f;
            motionZ *= 0.91f;
            motionY -= 0.08f;
            if (onGround) {
                motionX *= 0.6f;
                motionZ *= 0.6f;
            }
        }
    }

    private float clipY(VoxelWorld world, float x, float y, float z, float dy) {
        if (dy == 0.0f) return 0.0f;
        int minX = floor(x - RADIUS + EPSILON), maxX = floor(x + RADIUS - EPSILON);
        int minZ = floor(z - RADIUS + EPSILON), maxZ = floor(z + RADIUS - EPSILON);
        int fromY = floor(y + Math.min(0.0f, dy));
        int toY = floor(y + HEIGHT + Math.max(0.0f, dy));
        for (int by = fromY; by <= toY; by++) {
            for (int bx = minX; bx <= maxX; bx++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    byte block = world.get(bx, by, bz);
                    if (block == Block.DOOR) {
                        int state = world.doorState(bx, by, bz);
                        float doorMinX = bx + (state == 1 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxX = bx + (state == 3 ? 3.0f / 16.0f : 1.0f);
                        float doorMinZ = bz + (state == 2 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxZ = bz + (state == 0 ? 3.0f / 16.0f : 1.0f);
                        if (x + RADIUS <= doorMinX || x - RADIUS >= doorMaxX ||
                            z + RADIUS <= doorMinZ || z - RADIUS >= doorMaxZ) continue;
                    } else if (!Block.solid(block)) continue;
                    if (dy > 0.0f && y + HEIGHT <= by) {
                        float gap = by - (y + HEIGHT);
                        if (dy > gap) dy = gap;
                    } else if (dy < 0.0f && y >= by + 1.0f) {
                        float gap = by + 1.0f - y;
                        if (dy < gap) dy = gap;
                    }
                }
            }
        }
        return dy;
    }

    private float clipX(VoxelWorld world, float x, float y, float z, float dx) {
        if (dx == 0.0f) return 0.0f;
        int minY = floor(y + EPSILON), maxY = floor(y + HEIGHT - EPSILON);
        int minZ = floor(z - RADIUS + EPSILON), maxZ = floor(z + RADIUS - EPSILON);
        int fromX = floor(x - RADIUS + Math.min(0.0f, dx));
        int toX = floor(x + RADIUS + Math.max(0.0f, dx));
        for (int bx = fromX; bx <= toX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    byte block = world.get(bx, by, bz);
                    if (block == Block.DOOR) {
                        int state = world.doorState(bx, by, bz);
                        float doorMinX = bx + (state == 1 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxX = bx + (state == 3 ? 3.0f / 16.0f : 1.0f);
                        float doorMinZ = bz + (state == 2 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxZ = bz + (state == 0 ? 3.0f / 16.0f : 1.0f);
                        if (y + HEIGHT <= by || y >= by + 1.0f ||
                            z + RADIUS <= doorMinZ || z - RADIUS >= doorMaxZ) continue;
                        if (dx > 0.0f && x + RADIUS <= doorMinX) {
                            float gap = doorMinX - (x + RADIUS);
                            if (dx > gap) dx = gap;
                        } else if (dx < 0.0f && x - RADIUS >= doorMaxX) {
                            float gap = doorMaxX - (x - RADIUS);
                            if (dx < gap) dx = gap;
                        }
                        continue;
                    }
                    if (!Block.solid(block)) continue;
                    if (dx > 0.0f && x + RADIUS <= bx) {
                        float gap = bx - (x + RADIUS);
                        if (dx > gap) dx = gap;
                    } else if (dx < 0.0f && x - RADIUS >= bx + 1.0f) {
                        float gap = bx + 1.0f - (x - RADIUS);
                        if (dx < gap) dx = gap;
                    }
                }
            }
        }
        return dx;
    }

    private float clipZ(VoxelWorld world, float x, float y, float z, float dz) {
        if (dz == 0.0f) return 0.0f;
        int minY = floor(y + EPSILON), maxY = floor(y + HEIGHT - EPSILON);
        int minX = floor(x - RADIUS + EPSILON), maxX = floor(x + RADIUS - EPSILON);
        int fromZ = floor(z - RADIUS + Math.min(0.0f, dz));
        int toZ = floor(z + RADIUS + Math.max(0.0f, dz));
        for (int bz = fromZ; bz <= toZ; bz++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bx = minX; bx <= maxX; bx++) {
                    byte block = world.get(bx, by, bz);
                    if (block == Block.DOOR) {
                        int state = world.doorState(bx, by, bz);
                        float doorMinX = bx + (state == 1 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxX = bx + (state == 3 ? 3.0f / 16.0f : 1.0f);
                        float doorMinZ = bz + (state == 2 ? 13.0f / 16.0f : 0.0f);
                        float doorMaxZ = bz + (state == 0 ? 3.0f / 16.0f : 1.0f);
                        if (y + HEIGHT <= by || y >= by + 1.0f ||
                            x + RADIUS <= doorMinX || x - RADIUS >= doorMaxX) continue;
                        if (dz > 0.0f && z + RADIUS <= doorMinZ) {
                            float gap = doorMinZ - (z + RADIUS);
                            if (dz > gap) dz = gap;
                        } else if (dz < 0.0f && z - RADIUS >= doorMaxZ) {
                            float gap = doorMaxZ - (z - RADIUS);
                            if (dz < gap) dz = gap;
                        }
                        continue;
                    }
                    if (!Block.solid(block)) continue;
                    if (dz > 0.0f && z + RADIUS <= bz) {
                        float gap = bz - (z + RADIUS);
                        if (dz > gap) dz = gap;
                    } else if (dz < 0.0f && z - RADIUS >= bz + 1.0f) {
                        float gap = bz + 1.0f - (z - RADIUS);
                        if (dz < gap) dz = gap;
                    }
                }
            }
        }
        return dz;
    }

    private boolean intersectsWater(VoxelWorld world, float x, float minY, float z, float maxY) {
        int minX = floor(x - RADIUS + EPSILON), maxX = floor(x + RADIUS - EPSILON);
        int minZ = floor(z - RADIUS + EPSILON), maxZ = floor(z + RADIUS - EPSILON);
        int fromY = floor(minY), toY = floor(maxY);
        for (int by = fromY; by <= toY; by++) {
            for (int bx = minX; bx <= maxX; bx++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (world.get(bx, by, bz) == Block.WATER && minY < by + 0.91f && maxY > by) return true;
                }
            }
        }
        return false;
    }

    private boolean intersectsSolid(VoxelWorld world, float x, float y, float z) {
        int minX = floor(x - RADIUS + EPSILON), maxX = floor(x + RADIUS - EPSILON);
        int minZ = floor(z - RADIUS + EPSILON), maxZ = floor(z + RADIUS - EPSILON);
        int minY = floor(y), maxY = floor(y + HEIGHT - EPSILON);
        for (int by = minY; by <= maxY; by++) {
            if (world.isSolidAt(minX, by, minZ) || world.isSolidAt(maxX, by, minZ) ||
                world.isSolidAt(minX, by, maxZ) || world.isSolidAt(maxX, by, maxZ)) return true;
        }
        return false;
    }

    private int floor(float value) {
        int i = (int)value;
        return value < i ? i - 1 : i;
    }
}

