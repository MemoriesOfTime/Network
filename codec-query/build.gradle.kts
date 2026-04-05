description = "Query protocol codec for Netty"

dependencies {
    api(libs.netty.common)
    api(libs.netty.buffer)
    api(libs.netty.codec)
    api(libs.netty.handler)
    api(libs.nukkitx.network.common)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.codec.query"
}
