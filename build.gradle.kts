plugins {
    java
    application
    distribution
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("ai.koog:koog-agents-jvm:1.3.0")
    implementation("com.agentclientprotocol:acp-agent-support:0.80.0")
    implementation("ai.koog:koog-agents-additions-jvm:1.3.0-beta")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

application {
    mainClass.set("ai.learn.agent.AcpTeachingAgent")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "ai.learn.agent.AcpTeachingAgent"
    }
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}