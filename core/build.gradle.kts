plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(libs.tink)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    systemProperty("calvault.largeTest", providers.gradleProperty("largeFileTest").getOrElse("false"))
}
