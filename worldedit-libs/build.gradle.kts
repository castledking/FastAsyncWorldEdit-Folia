tasks.register("build") {
    dependsOn(subprojects.filter { it.tasks.names.contains("build") }.map { it.tasks.named("build") })
}
