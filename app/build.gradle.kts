plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.livestudio"
    compileSdk = 34
    defaultConfig { applicationId = "com.livestudio"; minSdk = 21; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.4")
}
