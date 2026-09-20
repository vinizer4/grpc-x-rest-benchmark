plugins {
    application
}

dependencies {
    implementation("org.postgresql:postgresql")
}

application {
    mainClass.set("com.benchmark.sales.datagen.MainKt")
}
