# Strategie multi-version pour Warp Proxy : 1.7.2 a latest

*Recherche approfondie -- Mars 2026*

---

## Table des matieres

1. [Comment Velocity gere le multi-version](#1-comment-velocity-gere-le-multi-version)
2. [Comment ViaVersion traduit les protocoles](#2-comment-viaversion-traduit-les-protocoles)
3. [Sources de donnees protocole Minecraft](#3-sources-de-donnees-protocole-minecraft)
4. [Pipeline automatise de mise a jour protocole](#4-pipeline-automatise-de-mise-a-jour-protocole)
5. [Enregistrement et rejeu de paquets](#5-enregistrement-et-rejeu-de-paquets)
6. [Compatibilite ascendante de l'API plugin](#6-compatibilite-ascendante-de-lapi-plugin)
7. [Base de regressions par version](#7-base-de-regressions-par-version)
8. [Strategie recommandee pour Warp](#8-strategie-recommandee-pour-warp)
9. [Design du pipeline CI/CD](#9-design-du-pipeline-cicd)
10. [Matrice de tests](#10-matrice-de-tests)

---

## 1. Comment Velocity gere le multi-version

### Architecture du StateRegistry

Velocity utilise un **enum `StateRegistry`** (HANDSHAKE, STATUS, LOGIN, CONFIG, PLAY) ou chaque etat contient deux registres : `clientbound` et `serverbound`. Chaque registre est un `PacketRegistry` qui contient une map `ProtocolVersion -> ProtocolRegistry`.

**Mecanisme cle** : la methode `register()` prend une classe de paquet et un tableau de `PacketMapping` :

```java
serverbound.register(KeepAlivePacket.class, KeepAlivePacket::new,
    map(0x00, MINECRAFT_1_7_2, false),
    map(0x0B, MINECRAFT_1_9, false),
    map(0x0C, MINECRAFT_1_12, false),
    // ... 15+ mappings jusqu'a 26.1
    map(0x1C, MINECRAFT_26_1, false));
```

Chaque `PacketMapping` definit un ID de paquet valide a partir d'une version donnee. Le systeme remplit automatiquement les versions intermediaires : si le mapping dit `0x0B` a partir de 1.9, alors 1.9, 1.9.1, 1.9.2, 1.9.4, 1.10, 1.11 utilisent tous `0x0B` jusqu'au prochain mapping.

**Le `ProtocolRegistry`** interne utilise deux maps :
- `IntObjectHashMap<Supplier<MinecraftPacket>>` -- ID vers constructeur (decodage)
- `Object2IntOpenHashMap<Class<MinecraftPacket>>` -- classe vers ID (encodage)

### Le `ProtocolVersion` enum

Velocity definit toutes les versions supportees dans un enum avec le numero de protocole numerique :

```
MINECRAFT_1_7_2(4), MINECRAFT_1_8(47), MINECRAFT_1_9(107), ...
MINECRAFT_1_21_5(770), MINECRAFT_1_21_6(771), MINECRAFT_26_1(775)
```

Plusieurs versions Minecraft partagent le meme protocole (ex: 1.8 a 1.8.9 = protocole 47, 1.16.4 et 1.16.5 = protocole 754).

### Limitations de Velocity pour Warp

1. **Tout est en dur** : les mappings de paquets sont codes directement dans `StateRegistry.java` (1145 lignes). Chaque nouvelle version necessite une modification manuelle.
2. **Pas de traduction de protocole** : Velocity ne traduit PAS entre versions. Le client et le serveur backend doivent parler la meme version. C'est ViaVersion qui ajoute cette capacite.
3. **Paquets limites** : Velocity ne decode que ~40 paquets (ceux qu'il doit inspecter). Le reste est forward en blind. C'est un choix delibere -- un proxy n'a pas besoin de comprendre tous les paquets.
4. **Tests minimaux** : `PacketRegistryTest.java` teste uniquement la mecanique du registre (ordre, doublons, resolution d'ID). Il n'y a PAS de test qui valide les mappings reels contre des donnees Mojang.

### Ce que Warp peut reprendre

- **Le pattern de registre range-based** est elegant et compact. Un mapping s'applique de sa version jusqu'au prochain mapping.
- **L'enum ProtocolVersion** avec constantes nommees est clair et type-safe.
- **Le flag `encodeOnly`** permet d'enregistrer des paquets qu'on envoie mais qu'on ne decodera jamais (ex: paquets clientbound qu'on forward tels quels).

### Ce que Warp doit faire differemment

- **Externaliser les mappings** dans des fichiers de donnees (JSON/NBT) au lieu de les coder en dur. Cela permet la mise a jour sans recompilation.
- **Generer automatiquement** les mappings depuis les donnees Mojang.
- **Tester les mappings** contre les rapports officiels de Mojang (`packets.json`).

---

## 2. Comment ViaVersion traduit les protocoles

### Architecture en chaine

ViaVersion utilise un pattern de **pipeline de protocoles chaines**. Au lieu de traduire directement de la version X a la version Y, il chaine des traductions incrementales :

```
Client 1.8 -> [Protocol1_8To1_9] -> [Protocol1_9To1_9_3] -> ... -> [Protocol1_20_5To1_21] -> Serveur 1.21
```

Chaque `Protocol` est une classe qui gere la transformation entre **exactement deux versions adjacentes**.

### Structure d'un Protocol

```java
public class Protocol1_12_2To1_13 extends AbstractProtocol<
    ClientboundPackets1_12_1,   // paquets unmapped clientbound
    ClientboundPackets1_13,     // paquets mapped clientbound
    ServerboundPackets1_12_1,   // paquets mapped serverbound
    ServerboundPackets1_13      // paquets unmapped serverbound
> {
    // Contient des Rewriters specialises :
    EntityPacketRewriter1_13 entityRewriter;
    ItemPacketRewriter1_13 itemRewriter;
    ComponentRewriter1_13 componentRewriter;
}
```

**4 types generiques** definissent les types de paquets en entree et sortie pour chaque direction. Le systeme detecte automatiquement les changements d'ID en comparant les enums de paquets par nom.

### Rewriters specialises

ViaVersion decompose la traduction en sous-systemes :
- **EntityRewriter** : remappe les entity IDs, metadata format
- **ItemRewriter** : traduit les item stacks (block IDs, NBT, components)
- **ComponentRewriter** : traduit le format de chat/texte
- **WorldRewriter** : traduit les chunks, block states
- **SoundRewriter** : remappe les sound IDs
- **TagRewriter** : traduit les tags de registre
- **ParticleRewriter** : remappe les IDs de particules

### Donnees de mapping

ViaVersion stocke ses mappings en **fichiers NBT compresses** dans `assets/viaversion/data/` :

```
mappings-1.12to1.13.nbt   (23 KB -- la plus grosse, c'est le "Flattening")
mappings-1.20.3to1.20.5.nbt (5.6 KB)
mappings-1.21.6to1.21.7.nbt (206 bytes -- changement minimal)
```

Ces fichiers contiennent les tables de correspondance pour blocks, items, sounds, particles, etc. Ils sont generes par le repo **ViaVersion/Mappings** qui :
1. Telecharge le JAR serveur Minecraft
2. Execute le generateur de donnees (`--reports`)
3. Compare les rapports entre deux versions
4. Produit les fichiers de mapping optimises (format NBT compact)

### Le ProtocolManager et le pathfinding

`ProtocolManager.getProtocolPath(clientVersion, serverVersion)` calcule le chemin le plus court entre deux versions. Si aucun chemin n'existe ou depasse `maxProtocolPathSize`, la connexion est refusee.

### Cout de maintenance par version

- **Version mineure** (ex: 1.21.6 -> 1.21.7) : mapping de 206 bytes, quelques heures de travail.
- **Version moyenne** (ex: 1.20.3 -> 1.20.5) : 5.6 KB de mappings, quelques jours.
- **Version majeure** (ex: 1.12.2 -> 1.13, le Flattening) : 23 KB de mappings + 162 KB de mapping de langues, plusieurs semaines de travail avec des bugs subtils pendant des mois.

### Ce que Warp peut apprendre

**Warp NE DOIT PAS copier l'approche ViaVersion.** Warp est un proxy "blind forwarding" -- il ne traduit pas entre versions. Mais les lecons sont :

1. **Les fichiers de mapping externalises** sont essentiels pour la maintenabilite.
2. **L'automatisation de la generation de mappings** (repo ViaVersion/Mappings) est un modele a suivre.
3. **La decomposition en rewriters** est utile si Warp doit un jour inspecter certains paquets specifiques.
4. **Le systeme de Protocol chaine** montre que la complexite croit lineairement avec le nombre de versions, pas exponentiellement.

---

## 3. Sources de donnees protocole Minecraft

### 3.1 Generateur de donnees intere Mojang (`--reports`)

Depuis Java Edition 1.13, le serveur Minecraft inclut un generateur de donnees.

**Commande (1.18+)** :
```bash
java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports
```

**Commande (1.13-1.17)** :
```bash
java -cp minecraft_server.jar net.minecraft.data.Main --reports
```

**Flags disponibles** :
- `--server` : contenu du data pack vanilla
- `--client` : donnees cote client
- `--dev` : conversion NBT vers SNBT
- `--reports` : rapports JSON (blocks, registres, commandes, **paquets**)
- `--all` : tout generer

**Le fichier `reports/packets.json`** est critique pour Warp. Structure :

```json
{
  "handshake": {
    "serverbound": {
      "minecraft:intention": { "protocol_id": 0 }
    }
  },
  "status": {
    "clientbound": {
      "minecraft:status_response": { "protocol_id": 0 },
      "minecraft:pong_response": { "protocol_id": 1 }
    },
    "serverbound": {
      "minecraft:status_request": { "protocol_id": 0 },
      "minecraft:ping_request": { "protocol_id": 1 }
    }
  },
  "login": { ... },
  "configuration": { ... },
  "play": { ... }
}
```

**Avantage** : c'est la source de verite officielle de Mojang. Les IDs sont 100% corrects.
**Limitation** : disponible seulement depuis 1.13. Pas de donnees pour 1.7.2-1.12.2. Ne contient que les IDs, pas la structure des paquets.

### 3.2 PrismarineJS/minecraft-data

Depot open-source contenant des donnees structurees pour toutes les versions de Minecraft (0.30c Classic a 1.21.10+).

**Donnees protocole disponibles** :
- `data/pc/{version}/protocol.json` : description **complete** du protocole avec types, champs, structures de chaque paquet
- Format ProtoDef (schema JSON de types de donnees)

**Structure du protocol.json** :
```
handshaking/
  toServer/
    packet_set_protocol, packet_legacy_server_list_ping
status/
  toClient/ toServer/
login/
  toClient/ toServer/
configuration/ (versions recentes)
  toClient/ toServer/
play/
  toClient/ toServer/ (200+ paquets)
```

**Automatisation** :
- **minecraft-data-auto-updater** : surveille les nouvelles releases, cree des PR automatiquement
- **minecraft-data-generator-server** : mod Fabric qui extrait les donnees depuis un serveur en cours d'execution
- **minecraft-jar-extractor** / **minecraft-wiki-extractor** : extraction depuis les JARs et le wiki

**Versions couvertes** : Java Edition de 0.30c a 1.21.10, Bedrock de 0.14 a 26.10.

**Pour Warp** : c'est la meilleure source pour les versions pre-1.13 ou `--reports` n'existe pas.

### 3.3 Burger (extraction depuis les JARs MC)

Outil Python qui decompile le JAR Minecraft et extrait les donnees de protocole par analyse du bytecode.

**Utilisation** :
```bash
python burger.py --jar minecraft_server.jar --output data.json
```

Historiquement utilise par PrismarineJS pour generer les donnees de recettes et certaines donnees protocole.

### 3.4 ViaVersion/Mappings (repo de generation)

Le pipeline de ViaVersion pour generer automatiquement les mappings entre versions :

1. `download_server.py` telecharge le dernier JAR depuis `piston-meta.mojang.com`
2. `MappingsGenerator` extrait les donnees du JAR serveur via `--reports`
3. `MappingsOptimizer` compare deux versions et produit les diffs en format NBT compact
4. Un workflow GitHub Actions (`check.yml`) execute tout cela **automatiquement a chaque heure** et cree un commit si un nouveau snapshot est detecte

### 3.5 Strategie recommandee pour Warp

| Version range | Source primaire | Source secondaire |
|---|---|---|
| 1.7.2 - 1.12.2 | PrismarineJS/minecraft-data | wiki.vg (archive) |
| 1.13 - 1.17 | `--reports` (java -cp) | PrismarineJS/minecraft-data |
| 1.18+ | `--reports` (bundler) | PrismarineJS/minecraft-data |

**Warp n'a besoin que des IDs de paquets, pas de la structure complete.** Le fichier `packets.json` de Mojang suffit pour 1.13+. Pour les versions anterieures, les donnees PrismarineJS sont la reference.

---

## 4. Pipeline automatise de mise a jour protocole

### 4.1 Workflow de detection (inspire de ViaVersion/Mappings)

```yaml
name: Detecter nouveau snapshot Minecraft
on:
  schedule:
    - cron: '*/30 * * * *'  # Toutes les 30 minutes
  workflow_dispatch:

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Verifier version actuelle
        id: check
        run: |
          # Telecharger le manifest Mojang
          MANIFEST=$(curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
          LATEST_SNAPSHOT=$(echo $MANIFEST | jq -r '.latest.snapshot')
          LATEST_RELEASE=$(echo $MANIFEST | jq -r '.latest.release')
          KNOWN_VERSION=$(cat last_known_version.txt)

          if [ "$LATEST_SNAPSHOT" = "$KNOWN_VERSION" ]; then
            echo "changed=false" >> $GITHUB_OUTPUT
          else
            echo "changed=true" >> $GITHUB_OUTPUT
            echo "version=$LATEST_SNAPSHOT" >> $GITHUB_OUTPUT

            # Trouver l'URL du JAR serveur
            VERSION_URL=$(echo $MANIFEST | jq -r ".versions[] | select(.id == \"$LATEST_SNAPSHOT\") | .url")
            SERVER_URL=$(curl -s $VERSION_URL | jq -r '.downloads.server.url')
            echo "server_url=$SERVER_URL" >> $GITHUB_OUTPUT
          fi

      - name: Telecharger et generer les rapports
        if: steps.check.outputs.changed == 'true'
        run: |
          wget -q ${{ steps.check.outputs.server_url }} -O server.jar
          java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports
          cp generated/reports/packets.json protocol-data/${{ steps.check.outputs.version }}/packets.json

      - name: Valider les mappings Warp
        if: steps.check.outputs.changed == 'true'
        run: |
          # Comparer le nouveau packets.json avec les mappings declares dans Warp
          java -jar tools/mapping-validator.jar \
            --expected protocol-data/${{ steps.check.outputs.version }}/packets.json \
            --actual src/main/resources/packet-mappings.json \
            --version ${{ steps.check.outputs.version }}

      - name: Creer PR si changements detectes
        if: steps.check.outputs.changed == 'true'
        run: |
          echo "${{ steps.check.outputs.version }}" > last_known_version.txt
          git checkout -b protocol-update/${{ steps.check.outputs.version }}
          git add .
          git commit -m "feat(protocol): detecter changements ${{ steps.check.outputs.version }}"
          git push origin HEAD
          gh pr create \
            --title "Protocol: ${{ steps.check.outputs.version }}" \
            --body "Nouveau snapshot detecte. Rapport de changements ci-joint."
```

### 4.2 Validateur de mappings (outil custom pour Warp)

Un outil Java qui :
1. Charge le `packets.json` genere par Mojang
2. Charge les mappings declares dans Warp
3. Compare les IDs et detecte :
   - IDs qui ont change
   - Nouveaux paquets ajoutes
   - Paquets supprimes
4. Genere un rapport et optionnellement met a jour les mappings

### 4.3 Integration PrismarineJS pour pre-1.13

Pour les versions 1.7.2-1.12.2, les donnees sont stables (pas de nouvelles versions). On peut les importer une fois depuis `minecraft-data` :

```bash
# Extraire les IDs de paquets pour les vieilles versions
node extract-packet-ids.js --versions 1.7,1.8,1.9,1.9.4,1.10,1.11,1.12,1.12.1,1.12.2
```

---

## 5. Enregistrement et rejeu de paquets

### 5.1 Outils existants

| Outil | Langage | Capacites | Pertinence pour Warp |
|---|---|---|---|
| **SniffCraft** | C++ | Proxy man-in-the-middle, log format ReplayMod (.mcpr) | Capture de sessions reelles |
| **mc3p DVR** | Python | Enregistrement/rejeu de messages pour test de plugins | Pattern de test a suivre |
| **Wireshark + minecraft-dissector** | Lua | Analyse PCAP avec dissecteur MC base sur minecraft-data | Debug reseau |
| **PacketEvents** | Java | Interception de paquets multi-version (1.8-1.21) | Reference API |
| **Rex** | Python | Proxy qui identifie et dump les paquets | Inspection rapide |

### 5.2 Strategie de recording/replay pour Warp

**Phase 1 : Capture de sessions de reference**

```java
// Module warp-recorder
public class PacketRecorder implements ChannelDuplexHandler {
    private final ProtocolVersion version;
    private final List<RecordedPacket> packets = new ArrayList<>();

    record RecordedPacket(
        long timestampNanos,
        Direction direction,
        int packetId,
        byte[] rawPayload  // Donnees brutes, pas decodees
    ) {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Capturer le paquet brut avant decodage
        if (msg instanceof ByteBuf buf) {
            packets.add(new RecordedPacket(
                System.nanoTime(), Direction.SERVERBOUND,
                ProtocolUtils.readVarInt(buf.duplicate()),
                ByteBufUtil.getBytes(buf.duplicate())
            ));
        }
        ctx.fireChannelRead(msg);
    }

    public void save(Path outputFile) {
        // Serialiser en format compact (version + liste de paquets)
    }
}
```

**Phase 2 : Rejeu pour tests de regression**

```java
// Module warp-test-harness
public class SessionReplayer {
    public void replay(Path recordingFile, WarpProxy proxy) {
        Recording recording = Recording.load(recordingFile);

        // Creer une connexion simulee
        EmbeddedChannel clientChannel = new EmbeddedChannel(
            proxy.createPipeline(recording.version())
        );

        for (RecordedPacket packet : recording.packets()) {
            ByteBuf buf = Unpooled.wrappedBuffer(packet.rawPayload());
            if (packet.direction() == Direction.SERVERBOUND) {
                clientChannel.writeInbound(buf);
            } else {
                clientChannel.writeOutbound(buf);
            }
            // Verifier qu'aucune exception n'est levee
            // Verifier que les paquets sortants sont valides
        }
    }
}
```

**Phase 3 : Corpus de sessions par version**

Maintenir un repertoire `test-recordings/` avec des sessions enregistrees pour chaque version supportee :

```
test-recordings/
  1.7.2/   login-sequence.rec, basic-play.rec
  1.8/     login-sequence.rec, basic-play.rec, combat.rec
  1.12.2/  login-sequence.rec, basic-play.rec, tab-complete.rec
  1.13/    login-sequence.rec, basic-play.rec, commands.rec
  1.19.3/  login-sequence.rec, basic-play.rec, signed-chat.rec
  1.20.2/  login-sequence.rec, config-phase.rec, server-switch.rec
  1.21.5/  login-sequence.rec, basic-play.rec, registry-sync.rec
```

---

## 6. Compatibilite ascendante de l'API plugin

### 6.1 Comment Bukkit/Spigot gere la compatibilite

- **Le champ `api-version`** dans `plugin.yml` declare la version API ciblee. Le serveur applique des couches de compatibilite arriere automatiques si la version est plus ancienne.
- **Constructeurs d'evenements** : Spigot ne garantit PAS la compatibilite des constructeurs d'evenements (ce ne sont pas de l'API). Ajouter un champ a un evenement casse les constructeurs existants.
- **Noms de materiaux** : changent a chaque version majeure, source #1 de casse.
- **NMS (net.minecraft.server)** : pas du tout garanti, casse a chaque version.

**Lecon** : la frontiere API/non-API doit etre clairement definie et documentee.

### 6.2 Comment Minestom gere l'evolution

- Minestom **ne supporte qu'une seule version** a la fois (la derniere). Pour le multi-version, ils recommandent ViaVersion.
- Aucune garantie de stabilite API entre versions majeures de Minestom.
- Approche "move fast and break things" -- l'oppose de ce que Warp doit faire.

### 6.3 Outils de verification pour Java

| Outil | Type | Usage |
|---|---|---|
| **japicmp** | Comparateur de JARs | Detecte les changements d'API binaires entre deux versions d'un JAR. Plugin Maven et Gradle disponibles. |
| **semver-check** | Plugin Maven | Valide que le numero de version respecte SemVer par rapport aux changements detectes |
| **Animal Sniffer** | Plugin Maven | Verifie qu'une library ne depend pas d'APIs absentes de la JDK cible |
| **Revapi** | Plugin Maven/Gradle | Analyse de compatibilite API avec support Java, rapport detaille |

### 6.4 Strategie recommandee pour Warp

**6.4.1 Separation claire API / Interne**

```
warp-api/          <-- Module public, versionne en SemVer strict
  com.warp.api.event/
  com.warp.api.player/
  com.warp.api.server/
  com.warp.api.plugin/

warp-proxy/        <-- Implementation interne, pas de garantie
  com.warp.proxy.protocol/
  com.warp.proxy.connection/
  com.warp.proxy.netty/
```

**6.4.2 Annotations de stabilite**

```java
@API(status = API.Status.STABLE, since = "1.0.0")
public interface ProxiedPlayer { ... }

@API(status = API.Status.EXPERIMENTAL, since = "0.5.0")
public interface ProtocolBridge { ... }

@API(status = API.Status.INTERNAL)
public class PacketDecoder { ... }
```

**6.4.3 japicmp dans le CI**

```kotlin
// build.gradle.kts (module warp-api)
plugins {
    id("me.champeau.gradle.japicmp") version "0.4.1"
}

tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("apiCheck") {
    oldClasspath.from(/* dernier JAR release */)
    newClasspath.from(tasks.jar)
    onlyModified = true
    failOnModification = true  // Echoue si un changement binaire est detecte
    richReport {
        destinationDir = layout.buildDirectory.dir("reports/japicmp")
        reportName = "api-compatibility.html"
    }
}
```

**6.4.4 Politique de deprecation**

1. `@Deprecated(since = "X.Y.0")` + `@ScheduledForRemoval(inVersion = "X.(Y+2).0")`
2. Deux cycles de version mineure avant suppression
3. Migration guide dans les release notes
4. Warnings a la compilation et dans les logs au runtime

---

## 7. Base de regressions par version

### 7.1 Changements protocole majeurs (cassants pour les proxys)

#### 1.7.2 -> 1.8 (Protocol 4 -> 47) -- "La Base"

- **UUID introduit** dans le Login Success : 1.7.x utilise un string, 1.8+ utilise un UUID formate
- **Spectator mode** ajoute, affecte les paquets de gamemode
- **Titre et footer** de tab : nouveau paquet
- **Resource pack** : nouveau paquet `ResourcePackSend`

#### 1.8 -> 1.9 (Protocol 47 -> 107) -- "Combat Update"

- **Dual wielding** : champ `main hand` ajoute a plusieurs paquets
- **Nouveaux paquets** : BossBar, VehicleMove, Teleport Confirm
- **Entity IDs** : refactoring massif
- **Changement de padding** dans certains champs VarInt
- **Gotcha proxy** : le client 1.8 envoie des paquets que 1.9 ne comprend pas (animation attack)

#### 1.12.2 -> 1.13 (Protocol 340 -> 393) -- "The Flattening"

**LE changement le plus cassant de l'histoire du protocole Minecraft.**

- **Suppression des data values** : les items/blocks passent de (ID + data value) a un ID unique
- **Tous les IDs changent** : blocks (4096 -> 11000+), items, biomes, particles, paintings, entities, statistiques, sons
- **Command system** : remplacement du systeme de tab-complete par Brigadier
- **Nouveaux paquets** : DeclareCommands, DeclareRecipes, Tags
- **Suppression des paquets anciens** : TabComplete ancien remplace
- Le mapping 1.12->1.13 de ViaVersion fait **23 KB** en NBT + **162 KB** de mapping de langues
- **Gotcha** : les noms de teams scoreboard utilisent des codes de couleur qui doivent etre remappes

#### 1.18.2 -> 1.19 (Protocol 758 -> 759) -- "Chat Signing Debut"

- **Signature cryptographique des messages chat** : cle publique envoyee au login
- **Nouveaux paquets** : PlayerChatMessage, SystemChatMessage (remplacement de ChatMessage)
- **Gotcha proxy** : le proxy doit gerer les cles de signature et les chaines de messages

#### 1.19 -> 1.19.1 (Protocol 759 -> 760) -- "Chat Reporting"

- **Message chain** : suivi des 20 derniers messages vus pour empecher l'omission
- **Index de message** incremental anti-reordonnancement
- **Gotcha proxy** : la chaine doit etre maintenue correctement sinon le client detecte des omissions

#### 1.19.1 -> 1.19.3 (Protocol 760 -> 761) -- "Chat Signing Simplifie"

- Cles de profil **plus obligatoires** pour rejoindre, seulement pour envoyer des messages
- Messages prives **plus signes**
- **Gotcha proxy** : attention au `enforce-secure-profile`, le proxy doit propager correctement le statut

#### 1.19.4 -> 1.20.2 (Protocol 762 -> 764) -- "Configuration Phase"

**Deuxieme changement le plus impactant pour les proxys.**

- **Nouvel etat protocole CONFIG** entre Login et Play
- Le client envoie un `LoginAcknowledged` puis passe en CONFIG
- Le serveur envoie des registry data, tags, resource packs AVANT d'entrer en PLAY
- **Server switch** : le proxy doit ramener le joueur en CONFIG, echanger les donnees, puis revenir en PLAY
- **Gotcha proxy** : oublier la phase CONFIG = "garbage packet decode failure" car le client decode avec la mauvaise table de paquets
- **Velocity** a du implementer un systeme double-handler (`ClientConfigSessionHandler` + `ConfigSessionHandler`) avec synchronisation par `CompletableFuture` et un timeout de 5 secondes

#### 1.20.2 -> 1.20.5 (Protocol 764 -> 766) -- "Item Components"

- **Format de slot change** : pre-1.20.5 utilise un boolean de presence, 1.20.5+ utilise le count du varint comme indicateur
- **KnownPacks** : nouveau paquet dans CONFIG pour negocier les packs de registre
- **Timing d'events** : `PlayerConfigurationEvent` doit fire depuis `KnownPacksPacket` car la reponse ne peut pas traverser les limites d'etat

#### 1.21 -> 1.21.1 (Protocol 767) -- "Strict Error Handling"

- **Login Success** necessite un byte `0x01` apres la liste de proprietes
- Ajouter ce byte en 1.21.2 (ou il n'existe pas) cause "1 byte extra"
- **Gotcha proxy** : il faut brancher sur le protocole exactement

#### 1.21.4 -> 1.21.5 (Protocol 769 -> 770) -- "Heightmap & JSON Changes"

- **Heightmaps** : encodage passe de tableaux NBT long[77] a tableaux directs max long[64]
- **JSON text components** : les cles passent de camelCase (`clickEvent`) a snake_case (`click_event`)
- **IDs de paquets** : decalage generique de la plupart des IDs clientbound

### 7.2 Gotchas connus par plage de versions

| Plage | Gotcha | Impact |
|---|---|---|
| 1.7.x | UUID en string, pas en raw bytes | Crash login |
| 1.7-1.8 | Pas de compression dans le handshake 1.7 | Paquet mal decode |
| 1.8 | Protocole 47 tres repandu, beaucoup de clients PvP | Performance critique |
| 1.9-1.12 | Tab-complete ancien format (string-based) | Fonctionnalite cassee |
| 1.12.2-1.13 | Le "Flattening" -- tout change | Necessite mapping exhaustif |
| 1.13-1.18 | Brigadier pour les commandes | Paquet Commands complexe a parser |
| 1.19-1.19.1 | Chat signing incomplet/changeant | Deconnexion client |
| 1.19.3 | Chat signing simplifie mais different | Faux positifs de detection |
| 1.20.2+ | CONFIG state obligatoire | Crash complet sans gestion |
| 1.20.5+ | Slot format change, KnownPacks | Items corrompus |
| 1.21-1.21.1 | Strict Error Handling byte | Crash login |
| 1.21.5+ | JSON snake_case, heightmaps | Texte chat casse |

### 7.3 IDs de paquets qui bougent constamment

Le paquet `KeepAlive` (serverbound) a eu **18 IDs differents** de 1.7.2 a 26.1 :
```
1.7.2=0x00, 1.9=0x0B, 1.12=0x0C, 1.12.1=0x0B, 1.13=0x0E, 1.14=0x0F,
1.16=0x10, 1.17=0x0F, 1.19=0x11, 1.19.1=0x12, 1.19.3=0x11, 1.19.4=0x12,
1.20.2=0x14, 1.20.3=0x15, 1.20.5=0x18, 1.21.2=0x1A, 1.21.6=0x1B, 26.1=0x1C
```

C'est representatif : la plupart des paquets PLAY bougent a chaque version mineure parce que Mojang ajoute des paquets au milieu et les IDs sont attribues sequentiellement.

---

## 8. Strategie recommandee pour Warp

### 8.1 Architecture de gestion du protocole

```
warp-protocol/
  src/main/resources/
    packet-registry/
      1.7.2.json     # IDs de paquets pour chaque version
      1.8.json
      ...
      26.1.json
    protocol-versions.json  # version name -> protocol number mapping

  src/main/java/com/warp/protocol/
    ProtocolVersion.java      # Enum ou constantes
    PacketDirection.java      # CLIENTBOUND, SERVERBOUND
    ProtocolState.java        # HANDSHAKE, STATUS, LOGIN, CONFIG, PLAY
    PacketRegistry.java       # Registre charge depuis les fichiers JSON
    PacketType.java           # Enum des types de paquets connus de Warp
```

### 8.2 Format des fichiers de mapping

```json
// packet-registry/1.21.5.json
{
  "protocol_version": 770,
  "states": {
    "handshake": {
      "serverbound": {
        "HANDSHAKE": 0
      }
    },
    "play": {
      "serverbound": {
        "TAB_COMPLETE": 14,
        "CHAT_MESSAGE": 8,
        "CLIENT_SETTINGS": 13,
        "PLUGIN_MESSAGE": 21,
        "KEEP_ALIVE": 27
      },
      "clientbound": {
        "KEEP_ALIVE": 38,
        "JOIN_GAME": 43,
        "DISCONNECT": 28,
        "PLUGIN_MESSAGE": 25,
        "RESPAWN": 75
      }
    }
  }
}
```

**Warp ne mappe que les ~30 paquets qu'il decode.** Le reste est forward en blind par ID brut.

### 8.3 Chargement au runtime

```java
public class PacketRegistry {
    // Map: ProtocolVersion -> (State -> (Direction -> (PacketType -> ID)))
    private final Map<Integer, StateRegistry> registries;

    public static PacketRegistry loadFromResources() {
        // Scanner packet-registry/*.json
        // Construire les maps
    }

    public int getPacketId(int protocolVersion, ProtocolState state,
                           PacketDirection direction, PacketType type) {
        // Lookup rapide
    }

    public PacketType getPacketType(int protocolVersion, ProtocolState state,
                                     PacketDirection direction, int packetId) {
        // Reverse lookup
    }
}
```

### 8.4 Strategie de support des versions

**Tier 1 -- Support complet** (decode + encode + test complet) :
- 1.7.2/1.7.10 (PvP legacy)
- 1.8.x (PvP dominant)
- Derniere version stable
- Derniere version snapshot (quand pertinent)

**Tier 2 -- Support forward** (blind forward, login/config gere) :
- 1.9 - 1.12.2
- 1.13 - 1.19.x
- Toutes les versions intermediaires

**Tier 3 -- Experimental** :
- Snapshots en cours

La difference entre Tier 1 et Tier 2 : en Tier 1, on a des tests de regression complets et des sessions enregistrees. En Tier 2, on s'assure que le login fonctionne et que le blind forwarding ne casse pas.

---

## 9. Design du pipeline CI/CD

### 9.1 Jobs et declencheurs

```
+-------------------+     +-----------------------+     +-------------------+
| Hourly Check      |     | PR / Push             |     | Release           |
| (cron: */60 * * *)|     | (on: push/PR)         |     | (on: tag v*)      |
+--------+----------+     +----------+------------+     +--------+----------+
         |                            |                           |
         v                            v                           v
+--------+----------+     +----------+------------+     +--------+----------+
| Detecter nouveau  |     | Build + Tests         |     | Build + Tests     |
| snapshot Mojang   |     | unitaires             |     | + Integration     |
+--------+----------+     +----------+------------+     +--------+----------+
         |                            |                           |
         v                            v                           v
+--------+----------+     +----------+------------+     +--------+----------+
| Generer packets   |     | Replay sessions       |     | Matrice multi-    |
| .json du snapshot |     | enregistrees          |     | version complete  |
+--------+----------+     +----------+------------+     +--------+----------+
         |                            |                           |
         v                            v                           v
+--------+----------+     +----------+------------+     +--------+----------+
| Valider vs        |     | japicmp API check     |     | Publish artifacts |
| mappings existants|     | (module warp-api)     |     | + Release notes   |
+--------+----------+     +----------+------------+     +--------+----------+
         |
         v
+--------+----------+
| Creer PR avec     |
| diff de mappings  |
+---------+---------+
```

### 9.2 Job de detection de snapshot

```yaml
name: Protocol Watch
on:
  schedule:
    - cron: '0 * * * *'  # Chaque heure

jobs:
  check-new-version:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Check Mojang manifest
        id: version
        run: |
          MANIFEST=$(curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
          LATEST=$(echo $MANIFEST | jq -r '.latest.snapshot')
          KNOWN=$(cat .protocol/last-known-version.txt 2>/dev/null || echo "none")
          echo "latest=$LATEST" >> $GITHUB_OUTPUT
          echo "known=$KNOWN" >> $GITHUB_OUTPUT
          [ "$LATEST" != "$KNOWN" ] && echo "changed=true" >> $GITHUB_OUTPUT || echo "changed=false" >> $GITHUB_OUTPUT

      - name: Download and generate reports
        if: steps.version.outputs.changed == 'true'
        run: |
          VERSION=${{ steps.version.outputs.latest }}
          URL=$(curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
            | jq -r ".versions[] | select(.id == \"$VERSION\") | .url")
          SERVER_URL=$(curl -s $URL | jq -r '.downloads.server.url')
          PROTOCOL=$(curl -s $URL | jq -r '.protocol_version // empty')

          mkdir -p /tmp/mc-gen
          wget -q $SERVER_URL -O /tmp/mc-gen/server.jar
          cd /tmp/mc-gen
          java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports

      - name: Validate and diff
        if: steps.version.outputs.changed == 'true'
        run: |
          # Comparer avec la derniere version connue
          ./tools/diff-packets.sh \
            .protocol/packets/${{ steps.version.outputs.known }}.json \
            /tmp/mc-gen/generated/reports/packets.json \
            > /tmp/diff-report.txt

          # Copier le nouveau fichier
          cp /tmp/mc-gen/generated/reports/packets.json \
            .protocol/packets/${{ steps.version.outputs.latest }}.json

      - name: Create PR
        if: steps.version.outputs.changed == 'true'
        run: |
          VERSION=${{ steps.version.outputs.latest }}
          echo "$VERSION" > .protocol/last-known-version.txt
          git checkout -b protocol-update/$VERSION
          git add .
          git commit -m "feat(protocol): ajouter donnees $VERSION"
          git push origin HEAD
          gh pr create \
            --title "Protocol: ajouter $VERSION" \
            --body "$(cat /tmp/diff-report.txt)"
```

### 9.3 Job de test multi-version

```yaml
name: Multi-Version Test Matrix
on:
  pull_request:
  push:
    branches: [main, dev]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - run: ./gradlew test

  replay-tests:
    needs: unit-tests
    runs-on: ubuntu-latest
    strategy:
      matrix:
        version: ["1.7.2", "1.8", "1.12.2", "1.13", "1.16.5", "1.19.4", "1.20.2", "1.20.5", "1.21", "1.21.5"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - name: Run replay tests for ${{ matrix.version }}
        run: |
          ./gradlew replayTest \
            -PtargetVersion=${{ matrix.version }} \
            -PrecordingDir=test-recordings/${{ matrix.version }}

  api-compatibility:
    needs: unit-tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - name: Check API compatibility
        run: ./gradlew :warp-api:japicmpCheck
```

---

## 10. Matrice de tests

### 10.1 Tests unitaires du registre de paquets

```java
@ParameterizedTest
@MethodSource("allSupportedVersions")
void packetRegistryHasAllRequiredMappings(ProtocolVersion version) {
    PacketRegistry registry = PacketRegistry.loadFromResources();

    // Verifier que tous les paquets essentiels sont mappes
    for (PacketType required : PacketType.REQUIRED_PACKETS) {
        assertDoesNotThrow(
            () -> registry.getPacketId(version.getProtocol(), required.state(),
                                        required.direction(), required),
            "Missing mapping for " + required + " in " + version
        );
    }
}

@ParameterizedTest
@MethodSource("versionsWithMojangData")
void packetIdsMatchMojangReports(ProtocolVersion version) {
    // Charger le packets.json Mojang
    JsonObject mojangPackets = loadMojangReport(version);
    PacketRegistry registry = PacketRegistry.loadFromResources();

    // Pour chaque paquet que Warp gere, verifier l'ID
    for (PacketType type : PacketType.values()) {
        int expected = extractIdFromMojangReport(mojangPackets, type);
        if (expected >= 0) {
            int actual = registry.getPacketId(version.getProtocol(),
                type.state(), type.direction(), type);
            assertEquals(expected, actual,
                type + " ID mismatch for " + version);
        }
    }
}
```

### 10.2 Tests d'integration de session

```java
@ParameterizedTest
@MethodSource("testRecordings")
void replayedSessionDoesNotThrow(Path recordingFile, ProtocolVersion version) {
    Recording recording = Recording.load(recordingFile);
    WarpProxy proxy = createTestProxy();

    EmbeddedChannel channel = createTestChannel(proxy, version);

    for (RecordedPacket packet : recording.packets()) {
        assertDoesNotThrow(
            () -> replayPacket(channel, packet),
            "Exception during replay at packet #" + packet.index()
        );
    }
}
```

### 10.3 Tests de compatibilite API

Integres via japicmp (voir section 6.4.3). Echouent si :
- Une methode publique est supprimee
- La signature d'une methode publique change
- Un champ public change de type
- Une interface publique perd une methode

### 10.4 Matrice version complete

```
          | Login | Config | KeepAlive | Chat | PluginMsg | ServerSwitch | Disconnect |
1.7.2     |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.8       |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.12.2    |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.13      |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.16.5    |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.19.4    |   T   |   N/A  |     T     |   T  |     T     |      T       |     T      |
1.20.2    |   T   |    T   |     T     |   T  |     T     |      T       |     T      |
1.20.5    |   T   |    T   |     T     |   T  |     T     |      T       |     T      |
1.21      |   T   |    T   |     T     |   T  |     T     |      T       |     T      |
1.21.5    |   T   |    T   |     T     |   T  |     T     |      T       |     T      |

T = Teste, N/A = Non applicable (CONFIG n'existe pas avant 1.20.2)
```

---

## Sources

- [PaperMC/Velocity - StateRegistry.java](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/StateRegistry.java)
- [PaperMC/Velocity - ProtocolVersion.java](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/api/src/main/java/com/velocitypowered/api/network/ProtocolVersion.java)
- [ViaVersion/ViaVersion - AbstractProtocol.java](https://github.com/ViaVersion/ViaVersion/blob/master/api/src/main/java/com/viaversion/viaversion/api/protocol/AbstractProtocol.java)
- [ViaVersion/ViaVersion - ProtocolManager.java](https://github.com/ViaVersion/ViaVersion/blob/master/api/src/main/java/com/viaversion/viaversion/api/protocol/ProtocolManager.java)
- [ViaVersion/Mappings - Pipeline de generation automatique](https://github.com/ViaVersion/Mappings)
- [PrismarineJS/minecraft-data](https://github.com/PrismarineJS/minecraft-data)
- [PrismarineJS/minecraft-data-generator](https://github.com/PrismarineJS/minecraft-data-generator)
- [Minecraft Wiki - Data Generators](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Data_Generators)
- [Minecraft Wiki - Protocol version](https://minecraft.wiki/w/Protocol_version)
- [ViaVersion/ViaProxy - Architecture DeepWiki](https://deepwiki.com/ViaVersion/ViaProxy)
- [Velocity - Configuration Stage DeepWiki](https://deepwiki.com/PaperMC/Velocity/8.1-configuration-stage)
- [Signed Chat and Chat Types (kennytv)](https://gist.github.com/kennytv/ed783dd244ca0321bbd882c347892874)
- [japicmp - Java API compatibility checker](https://github.com/siom79/japicmp)
- [japicmp Gradle plugin](https://github.com/melix/japicmp-gradle-plugin)
- [SniffCraft - Packet sniffer](https://github.com/adepierre/SniffCraft)
- [minecraft-dissector - Wireshark](https://github.com/aresrpg/minecraft-dissector)
- [PacketEvents](https://github.com/retrooper/packetevents)
- [mc3p DVR - Record/replay](https://github.com/mmcgill/mc3p/wiki/DVR)
- [Proxy challenges (Hypixel Forum)](https://hypixel.net/threads/i-thought-proxying-minecraft-would-take-a-weekend-it-took-13-days-heres-why.6064963/)
