description = "RCON protocol codec for Netty"

dependencies {
    api(platform(libs.netty.bom))
    api(libs.netty.codec.base)
    api(libs.netty.transport)
    implementation(libs.netty.transport.classes.epoll)
    implementation(libs.netty.transport.classes.kqueue)
    api(libs.nukkitx.network.common) {
        exclude(group = "io.netty")
    }

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.codec.rcon"
}
