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
    alias(libs.plugins.roborazzi)
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
            // androidContext() for the journal's SharedPreferences provisioning.
            implementation(libs.koin.android)
            // Kable BLE — the real BoardConnection transport (S-09). Android + iOS only;
            // never commonMain/wasmJs (web is digital-only, no physical board).
            implementation(libs.kable.core)
            // Activity-result launcher for the runtime BLE permission request (S-09 connection screen).
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            // Official window classification: the modern WindowSizeClass type (width 600/840 + height
            // 480/900 breakpoints). Type-only — the class is computed from LocalWindowInfo at the App
            // root (ladder rung 2: material3.adaptive needs AGP >= 9.1, we're on 9.0.1).
            implementation(libs.androidx.window.core)
            // Icon-only actions in the compact-height rail (AdaptiveActionButton). Frozen at 1.7.3
            // upstream; unused icons are stripped per target (R8 / wasm DCE / Native link).
            implementation(libs.compose.materialIconsExtended)
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
            implementation(libs.supabase.functions)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // Durable key-value store backing the game journal (SharedPreferences /
            // NSUserDefaults / localStorage) — write-ahead half of the §6.2 invariant.
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            // compose.uiTest v2 smoke flows (uitest/): real App() + Koin fake overrides, driven by
            // semantics. Contract targets are iosSimulatorArm64Test + wasmJsTest; on the Android
            // host the uitest/ package is excluded below (no instrumentation under plain JUnit4).
            implementation(libs.compose.uiTest)
        }
        // Golden (screenshot) tests: JVM-only, Robolectric-rendered (NATIVE graphics), Roborazzi-
        // compared. Goldens live in src/androidHostTest/snapshots/ — see "Screenshot (golden)
        // tests" in ../AGENTS.md for the record/verify invocations.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.androidx.uiTestManifest)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            // Kable BLE — the real BoardConnection transport (S-09). iOS half of the
            // Android+iOS pairing; never wasmJs.
            implementation(libs.kable.core)
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

// The compose.uiTest smoke flows (src/commonTest/.../uitest/) run on the contract targets:
// iosSimulatorArm64Test + wasmJsTest. The Android host task is a plain-JUnit4 JVM run with no
// instrumentation — AndroidComposeUiTestEnvironment NPEs probing android.os.Build.FINGERPRINT
// (its Robolectric detection) before any test body runs, and commonTest classes cannot carry
// @RunWith(RobolectricTestRunner). Android behavior stays covered by the ViewModel/reducer
// suites. Only the JVM host task has type Test, so the iOS/wasm test tasks are unaffected.
// The same host task also carries the Roborazzi mode flags: -Droborazzi.* forwarded from the
// CLI into the test JVM, so `:shared:testAndroidHostTest -Droborazzi.test.record=true` (and
// …verify=true) works regardless of whether the Roborazzi plugin wires its record/verify tasks
// for the AGP KMP androidHostTest suite.
tasks.withType<Test>().configureEach {
    exclude("**/uitest/**")
    listOf("roborazzi.test.record", "roborazzi.test.compare", "roborazzi.test.verify").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
