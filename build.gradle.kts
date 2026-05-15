plugins {
<<<<<<< ours
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
=======
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
>>>>>>> theirs
}
