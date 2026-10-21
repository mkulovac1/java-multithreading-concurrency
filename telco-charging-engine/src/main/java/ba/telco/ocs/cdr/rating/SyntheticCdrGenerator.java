package ba.telco.ocs.cdr.rating;

import ba.telco.ocs.tariff.entity.ServiceType;

final class SyntheticCdrGenerator {

    private static final ServiceType[] TYPES = ServiceType.values();

    private SyntheticCdrGenerator() {
    }

    static ServiceType serviceType(long seed, long index) {
        return TYPES[(int) Math.floorMod(mix(seed, index), (long) TYPES.length)];
    }

    static long units(long seed, long index, ServiceType serviceType) {
        long random = Math.floorMod(mix(seed ^ 0x5DEECE66DL, index), 1_000_000L);
        return switch (serviceType) {
            case VOICE -> 1 + random % 1_800;
            case SMS -> 1 + random % 3;
            case DATA -> 1 + random % 51_200;
        };
    }

    private static long mix(long seed, long index) {
        long z = seed + index * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
