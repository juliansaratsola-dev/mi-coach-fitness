tasks.register("assembleDebug") {
    dependsOn(":app:assembleDebug")
}

tasks.register("testDebugUnitTest") {
    dependsOn(":app:testDebugUnitTest")
}
