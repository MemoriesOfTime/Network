# Network

### Introduction

Network components used within Cloudburst projects.

### Components

- [`netty-transport-raknet`](transport-raknet/README.md) - A RakNet implementation based on Netty patterns

### Maven

##### Repository:

Snapshots can be found in the repository below.

<details open>
<summary>Gradle (Kotlin DSL)</summary>

```kotlin
repositories {
    maven("https://repo.mot.dev/maven-snapshots")
}
```

</details>
<details>
<summary>Gradle</summary>

```groovy
repositories {
    maven {
        url 'https://repo.mot.dev/maven-snapshots'
    }
}
```

</details>
<details>
<summary>Maven</summary>

```xml

<repositories>
  <repository>
    <id>mot-snapshots</id>
    <url>https://repo.mot.dev/maven-snapshots</url>
  </repository>
</repositories>
```

</details>
