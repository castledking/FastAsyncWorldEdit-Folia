applyPaperweightAdapterConfiguration()

repositories {
    gradlePluginPortal()
}

plugins {
    `java-library`
}

dependencies {
    the<io.papermc.paperweight.userdev.PaperweightUserDependenciesExtension>().paperDevBundle("1.21.8-R0.1-SNAPSHOT")
    api(libs.paperlib)
    implementation(project(":worldedit-bukkit"))
}
