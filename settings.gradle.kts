pluginManagement {
    repositories {
        // 国内镜像优先：直连 google()/mavenCentral() 在国内经常超时或极慢。
        // 镜像放在最前，原仓库保留在最后作为兜底（镜像没有的构件仍能取到）。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：国内镜像优先，原仓库兜底。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // JitPack 与 GitLab 没有可用的国内镜像（Miuix 走 JitPack），只能直连
        maven("https://jitpack.io")
        maven("https://gitlab.com/api/v4/projects/18497829/packages/maven")
    }
}

rootProject.name = "apkupdater"
include(":app")
