import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs)
    implementation(libs.koin.core)
}

compose.desktop {
    application {
        mainClass = "com.yash.multipickle.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            modules("jdk.security.auth", "jdk.crypto.ec", "jdk.unsupported")

            // 1. Clean package name for Linux package managers
            packageName = "pickleshare"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("installer-resources"))
            packageVersion = "1.0.0"

            linux {
                debMaintainer = "acharkisabzi@gmail.com"

                // 2. Add an app menu shortcut in KDE/GNOME
                shortcut = true

                // Optional: Group it in the menu (e.g., Network, Utility)
                menuGroup = "Network"

                // 3. Start from the root project directory to find the shared icon
                iconFile.set(project.rootProject.file("shared/src/commonMain/composeResources/drawable/pickleshare_logo.png"))
            }
        }
    }
}