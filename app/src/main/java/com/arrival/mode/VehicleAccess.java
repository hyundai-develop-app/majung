package com.arrival.mode;

import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.Gear;

/** Vehicle 의 getGear() 는 Kotlin 메타데이터상 private 로 보여 Kotlin 에서 못 부른다. Java 는 바이트코드(public)대로 부를 수 있다. */
final class VehicleAccess {
    private VehicleAccess() {}
    static Gear gear(Vehicle v) { return v.getGear(); }
}
