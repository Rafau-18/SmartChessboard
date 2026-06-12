import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildkonfig)
}

// Supabase creds: local.properties (dev) → -P project property / env (prod build).
// anon key is public (RLS-protected); the service-role key must NEVER be injected here.
val localProps =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val supabaseUrl =
    (
        localProps.getProperty("SUPABASE_URL")
            ?: project.findProperty("SUPABASE_URL") as String?
    ).orEmpty()
val supabaseAnonKey =
    (
        localProps.getProperty("SUPABASE_ANON_KEY")
            ?: project.findProperty("SUPABASE_ANON_KEY") as String?
    ).orEmpty()
// Google Web OAuth client ID for the Android native (Credential Manager) sign-in.
// Public identifier, not a secret — same injection path as the Supabase values.
// May be empty: the browser OAuth fallback works without it; only the native sheet needs it.
val googleServerClientId =
    (
        localProps.getProperty("GOOGLE_SERVER_CLIENT_ID")
            ?: project.findProperty("GOOGLE_SERVER_CLIENT_ID") as String?
    ).orEmpty()

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    androidLibrary {
        namespace = "org.rurbaniak.smartchessboard.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.client.okhttp)
            // Credential Manager backing for compose-auth's native Google sign-in (Android only).
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.googleid)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation3.ui)
            implementation(libs.androidx.lifecycle.viewmodelNavigation3)
            implementation(project.dependencies.platform(libs.supabase.bom))
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.composeAuth)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            // Browser-history binding for Nav3 (not in the base multiplatform library at 1.1.1) —
            // maps the back stack to browser Back/Forward + URL fragment. wasmJs-only.
            implementation(libs.navigation3.browser)
        }
    }
}

buildkonfig {
    packageName = "org.rurbaniak.smartchessboard"
    defaultConfigs {
        buildConfigField(STRING, "SUPABASE_URL", supabaseUrl)
        buildConfigField(STRING, "SUPABASE_ANON_KEY", supabaseAnonKey)
        buildConfigField(STRING, "GOOGLE_SERVER_CLIENT_ID", googleServerClientId)
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
