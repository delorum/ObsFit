pluginManagement { repositories { google { content { excludeGroup("junit"); excludeGroup("org.hamcrest") } }; mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google { content { excludeGroup("junit"); excludeGroup("org.hamcrest") } }; mavenCentral() } }
rootProject.name = "ObsFit"
include(":app", ":core")
