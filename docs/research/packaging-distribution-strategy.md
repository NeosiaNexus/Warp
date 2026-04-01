# Strategie de packaging, distribution et deploiement de Warp

**Date** : 31 mars 2026
**Objectif** : Rendre Warp trivialement facile a installer et deployer sur toute plateforme.
**Methode** : Recherche croisee des meilleures pratiques Docker, GraalVM, Shadow JAR, jpackage, Homebrew, Picocli, JReleaser, Maven Central, et des conventions du monde Minecraft.

---

## Table des matieres

1. [Docker multi-arch](#1-docker-multi-arch)
2. [GraalVM native-image : verdict](#2-graalvm-native-image--verdict)
3. [Fat JAR / Shadow JAR](#3-fat-jar--shadow-jar)
4. [Packages OS (deb, rpm, jpackage)](#4-packages-os-deb-rpm-jpackage)
5. [Homebrew / gestionnaires de paquets](#5-homebrew--gestionnaires-de-paquets)
6. [Design CLI avec Picocli](#6-design-cli-avec-picocli)
7. [Mecanisme de mise a jour automatique](#7-mecanisme-de-mise-a-jour-automatique)
8. [Publication Maven Central](#8-publication-maven-central)
9. [Site de documentation](#9-site-de-documentation)
10. [Automatisation des releases](#10-automatisation-des-releases)
11. [Fichiers de reference complets](#11-fichiers-de-reference-complets)

---

## 1. Docker multi-arch

### 1.1 Choix de l'image de base

| Image | Taille | Avantages | Inconvenients |
|-------|--------|-----------|---------------|
| `eclipse-temurin:21-jre-jammy` | ~175 MB | Standard industrie, glibc, large adoption | Plus lourd |
| `gcr.io/distroless/java21-debian12` | ~192 MB | Zero shell, surface d'attaque minimale | Debug impossible sans conteneur ephemere |
| `eclipse-temurin:21-jre-alpine` | ~114 MB | Le plus leger | musl libc, incompatible JNI (libdeflate, OpenSSL natif) |
| `eclipse-temurin:21-jre-noble` | ~180 MB | Ubuntu 24.04, tres recent | Taille similaire a jammy |

**Recommandation pour Warp** : `eclipse-temurin:21-jre-jammy` comme image de base.

Raisons :
- Warp utilise des dependances JNI (libdeflate, potentiellement OpenSSL natif via netty-tcnative) → musl/Alpine est **incompatible**
- Distroless interdit le shell → impossible de debugger en production, pas de `HEALTHCHECK` avec des commandes shell simples
- Temurin/Jammy est le choix de Velocity, Paper, et la majorite de l'ecosysteme Minecraft
- glibc garantit la compatibilite avec toutes les librairies natives

**Optimisation avancee avec jlink** : On peut construire un JRE custom ne contenant que les modules necessaires pour reduire la taille d'environ 60%. Eclipse Temurin JDK en stage builder + `jlink --add-modules` → copie dans une image `debian:bookworm-slim` ou `ubuntu:jammy`. Resultat typique : ~75 MB au lieu de 175 MB.

### 1.2 JVM et detection de conteneur

Depuis Java 10+ (et 8u191+), `-XX:+UseContainerSupport` est actif par defaut. Le JVM detecte automatiquement les limites cgroups du conteneur.

**Flags JVM recommandes pour conteneur** :
```
-XX:+UseContainerSupport              # defaut depuis Java 10
-XX:MaxRAMPercentage=75.0             # 75% de la RAM container pour le heap
-XX:+UseG1GC                          # GC adapte aux charges proxy
-XX:+UseStringDeduplication           # reduit la memoire pour les strings repetees
-XX:+ExitOnOutOfMemoryError           # crash propre plutot que zombie
-Djava.security.egd=file:/dev/urandom # startup plus rapide
```

**Regle critique** : Ne JAMAIS combiner `-Xmx`/`-Xms` avec `MaxRAMPercentage` — l'un prend le pas sur l'autre. Toujours laisser 25% de la memoire conteneur pour le non-heap (threads, metaspace, NIO buffers, GC).

### 1.3 Dockerfile production-ready

```dockerfile
# ============================================================
# Warp Proxy — Production Dockerfile
# Multi-stage, multi-arch (amd64 + arm64)
# ============================================================

# --- Stage 1 : Build ---
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Cache des dependances Gradle (layer separee)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts gradle.properties ./
COPY build-logic/ build-logic/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies || true

# Copie du source et build
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :warp-proxy:shadowJar

# --- Stage 2 : Runtime ---
FROM eclipse-temurin:21-jre-jammy

# Metadata OCI
LABEL org.opencontainers.image.title="Warp Proxy" \
      org.opencontainers.image.description="High-performance Minecraft proxy" \
      org.opencontainers.image.url="https://github.com/warp-mc/warp" \
      org.opencontainers.image.source="https://github.com/warp-mc/warp" \
      org.opencontainers.image.vendor="Warp" \
      org.opencontainers.image.licenses="Apache-2.0"

# Utilisateur non-root
RUN groupadd --gid 1000 warp && \
    useradd --uid 1000 --gid warp --shell /bin/bash --create-home warp

# Repertoire de travail
WORKDIR /opt/warp
RUN chown warp:warp /opt/warp

# Copie du JAR
COPY --from=builder --chown=warp:warp \
    /build/warp-proxy/build/libs/warp.jar /opt/warp/warp.jar

# Volume pour la configuration et les plugins
VOLUME ["/opt/warp/config", "/opt/warp/plugins"]

# Ports
# 25577 = proxy port par defaut
# 9100 = metriques Prometheus (optionnel)
EXPOSE 25577 9100

# Health check : verifie que le port proxy repond
# Warp expose un endpoint TCP de health check sur le port proxy
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD java -cp /opt/warp/warp.jar dev.warp.cli.HealthCheck || exit 1

# Utilisateur non-root
USER warp

# JVM flags optimises pour conteneur
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/warp/warp.jar $0 $@"]
CMD ["start"]
```

### 1.4 Build multi-arch avec buildx

```bash
# Creer un builder multi-arch
docker buildx create --name warp-builder --driver docker-container --bootstrap --use

# Build + push pour amd64 et arm64
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --tag ghcr.io/warp-mc/warp:1.0.0 \
  --tag ghcr.io/warp-mc/warp:latest \
  --push .
```

**Note** : l'emulation QEMU pour arm64 sur un runner amd64 est lente. Pour la CI, deux strategies :
1. **QEMU** (simple, plus lent) : suffisant car Warp est un JAR, pas de compilation native croisee
2. **Runners natifs arm64** (rapide, complexe) : GitHub propose des runners arm64 en beta

Pour un JAR Java, l'image est identique pour les deux arches — seul le JRE de base change. Le build est donc rapide meme en emulation.

---

## 2. GraalVM native-image : verdict

### 2.1 Analyse de compatibilite

| Composant Warp | Compatibilite native-image | Probleme |
|---|---|---|
| **Netty** | Partielle | Fonctionne avec configuration de reflection. Issues connues avec DNS resolver, Arena.ofShared (GraalVM 25+). Netty 4.2 a des deps non-optionnelles sur les annotations GraalVM. |
| **JNI (libdeflate)** | Problematique | JNI est supporte mais necessite une configuration manuelle exhaustive. Chaque methode native doit etre declaree. |
| **Systeme de plugins** | **Incompatible** | GraalVM exige un "closed-world assumption" : tout le code doit etre connu au build. `Class.forName()`, `ClassLoader` custom, chargement dynamique de JARs = **fondamentalement impossible**. |
| **Reflection (config, events)** | Difficile | Chaque classe/methode/champ accessible par reflection doit etre declare a l'avance. |
| **Module system (JPMS)** | Partiel | Support basique mais les interactions complexes avec les plugins posent probleme. |

### 2.2 Precedent : Minecraft natif

Le projet `hpi-swa/native-minecraft-server` a demontre :
- Compilation possible mais uniquement pour **une seule version** (1.18.2)
- Executable de ~120 MB (40 MB compresse)
- Bugs intermittents au demarrage (crashes de cast)
- **Zero support de mods/plugins** — pas de chargement dynamique
- Projet explicitement "a des fins de demonstration uniquement"

### 2.3 Verdict

**GraalVM native-image n'est PAS viable pour Warp.**

Raisons decisives :
1. **Le systeme de plugins est incompatible** — c'est un deal-breaker. Les plugins Warp sont charges dynamiquement au runtime via des ClassLoaders custom, exactement ce que native-image interdit.
2. **L'effort de maintenance est disproportionne** — chaque dependance, chaque appel reflexif necessite une configuration manuelle. Chaque mise a jour risque de casser le build natif.
3. **Le gain est marginal pour un proxy long-running** — le JIT compiler d'OpenJDK produit du code plus rapide qu'AOT sur les charges de travail longues. Le gain de startup (quelques secondes) est non pertinent pour un proxy qui tourne 24/7.
4. **Netty + GraalVM reste fragile** — les issues continuent a apparaitre en 2025/2026.

**Alternative recommandee** : Se concentrer sur l'optimisation JVM (CDS — Class Data Sharing, AppCDS) pour reduire le temps de demarrage sans sacrifier la compatibilite.

---

## 3. Fat JAR / Shadow JAR

### 3.1 Configuration Shadow

Plugin : `com.gradleup.shadow` (anciennement `com.github.johnrengelman.shadow`)
Version actuelle : **9.x** (requiert Gradle 9+, Java 17+)

```kotlin
// build-logic/src/main/kotlin/warp.application-conventions.gradle.kts
plugins {
    id("com.gradleup.shadow")
    application
}

application {
    mainClass.set("dev.warp.cli.WarpMain")
}

tasks.shadowJar {
    archiveBaseName.set("warp")
    archiveClassifier.set("")  // pas de "-all" suffix
    archiveVersion.set("")     // version dans le manifest uniquement

    // Merge des fichiers META-INF/services (crucial pour Netty, SLF4J, etc.)
    mergeServiceFiles()

    // Relocation des dependances pour eviter les conflits avec les plugins
    relocate("io.netty", "dev.warp.libs.netty")
    relocate("com.google.gson", "dev.warp.libs.gson")
    relocate("org.slf4j", "dev.warp.libs.slf4j")
    relocate("ch.qos.logback", "dev.warp.libs.logback")
    relocate("com.typesafe.config", "dev.warp.libs.config")
    relocate("info.picocli", "dev.warp.libs.picocli")
    // NE PAS relocater les packages exposes dans warp-api

    // Minimize : exclure les classes inutilisees des dependances
    minimize {
        // Garder les classes chargees par reflection
        exclude(dependency("ch.qos.logback:.*"))
        exclude(dependency("org.slf4j:.*"))
    }

    // Manifest
    manifest {
        attributes(
            "Main-Class" to "dev.warp.cli.WarpMain",
            "Implementation-Title" to "Warp Proxy",
            "Implementation-Version" to project.version,
            "Multi-Release" to "true"
        )
    }
}

// Le JAR standard ne sert a rien quand on a le shadow JAR
tasks.jar {
    enabled = false
}
```

### 3.2 Pourquoi la relocation est critique

Les plugins Warp auront leurs propres dependances. Si un plugin embarque Gson 2.10 et Warp utilise Gson 2.11, il y aura conflit sur le classpath. En relocalisant les packages internes de Warp sous `dev.warp.libs.*`, les plugins peuvent utiliser n'importe quelle version de ces librairies sans conflit.

**Attention** : ne JAMAIS relocater :
- Les packages de `warp-api` (l'API publique)
- Les packages Kotlin stdlib si utilise (couplatage tight avec le compilateur)
- Les classes chargees par SPI (`META-INF/services`) — `mergeServiceFiles()` s'en charge

### 3.3 Experience utilisateur

```bash
# Telecharger
curl -LO https://github.com/warp-mc/warp/releases/latest/download/warp.jar

# Lancer
java -jar warp.jar

# Avec options
java -jar warp.jar start --config ./my-config.toml
java -jar warp.jar version
```

Un seul fichier, aucune dependance externe sauf un JRE 21+. C'est la methode de distribution principale et celle a laquelle les administrateurs Minecraft sont habitues (identique a Velocity, Paper, BungeeCord).

---

## 4. Packages OS (deb, rpm, jpackage)

### 4.1 jpackage (Java 21)

`jpackage` cree des installateurs natifs incluant un JRE embarque. Depuis Java 21, il supporte `--launcher-as-service` pour generer automatiquement un service systemd.

```bash
# Generer un paquet .deb avec service systemd
jpackage \
  --input build/libs/ \
  --name warp \
  --main-jar warp.jar \
  --main-class dev.warp.cli.WarpMain \
  --type deb \
  --app-version 1.0.0 \
  --description "Warp - High-performance Minecraft proxy" \
  --vendor "Warp Project" \
  --license-file LICENSE \
  --linux-package-name warp \
  --linux-deb-maintainer "team@warp.dev" \
  --linux-app-category network \
  --linux-shortcut \
  --launcher-as-service \
  --java-options "-XX:+UseG1GC" \
  --java-options "-XX:MaxRAMPercentage=75.0" \
  --java-options "-XX:+ExitOnOutOfMemoryError"

# Generer un paquet .rpm
jpackage \
  --input build/libs/ \
  --name warp \
  --main-jar warp.jar \
  --main-class dev.warp.cli.WarpMain \
  --type rpm \
  --app-version 1.0.0 \
  # ... memes options
```

**Pre-requis** : `fakeroot` (Ubuntu) ou `rpm-build` (Red Hat).

### 4.2 Service systemd (manuel, pour les admins qui preferent le JAR)

```ini
# /etc/systemd/system/warp.service
[Unit]
Description=Warp Minecraft Proxy
Documentation=https://docs.warp.dev
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=warp
Group=warp
WorkingDirectory=/opt/warp
ExecStart=/usr/bin/java \
    -XX:+UseG1GC \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseStringDeduplication \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/urandom \
    -jar /opt/warp/warp.jar start

# Restart automatique en cas de crash
Restart=on-failure
RestartSec=5
StartLimitBurst=5
StartLimitIntervalSec=60

# Securite
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/opt/warp
PrivateTmp=yes
PrivateDevices=yes

# Logs via journald
StandardOutput=journal
StandardError=journal
SyslogIdentifier=warp

# Arret propre
TimeoutStopSec=30
KillSignal=SIGTERM

[Install]
WantedBy=multi-user.target
```

**Rotation des logs** : journald gere automatiquement la rotation via `SystemMaxUse` et `MaxRetentionSec` dans `/etc/systemd/journald.conf`. Aucune configuration supplementaire requise. Consulter les logs avec :

```bash
journalctl -u warp -f          # suivre en temps reel
journalctl -u warp --since "1 hour ago"  # dernier heure
```

### 4.3 Script d'installation rapide

```bash
#!/usr/bin/env bash
# install.sh — installation rapide de Warp
set -euo pipefail

WARP_VERSION="${1:-latest}"
WARP_DIR="/opt/warp"
WARP_USER="warp"

# Creer l'utilisateur
if ! id "$WARP_USER" &>/dev/null; then
    sudo useradd --system --home-dir "$WARP_DIR" --shell /usr/sbin/nologin "$WARP_USER"
fi

# Creer les repertoires
sudo mkdir -p "$WARP_DIR"/{config,plugins,data}
sudo chown -R "$WARP_USER:$WARP_USER" "$WARP_DIR"

# Telecharger le JAR
if [ "$WARP_VERSION" = "latest" ]; then
    URL=$(curl -s https://api.github.com/repos/warp-mc/warp/releases/latest | \
          grep -o 'https://.*warp\.jar' | head -1)
else
    URL="https://github.com/warp-mc/warp/releases/download/v${WARP_VERSION}/warp.jar"
fi
sudo curl -Lo "$WARP_DIR/warp.jar" "$URL"

# Installer le service systemd
sudo curl -Lo /etc/systemd/system/warp.service \
    "https://raw.githubusercontent.com/warp-mc/warp/main/dist/warp.service"
sudo systemctl daemon-reload
sudo systemctl enable warp

echo "Warp installe dans $WARP_DIR"
echo "  Demarrer : sudo systemctl start warp"
echo "  Logs     : journalctl -u warp -f"
```

---

## 5. Homebrew / gestionnaires de paquets

### 5.1 Homebrew Tap

Creer un depot `warp-mc/homebrew-warp` contenant la formule :

```ruby
# Formula/warp.rb
class Warp < Formula
  desc "High-performance Minecraft proxy"
  homepage "https://warp.dev"
  url "https://github.com/warp-mc/warp/releases/download/v1.0.0/warp.jar"
  sha256 "abc123..."
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "warp.jar"
    (bin/"warp").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" \\
        -XX:+UseG1GC \\
        -XX:+UseStringDeduplication \\
        -jar "#{libexec}/warp.jar" "$@"
    EOS
  end

  service do
    run [Formula["openjdk@21"].opt_bin/"java", "-jar", opt_libexec/"warp.jar", "start"]
    working_dir var/"warp"
    keep_alive true
    log_path var/"log/warp.log"
    error_log_path var/"log/warp-error.log"
  end

  test do
    assert_match "Warp", shell_output("#{bin}/warp version")
  end
end
```

**Installation** :
```bash
brew tap warp-mc/warp
brew install warp
```

JReleaser peut automatiser la mise a jour de cette formule a chaque release.

### 5.2 SDKMAN

SDKMAN est reserve aux SDK/JDK et outils de developpement. Warp n'est pas un outil de developpement au sens SDKMAN — il faudrait un accord avec les mainteneurs SDKMAN pour un "custom candidate", ce qui est disproportionne a ce stade.

**Verdict** : non prioritaire, a reconsiderer si Warp atteint une masse critique d'utilisateurs.

### 5.3 AUR (Arch Linux)

```bash
# PKGBUILD pour AUR
pkgname=warp
pkgver=1.0.0
pkgrel=1
pkgdesc="High-performance Minecraft proxy"
arch=('any')
url="https://github.com/warp-mc/warp"
license=('Apache-2.0')
depends=('java-runtime>=21')
source=("https://github.com/warp-mc/warp/releases/download/v${pkgver}/warp.jar"
        "warp.service")
sha256sums=('SKIP' 'SKIP')

package() {
    install -Dm644 "${srcdir}/warp.jar" "${pkgdir}/usr/share/java/warp/warp.jar"
    install -Dm644 "${srcdir}/warp.service" "${pkgdir}/usr/lib/systemd/system/warp.service"

    # Wrapper script
    install -dm755 "${pkgdir}/usr/bin"
    cat > "${pkgdir}/usr/bin/warp" <<'EOF'
#!/bin/bash
exec java -XX:+UseG1GC -jar /usr/share/java/warp/warp.jar "$@"
EOF
    chmod 755 "${pkgdir}/usr/bin/warp"
}
```

Conventions Arch respectees : JARs dans `/usr/share/java/warp/`, pas besoin du suffix `-bin` pour les packages Java.

### 5.4 Snap / Flatpak

**Verdict** : non pertinent. Snap et Flatpak ciblent les applications desktop avec GUI. Un proxy Minecraft est un serveur headless. L'overhead de confinement Snap/Flatpak n'apporte rien et complique le networking. Docker couvre mieux ce besoin.

### 5.5 Resume des canaux par priorite

| Canal | Priorite | Public cible | Effort |
|-------|----------|--------------|--------|
| **JAR direct** (GitHub Releases) | P0 | Tous | Minime |
| **Docker** (GHCR + Docker Hub) | P0 | DevOps, hebergeurs | Moyen |
| **Homebrew Tap** | P1 | macOS/Linux devs | Faible (JReleaser l'automatise) |
| **.deb / .rpm** (jpackage) | P1 | Admins Linux | Moyen |
| **AUR** | P2 | Communaute Arch | Faible |
| Script install.sh | P2 | Quick start | Faible |
| Snap/Flatpak | Non | — | — |
| SDKMAN | Non | — | — |

---

## 6. Design CLI avec Picocli

### 6.1 Pourquoi Picocli

- Framework CLI le plus mature en Java (version actuelle : 4.7.7)
- Supporte les sous-commandes imbriquees, l'auto-completion TAB, les couleurs ANSI
- Un seul fichier source — peut etre inclus sans ajouter de dependance
- Compatible GraalVM native-image (non utilise ici, mais bon signe de maturite)
- Codes de sortie, mode interactif, conversion de types automatique

### 6.2 Structure des commandes

```
warp                          # Affiche l'aide
warp start                    # Demarre le proxy (mode principal)
warp start --config <path>    # Config custom
warp start --port <port>      # Override le port
warp stop                     # Arret propre (envoie SIGTERM ou commande IPC)
warp status                   # Affiche l'etat (connexions, backends, uptime)
warp version                  # Version, build, Java, OS
warp drain                    # Active le mode drain (plus de nouvelles connexions)
warp drain --cancel           # Desactive le drain
warp reload                   # Recharge la config a chaud (sans redemarrage)
warp plugin list              # Liste les plugins installes
warp plugin install <name>    # Installe un plugin depuis le registre
warp plugin remove <name>     # Desinstalle un plugin
warp migrate --from velocity  # Migration depuis Velocity (convertit velocity.toml)
warp check                    # Valide la configuration sans demarrer
warp update                   # Verifie et installe les mises a jour
warp update --check           # Verifie seulement (pas d'installation)
```

### 6.3 Implementation

```java
@Command(
    name = "warp",
    mixinStandardHelpOptions = true,
    versionProvider = WarpVersionProvider.class,
    description = "High-performance Minecraft proxy",
    subcommands = {
        StartCommand.class,
        StopCommand.class,
        StatusCommand.class,
        DrainCommand.class,
        ReloadCommand.class,
        PluginCommand.class,
        MigrateCommand.class,
        CheckCommand.class,
        UpdateCommand.class,
    }
)
public final class WarpMain implements Runnable {

    @Override
    public void run() {
        // Sans sous-commande → affiche l'aide
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WarpMain())
            .setColorScheme(WarpColorScheme.create())
            .execute(args);
        System.exit(exitCode);
    }
}

// --- Exemple : StartCommand ---
@Command(name = "start", description = "Start the Warp proxy")
public final class StartCommand implements Callable<Integer> {

    @Option(names = {"--config", "-c"}, description = "Path to config file",
            defaultValue = "warp.toml")
    private Path configPath;

    @Option(names = {"--port", "-p"}, description = "Proxy listen port")
    private Integer port;  // null = utilise la config

    @Option(names = {"--no-plugins"}, description = "Disable plugin loading")
    private boolean noPlugins;

    @Override
    public Integer call() {
        // Bootstrap du proxy
        var config = WarpConfig.load(configPath);
        if (port != null) config.setPort(port);

        var proxy = new WarpProxy(config);
        if (!noPlugins) proxy.loadPlugins();

        proxy.start();  // bloquant
        return 0;
    }
}

// --- Codes de sortie ---
public final class ExitCodes {
    public static final int SUCCESS = 0;
    public static final int GENERAL_ERROR = 1;
    public static final int INVALID_CONFIG = 2;
    public static final int PORT_IN_USE = 3;
    public static final int UPDATE_AVAILABLE = 100;  // pour le wrapper script
}
```

### 6.4 Detection du mode interactif

```java
// Si stdin est un terminal → mode interactif (couleurs, progress bars)
// Si stdin n'est pas un terminal → mode CI/script (pas de couleurs, JSON output)
boolean interactive = System.console() != null;
```

### 6.5 Auto-completion

Picocli genere un script de completion pour bash/zsh :
```bash
# Generation
java -cp warp.jar picocli.AutoComplete dev.warp.cli.WarpMain

# Installation
source warp_completion.bash
# ou copier dans /etc/bash_completion.d/
```

A distribuer dans le Homebrew tap et les packages deb/rpm.

---

## 7. Mecanisme de mise a jour automatique

### 7.1 Architecture recommandee

Le pattern "exit code + wrapper script" est le plus robuste et le plus simple :

```
┌──────────────────────────────────────────────┐
│                 Wrapper Script                │
│                (warp-launcher)                │
│                                              │
│  1. Lance java -jar warp.jar                 │
│  2. Si exit code = 100 → re-lance le JAR     │
│  3. Si exit code = 0   → arret normal        │
│  4. Si exit code = 1   → erreur, log, arret  │
└──────────────────────────────────────────────┘
```

### 7.2 Flow de mise a jour

```
warp update --check
    │
    ├─ GET https://api.github.com/repos/warp-mc/warp/releases/latest
    │
    ├─ Compare version locale vs version distante (SemVer)
    │
    ├─ Si a jour → "Warp is up to date (v1.2.3)"
    │
    └─ Si MAJ dispo → Affiche changelog resume
                        │
                        ├─ warp update
                        │   ├─ Telecharge warp-1.3.0.jar dans /opt/warp/.update/
                        │   ├─ Verifie le checksum SHA-256
                        │   ├─ Renomme warp.jar → warp.jar.bak
                        │   ├─ Deplace warp-1.3.0.jar → warp.jar
                        │   └─ Exit code 100 → wrapper relance
                        │
                        └─ Si le nouveau JAR ne demarre pas
                            ├─ Detecte via timeout (pas de bind dans les 30s)
                            ├─ Restaure warp.jar.bak → warp.jar
                            └─ Log l'erreur, relance l'ancienne version
```

### 7.3 Principes

- **Opt-in** : la verification au demarrage est desactivee par defaut. Activable dans `warp.toml` : `auto-update-check = true`
- **Jamais de redemarrage automatique** sans action explicite de l'admin
- **Rollback integre** : si la nouvelle version echoue, l'ancienne est restauree
- **Checksum SHA-256** : verifie l'integrite du telechargement contre le fichier `.sha256` de la release
- **Pas de dependance externe** : utilise `java.net.http.HttpClient` et l'API GitHub Releases

### 7.4 Alternative : update4j

La librairie `update4j` (v1.5.9, Apache 2.0) offre un framework complet de mise a jour pour Java 9+. Elle supporte l'hebergement n'importe ou (S3, GitHub, Maven Central) et se met a jour elle-meme.

**Verdict** : surdimensionne pour Warp. Le pattern exit code + wrapper est plus simple, plus transparent, et sans dependance supplementaire. update4j est pertinent pour des applications desktop, pas un proxy serveur.

---

## 8. Publication Maven Central

### 8.1 Artefacts a publier

| Artefact | GroupId | Usage |
|----------|---------|-------|
| `warp-api` | `dev.warp` | API publique pour les developpeurs de plugins |
| `warp-protocol` | `dev.warp` | Pour ceux qui veulent manipuler le protocole MC |
| `warp-plugin-api` | `dev.warp` | SPI pour les extensions |
| `warp-testing` | `dev.warp` | Framework de test pour les plugins |
| `warp-bom` | `dev.warp` | Bill of Materials pour aligner les versions |

Le JAR principal (`warp-proxy` avec shadow) n'est **PAS** publie sur Maven Central — il est distribue via GitHub Releases et Docker.

### 8.2 Configuration Gradle avec Vanniktech

```kotlin
// build-logic/src/main/kotlin/warp.publish-conventions.gradle.kts
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true
    )
    signAllPublications()

    coordinates(
        groupId = "dev.warp",
        artifactId = project.name,
        version = project.version.toString()
    )

    pom {
        name.set(project.name)
        description.set("Warp Proxy - High-performance Minecraft proxy")
        inceptionYear.set("2026")
        url.set("https://github.com/warp-mc/warp")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("warp-team")
                name.set("Warp Team")
                url.set("https://github.com/warp-mc")
            }
        }

        scm {
            url.set("https://github.com/warp-mc/warp")
            connection.set("scm:git:git://github.com/warp-mc/warp.git")
            developerConnection.set("scm:git:ssh://git@github.com/warp-mc/warp.git")
        }
    }
}
```

### 8.3 Secrets GitHub necessaires

```
MAVEN_CENTRAL_USERNAME    # Token utilisateur Sonatype Central Portal
MAVEN_CENTRAL_PASSWORD    # Token mot de passe
ORG_GRADLE_PROJECT_signingInMemoryKey          # Cle GPG ASCII-armored
ORG_GRADLE_PROJECT_signingInMemoryKeyId        # ID court de la cle (8 chars)
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword  # Passphrase GPG
```

Le plugin Vanniktech detecte automatiquement les variables `ORG_GRADLE_PROJECT_signing*` pour la signature in-memory, adaptee au CI.

### 8.4 Workflow de publication

```
Tag v1.2.3 pousse sur main
    │
    ├─ Build + tests complets
    ├─ ./gradlew publishAndReleaseToMavenCentral
    │   ├─ Signe les artefacts (GPG in-memory)
    │   ├─ Upload vers Sonatype Central Portal
    │   ├─ Validation automatique (poll toutes les 5s, timeout 60min)
    │   └─ Publication effective sur Maven Central
    │
    └─ Snapshots : sur chaque merge dans main
        └─ ./gradlew publishToMavenCentral (sans release)
```

---

## 9. Site de documentation

### 9.1 Stack recommandee

| Composant | Outil | Raison |
|-----------|-------|--------|
| Generateur | **Docusaurus 3** | Standard React, versioning integre, MDX |
| Hebergement | **GitHub Pages** | Gratuit, CI integre, custom domain |
| Recherche | **Algolia DocSearch** (gratuit pour l'OSS) | Le plus integre avec Docusaurus, zero infra |
| Alternative recherche | **docusaurus-search-local** | Offline, pas de service tiers |
| API docs | **Javadoc** genere par Gradle | Integre dans la CI, publie comme sous-page |

### 9.2 Structure du site

```
docs.warp.dev/
├── /                       # Landing page
├── /docs/                  # Guide utilisateur
│   ├── getting-started/
│   ├── configuration/
│   ├── plugins/
│   ├── migration/          # Migration depuis Velocity
│   ├── deployment/         # Docker, systemd, etc.
│   └── faq/
├── /api/                   # Javadoc (warp-api)
├── /blog/                  # Blog technique (releases, deep dives)
└── /versions/              # Docs versionnees (1.x, 2.x)
```

### 9.3 Versioning des docs

Docusaurus supporte nativement le versioning. Strategie :
- Branche `main` → version "Next" (dev)
- Chaque release majeure (1.0, 2.0) → snapshot des docs avec `docusaurus version 1.0`
- URL : `docs.warp.dev/docs/1.0/getting-started` vs `docs.warp.dev/docs/next/getting-started`

### 9.4 Integration Javadoc

La CI genere la Javadoc et la deploie comme assets statiques :
```yaml
# Dans le workflow GitHub Actions
- name: Generate Javadoc
  run: ./gradlew :warp-api:javadoc

- name: Copy Javadoc to docs site
  run: cp -r warp-api/build/docs/javadoc docs/static/api
```

### 9.5 Recherche

**Algolia DocSearch** : programme gratuit pour les projets open-source. Le crawler Algolia indexe le site et expose une API de recherche. Docusaurus a un plugin natif `@docusaurus/preset-classic` qui l'integre.

**Alternative** : `@easyops-cn/docusaurus-search-local` pour un index local, sans service tiers. Poids : quelques centaines de Ko. Moins bon pour les gros sites mais suffisant pour demarrer.

**Pagefind** : excellent pour les sites statiques generiques, mais pas d'integration native avec Docusaurus. A eviter pour l'instant.

---

## 10. Automatisation des releases

### 10.1 Vue d'ensemble du pipeline

```
  Développeur                    GitHub Actions                    Distribution
  ──────────                    ──────────────                    ────────────
       │                              │                                │
  git push tag v1.2.3 ──────────────>│                                │
       │                              │                                │
       │                     ┌────────┴────────┐                      │
       │                     │  Build + Tests   │                      │
       │                     └────────┬────────┘                      │
       │                              │                                │
       │                     ┌────────┴────────┐                      │
       │                     │   JReleaser      │                      │
       │                     │   full-release   │                      │
       │                     └────────┬────────┘                      │
       │                              │                                │
       │               ┌──────────────┼──────────────┐                │
       │               │              │              │                │
       │        GitHub Release   Docker Image   Maven Central         │
       │        + changelog     GHCR + DockerHub  warp-api            │
       │        + warp.jar      multi-arch        warp-bom            │
       │               │              │              │                │
       │               │    Homebrew formula    AUR PKGBUILD          │
       │               │      auto-update        (manuel)             │
       │               │              │              │                │
       │               └──────────────┼──────────────┘                │
       │                              │                                │
       │                     Discord webhook                           │
       │                     (annonce release)                         │
       │                              │                                │
```

### 10.2 Outil principal : JReleaser

JReleaser orchestre tout le processus de release. Il supporte nativement :
- Conventional Commits → changelog automatique
- GitHub Releases avec artefacts
- Docker multi-arch via buildx
- Homebrew formula auto-update
- Annonces (Discord, Twitter, Slack)
- Signing GPG

### 10.3 Configuration JReleaser

```yaml
# jreleaser.yml (a la racine du projet)
project:
  name: warp
  description: High-performance Minecraft proxy
  longDescription: |
    Warp is an open-source, high-performance Minecraft Java Edition proxy
    designed as a modern alternative to Velocity.
  website: https://warp.dev
  docsUrl: https://docs.warp.dev
  license: Apache-2.0
  authors:
    - Warp Team
  tags:
    - minecraft
    - proxy
    - java
  java:
    groupId: dev.warp
    version: 21
    multiProject: true

release:
  github:
    owner: warp-mc
    name: warp
    overwrite: true
    changelog:
      formatted: ALWAYS
      preset: conventional-commits
      format: "- {{commitShortHash}} {{commitTitle}}"
      contributors:
        format: "- {{contributorName}} ({{contributorUsernameAsLink}})"
      hide:
        categories:
          - merge
        contributors:
          - GitHub
          - dependabot

signing:
  active: ALWAYS
  armored: true

distributions:
  warp:
    type: JAVA_BINARY
    artifacts:
      - path: warp-proxy/build/libs/warp.jar

packagers:
  docker:
    active: ALWAYS
    repository:
      owner: warp-mc
      name: warp
    registries:
      - serverName: ghcr
        server: ghcr.io
      - serverName: docker
        server: DEFAULT
    baseImage: eclipse-temurin:21-jre-jammy
    imageNames:
      - "{{repoOwner}}/{{distributionName}}:{{tagName}}"
      - "{{repoOwner}}/{{distributionName}}:latest"
    buildx:
      enabled: true
      createBuilder: true
      platforms:
        - linux/amd64
        - linux/arm64

  brew:
    active: ALWAYS
    repository:
      owner: warp-mc
      name: homebrew-warp
    formulaName: warp
    dependencies:
      - key: openjdk@21

announce:
  discord:
    active: ALWAYS
    webhook: "{{discordWebhook}}"
    message: |
      🚀 Warp {{projectVersion}} released!
      {{changelog}}
      Download: {{releaseNotesUrl}}
```

---

## 11. Fichiers de reference complets

### 11.1 GitHub Actions — Pipeline de release

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write
  packages: write
  id-token: write

jobs:
  # ──────────────────────────────────────────
  # Job 1 : Build et tests
  # ──────────────────────────────────────────
  build:
    name: Build & Test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0  # requis pour JReleaser (changelog)

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle

      - name: Build & Test
        run: ./gradlew build --no-daemon

      - name: Shadow JAR
        run: ./gradlew :warp-proxy:shadowJar --no-daemon

      - name: Upload artifacts
        uses: actions/upload-artifact@v4
        with:
          name: build-artifacts
          path: |
            warp-proxy/build/libs/warp.jar
            warp-api/build/libs/*.jar
          retention-days: 1

  # ──────────────────────────────────────────
  # Job 2 : Publication Maven Central
  # ──────────────────────────────────────────
  publish-maven:
    name: Publish to Maven Central
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle

      - name: Publish to Maven Central
        run: ./gradlew publishAndReleaseToMavenCentral --no-daemon
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_SIGNING_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.GPG_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}

  # ──────────────────────────────────────────
  # Job 3 : Docker multi-arch
  # ──────────────────────────────────────────
  docker:
    name: Docker Multi-Arch
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Download artifacts
        uses: actions/download-artifact@v4
        with:
          name: build-artifacts

      - name: Set up QEMU
        uses: docker/setup-qemu-action@v3

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Extract version from tag
        id: version
        run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          platforms: linux/amd64,linux/arm64
          tags: |
            ghcr.io/warp-mc/warp:${{ steps.version.outputs.VERSION }}
            ghcr.io/warp-mc/warp:latest
            warpmcproxy/warp:${{ steps.version.outputs.VERSION }}
            warpmcproxy/warp:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  # ──────────────────────────────────────────
  # Job 4 : GitHub Release + JReleaser
  # ──────────────────────────────────────────
  release:
    name: GitHub Release
    needs: [build, publish-maven, docker]
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - name: Download artifacts
        uses: actions/download-artifact@v4
        with:
          name: build-artifacts

      - name: Run JReleaser
        uses: jreleaser/release-action@v2
        with:
          arguments: full-release
        env:
          JRELEASER_GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          JRELEASER_GPG_SECRET_KEY: ${{ secrets.GPG_SIGNING_KEY }}
          JRELEASER_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
          JRELEASER_GPG_PUBLIC_KEY: ${{ secrets.GPG_PUBLIC_KEY }}
          JRELEASER_DISCORD_WEBHOOK: ${{ secrets.DISCORD_WEBHOOK }}

  # ──────────────────────────────────────────
  # Job 5 : Documentation
  # ──────────────────────────────────────────
  docs:
    name: Deploy Documentation
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle

      - name: Generate Javadoc
        run: ./gradlew :warp-api:javadoc --no-daemon

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: npm
          cache-dependency-path: docs/website/package-lock.json

      - name: Build Docusaurus site
        working-directory: docs/website
        run: |
          npm ci
          cp -r ../../warp-api/build/docs/javadoc static/api
          npm run build

      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v4
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: docs/website/build
          cname: docs.warp.dev
```

### 11.2 GitHub Actions — CI continue

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read
  checks: write

jobs:
  build:
    name: Build & Test
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: [21, 22]  # tester la prochaine version aussi
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java ${{ matrix.java }}
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: gradle

      - name: Build
        run: ./gradlew build --no-daemon

      - name: Test Report
        uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          report_paths: '**/build/test-results/test/TEST-*.xml'

  # Snapshot publish sur merge dans main
  snapshot:
    name: Publish Snapshot
    needs: build
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle

      - name: Publish Snapshot
        run: ./gradlew publishToMavenCentral --no-daemon
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_SIGNING_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.GPG_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
```

### 11.3 Dockerfile runtime-only (pour CI pre-build)

Pour les utilisateurs qui buildent eux-memes ou en CI quand le JAR est deja construit :

```dockerfile
# Dockerfile.prebuilt — utilise un JAR deja construit
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="Warp Proxy" \
      org.opencontainers.image.source="https://github.com/warp-mc/warp"

RUN groupadd --gid 1000 warp && \
    useradd --uid 1000 --gid warp --shell /bin/bash --create-home warp

WORKDIR /opt/warp
RUN chown warp:warp /opt/warp

COPY --chown=warp:warp warp.jar /opt/warp/warp.jar

VOLUME ["/opt/warp/config", "/opt/warp/plugins"]
EXPOSE 25577 9100

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD java -cp /opt/warp/warp.jar dev.warp.cli.HealthCheck || exit 1

USER warp

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/warp/warp.jar $0 $@"]
CMD ["start"]
```

### 11.4 docker-compose.yml d'exemple

```yaml
# docker-compose.yml — Warp + backends Minecraft
services:
  warp:
    image: ghcr.io/warp-mc/warp:latest
    container_name: warp-proxy
    restart: unless-stopped
    ports:
      - "25565:25577"    # Port public vers port proxy interne
      - "9100:9100"      # Metriques Prometheus (optionnel)
    volumes:
      - ./config:/opt/warp/config
      - ./plugins:/opt/warp/plugins
    environment:
      JAVA_OPTS: >-
        -XX:+UseContainerSupport
        -XX:MaxRAMPercentage=75.0
        -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 512M
          cpus: "2.0"
    networks:
      - minecraft

  lobby:
    image: itzg/minecraft-server
    environment:
      EULA: "TRUE"
      TYPE: PAPER
      ONLINE_MODE: "FALSE"
    volumes:
      - ./servers/lobby:/data
    networks:
      - minecraft

  survival:
    image: itzg/minecraft-server
    environment:
      EULA: "TRUE"
      TYPE: PAPER
      ONLINE_MODE: "FALSE"
    volumes:
      - ./servers/survival:/data
    networks:
      - minecraft

networks:
  minecraft:
    driver: bridge
```

---

## Annexe A : Resume des decisions

| Decision | Choix | Raison |
|----------|-------|--------|
| Image Docker de base | `eclipse-temurin:21-jre-jammy` | Compatibilite JNI, standard industrie |
| GraalVM native-image | **Rejete** | Plugins incompatibles, effort disproportionne |
| Shadow JAR | `com.gradleup.shadow` 9.x | Fat JAR avec relocation, standard Gradle |
| Package manager | JReleaser | Orchestre tout : GH Release, Docker, Homebrew, Discord |
| CLI framework | Picocli 4.7.x | Maturite, sous-commandes, autocompletion |
| Publication Maven Central | Vanniktech plugin 0.36+ | Le plus maintenu, Sonatype Central Portal natif |
| Documentation | Docusaurus 3 + GitHub Pages | Gratuit, versioning, MDX |
| Mise a jour auto | Exit code + wrapper | Simple, transparent, sans dependance |
| OS packages | jpackage (deb/rpm) + systemd | Integre Java 21, service systemd natif |
| Recherche docs | Algolia DocSearch | Gratuit pour OSS, integration Docusaurus native |

## Annexe B : Secrets GitHub requis

| Secret | Usage |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | Token Sonatype Central Portal |
| `MAVEN_CENTRAL_PASSWORD` | Token Sonatype Central Portal |
| `GPG_SIGNING_KEY` | Cle privee GPG ASCII-armored |
| `GPG_KEY_ID` | ID court de la cle (8 chars) |
| `GPG_PASSPHRASE` | Passphrase de la cle GPG |
| `GPG_PUBLIC_KEY` | Cle publique GPG ASCII-armored (pour JReleaser) |
| `DOCKERHUB_USERNAME` | Compte Docker Hub |
| `DOCKERHUB_TOKEN` | Access token Docker Hub |
| `DISCORD_WEBHOOK` | URL du webhook Discord pour les annonces |

## Annexe C : Ordre de priorite d'implementation

**Phase 1 (MVP)** — avant la premiere release publique :
1. Shadow JAR avec relocation et `java -jar warp.jar`
2. CLI Picocli (`start`, `version`, `stop`, `status`)
3. Dockerfile production-ready
4. CI GitHub Actions (build + tests)

**Phase 2 (v1.0)** — premiere release stable :
5. Pipeline release complet (JReleaser + GitHub Release)
6. Docker multi-arch (GHCR)
7. Publication Maven Central de `warp-api`
8. Service systemd + script install.sh

**Phase 3 (post-v1.0)** — croissance de l'ecosysteme :
9. Homebrew tap
10. Docusaurus + GitHub Pages
11. Package AUR
12. Mecanisme de mise a jour auto
13. Packages deb/rpm via jpackage
14. Docker Hub (en plus de GHCR)
15. Annonces Discord automatiques

---

## Sources

### Docker et images Java
- [Best Docker Base Images for Java 2025](https://dev.to/devaaai/best-docker-base-images-and-performance-optimization-for-java-applications-in-2025-kdd)
- [Best Java Docker Image: Comparison Guide 2026](https://www.chainguard.dev/supply-chain-security-101/best-java-docker-image-comparison-guide-2026)
- [Docker Multi-platform Docs](https://docs.docker.com/build/building/multi-platform/)
- [Eclipse Temurin Docker Hub](https://hub.docker.com/_/eclipse-temurin)
- [Distroless Docker Images Guide](https://bell-sw.com/blog/distroless-containers-for-security-and-size/)
- [Slim Docker Images for Java](https://piotrminkowski.com/2023/11/07/slim-docker-images-for-java/)
- [JLink Docker Image Optimization](https://snyk.io/blog/jlink-create-docker-images-spring-boot-java/)
- [Java on Containers Guide - Datadog](https://www.datadoghq.com/blog/java-on-containers/)
- [JVM Container Memory Best Practices](https://dzone.com/articles/best-practices-java-memory-arguments-for-container)

### GraalVM
- [Native Minecraft Server (GraalVM)](https://github.com/hpi-swa/native-minecraft-server)
- [GraalVM Native Image Libraries](https://www.graalvm.org/native-image/libraries-and-frameworks/)
- [GraalVM Compatibility Guide](https://www.graalvm.org/latest/reference-manual/native-image/metadata/Compatibility/)
- [GraalVM Native Image Limitations](https://www.graalvm.org/22.0/reference-manual/native-image/Limitations/)
- [Netty + GraalVM Issues](https://github.com/netty/netty/issues/11122)

### Shadow JAR
- [Shadow Gradle Plugin](https://gradleup.com/shadow/)
- [Shadow Relocation](https://gradleup.com/shadow/configuration/relocation/)
- [Shadow Service File Merging](https://gradleup.com/shadow/configuration/merging/)

### jpackage et OS packages
- [JPackage Guide - dev.java](https://dev.java/learn/jvm/tool/jpackage/)
- [jpackage Baeldung](https://www.baeldung.com/java14-jpackage)
- [Java Package Guidelines - Arch Wiki](https://wiki.archlinux.org/title/Java_package_guidelines)

### Homebrew et distribution
- [Homebrew Taps](https://docs.brew.sh/Taps)
- [JReleaser Homebrew Packager](https://jreleaser.org/guide/latest/reference/packagers/homebrew.html)
- [SDKMAN Vendors](https://sdkman.io/vendors/)

### CLI
- [Picocli](https://picocli.info/)
- [Picocli Quick Guide](https://picocli.info/quick-guide.html)
- [Picocli Autocompletion](https://picocli.info/autocomplete.html)

### Auto-update
- [update4j](https://github.com/update4j/update4j)
- [Cantara Java Auto-Update](https://github.com/Cantara/Java-Auto-Update)

### Maven Central
- [Vanniktech Maven Publish Plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
- [JReleaser Maven Central 2025 Guide](https://foojay.io/today/how-to-publish-a-java-maven-project-to-maven-central-using-jreleaser-and-github-actions-2025-guide/)
- [Sonatype Central Portal Publishing](https://central.sonatype.org/publish/publish-portal-maven/)

### Documentation
- [Docusaurus](https://docusaurus.io/)
- [Docusaurus Search](https://docusaurus.io/docs/search)
- [Pagefind](https://pagefind.app/)
- [docusaurus-search-local](https://github.com/easyops-cn/docusaurus-search-local)

### Release automation
- [JReleaser](https://jreleaser.org/)
- [JReleaser GitHub Actions](https://jreleaser.org/guide/latest/continuous-integration/github-actions.html)
- [JReleaser Changelog Config](https://jreleaser.org/guide/latest/reference/release/changelog.html)
- [JReleaser Docker Packager](https://jreleaser.org/guide/latest/reference/packagers/docker.html)
- [Publishing Multi-Arch Docker to GHCR](https://dev.to/pradumnasaraf/publishing-multi-arch-docker-images-to-ghcr-using-buildx-and-github-actions-2k7j)

### Velocity (reference)
- [Velocity Getting Started](https://docs.papermc.io/velocity/getting-started/)
- [Velocity GitHub](https://github.com/PaperMC/Velocity)
